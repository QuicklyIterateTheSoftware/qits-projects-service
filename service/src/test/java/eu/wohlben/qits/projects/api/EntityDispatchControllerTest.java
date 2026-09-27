package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one dispatch path (qits-394), end to end through {@code POST /entities/{id}/dispatch} and the
 * two transition doors, against the recording ports.
 *
 * <p>What it pins: <b>the status picks the phase for every archetype</b> and nothing starts past the
 * work; <b>an epic and a ticket at the same status take the same path</b> with their own words;
 * <b>FLOW pushes the next prompt</b> on the agent's transition and <b>PHASE does not</b>, because the
 * bit rides on the entity row across the two requests; a second PHASE press continues from the new
 * status; and <b>both modes ask for the release at VERIFIED</b>. The ticket door this one replaced
 * left its refusal cases behind in {@link TicketFlowDispatchTest}, pointed at this door.
 *
 * <p>No profile of its own: the default {@code @QuarkusTest} application, the one every door suite
 * shares, so this class costs no boot (the test-profile budget rule).
 */
@QuarkusTest
public class EntityDispatchControllerTest {

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @Inject RecordingWorkspaceAgentTurns turns;

  @Inject eu.wohlben.qits.projects.control.ProjectService projects;

  @Inject eu.wohlben.qits.entities.control.WorkEntityService workEntities;

  /** Every project this class made, so the requests its releases opened can be taken away again. */
  private final List<String> projectIds = new ArrayList<>();

  @BeforeEach
  void resetThePorts() {
    dispatch.reset();
    turns.reset();
  }

  /** Open requests must not outlive this class: the release sweep walks every open row. */
  @AfterEach
  void dropTheRequestsTheEntitiesAskedFor() {
    QuarkusTransaction.requiringNew()
        .run(() -> projectIds.forEach(id -> ReleaseRequest.delete("projectId = ?1", id)));
    projectIds.clear();
  }

  // ---- a campaign's press is its start (qits-417) ----------------------------------------------

