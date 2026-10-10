package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.entities.control.DossierService;
import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.DossierOwner;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.entities.mapper.DossierPageMapper;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
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
 * <p><b>The doors are the {@code /work} family's</b> — since qits-976 deleted the per-archetype and
 * {@code /entities} controllers, the only work-entity write doors there are. The granted writes are
 * driven for the through-case and the refusal: the binding is one helper called as the first
 * statement of each route ({@code EntitiesAgentAccess}). What is <em>not</em> representative is the
 * batched transition, which has a binding of its own — all or nothing over a whole request — and
 * that one is tested in full.
 *
 * <p><b>{@code WorkDossierAssetController.inline} is the one granted route not driven here.</b> Its
 * write copies a figure out of a live refinement's attachments, which needs a refinement row, a
 * container-side prompt attachment and the {@code DossierFigures} crossing to stand up — wholly
 * disproportionate to re-reading one line of binding. Its role list is pinned by {@code
 * AgentReadAccessTest} and its binding is the same {@code requireProject} call as the doors below.
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
   * An admin workspace's agent (qits-628 follow-up): {@code qits:admin-agent} and {@code
   * qits:agent}, no {@code qits:admin} at all. The owner's rule is "for now it may use everything
   * {@code qits:admin} may use", so {@code isBoundAgent}'s wider-role list must read this one as
   * unbound too, exactly like {@link #OPERATOR_AGENT}.
   */
  private static final SecurityIdentity ADMIN_AGENT_OPERATOR =
      AgentTokens.token(Map.of("project", FOREIGN_PROJECT), "qits:admin-agent", "qits:agent");

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

  @Inject EntityIdResolver entityIds;
  @Inject ProjectChangePublisher publisher;
  @Inject DossierPageMapper dossierPageMapper;

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

  /** The shared implementation every {@code /work} door delegates to (qits-969). */
  @Inject WorkEntityDoors workDoors;

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

  private WorkChildrenController children(SecurityIdentity caller) {
    WorkChildrenController door = new WorkChildrenController();
    door.ids = entityIds;
    door.doors = workDoors;
    door.identity = caller;
    return door;
  }

  private WorkDossierController dossier(SecurityIdentity caller) {
    WorkDossierController door = new WorkDossierController();
    door.ids = entityIds;
    door.dossier = dossierService;
    door.mapper = dossierPageMapper;
    door.publisher = publisher;
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

  /** The generic create's body for an epic filed in {@code project}. */
  private static JsonNode filedEpic(String project, String title) {
    return JsonNodeFactory.instance
        .objectNode()
        .put("archetype", "EPIC")
        .put("project", project)
        .put("title", title);
  }

  /** A child's body: the path names the parent and decides the kind. */
  private static JsonNode child(String title) {
    return JsonNodeFactory.instance.objectNode().put("title", title);
  }

  /** A task's body under a feature: a task must name a repository of the parent's project. */
  private static JsonNode step(String title) {
    return JsonNodeFactory.instance.objectNode().put("title", title).put("repositoryId", OWN_REPO);
  }

  private static WorkController.WorkStatusMove to(String target) {
    return new WorkController.WorkStatusMove(target);
  }

  private static WorkDossierController.WorkDossierPageWrite pageWrite(String body, long version) {
    return new WorkDossierController.WorkDossierPageWrite(null, body, version);
  }

  private static WorkCommentController.WorkCommentCreate remark(String body) {
    return new WorkCommentController.WorkCommentCreate(body);
  }

  private static TransitionedEntity created(jakarta.ws.rs.core.Response response) {
    assertEquals(201, response.getStatus());
    return (TransitionedEntity) response.getEntity();
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
   * Every granted write, for the agent whose {@code project} claim is this project's, addressed by
   * qualified id and by UUID alike. The claim is the only thing that differs from the refusals below
   * — the rows, the bodies and the beans are the same.
   */
  @Test
  void anAgentWritesItsOwnProjectsEntities() {
    String ticket = qualified(OWN_PROJECT, rows.ticketId());

    // A part and a step through the children door, edited and the step deleted (add_feature,
    // update_feature, add_task, update_task, remove_task).
    String feature = created(children(AGENT).create(rows.epicQualifiedId(), child("A part"))).id();
    assertEquals("A renamed part", work(AGENT).patch(feature, retitle("A renamed part")).title());
    String task = created(children(AGENT).create(feature, step("A step"))).id();
    assertEquals("A renamed step", work(AGENT).patch(task, retitle("A renamed step")).title());
    assertTrue(work(AGENT).delete(task).success());

    // A REPORTED ticket's refine phase is the work a block can stop; a REFINED one has none running.
    assertTrue(work(AGENT).setBlocked(ticket, new WorkController.WorkBlockRequest(true, "waiting")).block().blocked());
    assertFalse(work(AGENT).setBlocked(ticket, new WorkController.WorkBlockRequest(false, null)).block().blocked());
    assertEquals("REFINED", work(AGENT).setStatus(ticket, to("REFINED")).status());

    // The thread every entity has (qits-551): a ticket and an epic named by qualified id, a task by
    // its UUID, and the remarks edited through the one comment door.
    String comment = workThreads(AGENT).add(ticket, remark("from the agent")).comment().id();
    assertEquals(
        "corrected", workThreads(AGENT).edit(ticket, comment, rewording("corrected")).comment().body());
    String onEpic = workThreads(AGENT).add(rows.epicQualifiedId(), remark("on the epic")).comment().id();
    String onTask = workThreads(AGENT).add(rows.taskId(), remark("on the task")).comment().id();
    assertEquals(rows.epicId(), ticketComments.getComment(onEpic).entityId);
    assertEquals(rows.taskId(), ticketComments.getComment(onTask).entityId);
    assertEquals(
        "epic, corrected",
        workThreads(AGENT)
            .edit(rows.epicId(), rows.epicCommentId(), rewording("epic, corrected"))
            .comment()
            .body());
    assertEquals(
        "task, corrected",
        workThreads(AGENT)
            .edit(rows.taskId(), rows.taskCommentId(), rewording("task, corrected"))
            .comment()
            .body());

    // The roots a project files (propose_epic, create_ticket).
    assertEquals("EPIC", created(work(AGENT).create(filedEpic(OWN_PROJECT, "A filed plan"))).archetype().name());
    var filed = created(work(AGENT).create(filedTicket(OWN_PROJECT, "A filed ticket")));
    assertEquals("TICKET", filed.archetype().name());
    assertEquals("REFINED", work(AGENT).setStatus(filed.qualifiedId(), to("REFINED")).status());
    var part = created(work(AGENT).create(partUnder(rows.epicQualifiedId(), "A generic part")));
    assertEquals(rows.epicId(), part.parent());

    // Both owners' dossiers.
    assertEquals(
        "rewritten",
        dossier(AGENT).put(rows.epicQualifiedId(), rows.epicPageId(), pageWrite("rewritten", 0L)).body());
    assertEquals(
        "rewritten",
        dossier(AGENT).put(ticket, rows.ticketPageId(), pageWrite("rewritten", 0L)).body());

    // The whole post-state, batched and at the entity's own address.
    assertEquals(
        "Restated by the batch",
        work(AGENT)
            .transition(Map.of(rows.epicQualifiedId(), epicRenamedTo("Restated by the batch")))
            .get(rows.epicQualifiedId())
            .title());
    assertEquals(
        "Restated in place",
        work(AGENT).put(rows.epicQualifiedId(), epicRenamedTo("Restated in place")).title());
    assertEquals("Patched by the agent", work(AGENT).patch(ticket, retitle("Patched by the agent")).title());
  }

  /**
   * <b>A platform service files, reads, comments on, edits and drops a MAINTENANCE ticket in a
   * project it has no tie to</b> (qits-667): its token carries no {@code project} claim, and none of
   * the doors it reaches asks it for one. The reporter and the comment's author are the caller's
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
    var filed = created(work(MACHINE).create(body));
    assertEquals(FOREIGN_PROJECT, filed.projectId());
    assertEquals("MAINTENANCE", filed.ticketType().name());
    assertEquals("dev-qits-maintenance", filed.createdBy());

    var read = work(MACHINE).get(filed.qualifiedId());
    assertEquals(filed.id(), read.id());
    assertEquals("dev-qits-maintenance", read.createdBy());

    var comment = workThreads(MACHINE).add(filed.id(), remark("still stuck")).comment();
    assertEquals("dev-qits-maintenance", comment.author());
    assertEquals(
        List.of("still stuck"),
        workThreads(MACHINE).list(filed.qualifiedId()).entries().stream()
            .map(e -> e.comment().body())
            .toList());

    assertEquals("Still stuck", work(MACHINE).patch(filed.id(), retitle("Still stuck")).title());

    var dropped = work(MACHINE).setStatus(filed.qualifiedId(), to("DROPPED"));
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
        work(MACHINE_AGENT).patch(rows.foreignTicketId(), retitle("Retitled by the machine")).title());
  }

  /** And an epic stays a person's on the status door, for a machine as for an agent. */
  @Test
  void aPlatformServiceMovingAnEpicIsRefused() {
    ForbiddenException refusal =
        assertThrows(
            ForbiddenException.class,
            () -> work(MACHINE).setStatus(rows.foreignEpicId(), to("REFINED")));
    assertEquals(403, refusal.statusCode());
    assertEquals("REPORTED", workEntities.get(Archetype.EPIC, rows.foreignEpicId()).status);
  }

  /**
   * <b>The status door keeps the epic a person's</b> (qits-548): an agent bound to the epic's own
   * project — the binding would let it through — is refused by the epic's rule, and the epic does
   * not move. A person moves it.
   */
  @Test
  void anAgentMovingItsOwnEpicIsRefused() {
    ForbiddenException refusal =
        assertThrows(
            ForbiddenException.class,
            () -> work(AGENT).setStatus(rows.epicQualifiedId(), to("REFINED")));
    assertEquals(403, refusal.statusCode());
    assertEquals("REPORTED", workEntities.get(Archetype.EPIC, rows.epicId()).status);
    assertEquals("REFINED", work(OPERATOR).setStatus(rows.epicId(), to("REFINED")).status());
  }

  /**
   * <b>The delete is the agent's for a feature or a task and a person's for a root</b> (qits-970):
   * the door admits the role, and refuses an epic's and a ticket's delete inside, the agent's own
   * project or not.
   */
  @Test
  void anAgentDeletesAPartButNeverARoot() {
    ForbiddenException epic =
        assertThrows(ForbiddenException.class, () -> work(AGENT).delete(rows.epicQualifiedId()));
    assertEquals(403, epic.statusCode());
    ForbiddenException ticket =
        assertThrows(ForbiddenException.class, () -> work(AGENT).delete(rows.ticketId()));
    assertEquals(403, ticket.statusCode());
    assertNotNull(workEntities.find(rows.epicId()));
    assertNotNull(workEntities.find(rows.ticketId()));

    assertTrue(work(AGENT).delete(rows.taskId()).success());
  }

  private String titleOf(String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.find(id).title);
  }

  private String qualified(String project, String id) {
    return QualifiedEntityIds.render(project, workEntities.find(id).number);
  }

  // ---- and are refused for any other project ----------------------------------------------------

  /** The same calls, by an agent whose token names the other project. */
  @Test
  void anAgentIsRefusedAnotherProjectsEntities() {
    assertRefusedEverything(FOREIGN_AGENT);
  }

  /**
   * <b>No claim covers nothing.</b> An agent role that arrived on a forwarded header carries no
   * token and therefore no {@code project} claim, so it matches no project and is refused every one
   * of these — never waved through on the grounds that there was nothing to compare.
   */
  @Test
  void anAgentWithNoClaimsIsRefusedEverything() {
    assertRefusedEverything(CLAIMLESS_AGENT);
  }

  /** Every granted write, against the own project's rows, refused — and nothing written. */
  private void assertRefusedEverything(SecurityIdentity caller) {
    String ticket = qualified(OWN_PROJECT, rows.ticketId());
    int ticketsBefore = workEntities.listByProject(Archetype.TICKET, OWN_PROJECT).size();

    refused(() -> children(caller).create(rows.epicId(), child("Not yours")));
    refused(() -> children(caller).create(rows.featureId(), step("Not yours")));
    refused(() -> work(caller).patch(rows.featureId(), retitle("Not yours")));
    refused(() -> work(caller).delete(rows.featureId()));
    refused(() -> work(caller).patch(rows.taskId(), retitle("Not yours")));
    refused(() -> work(caller).delete(rows.taskId()));
    refused(() -> work(caller).setStatus(ticket, to("REFINED")));
    refused(() -> work(caller).setBlocked(ticket, new WorkController.WorkBlockRequest(true, "x")));
    refused(() -> workThreads(caller).add(ticket, remark("not yours")));
    refused(() -> workThreads(caller).add(rows.epicQualifiedId(), remark("not yours")));
    refused(() -> workThreads(caller).add(rows.taskId(), remark("not yours")));
    refused(() -> workThreads(caller).edit(ticket, rows.commentId(), rewording("not yours")));
    refused(
        () -> workThreads(caller).edit(rows.epicId(), rows.epicCommentId(), rewording("not yours")));
    refused(
        () -> workThreads(caller).edit(rows.taskId(), rows.taskCommentId(), rewording("not yours")));
    refused(() -> work(caller).create(filedEpic(OWN_PROJECT, "Not yours")));
    refused(() -> work(caller).create(filedTicket(OWN_PROJECT, "Not yours")));
    refused(() -> work(caller).create(partUnder(rows.epicId(), "Not yours")));
    refused(
        () ->
            dossier(caller).put(rows.epicId(), rows.epicPageId(), pageWrite("not yours", 0L)));
    refused(() -> dossier(caller).put(ticket, rows.ticketPageId(), pageWrite("not yours", 0L)));
    refused(
        () -> work(caller).transition(Map.of(rows.epicQualifiedId(), epicRenamedTo("Not yours"))));
    refused(() -> work(caller).put(rows.epicQualifiedId(), epicRenamedTo("Not yours")));
    refused(() -> work(caller).patch(ticket, retitle("Not yours")));
    refused(() -> work(caller).patch(rows.epicId(), retitle("Not yours")));

    // Nothing moved: every refusal ran before its write. Read in a transaction of its own: outside
    // one this thread keeps the rows it read first.
    assertCommentsUntouched();
    assertEquals("The own plan", titleOf(rows.epicId()));
    assertEquals("The own ticket", titleOf(rows.ticketId()));
    assertEquals("body", dossierService.get(rows.epicPageId()).body);
    assertEquals("body", dossierService.get(rows.ticketPageId()).body);
    assertEquals("REPORTED", workEntities.get(Archetype.TICKET, rows.ticketId()).status);
    assertEquals(false, workEntities.get(Archetype.TICKET, rows.ticketId()).blocked);
    assertEquals(
        ticketsBefore,
        workEntities.listByProject(Archetype.TICKET, OWN_PROJECT).size(),
        "no ticket was filed");
    assertEquals(
        1, workEntities.listChildren(Archetype.FEATURE, rows.epicId()).size(), "no part was added");
    assertEquals(
        1, workEntities.listChildren(Archetype.TASK, rows.featureId()).size(), "no step was added");
  }

  /** The seeded threads, exactly as {@link #seed} wrote them: one remark each, text unchanged. */
  private void assertCommentsUntouched() {
    assertEquals(1, ticketComments.listComments(rows.ticketId()).size(), "nothing added to the ticket");
    assertEquals(1, ticketComments.listComments(rows.epicId()).size(), "nothing added to the epic");
    assertEquals(1, ticketComments.listComments(rows.taskId()).size(), "nothing added to the task");
    assertEquals("a note", ticketComments.getComment(rows.commentId()).body);
    assertEquals("an epic note", ticketComments.getComment(rows.epicCommentId()).body);
    assertEquals("a task note", ticketComments.getComment(rows.taskCommentId()).body);
  }

  // ---- the block of any lifecycle archetype (qits-592) ------------------------------------------

  /**
   * The block door binds like every other granted write: an agent blocks and unblocks an epic of
   * its own project, named by its qualified id, and one of another project — or with no claim at all
   * — is refused before the flag or the thread is touched.
   */
  @Test
  void theBlockDoorBindsToTheAgentsOwnProject() {
    var blocking = new WorkController.WorkBlockRequest(true, "not yours to stop");
    refused(() -> work(FOREIGN_AGENT).setBlocked(rows.epicId(), blocking));
    refused(() -> work(FOREIGN_AGENT).setBlocked(rows.epicQualifiedId(), blocking));
    refused(() -> work(CLAIMLESS_AGENT).setBlocked(rows.epicId(), blocking));
    assertCommentsUntouched();
    assertEquals(false, workEntities.get(Archetype.EPIC, rows.epicId()).blocked);

    var own =
        work(AGENT)
            .setBlocked(
                rows.epicQualifiedId(),
                new WorkController.WorkBlockRequest(true, "the idp has to release first"))
            .block();
    assertEquals(rows.epicId(), own.entityId());
    assertTrue(own.blocked());
    assertEquals(
        false,
        work(AGENT)
            .setBlocked(rows.epicId(), new WorkController.WorkBlockRequest(false, null))
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
        created(children(OPERATOR).create(rows.epicId(), child("Added by a person"))).title());
    assertEquals(
        "Added by a person holding both roles",
        created(
                children(OPERATOR_AGENT)
                    .create(rows.epicId(), child("Added by a person holding both roles")))
            .title());
  }

  /**
   * {@code qits:admin-agent} is wider too (qits-628 follow-up), with no {@code qits:admin} on the
   * token at all: a write to a project the token's {@code project} claim does not name still goes
   * through, exactly as {@link #anAdminIsUnaffectedByTheBinding} proves for {@code qits:admin}.
   */
  @Test
  void anAdminAgentIsUnaffectedByTheBindingWithNoAdminRole() {
    assertEquals(
        "Added by an admin workspace's agent",
        created(
                children(ADMIN_AGENT_OPERATOR)
                    .create(rows.epicId(), child("Added by an admin workspace's agent")))
            .title());
  }

  // ---- the batched transition, which binds all or nothing ---------------------------------------

  /** Every id and every parent inside the agent's own project: the batch goes through. */
  @Test
  void aBatchWhollyInsideItsOwnProjectGoesThrough() {
    Map<String, EntityTransition> request = new LinkedHashMap<>();
    request.put(rows.epicId(), epicRenamedTo("The own plan, restated"));
    request.put(rows.featureId(), featureUnder(rows.epicId(), "The own part, restated"));

    var written = work(AGENT).transition(request);

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

    refused(() -> work(AGENT).transition(request));

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

    refused(() -> work(AGENT).transition(request));

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
        assertThrows(BadRequestException.class, () -> work(AGENT).transition(request));

    assertEquals(400, refusal.statusCode());
    assertTrue(
        refusal.getMessage().contains("no-such-entity"),
        "the 400 names the id that resolved to nothing: " + refusal.getMessage());
  }

  // ---- the roles, over HTTP ---------------------------------------------------------------------

  /**
   * The doors a platform service reaches over HTTP with {@code X-Qits-Roles: qits:system}
   * (qits-667): the role lists admit the machine, and the whole MAINTENANCE round trip goes through
   * in a project it has no tie to, the reporter being the asserted user.
   */
  @Test
  void aForwardedPlatformServicePassesItsDoors() {
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
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .body("createdBy", org.hamcrest.Matchers.equalTo("qits-maintenance"))
            .extract()
            .path("id");
    asForwardedMachine()
        .get("/projects/api/work/" + id)
        .then()
        .statusCode(200)
        .body("ticketType", org.hamcrest.Matchers.equalTo("MAINTENANCE"));
    asForwardedMachine()
        .body(Map.of("body", "still stuck"))
        .post("/projects/api/work/" + id + "/comments")
        .then()
        .statusCode(200)
        .body("comment.author", org.hamcrest.Matchers.equalTo("qits-maintenance"));
    asForwardedMachine()
        .get("/projects/api/work/" + id + "/comments")
        .then()
        .statusCode(200)
        .body("entries[0].comment.author", org.hamcrest.Matchers.equalTo("qits-maintenance"));
    asForwardedMachine()
        .body(Map.of("title", "Still stuck over HTTP"))
        .patch("/projects/api/work/" + id)
        .then()
        .statusCode(200)
        .body("title", org.hamcrest.Matchers.equalTo("Still stuck over HTTP"));
    asForwardedMachine()
        .body(Map.of("target", "DROPPED"))
        .post("/projects/api/work/" + id + "/status")
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

  private static RequestSpecification asForwardedCiRun() {
    return given()
        .header("X-Qits-User", "qits-ci")
        .header("X-Qits-Roles", "qits:ci-run")
        .contentType(ContentType.JSON);
  }

  /**
   * {@code qits:ci-run} reaches {@code GET /work/{qualifiedId}} too (qits-1142): the CI step's run
   * token may read the work item it is building for, and nothing else on this surface — a write is
   * refused at the door, which a method-level {@code @RolesAllowed} replacing the class's is what
   * makes true of this one route alone.
   */
  @Test
  void aForwardedCiRunTokenReadsTheWorkItemAndCannotCreate() {
    asForwardedCiRun()
        .get("/projects/api/work/" + rows.ticketId())
        .then()
        .statusCode(200)
        .body("id", org.hamcrest.Matchers.equalTo(rows.ticketId()));
    asForwardedCiRun()
        .body(
            Map.of(
                "archetype", "TICKET",
                "project", OWN_PROJECT,
                "ticketType", "BUG",
                "title", "ci-run may not create",
                "impetus", "it occurs"))
        .post("/projects/api/work")
        .then()
        .statusCode(403);
  }

  /**
   * The admin-only writes are refused at the door, before any body runs. The id names nothing on
   * purpose: the refusal must come from the role list rather than from anything the method could
   * have decided, and a 403 here against the 404 below is exactly that difference.
   */
  @Test
  void theSignOffsAndTheCommentDeleteAreRefusedAtTheDoor() {
    asForwardedAgent()
        .delete("/projects/api/work/no-such-entity/comments/no-such-comment")
        .then()
        .statusCode(403);
    asForwardedAgent()
        .body(Map.of())
        .post("/projects/api/work/no-such-entity/members/no-such-member/criteria/no-such/approve")
        .then()
        .statusCode(403);
    asForwardedAgent().post("/projects/api/work/no-such-entity/dispatch").then().statusCode(403);
    asForwardedAgent().post("/projects/api/work/no-such-entity/refinement").then().statusCode(403);
    // And on a real comment: the door refuses before any body runs, so the remark stays.
    asForwardedAgent()
        .delete("/projects/api/work/" + rows.epicId() + "/comments/" + rows.epicCommentId())
        .then()
        .statusCode(403);
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
        .body(Map.of("title", "no such entity"))
        .patch("/projects/api/work/no-such-entity")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("target", "REFINED"))
        .post("/projects/api/work/no-such-entity/status")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("body", "no such entity"))
        .post("/projects/api/work/no-such-entity/comments")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("body", "no such comment"))
        .patch("/projects/api/work/" + rows.epicId() + "/comments/no-such-comment")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("title", "no such parent"))
        .post("/projects/api/work/no-such-entity/children")
        .then()
        .statusCode(404);
    asForwardedAgent().delete("/projects/api/work/no-such-entity").then().statusCode(404);

    asForwardedAgent().get("/projects/api/work/" + rows.epicId()).then().statusCode(200);
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
        .post("/projects/api/work/" + rows.epicId() + "/children")
        .then()
        .statusCode(403)
        .body("message", org.hamcrest.Matchers.equalTo(REFUSAL));

    assertEquals(
        1, workEntities.listChildren(Archetype.FEATURE, rows.epicId()).size(), "nothing was added");
  }
}
