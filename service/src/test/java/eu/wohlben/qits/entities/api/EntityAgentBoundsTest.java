package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.entities.control.DossierService;
import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.control.EntityTransitionService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.DossierOwner;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.entities.mapper.DossierPageMapper;
import eu.wohlben.qits.entities.mapper.EntityCommentMapper;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * An agent at the entity write doors: it writes the rows of its own project, is refused everywhere
 * else, and never moves an epic through its lifecycle or deletes anything a tool does not delete.
 *
 * <p><b>Two layers, tested where each can be reached — the split {@code
 * ReleaseRequestAgentBoundsTest} explains and this class inherits.</b> The <b>binding</b> reads the
 * token's {@code project} claim, and a {@code @QuarkusTest} cannot put a token in front of this
 * service: the forwarded-header mechanism's {@code %test} dev user wins over a {@code @TestSecurity}
 * identity, and that dev user holds all four platform roles. So the controllers are driven directly,
 * with hand-made identities from {@link AgentTokens} over real rows and the real beans. The
 * <b>roles</b> are what a forwarded header carries, so they are tested over HTTP with {@code
 * X-Qits-Roles: qits:agent} — and only the roles: a forwarded agent carries no token at all, so the
 * claim binding is unreachable from there by construction.
 *
 * <p><b>What the HTTP half can still say, and it is the sharper half of the door.</b> A granted
 * route and an admin-only route both answer 403 to a bound agent that does not cover the project —
 * so the two are told apart by an id that names nothing: the granted route passes the role check,
 * runs, resolves the id and answers <b>404</b>, while the admin-only route never reaches its body
 * and answers 403. That is the role list being read, observed from outside.
 *
 * <p><b>The representative routes.</b> One write per controller is driven for the through-case and
 * the refusal, rather than all twenty: the binding is one helper called as the first statement
 * of each route ({@code EntitiesAgentAccess}), so what a second route on the same controller would
 * add is a second reading of one line. What is <em>not</em> representative is the batched
 * transition, which has a binding of its own — all or nothing over a whole request — and that one is
 * tested in full.
 *
 * <p><b>{@code DossierAssetController.inline} is the one granted route not driven here.</b> Its
 * write copies a figure out of a live refinement's attachments, which needs a refinement row, a
 * container-side prompt attachment and the {@code DossierFigures} crossing to stand up — wholly
 * disproportionate to re-reading one line of binding. Its role list is pinned by {@code
 * AgentReadAccessTest} and its binding is the same {@code requireProject} call as the twelve
 * below.
 */
@QuarkusTest
class EntityAgentBoundsTest {

  private static final String OWN_PROJECT = "entity-bounds-own";
  private static final String OWN_REPO = "entity-bounds-own-repo";
  private static final String FOREIGN_PROJECT = "entity-bounds-foreign";

  /** The agent of the project the rows below belong to. */
  private static final SecurityIdentity AGENT =
      AgentTokens.token(Map.of("project", OWN_PROJECT), "qits:agent");

  /** The same role, a token of its own, and somebody else's project. */
  private static final SecurityIdentity FOREIGN_AGENT =
      AgentTokens.token(Map.of("project", FOREIGN_PROJECT), "qits:agent");

  /** The agent role off a forwarded header: no token, so no claim, so nothing is covered. */
  private static final SecurityIdentity CLAIMLESS_AGENT = AgentTokens.forwarded("qits:agent");

  /** A person. */
  private static final SecurityIdentity OPERATOR = AgentTokens.token(Map.of(), "qits:admin");

  /** A person whose session also carries the agent role: {@code isBoundAgent} must read it as one. */
  private static final SecurityIdentity OPERATOR_AGENT =
      AgentTokens.token(Map.of("project", FOREIGN_PROJECT), "qits:admin", "qits:agent");

  /**
   * A platform service's client token (qits-667): the fixed role {@code qits:system}, its {@code
   * sub} the client id, and no {@code project} claim at all — qits-maintenance filing a MAINTENANCE
   * ticket wherever a stuck release request belongs.
   */
  private static final SecurityIdentity MACHINE =
      AgentTokens.token(Map.of("sub", "dev-qits-maintenance"), "qits:system");

  /**
   * A machine caller that also holds the agent role, bound to {@code OWN_PROJECT}: {@code
   * isBoundAgent} reads {@code qits:system} as a wider role, so it is not bound at all — the
   * helper's rule since before qits-667, and kept.
   */
  private static final SecurityIdentity MACHINE_AGENT =
      AgentTokens.token(
          Map.of("sub", "dev-qits-maintenance", "project", OWN_PROJECT), "qits:system", "qits:agent");

  private static final String REFUSAL = "An agent may write only the entities of its own project.";

  @Inject WorkEntityService workEntities;
  @Inject EntityCommentService ticketComments;
  @Inject DossierService dossierService;
  @Inject EntityTransitionService transitionService;
  @Inject EntityCatalogService catalogService;
  @Inject RepositoryService repositoryService;

  @Inject EntityCommentMapper ticketCommentMapper;
  @Inject EntityIdResolver entityIds;
  @Inject ProjectChangePublisher publisher;
  @Inject DossierPageMapper dossierPageMapper;

  @Inject EpicsTopicHints epicHints;
  @Inject TicketsTopicHints ticketHints;
  @Inject QualifiedEntityIds qualifiedIds;

  /** Everything one test needs: an own-project tree and enough of another project to reach for. */
  private record Seeded(
      String epicId,
      String featureId,
      String taskId,
      String ticketId,
      String commentId,
      String epicCommentId,
      String taskCommentId,
      String epicQualifiedId,
      String epicPageId,
      String ticketPageId,
      String foreignEpicId,
      String foreignTicketId) {}

  private Seeded rows;

