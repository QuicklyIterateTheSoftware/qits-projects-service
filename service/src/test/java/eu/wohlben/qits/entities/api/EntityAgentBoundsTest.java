package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.DossierService;
import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.control.EntityTransitionService;
import eu.wohlben.qits.entities.control.EpicService;
import eu.wohlben.qits.entities.control.FeatureService;
import eu.wohlben.qits.entities.control.TaskService;
import eu.wohlben.qits.entities.control.TicketService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.DossierOwner;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.entities.mapper.DossierPageMapper;
import eu.wohlben.qits.entities.mapper.TicketCommentMapper;
import eu.wohlben.qits.entities.mapper.WorkEntityMapper;
import eu.wohlben.qits.projects.api.DispatchedWorkspaces;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.api.TicketPhaseAdvance;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
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
 * the refusal, rather than all twenty-two: the binding is one helper called as the first statement
 * of each route ({@code EntitiesAgentAccess}), so what a second route on the same controller would
 * add is a second reading of one line. What is <em>not</em> representative is the batched
 * transition, which has a binding of its own — all or nothing over a whole request — and that one is
 * tested in full.
 *
 * <p><b>{@code DossierAssetController.inline} is the one granted route not driven here.</b> Its
 * write copies a figure out of a live refinement's attachments, which needs a refinement row, a
 * container-side prompt attachment and the {@code DossierFigures} crossing to stand up — wholly
 * disproportionate to re-reading one line of binding. Its role list is pinned by {@code
 * AgentReadAccessTest} and its binding is the same {@code requireProject} call as the fourteen
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

  private static final String REFUSAL = "An agent may write only the entities of its own project.";

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject TicketService ticketService;
  @Inject DossierService dossierService;
  @Inject EntityTransitionService transitionService;
  @Inject EntityCatalogService catalogService;

  @Inject WorkEntityMapper workEntityMapper;
  @Inject TicketCommentMapper ticketCommentMapper;
  @Inject DossierPageMapper dossierPageMapper;

  @Inject EpicsTopicHints epicHints;
  @Inject TicketsTopicHints ticketHints;
  @Inject QualifiedEntityIds qualifiedIds;
  @Inject DispatchedWorkspaces dispatchedWorkspaces;
  @Inject TicketPhaseAdvance phaseAdvance;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;

  /** Everything one test needs: an own-project tree and enough of another project to reach for. */
  private record Seeded(
      String epicId,
      String featureId,
      String taskId,
      String ticketId,
      String commentId,
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
    String epicId = epicService.create(OWN_PROJECT, "The own plan", "as filed", "seed").id;
    String featureId = featureService.create(epicId, "The own part", null, null, "seed").entity().id;
    String taskId =
        taskService.create(featureId, OWN_REPO, "The own step", null, null, "seed").entity().id;
    String ticketId =
        ticketService
            .create(OWN_PROJECT, "The own ticket", "it occurs", null, "BUG", null, "seed")
            .id;
    String commentId = ticketService.addComment(ticketId, "a note", "seed").id;
    String epicPageId =
        dossierService.create(DossierOwner.epic(epicId), "Epic page", "body", "seed").id;
    String ticketPageId =
        dossierService.create(DossierOwner.ticket(ticketId), "Ticket page", "body", "seed").id;

    String foreignEpicId =
        epicService.create(FOREIGN_PROJECT, "The other plan", "as filed", "seed").id;
    String foreignTicketId =
        ticketService
            .create(FOREIGN_PROJECT, "The other ticket", "it occurs", null, "BUG", null, "seed")
            .id;

    rows =
        new Seeded(
            epicId,
            featureId,
            taskId,
            ticketId,
            commentId,
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

  private EpicController epics(SecurityIdentity caller) {
    EpicController door = new EpicController();
    door.epicService = epicService;
    door.featureService = featureService;
    door.workEntityMapper = workEntityMapper;
    door.identity = caller;
    door.hints = epicHints;
    door.dispatchedWorkspaces = dispatchedWorkspaces;
    door.qualifiedIds = qualifiedIds;
    return door;
  }

  private FeatureController features(SecurityIdentity caller) {
    FeatureController door = new FeatureController();
    door.featureService = featureService;
    door.taskService = taskService;
    door.epicService = epicService;
    door.workEntityMapper = workEntityMapper;
    door.repositoryService = repositoryService;
    door.identity = caller;
    door.hints = epicHints;
    door.qualifiedIds = qualifiedIds;
    return door;
  }

  private TaskController tasks(SecurityIdentity caller) {
    TaskController door = new TaskController();
    door.taskService = taskService;
    door.workEntityMapper = workEntityMapper;
    door.identity = caller;
    door.hints = epicHints;
    door.qualifiedIds = qualifiedIds;
    return door;
  }

  private TicketController tickets(SecurityIdentity caller) {
    TicketController door = new TicketController();
    door.ticketService = ticketService;
    door.workEntityMapper = workEntityMapper;
    door.commentMapper = ticketCommentMapper;
    door.identity = caller;
    door.hints = ticketHints;
    door.dispatchedWorkspaces = dispatchedWorkspaces;
    door.qualifiedIds = qualifiedIds;
    door.phaseAdvance = phaseAdvance;
    return door;
  }

  private TicketCommentController comments(SecurityIdentity caller) {
    TicketCommentController door = new TicketCommentController();
    door.ticketService = ticketService;
    door.commentMapper = ticketCommentMapper;
    door.identity = caller;
    door.hints = ticketHints;
    return door;
  }

  private ProjectEpicsController projectEpics(SecurityIdentity caller) {
    ProjectEpicsController door = new ProjectEpicsController();
    door.epicService = epicService;
    door.workEntityMapper = workEntityMapper;
    door.projectService = projectService;
    door.identity = caller;
    door.hints = epicHints;
    door.dispatchedWorkspaces = dispatchedWorkspaces;
    return door;
  }

  private ProjectTicketsController projectTickets(SecurityIdentity caller) {
    ProjectTicketsController door = new ProjectTicketsController();
    door.ticketService = ticketService;
    door.workEntityMapper = workEntityMapper;
    door.projectService = projectService;
    door.identity = caller;
    door.hints = ticketHints;
    door.dispatchedWorkspaces = dispatchedWorkspaces;
    return door;
  }

  private DossierController dossier(SecurityIdentity caller) {
    DossierController door = new DossierController();
    door.dossier = dossierService;
    door.epicService = epicService;
    door.mapper = dossierPageMapper;
    door.identity = caller;
    door.hints = epicHints;
    return door;
  }

  private TicketDossierController ticketDossier(SecurityIdentity caller) {
    TicketDossierController door = new TicketDossierController();
    door.dossier = dossierService;
    door.ticketService = ticketService;
    door.mapper = dossierPageMapper;
    door.identity = caller;
    door.hints = ticketHints;
    return door;
  }

  private EntityTransitionController entities(SecurityIdentity caller) {
    EntityTransitionController door = new EntityTransitionController();
    door.transitions = transitionService;
    door.catalog = catalogService;
    door.identity = caller;
    door.epicHints = epicHints;
    door.ticketHints = ticketHints;
    door.qualifiedIds = qualifiedIds;
    return door;
  }

  // ---- the request bodies ----------------------------------------------------------------------

  private static EpicController.UpdateEpicRequest epicEdit(String title) {
    return new EpicController.UpdateEpicRequest(title, "edited");
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

  private static TicketController.UpdateTicketRequest ticketEdit(String title) {
    return new TicketController.UpdateTicketRequest(
        title, null, false, null, false, null, null, false);
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

  /** An epic restated as itself, with a new title — the smallest legal whole post-state. */
  private static EntityTransition epicRenamedTo(String title) {
    return new EntityTransition(
        Archetype.EPIC,
        new EntityTransition.Membership(null, null),
        title,
        null,
        "REFINING",
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** A feature restated as itself, hanging under {@code parent}. */
  private static EntityTransition featureUnder(String parent, String title) {
    return new EntityTransition(
        Archetype.FEATURE,
        new EntityTransition.Membership(parent, null),
        title,
        null,
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
    assertEquals(
        "Retitled by the agent",
        epics(AGENT).update(rows.epicId(), epicEdit("Retitled by the agent")).epic().title());
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
        "A renamed ticket",
        tickets(AGENT).update(rows.ticketId(), ticketEdit("A renamed ticket")).ticket().title());
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
  }

  // ---- and are refused for any other project ----------------------------------------------------

  /** The same fourteen calls, by an agent whose token names the other project. */
  @Test
  void anAgentIsRefusedAnotherProjectsEntities() {
    refused(() -> epics(FOREIGN_AGENT).update(rows.epicId(), epicEdit("Not yours")));
    refused(() -> epics(FOREIGN_AGENT).createFeature(rows.epicId(), newFeature("Not yours")));
    refused(() -> features(FOREIGN_AGENT).update(rows.featureId(), featureEdit("Not yours")));
    refused(() -> features(FOREIGN_AGENT).createTask(rows.featureId(), newTask("Not yours")));
    refused(() -> features(FOREIGN_AGENT).delete(rows.featureId()));
    refused(() -> tasks(FOREIGN_AGENT).update(rows.taskId(), taskEdit("Not yours")));
    refused(() -> tasks(FOREIGN_AGENT).delete(rows.taskId()));
    refused(() -> tickets(FOREIGN_AGENT).update(rows.ticketId(), ticketEdit("Not yours")));
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

    // Nothing moved: every refusal ran before its write.
    assertEquals("The own plan", epicService.get(rows.epicId()).title);
    assertEquals("body", dossierService.get(rows.epicPageId()).body);
  }

  /**
   * <b>No claim covers nothing.</b> An agent role that arrived on a forwarded header carries no
   * token and therefore no {@code project} claim, so it matches no project and is refused every one
   * of these — never waved through on the grounds that there was nothing to compare.
   */
  @Test
  void anAgentWithNoClaimsIsRefusedEverything() {
    refused(() -> epics(CLAIMLESS_AGENT).update(rows.epicId(), epicEdit("No claim")));
    refused(() -> epics(CLAIMLESS_AGENT).createFeature(rows.epicId(), newFeature("No claim")));
    refused(() -> features(CLAIMLESS_AGENT).update(rows.featureId(), featureEdit("No claim")));
    refused(() -> features(CLAIMLESS_AGENT).createTask(rows.featureId(), newTask("No claim")));
    refused(() -> features(CLAIMLESS_AGENT).delete(rows.featureId()));
    refused(() -> tasks(CLAIMLESS_AGENT).update(rows.taskId(), taskEdit("No claim")));
    refused(() -> tasks(CLAIMLESS_AGENT).delete(rows.taskId()));
    refused(() -> tickets(CLAIMLESS_AGENT).update(rows.ticketId(), ticketEdit("No claim")));
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
        "Edited by a person",
        epics(OPERATOR).update(rows.epicId(), epicEdit("Edited by a person")).epic().title());
    assertEquals(
        "Edited by a person holding both roles",
        epics(OPERATOR_AGENT)
            .update(rows.epicId(), epicEdit("Edited by a person holding both roles"))
            .epic()
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

    assertEquals("The own plan", epicService.get(rows.epicId()).title);
    assertEquals("The other plan", epicService.get(rows.foreignEpicId()).title);
    assertEquals(rows.epicId(), featureService.get(rows.featureId()).parentId());
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

    assertEquals(rows.epicId(), featureService.get(rows.featureId()).parentId());
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
        .body(Map.of("target", "IMPLEMENTATION"))
        .post("/projects/api/epics/no-such-entity/transition")
        .then()
        .statusCode(403);
    asForwardedAgent().delete("/projects/api/epics/no-such-entity").then().statusCode(403);
    asForwardedAgent().delete("/projects/api/tickets/no-such-entity").then().statusCode(403);
    asForwardedAgent().delete("/projects/api/ticket-comments/no-such-entity").then().statusCode(403);
  }

  /**
   * A granted route gets past the role check and runs: it resolves the id first — which is the
   * binding's precondition and the reason an unknown id is still a 404 — and answers 404 rather than
   * the 403 the door would have given. The reads are unrestricted as they always were.
   */
  @Test
  void aGrantedRouteGetsPastTheRoleCheck() {
    asForwardedAgent()
        .body(Map.of("title", "no such epic"))
        .put("/projects/api/epics/no-such-entity")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("title", "no such ticket"))
        .put("/projects/api/tickets/no-such-entity")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("body", "no such comment"))
        .put("/projects/api/ticket-comments/no-such-entity")
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
        .put("/projects/api/epics/" + rows.epicId())
        .then()
        .statusCode(403)
        .body("message", org.hamcrest.Matchers.equalTo(REFUSAL));

    assertEquals("The own plan", epicService.get(rows.epicId()).title);
  }
}
