package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.entities.control.EntityBlockState;
import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.security.AgentTokens;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery desk at the dispatch door (epic qits-1440, task qits-1492): a {@code qits:desk}
 * token bound to its project's claim, pressing under a named rule, inside its project's {@code
 * front_desk.delivery} — its loop, its cap and its start-refined declaration.
 *
 * <p><b>Two layers, as {@code EntityAgentBoundsTest} splits them.</b> The binding reads the token's
 * {@code project} claim, which a {@code @QuarkusTest} cannot put in front of this service over HTTP
 * (the {@code %test} dev user wins over any test identity), so the press is driven through {@link
 * DispatchDoors#press} with hand-made identities from {@link AgentTokens}, over real rows and the
 * recording workspaces port. The <b>roles</b> are what a forwarded header carries and are driven
 * over HTTP: a plain {@code qits:agent} is refused at the door, a forwarded {@code qits:desk} passes
 * it (and, carrying no claim, is refused as another project's), and {@code qits:admin} presses as
 * before.
 *
 * <p>No profile of its own: the default application every door suite shares.
 */
@QuarkusTest
public class DeskDispatchTest {

  private static final String RULE = "backlog-newest-first";

  @Inject DispatchDoors doors;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @Inject ProjectRepository projectRows;

  @Inject WorkEntityRepository entityRows;

  @Inject ProjectService projects;

  @Inject WorkEntityService workEntities;

  @Inject EntityDispatchService dispatchEntities;

  private final List<String> projectIds = new ArrayList<>();

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
  }

  @AfterEach
  void dropTheRequestsTheEntitiesAskedFor() {
    QuarkusTransaction.requiringNew()
        .run(() -> projectIds.forEach(id -> ReleaseRequest.delete("projectId = ?1", id)));
    projectIds.clear();
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private RequestSpecification asAdmin(String user) {
    return given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(user))
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", "qits:admin");
  }

  private String createProject(String name) {
    String id =
        asAdmin("setup")
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    projectIds.add(id);
    return id;
  }

  /** The project's stored delivery settings, as the reconcile would have written them. */
  private void delivery(String projectId, boolean enabled, int maxRunning, boolean startRefined) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var project = projectRows.findById(projectId);
              project.deliveryEnabled = enabled;
              project.deliveryMaxRunning = maxRunning;
              project.deliveryStartRefined = startRefined;
            });
  }

  private String createTicket(String projectId, String title) {
    return TestCriteria.give(
        WorkRequests.ticket(
            () -> asAdmin("setup"), projectId, title, "BUG", "something occurs here"));
  }

  private void transition(String id, String target) {
    WorkRequests.status(() -> asAdmin("dana"), id, target).then().statusCode(200);
  }

  /** The project's delivery desk: {@code qits:agent} and {@code qits:desk}, bound to its project. */
  private static SecurityIdentity desk(String projectId) {
    return AgentTokens.token(
        Map.of("sub", "tok-desk-" + projectId, "project", projectId), "qits:agent", "qits:desk");
  }

  private DispatchRefused refused(
      SecurityIdentity caller, String entityId, String mode, String rule) {
    return assertThrows(DispatchRefused.class, () -> doors.press(caller, entityId, mode, rule));
  }

  private WorkEntity row(String id) {
    return dispatchEntities.fresh(id);
  }

  private List<String> threadOf(String entityId) {
    return asAdmin("dana")
        .when()
        .get("/projects/api/work/" + entityId + "/comments")
        .then()
        .statusCode(200)
        .extract()
        .path("entries.comment.body");
  }

  private String qualifiedIdOf(String projectId, WorkEntity entity) {
    return QualifiedEntityIds.render(projects.get(projectId).slug, entity.number);
  }

  /** An ACTIVE agent at qits-workspaces working on {@code workId}. */
  private static WorkspaceAgentDispatch.Agent activeOn(String workId) {
    return RecordingWorkspaceAgentDispatch.active(
        "agent-" + workId, 7L, "wrapper", workId, "ticket/" + workId);
  }

  // ---- the press -----------------------------------------------------------------------------

  /** The desk dispatches a REPORTED ticket of its own project, as a machine, under its rule. */
  @Test
  public void aDeskDispatchesAReportedTicketOfItsOwnProject() {
    String projectId = createProject("Desk Dispatch");
    delivery(projectId, true, 2, false);
    String ticketId = createTicket(projectId, "For the desk");

    DispatchDoors.Pressed pressed = doors.press(desk(projectId), ticketId, "FLOW", RULE);

    assertEquals("refine", pressed.dispatch().phase());
    assertEquals(1, dispatch.calls().size());
    assertNull(
        row(ticketId).preApprovedBy,
        "with start_refined off, a desk's press at REPORTED pre-approves nothing (qits-1714)");
    List<String> thread = threadOf(ticketId);
    String comment = thread.get(thread.size() - 1);
    assertTrue(
        comment.endsWith(" Dispatched by the delivery desk under rule `" + RULE + "`."), comment);
  }

  /** Another project's claim is a 403, before anything else is decided and nothing is written. */
  @Test
  public void aDeskWithAnotherProjectsClaimIsRefused403() {
    String projectId = createProject("Desk Own");
    String otherId = createProject("Desk Other");
    delivery(projectId, true, 2, false);
    delivery(otherId, true, 2, false);
    String ticketId = createTicket(projectId, "Not the other desk's");

    DispatchRefused refusal = refused(desk(otherId), ticketId, "FLOW", RULE);
    assertEquals(403, refusal.statusCode());
    assertEquals("The delivery desk may dispatch only the items of its own project.", refusal.getMessage());
    // Before the rule's 400, too.
    assertEquals(403, refused(desk(otherId), ticketId, "FLOW", null).statusCode());
    // A desk token with no project claim is nobody's project's.
    SecurityIdentity unbound = AgentTokens.token(Map.of("sub", "tok-x"), "qits:agent", "qits:desk");
    assertEquals(403, refused(unbound, ticketId, "FLOW", RULE).statusCode());
    assertTrue(dispatch.calls().isEmpty());
  }

  /** No rule is a 400 — blank as well as absent — and comes before the loop's 409. */
  @Test
  public void aDeskNamingNoRuleIsRefused400() {
    String projectId = createProject("Desk No Rule");
    String ticketId = createTicket(projectId, "Which rule");

    for (String rule : new String[] {null, "", "  "}) {
      DispatchRefused refusal = refused(desk(projectId), ticketId, "FLOW", rule);
      assertEquals(400, refusal.statusCode(), String.valueOf(rule));
      assertEquals("The delivery desk names the rule it dispatches under.", refusal.getMessage());
    }
    assertTrue(dispatch.calls().isEmpty());
  }

  /** The loop off is a 409 naming the declaration, and comes before the campaign's. */
  @Test
  public void aDeskWhoseLoopIsOffIsRefused409() {
    String projectId = createProject("Desk Loop Off");
    String ticketId = createTicket(projectId, "Loop off");
    String campaignId = workEntities.createCampaign(projectId, "Spring", null, "setup").id;

    for (String id : List.of(ticketId, campaignId)) {
      DispatchRefused refusal = refused(desk(projectId), id, "FLOW", RULE);
      assertEquals(409, refusal.statusCode());
      assertEquals(
          "The delivery loop is off for this project (front_desk.delivery.enabled in"
              + " .config/qits/project.yml).",
          refusal.getMessage());
    }
    assertTrue(dispatch.calls().isEmpty());
  }

  /** A campaign is a 409: the campaign path is a person's, never the desk's. */
  @Test
  public void aDeskIsRefusedACampaign() {
    String projectId = createProject("Desk Campaign");
    delivery(projectId, true, 2, false);
    String campaignId = workEntities.createCampaign(projectId, "Spring", null, "setup").id;

    DispatchRefused refusal = refused(desk(projectId), campaignId, "FLOW", RULE);
    assertEquals(409, refusal.statusCode());
    assertTrue(refusal.getMessage().contains("not the delivery desk's to start"), refusal.getMessage());
    assertTrue(dispatch.calls().isEmpty());
  }

  /**
   * At {@code max_running} the press is a 409 naming the count, the limit and the running items. A
   * running item has an assignee and an ACTIVE agent; an OBSOLETE agent does not count, and neither
   * does the item being pressed.
   */
  @Test
  public void aDeskAtItsCapIsRefused409NamingTheRunningItems() {
    String projectId = createProject("Desk Cap");
    delivery(projectId, true, 1, false);
    String runningId = createTicket(projectId, "Already running");
    String nextId = createTicket(projectId, "Next in line");
    // A person's press puts an agent on the first: the assignee is written.
    asAdmin("mallory")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + runningId + "/dispatch")
        .then()
        .statusCode(200);
    dispatch.reset();
    dispatch.willFind(activeOn(runningId));

    DispatchRefused refusal = refused(desk(projectId), nextId, "FLOW", RULE);
    assertEquals(409, refusal.statusCode());
    String running = qualifiedIdOf(projectId, row(runningId));
    assertTrue(refusal.getMessage().contains("at most 1 items"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("1 are running: " + running + "."), refusal.getMessage());
    assertTrue(dispatch.calls().isEmpty());

    // Its agent OBSOLETE: nothing runs, so the press goes through.
    dispatch.willFind(
        RecordingWorkspaceAgentDispatch.inState(
            "agent-old", 7L, "wrapper", runningId, "ticket/x", "OBSOLETE", Instant.now()));
    doors.press(desk(projectId), nextId, "FLOW", RULE);
    assertEquals(1, dispatch.calls().size());

    // The item pressed never counts itself: the first again, its own agent ACTIVE, cap 1.
    dispatch.reset();
    dispatch.willFind(activeOn(runningId));
    doors.press(desk(projectId), runningId, "FLOW", RULE);
    assertEquals(1, dispatch.calls().size());
  }

  /**
   * An explicitly blocked item holds no slot (qits-1569): it waits on a person or another item, and
   * counting it starved the backlog. At cap 1 with the only running ticket blocked, the next press
   * goes through; unblocked, it takes its slot back and the cap refuses again.
   */
  @Test
  public void aBlockedRunningItemDoesNotHoldADeskSlot() {
    String projectId = createProject("Desk Blocked Slot");
    delivery(projectId, true, 1, false);
    String blockedId = createTicket(projectId, "Waiting on the owner");
    String nextId = createTicket(projectId, "Next in line");
    asAdmin("mallory")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + blockedId + "/dispatch")
        .then()
        .statusCode(200);
    asAdmin("mallory")
        .body(Map.of("blocked", true, "reason", "Waiting on the owner's answer"))
        .when()
        .post("/projects/api/work/" + blockedId + "/blocked")
        .then()
        .statusCode(200);
    assertTrue(row(blockedId).blocked);
    dispatch.reset();
    dispatch.willFind(activeOn(blockedId));

    doors.press(desk(projectId), nextId, "FLOW", RULE);
    assertEquals(1, dispatch.calls().size());

    // Unblocked, it holds its slot again: the next running item now counts against it.
    workEntities.setBlocked(Archetype.TICKET, blockedId, false, "mallory");
    String thirdId = createTicket(projectId, "Third in line");
    // The test thread's session still holds the row the first press listed, blocked; a request's
    // press reads afresh, so this one is made to as well.
    entityRows.getEntityManager().clear();
    assertEquals(409, refused(desk(projectId), thirdId, "FLOW", RULE).statusCode());
  }

  /**
   * The derived block keeps its slot (qits-1569): an agent waiting for a person — on its own
   * release request, say — reads as blocked (qits-895), but it is in-flight work, so only the
   * explicit flag frees the slot and the cap still refuses.
   */
  @Test
  public void anItemOnlyWaitingOnItsAgentStillHoldsADeskSlot() {
    String projectId = createProject("Desk Waiting Slot");
    delivery(projectId, true, 1, false);
    String waitingId = createTicket(projectId, "Agent waiting");
    String nextId = createTicket(projectId, "Next in line");
    asAdmin("mallory")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + waitingId + "/dispatch")
        .then()
        .statusCode(200);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                entityRows
                    .getEntityManager()
                    .createNativeQuery(
                        "update entity set agent_waiting_since = now() - interval '1 hour'"
                            + " where id = '"
                            + waitingId
                            + "'")
                    .executeUpdate());
    WorkEntity waiting = row(waitingId);
    assertTrue(EntityBlockState.of(waiting).blocked(), "the derived block stands");
    assertFalse(waiting.blocked, "and the explicit flag is not set");
    dispatch.reset();
    dispatch.willFind(activeOn(waitingId));

    DispatchRefused refusal = refused(desk(projectId), nextId, "FLOW", RULE);
    assertEquals(409, refusal.statusCode());
    assertTrue(
        refusal.getMessage().contains("1 are running: " + qualifiedIdOf(projectId, waiting) + "."),
        refusal.getMessage());
    assertTrue(dispatch.calls().isEmpty());
  }

  /** REFINED with start_refined off keeps the REFINED_WAITS 409 and says start_refined is off. */
  @Test
  public void aDeskAtRefinedWithStartRefinedOffIsRefused409() {
    String projectId = createProject("Desk Refined Off");
    delivery(projectId, true, 2, false);
    String ticketId = createTicket(projectId, "Refined, waiting");
    transition(ticketId, "REFINED");

    DispatchRefused refusal = refused(desk(projectId), ticketId, "FLOW", "start-refined");
    assertEquals(409, refusal.statusCode());
    assertTrue(refusal.getMessage().contains(EntityDispatch.REFINED_WAITS), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("start_refined is off"), refusal.getMessage());
    assertEquals("REFINED", row(ticketId).status);
    assertNull(row(ticketId).preApprovedBy);
    assertTrue(dispatch.calls().isEmpty());
  }

  /**
   * REFINED with start_refined on: a FLOW press is the scheduling, REFINED → READY_FOR_DEV on the
   * project's declaration rather than a person, then implement as a READY_FOR_DEV press — with
   * {@code preApprovedBy} naming the declaration. A PHASE press still waits.
   */
  @Test
  public void aDeskAtRefinedWithStartRefinedOnSchedulesAndDispatches() {
    String projectId = createProject("Desk Refined On");
    delivery(projectId, true, 2, true);
    String ticketId = createTicket(projectId, "Refined, ready");
    transition(ticketId, "REFINED");

    DispatchRefused phase = refused(desk(projectId), ticketId, "PHASE", "start-refined");
    assertEquals(409, phase.statusCode());
    assertTrue(phase.getMessage().contains(EntityDispatch.REFINED_WAITS), phase.getMessage());
    assertEquals("REFINED", row(ticketId).status);

    DispatchDoors.Pressed pressed = doors.press(desk(projectId), ticketId, "FLOW", "start-refined");

    assertEquals("implement", pressed.dispatch().phase());
    assertEquals("IMPLEMENTING", row(ticketId).status);
    assertEquals(
        "delivery desk (start-refined, .config/qits/project.yml)", row(ticketId).preApprovedBy);
    assertEquals(1, dispatch.calls().size());
    List<String> thread = threadOf(ticketId);
    assertTrue(
        thread.get(thread.size() - 1).endsWith(" Dispatched by the delivery desk under rule `start-refined`."));
  }

  // ---- the desk's pre-approval at REPORTED (qits-1714) ----------------------------------------

  /**
   * With start_refined on, a desk's FLOW press at REPORTED pre-approves the scheduling: the row
   * carries {@link EntityDispatch#DESK_PRE_APPROVAL} for {@code PhaseAdvance} to spend at REFINED,
   * and the press itself still only starts refine.
   */
  @Test
  public void aDeskFlowPressAtReportedUnderStartRefinedPreApprovesTheScheduling() {
    String projectId = createProject("Desk Pre-approves");
    delivery(projectId, true, 2, true);
    String ticketId = createTicket(projectId, "All the way, on the declaration");

    DispatchDoors.Pressed pressed = doors.press(desk(projectId), ticketId, "FLOW", RULE);

    assertEquals("refine", pressed.dispatch().phase());
    assertEquals(EntityDispatch.DESK_PRE_APPROVAL, row(ticketId).preApprovedBy);
  }

  /** With start_refined off, the same press writes nothing. */
  @Test
  public void aDeskFlowPressAtReportedWithStartRefinedOffWritesNoPreApproval() {
    String projectId = createProject("Desk No Pre-approval");
    delivery(projectId, true, 2, false);
    String ticketId = createTicket(projectId, "Waits for the owner once refined");

    doors.press(desk(projectId), ticketId, "FLOW", RULE);

    assertNull(row(ticketId).preApprovedBy);
  }

  /** A desk's PHASE press clears a pending desk pre-approval: it asked for one phase. */
  @Test
  public void aDeskPhasePressClearsADeskPreApproval() {
    String projectId = createProject("Desk Phase Clears");
    delivery(projectId, true, 2, true);
    String ticketId = createTicket(projectId, "One phase only");
    dispatchEntities.setPreApprovedBy(ticketId, EntityDispatch.DESK_PRE_APPROVAL, "tok-desk");

    doors.press(desk(projectId), ticketId, "PHASE", RULE);

    assertNull(row(ticketId).preApprovedBy);
  }

  /**
   * A desk press never writes over a person's pre-approval, nor clears it: neither its FLOW press
   * under start_refined nor its PHASE press.
   */
  @Test
  public void aDeskPressLeavesAPersonsPreApprovalAlone() {
    String projectId = createProject("Desk Leaves Persons");
    delivery(projectId, true, 5, true);
    String flowId = createTicket(projectId, "Dana's, pressed FLOW by the desk");
    String phaseId = createTicket(projectId, "Dana's, pressed PHASE by the desk");
    dispatchEntities.setPreApprovedBy(flowId, "dana", "dana");
    dispatchEntities.setPreApprovedBy(phaseId, "dana", "dana");

    doors.press(desk(projectId), flowId, "FLOW", RULE);
    doors.press(desk(projectId), phaseId, "PHASE", RULE);

    assertEquals("dana", row(flowId).preApprovedBy);
    assertEquals("dana", row(phaseId).preApprovedBy);
  }

  /** Every other machine is unchanged at REFINED: an admin-agent's FLOW press still waits. */
  @Test
  public void anotherMachineAtRefinedIsUnchangedByTheDeclaration() {
    String projectId = createProject("Desk Other Machine");
    delivery(projectId, true, 2, true);
    String ticketId = createTicket(projectId, "Refined, not the desk");
    transition(ticketId, "REFINED");

    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "admin-agent")
        .header("X-Qits-Roles", "qits:admin-agent")
        .body(Map.of("mode", "FLOW", "rule", "start-refined"))
        .when()
        .post("/projects/api/work/" + ticketId + "/dispatch")
        .then()
        .statusCode(409)
        .body("message", containsString(EntityDispatch.REFINED_WAITS));
    assertEquals("REFINED", row(ticketId).status);
  }

  // ---- the door's roles, over HTTP -------------------------------------------------------------

  /**
   * A plain {@code qits:agent} is refused at the door; a forwarded {@code qits:desk} passes it and,
   * carrying no token and so no claim, is refused as another project's; {@code qits:admin} presses
   * as before, with no rule and with the loop off.
   */
  @Test
  public void theDoorAdmitsTheDeskAndStillRefusesAPlainAgent() {
    String projectId = createProject("Desk Door");
    String ticketId = createTicket(projectId, "At the door");

    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "agent")
        .header("X-Qits-Roles", "qits:agent")
        .body(Map.of("mode", "FLOW", "rule", RULE))
        .when()
        .post("/projects/api/work/" + ticketId + "/dispatch")
        .then()
        .statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "desk")
        .header("X-Qits-Roles", "qits:desk")
        .body(Map.of("mode", "FLOW", "rule", RULE))
        .when()
        .post("/projects/api/work/" + ticketId + "/dispatch")
        .then()
        .statusCode(403)
        .body("message", equalTo("The delivery desk may dispatch only the items of its own project."));
    assertTrue(dispatch.calls().isEmpty());

    asAdmin("mallory")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + ticketId + "/dispatch")
        .then()
        .statusCode(200)
        .body("dispatch.phase", equalTo("refine"));
    List<String> thread = threadOf(ticketId);
    assertTrue(
        !thread.get(thread.size() - 1).contains("delivery desk"),
        "a person's press says nothing about the desk");
  }
}