  @BeforeEach
  void seed() {
    // The two projects and the one repository a task must name are `domain` rows, so they are
    // written straight through Panache, the way ReleaseRequestAgentBoundsTest writes its pair.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              seedProject(OWN_PROJECT, OWN_REPO);
              seedProject(FOREIGN_PROJECT, null);
            });

    // The entity rows go through their own services, which own their transactions (WritePatience is
    // DbRetry.inNewTx) and therefore must not be called from inside one. Fresh rows per test: the
    // most valuable assertion in this class is that a refused batch wrote NOTHING, and that is only
    // readable against state this test put there.
    var epic =
        workEntities
            .create(
                Archetype.EPIC, OWN_PROJECT, EntityWrite.epic("The own plan", "as filed").withAcceptanceCriteria(TestCriteria.CRITERIA), "seed")
            .entity();
    String epicId = epic.id;
    String featureId =
        workEntities
            .create(
                Archetype.FEATURE, epicId, EntityWrite.feature("The own part", null, null), "seed")
            .entity()
            .id;
    String taskId =
        workEntities
            .create(
                Archetype.TASK,
                featureId,
                EntityWrite.task(OWN_REPO, "The own step", null, null),
                "seed")
            .entity()
            .id;
    String ticketId =
        workEntities
            .create(
                Archetype.TICKET,
                OWN_PROJECT,
                EntityWrite.ticket("The own ticket", "it occurs", null, "BUG", null).withAcceptanceCriteria(TestCriteria.CRITERIA),
                "seed")
            .entity()
            .id;
    String commentId = ticketComments.addComment(ticketId, "a note", "seed").id;
    String epicCommentId = ticketComments.addComment(epicId, "an epic note", "seed").id;
    String taskCommentId = ticketComments.addComment(taskId, "a task note", "seed").id;
    String epicPageId =
        dossierService.create(DossierOwner.epic(epicId), "Epic page", "body", "seed").id;
    String ticketPageId =
        dossierService.create(DossierOwner.ticket(ticketId), "Ticket page", "body", "seed").id;

    String foreignEpicId =
        workEntities
            .create(
                Archetype.EPIC,
                FOREIGN_PROJECT,
                EntityWrite.epic("The other plan", "as filed").withAcceptanceCriteria(TestCriteria.CRITERIA),
                "seed")
            .entity()
            .id;
    String foreignTicketId =
        workEntities
            .create(
                Archetype.TICKET,
                FOREIGN_PROJECT,
                EntityWrite.ticket("The other ticket", "it occurs", null, "BUG", null).withAcceptanceCriteria(TestCriteria.CRITERIA),
                "seed")
            .entity()
            .id;

    rows =
        new Seeded(
            epicId,
            featureId,
            taskId,
            ticketId,
            commentId,
            epicCommentId,
            taskCommentId,
            QualifiedEntityIds.render(OWN_PROJECT, epic.number),
            epicPageId,
            ticketPageId,
            foreignEpicId,
            foreignTicketId);
  }

  private static void seedProject(String projectId, String repoId) {
    if (Project.findById(projectId) == null) {
      Project project = new Project();
      project.id = projectId;
      project.name = projectId;
      project.slug = projectId;
      project.persist();
    }
    if (repoId != null && Repository.findById(repoId) == null) {
      Repository repository = new Repository();
      repository.id = repoId;
      repository.project = Project.findById(projectId);
      repository.mainBranch = "main";
      repository.persist();
    }
  }

  // ---- the doors, driven directly --------------------------------------------------------------

  /**
   * Every per-archetype door is a thin resource over the one {@link EntityRoutes} bean (qits-399),
   * so the doors below share the real bean and differ only in the caller they are handed.
   */
  @Inject EntityRoutes routes;

  private EpicController epics(SecurityIdentity caller) {
    EpicController door = new EpicController();
    door.routes = routes;
    door.identity = caller;
    return door;
  }

  private FeatureController features(SecurityIdentity caller) {
    FeatureController door = new FeatureController();
    door.routes = routes;
    door.identity = caller;
    return door;
  }

  private TaskController tasks(SecurityIdentity caller) {
    TaskController door = new TaskController();
    door.routes = routes;
    door.identity = caller;
    return door;
  }

  private TicketController tickets(SecurityIdentity caller) {
    TicketController door = new TicketController();
    door.routes = routes;
    door.entities = workEntities;
    door.comments = ticketComments;
    door.commentMapper = ticketCommentMapper;
    door.identity = caller;
    door.hints = ticketHints;
    return door;
  }

  private TicketCommentController comments(SecurityIdentity caller) {
    TicketCommentController door = new TicketCommentController();
    door.comments = ticketComments;
    door.commentMapper = ticketCommentMapper;
    door.identity = caller;
    door.hints = ticketHints;
    return door;
  }

  private EntityCommentController threads(SecurityIdentity caller) {
    EntityCommentController door = new EntityCommentController();
    door.ids = entityIds;
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }


  private CommentController commentDoor(SecurityIdentity caller) {
    CommentController door = new CommentController();
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }


  private ProjectEpicsController projectEpics(SecurityIdentity caller) {
    ProjectEpicsController door = new ProjectEpicsController();
    door.routes = routes;
    door.identity = caller;
    return door;
  }

  private ProjectTicketsController projectTickets(SecurityIdentity caller) {
    ProjectTicketsController door = new ProjectTicketsController();
    door.routes = routes;
    door.identity = caller;
    return door;
  }

  private DossierController dossier(SecurityIdentity caller) {
    DossierController door = new DossierController();
    door.dossier = dossierService;
    door.entities = workEntities;
    door.mapper = dossierPageMapper;
    door.identity = caller;
    door.hints = epicHints;
    return door;
  }

  private TicketDossierController ticketDossier(SecurityIdentity caller) {
    TicketDossierController door = new TicketDossierController();
    door.dossier = dossierService;
    door.entities = workEntities;
    door.mapper = dossierPageMapper;
    door.identity = caller;
    door.hints = ticketHints;
    return door;
  }

  private EntityTransitionController entities(SecurityIdentity caller) {
    EntityTransitionController door = new EntityTransitionController();
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }


  private EntityPatchController patches(SecurityIdentity caller) {
    EntityPatchController door = new EntityPatchController();
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }


  private EntityCreateController creates(SecurityIdentity caller) {
    EntityCreateController door = new EntityCreateController();
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }


  private EntityStatusController statuses(SecurityIdentity caller) {
    EntityStatusController door = new EntityStatusController();
    door.ids = entityIds;
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }


  private EntityReadController reads() {
    EntityReadController door = new EntityReadController();
    door.ids = entityIds;
    door.doors = workDoors;
    return door;
  }


  /** The block of any lifecycle archetype (qits-592). */
  @Inject eu.wohlben.qits.projects.api.EntityBlocks entityBlocks;

  private EntityBlockController blocks(SecurityIdentity caller) {
    EntityBlockController door = new EntityBlockController();
    door.ids = entityIds;
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }

  /** The shared implementation every generic door delegates to (qits-969). */
  @Inject WorkEntityDoors workDoors;

  /** The work family's doors, driven like the generic ones (qits-969). */
  private WorkController work(SecurityIdentity caller) {
    WorkController door = new WorkController();
    door.ids = entityIds;
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }

  private WorkCommentController workThreads(SecurityIdentity caller) {
    WorkCommentController door = new WorkCommentController();
    door.ids = entityIds;
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }


  // ---- the request bodies ----------------------------------------------------------------------

  /** The generic create's body for a ticket filed in {@code project} (qits-548). */
  private static JsonNode filedTicket(String project, String title) {
    var body =
        JsonNodeFactory.instance
            .objectNode()
            .put("archetype", "TICKET")
            .put("project", project)
            .put("title", title)
            .put("ticketType", "BUG")
            .put("impetus", "it occurs");
    body.putArray("acceptanceCriteria").add(TestCriteria.CRITERIA.get(0));
    return body;
  }

  /** The generic create's body for a feature under {@code parent}. */
  private static JsonNode partUnder(String parent, String title) {
    return JsonNodeFactory.instance
        .objectNode()
        .put("archetype", "FEATURE")
        .put("parent", parent)
        .put("title", title);
  }

  private static EntityStatusController.EntityStatusMove to(String target) {
    return new EntityStatusController.EntityStatusMove(target);
  }

  private static EpicController.CreateFeatureRequest newFeature(String title) {
    return new EpicController.CreateFeatureRequest(title, null, null);
  }

  private static FeatureController.UpdateFeatureRequest featureEdit(String title) {
    return new FeatureController.UpdateFeatureRequest(title, null, null, false, null, false);
  }

  private static FeatureController.CreateTaskRequest newTask(String title) {
    return new FeatureController.CreateTaskRequest(OWN_REPO, title, null, null);
  }

  private static TaskController.UpdateTaskRequest taskEdit(String title) {
    return new TaskController.UpdateTaskRequest(title, null, null, false, null, false);
  }

  private static ProjectEpicsController.CreateEpicRequest newEpic(String title) {
    return new ProjectEpicsController.CreateEpicRequest(title, null);
  }

  private static ProjectTicketsController.CreateTicketRequest newTicket(String title) {
    return new ProjectTicketsController.CreateTicketRequest(title, "it occurs", null, "BUG", null);
  }

  private static DossierController.WritePage pageWrite(String body, long version) {
    return new DossierController.WritePage(null, body, version);
  }

  private static EntityCommentController.CreateEntityCommentRequest remark(String body) {
    return new EntityCommentController.CreateEntityCommentRequest(body);
  }

  /** A comment's merge patch: the new text, and nothing else it could name. */
  private static JsonNode rewording(String body) {
    return JsonNodeFactory.instance.objectNode().put("body", body);
  }

  /** The smallest merge patch: a new title, everything else left alone. */
  private static JsonNode retitle(String title) {
    return JsonNodeFactory.instance.objectNode().put("title", title);
  }

  /** An epic restated as itself, with a new title — the smallest legal whole post-state. */
  private static EntityTransition epicRenamedTo(String title) {
    return new EntityTransition(
        Archetype.EPIC,
        new EntityTransition.Membership(null, null),
        title,
        null,
        "REPORTED",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** A feature restated as itself, hanging under {@code parent}. */
  /** A feature under {@code parent}, stated REPORTED: a transition mints no status (qits-763). */
  private static EntityTransition featureUnder(String parent, String title) {
    return new EntityTransition(
        Archetype.FEATURE,
        new EntityTransition.Membership(parent, null),
        title,
        null,
        "REPORTED",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static void refused(Executable call) {
    ForbiddenException refusal = assertThrows(ForbiddenException.class, call);
    assertEquals(403, refusal.statusCode());
    assertEquals(REFUSAL, refusal.getMessage());
  }

  // ---- the writes go through for the project the token names ------------------------------------

  /**
   * One representative write per controller, for the agent whose {@code project} claim is this
   * project's. The claim is the only thing that differs from the refusals below — the rows, the
   * bodies and the beans are the same.
   */
  @Test
  void anAgentWritesItsOwnProjectsEntities() {
    String feature = epics(AGENT).createFeature(rows.epicId(), newFeature("A part")).feature().id();
    assertNotNull(feature);

    assertEquals(
        "A renamed part",
        features(AGENT).update(feature, featureEdit("A renamed part")).feature().title());
    String task = features(AGENT).createTask(feature, newTask("A step")).task().id();
    assertNotNull(task);

    assertEquals("A renamed step", tasks(AGENT).update(task, taskEdit("A renamed step")).task().title());
    assertTrue(tasks(AGENT).delete(task).success());

    assertEquals(
        "REFINED",
        tickets(AGENT)
            .transition(rows.ticketId(), new TicketController.TransitionTicketRequest("REFINED"))
            .ticket()
            .status());
    String comment =
        tickets(AGENT)
            .createComment(
                rows.ticketId(), new TicketController.CreateTicketCommentRequest("from the agent"))
            .comment()
            .id();
    assertEquals(
        "corrected",
        comments(AGENT)
            .update(comment, new TicketCommentController.UpdateTicketCommentRequest("corrected"))
            .comment()
            .body());

    // The thread every entity has (qits-551): an epic named by its qualified id, a task by its
    // UUID, and both remarks edited through the one comment door.
    String onEpic = threads(AGENT).create(rows.epicQualifiedId(), remark("on the epic")).comment().id();
    String onTask = threads(AGENT).create(rows.taskId(), remark("on the task")).comment().id();
    assertEquals(rows.epicId(), ticketComments.getComment(onEpic).entityId);
    assertEquals(rows.taskId(), ticketComments.getComment(onTask).entityId);
    assertEquals(
        "epic, corrected",
        commentDoor(AGENT).patch(rows.epicCommentId(), rewording("epic, corrected")).comment().body());
    assertEquals(
        "task, corrected",
        commentDoor(AGENT).patch(rows.taskCommentId(), rewording("task, corrected")).comment().body());

    assertNotNull(projectEpics(AGENT).create(OWN_PROJECT, newEpic("A filed plan")).epic().id());
    assertNotNull(projectTickets(AGENT).create(OWN_PROJECT, newTicket("A filed ticket")).ticket().id());

    assertEquals(
        "rewritten",
        dossier(AGENT).write(rows.epicId(), rows.epicPageId(), pageWrite("rewritten", 0L)).body());
    assertEquals(
        "rewritten",
        ticketDossier(AGENT)
            .write(rows.ticketId(), rows.ticketPageId(), pageWrite("rewritten", 0L))
            .body());

    var written =
        entities(AGENT).transition(Map.of(rows.epicId(), epicRenamedTo("Restated by the batch")));
    assertEquals("Restated by the batch", written.get(rows.epicId()).title());

    assertEquals(
        "Patched by the agent",
        patches(AGENT).patch(rows.ticketId(), retitle("Patched by the agent")).title());

    // The generic create and lifecycle move (qits-548).
    var filed =
        (TransitionedEntity)
            creates(AGENT).create(filedTicket(OWN_PROJECT, "Filed generically")).getEntity();
    assertEquals("TICKET", filed.archetype().name());
    var part =
        (TransitionedEntity)
            creates(AGENT).create(partUnder(rows.epicQualifiedId(), "A generic part")).getEntity();
    assertEquals(rows.epicId(), part.parent());
    assertEquals("REFINED", statuses(AGENT).move(filed.id(), to("REFINED")).status());
  }

  /**
   * <b>A platform service files, reads, comments on, edits and drops a MAINTENANCE ticket in a
   * project it has no tie to</b> (qits-667): its token carries no {@code project} claim, and none of
   * the five generic doors asks it for one. The reporter and the comment's author are the caller's
   * principal — the client id its token's {@code sub} names — as for every other caller.
   */
  @Test
  void aPlatformServiceWorksAMaintenanceTicketInAnyProject() {
    JsonNode body =
        JsonNodeFactory.instance
            .objectNode()
            .put("archetype", "TICKET")
            .put("project", FOREIGN_PROJECT)
            .put("ticketType", "MAINTENANCE")
            .put("title", "A stuck release request")
            .put("impetus", "the gate failed with nobody watching")
            .put("description", "the long report");
    var filed = (TransitionedEntity) creates(MACHINE).create(body).getEntity();
    assertEquals(FOREIGN_PROJECT, filed.projectId());
    assertEquals("MAINTENANCE", filed.ticketType().name());
    assertEquals("dev-qits-maintenance", filed.createdBy());

    var read = reads().get(filed.qualifiedId());
    assertEquals(filed.id(), read.id());
    assertEquals("dev-qits-maintenance", read.createdBy());

    var comment = threads(MACHINE).create(filed.id(), remark("still stuck")).comment();
    assertEquals("dev-qits-maintenance", comment.author());
    assertEquals(
        List.of("still stuck"),
        threads(MACHINE).list(filed.qualifiedId()).entries().stream()
            .map(e -> e.comment().body())
            .toList());

    assertEquals(
        "Still stuck", patches(MACHINE).patch(filed.id(), retitle("Still stuck")).title());

    var dropped = statuses(MACHINE).move(filed.qualifiedId(), to("DROPPED"));
    assertEquals("DROPPED", dropped.status());
    assertEquals("dev-qits-maintenance", dropped.changedBy());
  }

  /**
   * A caller holding both {@code qits:system} and {@code qits:agent} is judged as the platform
   * service, not as the agent: its own-project claim does not stop it writing another project's
   * ticket. That is {@code EntitiesAgentAccess}'s wider-role rule, which predates qits-667.
   */
  @Test
  void aPlatformServiceThatAlsoHoldsTheAgentRoleIsNotBound() {
    assertEquals(
        "Retitled by the machine",
        patches(MACHINE_AGENT)
            .patch(rows.foreignTicketId(), retitle("Retitled by the machine"))
            .title());
  }

  /** And an epic stays a person's on the status door, for a machine as for an agent. */
  @Test
  void aPlatformServiceMovingAnEpicIsRefused() {
    ForbiddenException refusal =
        assertThrows(
            ForbiddenException.class,
            () -> statuses(MACHINE).move(rows.foreignEpicId(), to("REFINED")));
    assertEquals(403, refusal.statusCode());
    assertEquals("REPORTED", workEntities.get(Archetype.EPIC, rows.foreignEpicId()).status);
  }

  /**
   * <b>The generic status door keeps the epic a person's</b> (qits-548): an agent bound to the
   * epic's own project — the binding would let it through — is refused by the epic's rule, exactly
   * as {@code POST /epics/{id}/transition}'s role list refuses it, and the epic does not move.
   */
  @Test
  void anAgentMovingItsOwnEpicThroughTheGenericDoorIsRefused() {
    ForbiddenException refusal =
        assertThrows(
            ForbiddenException.class,
            () -> statuses(AGENT).move(rows.epicQualifiedId(), to("REFINED")));
    assertEquals(403, refusal.statusCode());
    assertEquals("REPORTED", workEntities.get(Archetype.EPIC, rows.epicId()).status);
    assertEquals("REFINED", statuses(OPERATOR).move(rows.epicId(), to("REFINED")).status());
  }

  /**
   * <b>The work family binds exactly as the generic doors do</b> (qits-969): the agent writes its
   * own project's rows through every {@code /work} write, addressed by qualified id, and is refused
   * the other project's with nothing written; a platform service reaches the five doors it reaches on
   * {@code /entities} in a project it has no tie to; an epic's status stays a person's.
   */
  @Test
  void theWorkFamilyBindsAsTheGenericDoorsDo() {
    String ticket = QualifiedEntityIds.render(OWN_PROJECT, workEntities.find(rows.ticketId()).number);
    String foreignTicket =
        QualifiedEntityIds.render(FOREIGN_PROJECT, workEntities.find(rows.foreignTicketId()).number);
    String foreignEpic =
        QualifiedEntityIds.render(FOREIGN_PROJECT, workEntities.find(rows.foreignEpicId()).number);

    // Through, for the agent's own project.
    assertTrue(
        work(AGENT)
            .setBlocked(ticket, new WorkController.WorkBlockRequest(true, "waiting"))
            .block()
            .blocked());
    assertEquals("By the agent", work(AGENT).patch(ticket, retitle("By the agent")).title());
    assertEquals(
        "Restated on /work",
        work(AGENT).put(rows.epicQualifiedId(), epicRenamedTo("Restated on /work")).title());
    assertEquals(
        "Batched on /work",
        work(AGENT)
            .transition(Map.of(rows.epicQualifiedId(), epicRenamedTo("Batched on /work")))
            .get(rows.epicQualifiedId())
            .title());
    String remarkId =
        workThreads(AGENT).add(ticket, new WorkCommentController.WorkCommentCreate("noted")).comment().id();
    assertEquals(
        "noted, corrected",
        workThreads(AGENT).edit(ticket, remarkId, rewording("noted, corrected")).comment().body());
    var filed =
        (TransitionedEntity) work(AGENT).create(filedTicket(OWN_PROJECT, "Filed on /work")).getEntity();
    assertEquals(
        "REFINED",
        work(AGENT).setStatus(filed.qualifiedId(), new WorkController.WorkStatusMove("REFINED")).status());

    // An epic's status stays a person's on /work too.
    refusedEpicMove(() -> work(AGENT).setStatus(rows.epicQualifiedId(), new WorkController.WorkStatusMove("REFINED")));

    // Refused, for another project's agent, with nothing written.
    refused(() -> work(FOREIGN_AGENT).patch(ticket, retitle("Not yours")));
    refused(() -> work(FOREIGN_AGENT).put(rows.epicQualifiedId(), epicRenamedTo("Not yours")));
    refused(
        () -> work(FOREIGN_AGENT).transition(Map.of(rows.epicQualifiedId(), epicRenamedTo("Not yours"))));
    refused(
        () -> workThreads(FOREIGN_AGENT).add(ticket, new WorkCommentController.WorkCommentCreate("x")));
    refused(() -> workThreads(FOREIGN_AGENT).edit(ticket, remarkId, rewording("not yours")));
    refused(() -> work(FOREIGN_AGENT).create(filedTicket(OWN_PROJECT, "Not yours")));
    refused(
        () -> work(FOREIGN_AGENT).setStatus(ticket, new WorkController.WorkStatusMove("REFINED")));
    refused(
        () ->
            work(FOREIGN_AGENT)
                .setBlocked(ticket, new WorkController.WorkBlockRequest(false, null)));
    // Read in a transaction of its own: outside one this thread keeps the rows it read first.
    assertEquals("By the agent", titleOf(rows.ticketId()));
    assertEquals("Batched on /work", titleOf(rows.epicId()));

    // A platform service, unbound, in a project it has no tie to.
    assertEquals(foreignTicket, work(MACHINE).get(foreignTicket).qualifiedId());
    assertEquals("By the machine", work(MACHINE).patch(foreignTicket, retitle("By the machine")).title());
    assertEquals(
        "dev-qits-maintenance",
        workThreads(MACHINE)
            .add(foreignTicket, new WorkCommentController.WorkCommentCreate("from the machine"))
            .comment()
            .author());
    assertEquals(1, workThreads(MACHINE).list(foreignTicket).entries().size());
    assertEquals(
        "DROPPED",
        work(MACHINE).setStatus(foreignTicket, new WorkController.WorkStatusMove("DROPPED")).status());
    refusedEpicMove(() -> work(MACHINE).setStatus(foreignEpic, new WorkController.WorkStatusMove("REFINED")));
  }

  private String titleOf(String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.find(id).title);
  }

  private static void refusedEpicMove(Executable call) {
    ForbiddenException refusal = assertThrows(ForbiddenException.class, call);
    assertEquals(403, refusal.statusCode());
  }

  // ---- and are refused for any other project ----------------------------------------------------

  /** The same calls, by an agent whose token names the other project. */
  @Test
  void anAgentIsRefusedAnotherProjectsEntities() {
    int ticketsBefore = workEntities.listByProject(Archetype.TICKET, OWN_PROJECT).size();
    refused(() -> epics(FOREIGN_AGENT).createFeature(rows.epicId(), newFeature("Not yours")));
    refused(() -> features(FOREIGN_AGENT).update(rows.featureId(), featureEdit("Not yours")));
    refused(() -> features(FOREIGN_AGENT).createTask(rows.featureId(), newTask("Not yours")));
    refused(() -> features(FOREIGN_AGENT).delete(rows.featureId()));
    refused(() -> tasks(FOREIGN_AGENT).update(rows.taskId(), taskEdit("Not yours")));
    refused(() -> tasks(FOREIGN_AGENT).delete(rows.taskId()));
    refused(
        () ->
            tickets(FOREIGN_AGENT)
                .transition(rows.ticketId(), new TicketController.TransitionTicketRequest("REFINED")));
    refused(
        () ->
            tickets(FOREIGN_AGENT)
                .createComment(
                    rows.ticketId(), new TicketController.CreateTicketCommentRequest("not yours")));
    refused(
        () ->
            comments(FOREIGN_AGENT)
                .update(
                    rows.commentId(),
                    new TicketCommentController.UpdateTicketCommentRequest("not yours")));
    refused(() -> projectEpics(FOREIGN_AGENT).create(OWN_PROJECT, newEpic("Not yours")));
    refused(() -> projectTickets(FOREIGN_AGENT).create(OWN_PROJECT, newTicket("Not yours")));
    refused(
        () ->
            dossier(FOREIGN_AGENT)
                .write(rows.epicId(), rows.epicPageId(), pageWrite("not yours", 0L)));
    refused(
        () ->
            ticketDossier(FOREIGN_AGENT)
                .write(rows.ticketId(), rows.ticketPageId(), pageWrite("not yours", 0L)));
    refused(
        () ->
            entities(FOREIGN_AGENT)
                .transition(Map.of(rows.epicId(), epicRenamedTo("Not yours"))));
    refused(() -> patches(FOREIGN_AGENT).patch(rows.ticketId(), retitle("Not yours")));
    refused(() -> patches(FOREIGN_AGENT).patch(rows.epicId(), retitle("Not yours")));
    refused(() -> threads(FOREIGN_AGENT).create(rows.epicId(), remark("not yours")));
    refused(() -> threads(FOREIGN_AGENT).create(rows.epicQualifiedId(), remark("not yours")));
    refused(() -> threads(FOREIGN_AGENT).create(rows.taskId(), remark("not yours")));
    refused(() -> commentDoor(FOREIGN_AGENT).patch(rows.epicCommentId(), rewording("not yours")));
    refused(() -> commentDoor(FOREIGN_AGENT).patch(rows.taskCommentId(), rewording("not yours")));
    refused(() -> creates(FOREIGN_AGENT).create(filedTicket(OWN_PROJECT, "Not yours")));
    refused(() -> creates(FOREIGN_AGENT).create(partUnder(rows.epicId(), "Not yours")));
    refused(() -> statuses(FOREIGN_AGENT).move(rows.ticketId(), to("REFINED")));

    // Nothing moved: every refusal ran before its write.
    assertCommentsUntouched();
    assertEquals("The own plan", workEntities.get(Archetype.EPIC, rows.epicId()).title);
    assertEquals("The own ticket", workEntities.get(Archetype.TICKET, rows.ticketId()).title);
    assertEquals("body", dossierService.get(rows.epicPageId()).body);
    assertEquals("REPORTED", workEntities.get(Archetype.TICKET, rows.ticketId()).status);
    assertEquals(
        ticketsBefore,
        workEntities.listByProject(Archetype.TICKET, OWN_PROJECT).size(),
        "no ticket was filed");
    assertEquals(
        1, workEntities.listChildren(Archetype.FEATURE, rows.epicId()).size(), "no part was added");
  }

  /**
   * <b>No claim covers nothing.</b> An agent role that arrived on a forwarded header carries no
   * token and therefore no {@code project} claim, so it matches no project and is refused every one
   * of these — never waved through on the grounds that there was nothing to compare.
   */
  @Test
  void anAgentWithNoClaimsIsRefusedEverything() {
    refused(() -> epics(CLAIMLESS_AGENT).createFeature(rows.epicId(), newFeature("No claim")));
    refused(() -> features(CLAIMLESS_AGENT).update(rows.featureId(), featureEdit("No claim")));
    refused(() -> features(CLAIMLESS_AGENT).createTask(rows.featureId(), newTask("No claim")));
    refused(() -> features(CLAIMLESS_AGENT).delete(rows.featureId()));
    refused(() -> tasks(CLAIMLESS_AGENT).update(rows.taskId(), taskEdit("No claim")));
    refused(() -> tasks(CLAIMLESS_AGENT).delete(rows.taskId()));
    refused(
        () ->
            tickets(CLAIMLESS_AGENT)
                .transition(rows.ticketId(), new TicketController.TransitionTicketRequest("REFINED")));
    refused(
        () ->
            tickets(CLAIMLESS_AGENT)
                .createComment(
                    rows.ticketId(), new TicketController.CreateTicketCommentRequest("no claim")));
    refused(
        () ->
            comments(CLAIMLESS_AGENT)
                .update(
                    rows.commentId(),
                    new TicketCommentController.UpdateTicketCommentRequest("no claim")));
    refused(() -> projectEpics(CLAIMLESS_AGENT).create(OWN_PROJECT, newEpic("No claim")));
    refused(() -> projectTickets(CLAIMLESS_AGENT).create(OWN_PROJECT, newTicket("No claim")));
    refused(
        () ->
            dossier(CLAIMLESS_AGENT)
                .write(rows.epicId(), rows.epicPageId(), pageWrite("no claim", 0L)));
    refused(
        () ->
            ticketDossier(CLAIMLESS_AGENT)
                .write(rows.ticketId(), rows.ticketPageId(), pageWrite("no claim", 0L)));
    refused(
        () ->
            entities(CLAIMLESS_AGENT).transition(Map.of(rows.epicId(), epicRenamedTo("No claim"))));
    refused(() -> patches(CLAIMLESS_AGENT).patch(rows.ticketId(), retitle("No claim")));
    refused(() -> threads(CLAIMLESS_AGENT).create(rows.epicId(), remark("no claim")));
    refused(() -> threads(CLAIMLESS_AGENT).create(rows.taskId(), remark("no claim")));
    refused(() -> commentDoor(CLAIMLESS_AGENT).patch(rows.epicCommentId(), rewording("no claim")));
    refused(() -> commentDoor(CLAIMLESS_AGENT).patch(rows.taskCommentId(), rewording("no claim")));
    refused(() -> creates(CLAIMLESS_AGENT).create(filedTicket(OWN_PROJECT, "No claim")));
    refused(() -> statuses(CLAIMLESS_AGENT).move(rows.ticketId(), to("REFINED")));
    assertCommentsUntouched();
  }

  /** The seeded threads, exactly as {@link #seed} wrote them: one remark each, text unchanged. */
  private void assertCommentsUntouched() {
    assertEquals(1, ticketComments.listComments(rows.epicId()).size(), "nothing added to the epic");
    assertEquals(1, ticketComments.listComments(rows.taskId()).size(), "nothing added to the task");
    assertEquals("an epic note", ticketComments.getComment(rows.epicCommentId()).body);
    assertEquals("a task note", ticketComments.getComment(rows.taskCommentId()).body);
  }

  // ---- the block of any lifecycle archetype (qits-592) ------------------------------------------

  /**
   * The generic block door binds like every other granted write: an agent blocks and unblocks an
   * epic of its own project, named by its qualified id, and one of another project — or with no
   * claim at all — is refused before the flag or the thread is touched.
   */
  @Test
  void theBlockDoorBindsToTheAgentsOwnProject() {
    var blocking = new EntityBlockController.EntityBlockRequest(true, "not yours to stop");
    refused(() -> blocks(FOREIGN_AGENT).setBlocked(rows.epicId(), blocking));
    refused(() -> blocks(FOREIGN_AGENT).setBlocked(rows.epicQualifiedId(), blocking));
    refused(() -> blocks(CLAIMLESS_AGENT).setBlocked(rows.epicId(), blocking));
    assertCommentsUntouched();
    assertEquals(false, workEntities.get(Archetype.EPIC, rows.epicId()).blocked);

    var own =
        blocks(AGENT)
            .setBlocked(
                rows.epicQualifiedId(),
                new EntityBlockController.EntityBlockRequest(true, "the idp has to release first"))
            .block();
    assertEquals(rows.epicId(), own.entityId());
    assertTrue(own.blocked());
    assertEquals(
        false,
        blocks(AGENT)
            .setBlocked(rows.epicId(), new EntityBlockController.EntityBlockRequest(false, null))
            .block()
            .blocked());
  }

  // ---- a person pays no binding ----------------------------------------------------------------

  /**
   * An operator is judged exactly as before — and so is one whose session also carries the agent
   * role, which is what {@code AgentAccess.isBoundAgent}'s wider-role list is for. Both write a
   * project neither token's {@code project} claim names.
   */
  @Test
  void anAdminIsUnaffectedByTheBinding() {
    assertEquals(
        "Added by a person",
        epics(OPERATOR).createFeature(rows.epicId(), newFeature("Added by a person")).feature().title());
    assertEquals(
        "Added by a person holding both roles",
        epics(OPERATOR_AGENT)
            .createFeature(rows.epicId(), newFeature("Added by a person holding both roles"))
            .feature()
            .title());
  }

  // ---- the batched transition, which binds all or nothing ---------------------------------------

  /** Every id and every parent inside the agent's own project: the batch goes through. */
  @Test
  void aBatchWhollyInsideItsOwnProjectGoesThrough() {
    Map<String, EntityTransition> request = new LinkedHashMap<>();
    request.put(rows.epicId(), epicRenamedTo("The own plan, restated"));
    request.put(rows.featureId(), featureUnder(rows.epicId(), "The own part, restated"));

    var written = entities(AGENT).transition(request);

    assertEquals("The own plan, restated", written.get(rows.epicId()).title());
    assertEquals(rows.epicId(), written.get(rows.featureId()).parent());
  }

  /**
   * <b>The all-or-nothing claim, and the assertion this class exists for.</b> A batch naming one
   * entity of another project is refused <em>entirely</em>: the resolution runs before the write, so
   * the own-project entry in the very same request is not written either. Half a post-state is not a
   * smaller version of it.
   */
  @Test
  void aBatchReachingAnotherProjectIsRefusedWithNothingWritten() {
    Map<String, EntityTransition> request = new LinkedHashMap<>();
    request.put(rows.epicId(), epicRenamedTo("Would have been renamed"));
    request.put(rows.foreignEpicId(), epicRenamedTo("Would have been renamed too"));

    refused(() -> entities(AGENT).transition(request));

    assertEquals("The own plan", workEntities.get(Archetype.EPIC, rows.epicId()).title);
    assertEquals("The other plan", workEntities.get(Archetype.EPIC, rows.foreignEpicId()).title);
    assertEquals(
        rows.epicId(), workEntities.nested(Archetype.FEATURE, rows.featureId()).parentId());
  }

  /**
   * A membership edge is the other way a batch reaches out of itself: the key is this agent's own
   * feature and the <em>parent</em> is somebody else's epic, which is a write into that project's
   * tree whatever the map's keys say.
   */
  @Test
  void aBatchHangingAnOwnEntityUnderAForeignParentIsRefused() {
    Map<String, EntityTransition> request =
        Map.of(rows.featureId(), featureUnder(rows.foreignEpicId(), "The own part"));

    refused(() -> entities(AGENT).transition(request));

    assertEquals(
        rows.epicId(), workEntities.nested(Archetype.FEATURE, rows.featureId()).parentId());
  }

  /**
   * <b>An id that resolves to nothing is not a refusal.</b> The binding can say nothing about a row
   * that does not exist, so the entry falls through to the write, which reports it beside every
   * other violation in its ordinary 400 — the documented contract for an unknown id, and a 403 in
   * its place would answer a question nobody asked.
   */
  @Test
  void anIdNamingNothingFallsThroughToTheOrdinary400() {
    Map<String, EntityTransition> request =
        Map.of("no-such-entity", epicRenamedTo("A ghost"));

    BadRequestException refusal =
        assertThrows(BadRequestException.class, () -> entities(AGENT).transition(request));

    assertEquals(400, refusal.statusCode());
    assertTrue(
        refusal.getMessage().contains("no-such-entity"),
        "the 400 names the id that resolved to nothing: " + refusal.getMessage());
  }

  // ---- the roles, over HTTP ---------------------------------------------------------------------

  /**
   * The five generic doors over HTTP with {@code X-Qits-Roles: qits:system} (qits-667): the role
   * lists admit the machine, and the whole MAINTENANCE round trip goes through in a project it has
   * no tie to, the reporter being the asserted user.
   */
  @Test
  void aForwardedPlatformServicePassesTheFiveDoors() {
    String id =
        asForwardedMachine()
            .body(
                Map.of(
                    "archetype", "TICKET",
                    "project", FOREIGN_PROJECT,
                    "ticketType", "MAINTENANCE",
                    "title", "Stuck over HTTP",
                    "impetus", "the gate failed with nobody watching",
                    "description", "the long report"))
            .post("/projects/api/entities")
            .then()
            .statusCode(201)
            .body("createdBy", org.hamcrest.Matchers.equalTo("qits-maintenance"))
            .extract()
            .path("id");
    asForwardedMachine()
        .get("/projects/api/entities/" + id)
        .then()
        .statusCode(200)
        .body("ticketType", org.hamcrest.Matchers.equalTo("MAINTENANCE"));
    asForwardedMachine()
        .body(Map.of("body", "still stuck"))
        .post("/projects/api/entities/" + id + "/comments")
        .then()
        .statusCode(200)
        .body("comment.author", org.hamcrest.Matchers.equalTo("qits-maintenance"));
    asForwardedMachine()
        .get("/projects/api/entities/" + id + "/comments")
        .then()
        .statusCode(200)
        .body("entries[0].comment.author", org.hamcrest.Matchers.equalTo("qits-maintenance"));
    asForwardedMachine()
        .body(Map.of("title", "Still stuck over HTTP"))
        .patch("/projects/api/entities/" + id)
        .then()
        .statusCode(200)
        .body("title", org.hamcrest.Matchers.equalTo("Still stuck over HTTP"));
    asForwardedMachine()
        .body(Map.of("target", "DROPPED"))
        .post("/projects/api/entities/" + id + "/status")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("DROPPED"));
  }

  private static RequestSpecification asForwardedMachine() {
    return given()
        .header("X-Qits-User", "qits-maintenance")
        .header("X-Qits-Roles", "qits:system")
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification asForwardedAgent() {
    return given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", "qits:agent")
        .contentType(ContentType.JSON);
  }

  /**
   * The four admin-only writes are refused at the door, before any body runs. The id names nothing
   * on purpose: the refusal must come from the role list rather than from anything the method could
   * have decided, and a 403 here against the 404 below is exactly that difference.
   */
  @Test
  void theLifecycleMoveAndTheDeletesAreRefusedAtTheDoor() {
    asForwardedAgent()
        .body(Map.of("target", "REFINED"))
        .post("/projects/api/epics/no-such-entity/transition")
        .then()
        .statusCode(403);
    asForwardedAgent().delete("/projects/api/epics/no-such-entity").then().statusCode(403);
    asForwardedAgent().delete("/projects/api/tickets/no-such-entity").then().statusCode(403);
    asForwardedAgent().delete("/projects/api/ticket-comments/no-such-entity").then().statusCode(403);
    asForwardedAgent().delete("/projects/api/comments/no-such-entity").then().statusCode(403);
    // And on a real comment: the door refuses before any body runs, so the remark stays.
    asForwardedAgent().delete("/projects/api/comments/" + rows.epicCommentId()).then().statusCode(403);
    assertEquals("an epic note", ticketComments.getComment(rows.epicCommentId()).body);
  }

  /**
   * A granted route gets past the role check and runs: it resolves the id first — which is the
   * binding's precondition and the reason an unknown id is still a 404 — and answers 404 rather than
   * the 403 the door would have given. The reads are unrestricted as they always were.
   */
  @Test
  void aGrantedRouteGetsPastTheRoleCheck() {
    asForwardedAgent()
        .body(Map.of("title", "no such feature"))
        .put("/projects/api/features/no-such-entity")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("target", "REFINED"))
        .post("/projects/api/tickets/no-such-entity/transition")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("body", "no such comment"))
        .put("/projects/api/ticket-comments/no-such-entity")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("title", "no such entity"))
        .patch("/projects/api/entities/no-such-entity")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("body", "no such entity"))
        .post("/projects/api/entities/no-such-entity/comments")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("body", "no such comment"))
        .patch("/projects/api/comments/no-such-comment")
        .then()
        .statusCode(404);

    asForwardedAgent().get("/projects/api/epics/" + rows.epicId()).then().statusCode(200);
  }

  /**
   * And on a row that does exist, a forwarded agent is refused by the binding rather than by the
   * door — the tokenless half of {@link #anAgentWithNoClaimsIsRefusedEverything}, observed from
   * outside with the message on the wire. This is as far as HTTP reaches: there is no way to put a
   * token carrying a {@code project} claim in front of a {@code @QuarkusTest}, which is why every
   * through-case above is driven directly.
   */
  @Test
  void aForwardedAgentCarriesNoClaimAndIsRefusedARealRow() {
    asForwardedAgent()
        .body(Map.of("title", "from a header"))
        .post("/projects/api/epics/" + rows.epicId() + "/features")
        .then()
        .statusCode(403)
        .body("message", org.hamcrest.Matchers.equalTo(REFUSAL));

    assertEquals(
        1, workEntities.listChildren(Archetype.FEATURE, rows.epicId()).size(), "nothing was added");
  }
}