  /**
   * A campaign is never dispatched onto a workspace: its press is its start, which a REPORTED
   * campaign refuses, and its read answers the campaign's own state. The start itself is {@code
   * campaignhost/CampaignExecutorTest}'s.
   */
  @Test
  void aCampaignsPressIsItsStartAndItsReadSaysSo() {
    String projectId = createProject("Dispatch Campaign");
    String campaignId = workEntities.createCampaign(projectId, "Spring", null, "setup").id;

    asAdmin("dana")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/entities/" + campaignId + "/dispatch")
        .then()
        .statusCode(409)
        .body("message", containsString("Start a campaign from REFINED"));
    asAdmin("dana")
        .when()
        .get("/projects/api/entities/" + campaignId + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.archetype", equalTo("CAMPAIGN"))
        .body("state.status", equalTo("REPORTED"))
        .body("state.nextPhase", equalTo("start"))
        .body("state.dispatchable", equalTo(false))
        .body("state.mode", nullValue());

    assertEquals(List.of(), dispatch.calls(), "no agent was dispatched onto a campaign");
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private RequestSpecification asAdmin(String user) {
    return given()
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

  private String createTicket(String projectId, String title) {
    return asAdmin("setup")
        .body(Map.of("title", title, "type", "BUG", "impetus", "something occurs here"))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  private String createEpic(String projectId, String title) {
    return asAdmin("setup")
        .body(Map.of("title", title, "description", "The pitch."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private String addFeature(String epicId, String title) {
    return asAdmin("setup")
        .body(Map.of("title", title, "description", "a slice"))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(200)
        .extract()
        .path("feature.id");
  }

  private void transitionTicket(String ticketId, String target) {
    asAdmin("dana")
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200);
  }

  private void transitionEpic(String epicId, String target) {
    asAdmin("dana")
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then()
        .statusCode(200);
  }

  /** The press, answered 200, and the phase it started. */
  private String press(String entityId, String mode) {
    return asAdmin("mallory")
        .body(Map.of("mode", mode))
        .when()
        .post("/projects/api/entities/" + entityId + "/dispatch")
        .then()
        .statusCode(200)
        .body("dispatch.entityId", equalTo(entityId))
        .body("dispatch.mode", equalTo(mode))
        .extract()
        .path("dispatch.phase");
  }

  private void pressRefused(String entityId, String mode, String says) {
    asAdmin("mallory")
        .body(Map.of("mode", mode))
        .when()
        .post("/projects/api/entities/" + entityId + "/dispatch")
        .then()
        .statusCode(409)
        .body("message", containsString(says));
  }

  private String wrapperIdOf(String projectId) {
    return projects.findWrapper(projectId).orElseThrow().id;
  }

  private List<Map<String, Object>> releaseRequestsOf(String repoId) {
    return asAdmin("dana")
        .when()
        .get("/projects/api/repositories/" + repoId + "/release-requests")
        .then()
        .statusCode(200)
        .extract()
        .path("requests");
  }

  @SuppressWarnings("unchecked")
  private static List<String> sourceNamesOf(Map<String, Object> request) {
    return ((List<Map<String, Object>>) request.get("sources"))
        .stream().map(source -> (String) source.get("name")).toList();
  }

  // ---- the status picks the phase, for every archetype ------------------------------------------

  @Test
  public void dispatchingATicketAtEachStatusStartsItsPhaseAndNothingPastTheWork() {
    String projectId = createProject("Dispatch Walk Ticket");
    String ticketId = createTicket(projectId, "Walk the ticket");

    assertEquals("refine", press(ticketId, "FLOW"));
    assertTrue(dispatch.lastCall().instruction().contains("Refine ticket \""));
    transitionTicket(ticketId, "REFINED");
    assertEquals("implement", press(ticketId, "FLOW"));
    assertTrue(dispatch.lastCall().instruction().contains("Implement ticket \""));
    transitionTicket(ticketId, "IMPLEMENTED");
    assertEquals("verify", press(ticketId, "FLOW"));
    assertTrue(dispatch.lastCall().instruction().contains("Verify ticket \""));
    int dispatched = dispatch.calls().size();

    transitionTicket(ticketId, "VERIFIED");
    pressRefused(ticketId, "FLOW", "is VERIFIED");
    transitionTicket(ticketId, "DONE");
    pressRefused(ticketId, "PHASE", "is DONE");
    String dropped = createTicket(projectId, "Decided against");
    transitionTicket(dropped, "DROPPED");
    pressRefused(dropped, "FLOW", "is DROPPED");

    assertEquals(dispatched, dispatch.calls().size(), "nothing past the work stands a workspace up");
  }

  @Test
  public void dispatchingAnEpicAtEachStatusStartsItsPhaseAndNothingPastTheWork() {
    String projectId = createProject("Dispatch Walk Epic");
    String epicId = createEpic(projectId, "Walk the epic");

    assertEquals("refine", press(epicId, "PHASE"));
    RecordingWorkspaceAgentDispatch.Dispatched refine = dispatch.lastCall();
    assertTrue(refine.instruction().contains("Refine epic \""), refine.instruction());
    assertEquals("epic/walk-the-epic", refine.branch());
    assertEquals(epicId, refine.subject().epicId(), "the workspace names the epic it is for");
    assertNull(refine.subject().ticketId());

    transitionEpic(epicId, "REFINED");
    assertEquals("implement", press(epicId, "PHASE"));
    assertTrue(dispatch.lastCall().instruction().contains("Implement epic \""));
    transitionEpic(epicId, "IMPLEMENTED");
    assertEquals("verify", press(epicId, "PHASE"));
    assertTrue(dispatch.lastCall().instruction().contains("Verify epic \""));
    int dispatched = dispatch.calls().size();

    transitionEpic(epicId, "VERIFIED");
    pressRefused(epicId, "PHASE", "Epic " + epicId + " is VERIFIED");
    transitionEpic(epicId, "DONE");
    pressRefused(epicId, "FLOW", "is DONE");
    String dropped = createEpic(projectId, "Never happening");
    transitionEpic(dropped, "DROPPED");
    pressRefused(dropped, "FLOW", "is DROPPED");

    assertEquals(dispatched, dispatch.calls().size(), "nothing past the work stands a workspace up");
  }

  /**
   * <b>Same status, same path, different words.</b> Both stand on the project's wrapper with the
   * whole estate branched, both start the refine phase, and each is told what refining it means.
   */
  @Test
  public void anEpicAndATicketAtTheSameStatusTakeTheSamePathWithDifferentPromptText() {
    String projectId = createProject("Dispatch Same Path");
    String ticketId = createTicket(projectId, "Same path ticket");
    String epicId = createEpic(projectId, "Same path epic");

    assertEquals("refine", press(ticketId, "FLOW"));
    RecordingWorkspaceAgentDispatch.Dispatched ticketRun = dispatch.lastCall();
    assertEquals("refine", press(epicId, "FLOW"));
    RecordingWorkspaceAgentDispatch.Dispatched epicRun = dispatch.lastCall();

    assertEquals(wrapperIdOf(projectId), ticketRun.repositoryId());
    assertEquals(ticketRun.repositoryId(), epicRun.repositoryId(), "both stand on the wrapper");
    assertTrue(ticketRun.branchTree() && epicRun.branchTree(), "both branch the whole estate");
    assertEquals("ticket/same-path-ticket", ticketRun.branch());
    assertEquals("epic/same-path-epic", epicRun.branch());
    assertEquals(ticketId, ticketRun.subject().ticketId());
    assertEquals(epicId, epicRun.subject().epicId());
    assertNotEquals(ticketRun.instruction(), epicRun.instruction(), "the words are per archetype");
    assertTrue(ticketRun.instruction().contains("update_ticket"), ticketRun.instruction());
    assertTrue(epicRun.instruction().contains("add_feature"), epicRun.instruction());
  }

  // ---- the continue-or-stop bit ----------------------------------------------------------------

  /** FLOW: the agent's claim delivers the next phase's prompt, for a ticket and for an epic. */
  @Test
  public void theFlowVariantPushesTheNextPromptOnTransition() {
    String projectId = createProject("Dispatch Flow");
    String ticketId = createTicket(projectId, "Flow ticket");
    String epicId = createEpic(projectId, "Flow epic");
    press(ticketId, "FLOW");
    press(epicId, "FLOW");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transitionTicket(ticketId, "REFINED");
    assertEquals(1, turns.calls().size(), "the flow carried the ticket on");
    assertEquals("ticket/flow-ticket", turns.lastCall().branch());
    assertTrue(turns.lastCall().text().contains("Implement ticket \""), turns.lastCall().text());

    transitionEpic(epicId, "REFINED");
    assertEquals(2, turns.calls().size(), "and the epic, through the epic's own transition door");
    assertEquals("epic/flow-epic", turns.lastCall().branch());
    assertEquals(wrapperIdOf(projectId), turns.lastCall().repositoryId());
    assertTrue(turns.lastCall().text().contains("Implement epic \""), turns.lastCall().text());
  }

  /** PHASE: the same claim delivers nothing — the bit survived the round trip on the row. */
  @Test
  public void theOneShotVariantDoesNotPushTheNextPromptOnTransition() {
    String projectId = createProject("Dispatch One Shot");
    String ticketId = createTicket(projectId, "One shot ticket");
    String epicId = createEpic(projectId, "One shot epic");
    press(ticketId, "PHASE");
    press(epicId, "PHASE");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transitionTicket(ticketId, "REFINED");
    transitionEpic(epicId, "REFINED");

    assertTrue(turns.calls().isEmpty(), "a one-phase run stops: " + turns.calls());
  }

  /**
   * <b>A second "next phase" continues from the new status</b> and keeps the run stopping — the way
   * an entity is stepped through by hand. A later FLOW press turns it back into a flow.
   */
  @Test
  public void aSecondPhasePressContinuesFromTheNewStatusAndStillStops() {
    String projectId = createProject("Dispatch Step");
    String ticketId = createTicket(projectId, "Step me");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    assertEquals("refine", press(ticketId, "PHASE"));
    transitionTicket(ticketId, "REFINED");
    assertTrue(turns.calls().isEmpty());

    assertEquals("implement", press(ticketId, "PHASE"));
    assertTrue(dispatch.lastCall().instruction().contains("Implement ticket \""));
    transitionTicket(ticketId, "IMPLEMENTED");
    assertTrue(turns.calls().isEmpty(), "the second press recorded PHASE again");

    assertEquals("verify", press(ticketId, "FLOW"));
    transitionTicket(ticketId, "REFINED"); // a failed verification, claimed by the agent
    assertEquals(1, turns.calls().size(), "a FLOW press turns the run back into a flow");
  }

  /**
   * <b>Both modes ask for the release at VERIFIED</b> — the bit governs the next prompt and nothing
   * else, because the branch is finished whoever pressed what.
   */
  @Test
  public void bothVariantsReleaseTheWorkspaceAtVerified() {
    String projectId = createProject("Dispatch Release Ticket");
    String wrapperId = wrapperIdOf(projectId);
    String oneShot = createTicket(projectId, "Released one shot");
    String flow = createTicket(projectId, "Released flow");
    press(oneShot, "PHASE");
    press(flow, "FLOW");
    dispatch.willReference(
        RecordingWorkspaceAgentDispatch.live(
            7L, wrapperId, "ws-a", "ticket/released-one-shot", oneShot, null),
        RecordingWorkspaceAgentDispatch.live(
            8L, wrapperId, "ws-b", "ticket/released-flow", flow, null));

    for (String ticketId : List.of(oneShot, flow)) {
      transitionTicket(ticketId, "REFINED");
      transitionTicket(ticketId, "IMPLEMENTED");
      transitionTicket(ticketId, "VERIFIED");
    }

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "the wrapper's one open request carries both: " + requests);
    List<String> sources = sourceNamesOf(requests.get(0));
    assertTrue(sources.contains("ticket/released-one-shot"), "the one-phase run: " + sources);
    assertTrue(sources.contains("ticket/released-flow"), "and the flow: " + sources);
  }

  /** An epic reaching VERIFIED asks for its own branch, titled as the epic, in PHASE mode too. */
  @Test
  public void anEpicReachingVerifiedAsksForTheReleaseOfItsBranch() {
    String projectId = createProject("Dispatch Release Epic");
    String wrapperId = wrapperIdOf(projectId);
    String epicId = createEpic(projectId, "Released epic");
    transitionEpic(epicId, "REFINED");
    press(epicId, "PHASE");
    dispatch.willReference(
        RecordingWorkspaceAgentDispatch.live(
            9L, wrapperId, "ws-e", "epic/released-epic", null, epicId));

    transitionEpic(epicId, "IMPLEMENTED");
    transitionEpic(epicId, "VERIFIED");

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), requests.toString());
    assertTrue(sourceNamesOf(requests.get(0)).contains("epic/released-epic"));
    assertEquals("Epic released-epic: Released epic", requests.get(0).get("summary"));
    assertTrue(
        dispatch.lookups().stream().anyMatch(looked -> looked.epicIds().contains(epicId)),
        "the epic is looked up at the workspaces port by epic id");
  }

  // ---- the door itself ------------------------------------------------------------------------

  /** The read the SPA draws the two actions from: the server's status→phase rule, per row. */
  @Test
  public void theReadNamesTheNextPhaseAndTheRecordedModeWithoutStartingAnything() {
    String projectId = createProject("Dispatch Read");
    String ticketId = createTicket(projectId, "Read me");
    String epicId = createEpic(projectId, "Read epic");
    String featureId = addFeature(epicId, "A slice");

    asAdmin("mallory")
        .when()
        .get("/projects/api/entities/" + ticketId + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.archetype", equalTo("TICKET"))
        .body("state.status", equalTo("REPORTED"))
        .body("state.nextPhase", equalTo("refine"))
        .body("state.dispatchable", equalTo(true))
        .body("state.mode", equalTo("FLOW"));

    press(epicId, "PHASE");
    transitionEpic(epicId, "REFINED");
    asAdmin("mallory")
        .when()
        .get("/projects/api/entities/" + epicId + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.nextPhase", equalTo("implement"))
        .body("state.mode", equalTo("PHASE"));

    asAdmin("mallory")
        .when()
        .get("/projects/api/entities/" + featureId + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.archetype", equalTo("FEATURE"))
        .body("state.nextPhase", nullValue())
        .body("state.dispatchable", equalTo(false))
        .body("state.mode", nullValue());

    assertEquals(1, dispatch.calls().size(), "reading starts nothing; only the one press did");
  }

  @Test
  public void aFeatureIsRefusedAndAMissingOrUnknownModeIsA400() {
    String projectId = createProject("Dispatch Refusals");
    String epicId = createEpic(projectId, "Holds a feature");
    String featureId = addFeature(epicId, "Not dispatchable");
    String ticketId = createTicket(projectId, "Say how");

    pressRefused(featureId, "FLOW", "no lifecycle");
    asAdmin("mallory")
        .body(Map.of())
        .when()
        .post("/projects/api/entities/" + ticketId + "/dispatch")
        .then()
        .statusCode(400)
        .body("message", containsString("mode is required"));
    asAdmin("mallory")
        .body(Map.of("mode", "SIDEWAYS"))
        .when()
        .post("/projects/api/entities/" + ticketId + "/dispatch")
        .then()
        .statusCode(400)
        .body("message", containsString("Unknown mode SIDEWAYS"));
    asAdmin("mallory")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/entities/no-such-entity/dispatch")
        .then()
        .statusCode(404);

    assertTrue(dispatch.calls().isEmpty(), "no refused press stood a workspace up");
  }

  /**
   * The press is {@code qits:admin} alone, exactly as the doors it replaces; the read admits an
   * agent, by the standing rule that an agent reads everywhere.
   */
  @Test
  public void anAgentMayReadButNotPress() {
    String projectId = createProject("Dispatch Roles");
    String ticketId = createTicket(projectId, "Not for agents");
    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "agent")
        .header("X-Qits-Roles", "qits:agent")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/entities/" + ticketId + "/dispatch")
        .then()
        .statusCode(403);
    given()
        .header("X-Qits-User", "agent")
        .header("X-Qits-Roles", "qits:agent")
        .when()
        .get("/projects/api/entities/" + ticketId + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.nextPhase", equalTo("refine"));
    assertTrue(dispatch.calls().isEmpty());
  }
}
