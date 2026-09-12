package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The epic agent-dispatch door — "Start implementation" — REST-level and end to end against the
 * recording port.
 *
 * <p>What it pins is the half of the flow this service owns, and there are three parts to that: the
 * <b>status move</b> the press makes, <b>what is asked for</b> (the wrapper's row id, {@code
 * epic/<slug>}, the whole-estate {@code branchTree}, the epic's id and an instruction rendered from
 * the epic), and the <b>order</b> between the two — a re-press on an epic that is already in
 * implementation dispatches rather than answering 409, which is the whole retry story. The dispatch
 * itself is qits-workspaces' and is not simulated here.
 *
 * <p>{@link TicketDispatchControllerTest} is the sibling and the shape is deliberately its; the
 * assertion that has no counterpart there is the one about the epic being left alone — an epic has
 * no thread to write on and this door must not edit the plan instead.
 */
@QuarkusTest
public class EpicDispatchControllerTest {

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @Inject eu.wohlben.qits.projects.control.ProjectService projects;

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
  }

  private RequestSpecification asAdmin(String user) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", "qits:admin");
  }

  private String createProject(String name) {
    return asAdmin("setup")
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title, String description) {
    return asAdmin("setup")
        .body(Map.of("title", title, "description", description))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private String addFeature(String epicId, String title, String description) {
    return asAdmin("setup")
        .body(Map.of("title", title, "description", description))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(200)
        .extract()
        .path("feature.id");
  }

  private void addTask(String featureId, String repositoryId, String title) {
    asAdmin("setup")
        .body(Map.of("repositoryId", repositoryId, "title", title))
        .when()
        .post("/projects/api/features/" + featureId + "/tasks")
        .then()
        .statusCode(200);
  }

  /** Move an epic on by the board's own door, so the fixture never depends on the door under test. */
  private void transition(String epicId, String target) {
    asAdmin("setup")
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then()
        .statusCode(200);
  }

  private String statusOf(String epicId) {
    return asAdmin("setup")
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .extract()
        .path("epic.status");
  }

  /** The wrapper by a different route than the door takes, so the assertion is about the row. */
  private String wrapperIdOf(String projectId) {
    return projects
        .findWrapper(projectId)
        .orElseThrow(() -> new AssertionError("the fixture project has no wrapper"))
        .id;
  }

  @Test
  public void startingImplementationFreezesTheEpicAndDispatchesOntoTheWholeEstate() {
    String projectId = createProject("Epic Dispatch Happy");
    String epicId = createEpic(projectId, "Planning domain", "The spine of the plan.");
    String lifecycle = addFeature(epicId, "Lifecycle", "statuses and the freeze");
    String branches = addFeature(epicId, "Branches", "what an agent may push");
    addTask(lifecycle, wrapperIdOf(projectId), "Freeze");
    addTask(branches, wrapperIdOf(projectId), "Name the refs");
    addTask(branches, wrapperIdOf(projectId), "Send them");

    asAdmin("mallory")
        .when()
        .post("/projects/api/epics/" + epicId + "/dispatch-agent")
        .then()
        .statusCode(200)
        .body("dispatch.workspaceRowId", equalTo(41))
        .body("dispatch.branch", equalTo("epic/planning-domain"))
        .body("dispatch.repositoryId", notNullValue())
        .body("dispatch.fresh", equalTo(true))
        .body("dispatch.agentLaunch", equalTo("SCHEDULED"));

    assertEquals(
        "IMPLEMENTATION",
        statusOf(epicId),
        "the press transitions the epic, and does it before the dispatch");

    RecordingWorkspaceAgentDispatch.Dispatched asked = dispatch.lastCall();
    assertEquals(
        wrapperIdOf(projectId),
        asked.repositoryId(),
        "an epic spans the estate, so the dispatch stands on the project's wrapper");
    assertEquals("epic/planning-domain", asked.branch());
    assertTrue(asked.branchTree(), "the aggregate workspace is the whole point for an epic");
    // The big whitelist: the epic branch, then each feature followed by its tasks.
    assertEquals(
        java.util.List.of(
            "refs/heads/epic/planning-domain",
            "refs/heads/feature/planning-domain/lifecycle",
            "refs/heads/task/planning-domain/lifecycle/freeze",
            "refs/heads/feature/planning-domain/branches",
            "refs/heads/task/planning-domain/branches/name-the-refs",
            "refs/heads/task/planning-domain/branches/send-them"),
        asked.gitRefs(),
        "an epic's agent may push the epic branch and every feature and task branch of it");

    // The subject is a FIELD and the goal is left empty: the epic tree moves under an
    // implementation, so a rendering of it frozen at creation is stale the moment a task is marked.
    assertEquals(epicId, asked.subject().epicId(), "the dispatch names the epic it is about");
    assertNull(asked.subject().ticketId(), "an epic dispatch names no ticket");

    assertTrue(
        asked.instruction().contains("get_epic"),
        "the agent is sent to read the epic live: " + asked.instruction());
    assertTrue(asked.instruction().contains(epicId), "and told which epic that is");
    // The epic is the pitch and the dossier is what this agent builds from, so one that only reads
    // the epic
    // fills the gaps by guessing — and the guess arrives in the diff looking like a decision.
    assertTrue(
        asked.instruction().contains("get_dossier_page"),
        "the agent is sent to the dossier for the detail the epic leaves out: "
            + asked.instruction());
    assertTrue(
        asked.instruction().contains("read-only while the epic is in implementation"),
        "and told it cannot correct the dossier from here, which is what the REFINING guard does");
    assertTrue(
        asked.instruction().contains("mark_task_implemented"),
        "and told to record each task as it lands");
    assertTrue(asked.instruction().contains("dependsOn"), "and to respect the ordering links");
    assertTrue(
        asked.instruction().contains("fully released"),
        "and that the work is not done until it is released");
    assertTrue(
        asked.instruction().contains("leave the epic in implementation"),
        "the other arm: an unfinished run says what is missing rather than claiming the epic");
  }

  @Test
  public void aSecondPressOnAnEpicAlreadyInImplementationDispatchesAgain() {
    String projectId = createProject("Epic Dispatch Again");
    String epicId = createEpic(projectId, "Second press", "Press it twice.");
    transition(epicId, "IMPLEMENTATION");
    dispatch.willAnswer(new WorkspaceAgentDispatch.Dispatch(77L, false, "SKIPPED_RUNNING"));

    // A re-press is how a failed dispatch is retried, so IMPLEMENTATION must not be a 409 here —
    // EpicService.planTransition would refuse IMPLEMENTATION -> IMPLEMENTATION, which is exactly
    // why the door only transitions a REFINING epic.
    asAdmin("mallory")
        .when()
        .post("/projects/api/epics/" + epicId + "/dispatch-agent")
        .then()
        .statusCode(200)
        .body("dispatch.workspaceRowId", equalTo(77))
        .body("dispatch.fresh", equalTo(false))
        .body("dispatch.agentLaunch", equalTo("SKIPPED_RUNNING"));

    assertEquals("epic/second-press", dispatch.lastCall().branch());
    assertEquals(
        java.util.List.of("refs/heads/epic/second-press"),
        dispatch.lastCall().gitRefs(),
        "an epic with no features or tasks may push its own branch and nothing else");
    assertEquals("IMPLEMENTATION", statusOf(epicId), "and the epic is where it already was");
  }

  @Test
  public void anEpicWhoseWorkIsOverIsRefusedNamingItsStatus() {
    String projectId = createProject("Epic Dispatch Over");
    String shipped = createEpic(projectId, "Already shipped", "Done.");
    transition(shipped, "IMPLEMENTATION");
    transition(shipped, "IMPLEMENTED");
    String abandoned = createEpic(projectId, "Never happening", "Dropped.");
    transition(abandoned, "ABANDONED");

    asAdmin("mallory")
        .when()
        .post("/projects/api/epics/" + shipped + "/dispatch-agent")
        .then()
        .statusCode(409)
        .body("message", containsString("IMPLEMENTED"));

    asAdmin("mallory")
        .when()
        .post("/projects/api/epics/" + abandoned + "/dispatch-agent")
        .then()
        .statusCode(409)
        .body("message", containsString("ABANDONED"));

    assertTrue(dispatch.calls().isEmpty(), "work that is over asks nothing of anybody");
  }

  @Test
  public void anUnknownEpicIsA404AndNothingIsDispatched() {
    asAdmin("mallory")
        .when()
        .post("/projects/api/epics/no-such-epic/dispatch-agent")
        .then()
        .statusCode(404);
    assertTrue(dispatch.calls().isEmpty(), "an epic that does not exist asks nothing of anybody");
  }

  @Test
  public void aFailedDispatchLeavesTheEpicInImplementationToBeRetried() {
    String projectId = createProject("Epic Dispatch Refused");
    String epicId = createEpic(projectId, "Nothing answers", "Nobody home.");
    dispatch.willFailWith(
        new eu.wohlben.qits.projects.error.DomainException(
            502, "Could not dispatch an agent onto epic/nothing-answers: 503"));

    asAdmin("mallory")
        .when()
        .post("/projects/api/epics/" + epicId + "/dispatch-agent")
        .then()
        .statusCode(502)
        .body("message", containsString("Could not dispatch an agent"));

    // The order's deliberate cost: the transition already happened, and that is the state the
    // retry needs. The epic is legitimately in implementation — somebody did decide to start.
    assertEquals("IMPLEMENTATION", statusOf(epicId));

    // And nothing was written on the epic itself: the description is the plan, not a log.
    asAdmin("mallory")
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.description", equalTo("Nobody home."))
        .body("epic.title", equalTo("Nothing answers"));
  }
}
