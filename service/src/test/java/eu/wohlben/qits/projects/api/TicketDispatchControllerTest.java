package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ticket agent-dispatch door, REST-level and end to end against the recording port.
 *
 * <p>What it pins is the half of the flow this service actually owns: <b>what is asked for</b> — the
 * wrapper's row id, {@code ticket/<slug>}, the whole-estate {@code branchTree}, the ticket's id and an
 * instruction rendered from the ticket — and <b>what the ticket is left saying</b> afterwards. The
 * dispatch itself is qits-workspaces' and is not simulated here.
 *
 * <p>The caller is named with the real {@code X-Qits-*} pair rather than {@code @TestSecurity},
 * because the comment's {@code author} is one of the assertions and the header is what produces it
 * in a deployment ({@code EpicsAuditIdentityTest}'s reasoning, applied to the one comment this door
 * writes itself).
 */
@QuarkusTest
public class TicketDispatchControllerTest {

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

  private String createTicket(String projectId, String title, String type, String description) {
    return asAdmin("setup")
        .body(
            java.util.Map.of(
                "title", title, "type", type, "description", description, "assignee", "dana"))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  /**
   * The wrapper by a different route than the door takes — {@code findWrapper} reads the archetype
   * where the door resolves the {@code <slug>-<slug>} name — so the assertion below is about the
   * row and not about the lookup.
   */
  private String wrapperIdOf(String projectId) {
    return projects
        .findWrapper(projectId)
        .orElseThrow(() -> new AssertionError("the fixture project has no wrapper"))
        .id;
  }

  @Test
  public void dispatchingAnAgentAsksForTheWholeEstateAndSaysSoOnTheThread() {
    String projectId = createProject("Dispatch Happy");
    String ticketId =
        createTicket(projectId, "Login button is the wrong colour", "BUG", "It is puce.");

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(200)
        .body("dispatch.workspaceRowId", equalTo(41))
        .body("dispatch.branch", equalTo("ticket/login-button-is-the-wrong-colour"))
        .body("dispatch.repositoryId", notNullValue())
        .body("dispatch.fresh", equalTo(true))
        .body("dispatch.agentLaunch", equalTo("SCHEDULED"));

    RecordingWorkspaceAgentDispatch.Dispatched asked = dispatch.lastCall();
    assertEquals(
        wrapperIdOf(projectId),
        asked.repositoryId(),
        "a ticket names no repository, so the dispatch stands on the project's wrapper");
    assertEquals("ticket/login-button-is-the-wrong-colour", asked.branch());
    assertTrue(asked.branchTree(), "the aggregate workspace is the whole point for a ticket");
    assertEquals(
        java.util.List.of("refs/heads/ticket/login-button-is-the-wrong-colour"),
        asked.gitRefs(),
        "a ticket's agent may push its own branch and nothing else");

    // The subject is a FIELD and the goal is left empty: the workspace names the ticket it is for,
    // and no copy of a row that goes on moving is frozen into its preamble.
    assertEquals(ticketId, asked.subject().ticketId(), "the dispatch names the ticket it is about");
    assertNull(asked.subject().epicId(), "a ticket dispatch names no epic");

    assertTrue(
        asked.instruction().contains("add_ticket_comment"),
        "the agent is told to report on the thread: " + asked.instruction());
    assertTrue(
        asked.instruction().contains("update_ticket_comment"),
        "and to keep that one comment current");
    assertTrue(
        asked.instruction().contains("fully released"),
        "and that the work is not done until it is released");
    assertTrue(
        asked.instruction().contains("transition_ticket"),
        "and to resolve the ticket itself rather than leaving it for somebody to notice");
    assertTrue(
        asked.instruction().contains("RESOLVED"),
        "naming the status, since the tool takes a target");
    assertTrue(
        asked.instruction().contains("leave it OPEN"),
        "the other arm: an unfinished run must not close the ticket");
    assertTrue(asked.instruction().contains(ticketId), "and where to read the ticket itself");

    asAdmin("mallory")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(1))
        .body("entries[0].comment.author", equalTo("mallory"))
        .body(
            "entries[0].comment.body",
            containsString("Dispatched a coding agent to workspace `ticket/"));
  }

  @Test
  public void aSecondDispatchThatFoundAnAgentAlreadyWorkingSaysThatInstead() {
    String projectId = createProject("Dispatch Again");
    String ticketId = createTicket(projectId, "Second press", "IMPROVEMENT", "Press it twice.");
    dispatch.willAnswer(new WorkspaceAgentDispatch.Dispatch(77L, false, "SKIPPED_RUNNING"));

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(200)
        .body("dispatch.workspaceRowId", equalTo(77))
        .body("dispatch.fresh", equalTo(false))
        .body("dispatch.agentLaunch", equalTo("SKIPPED_RUNNING"));

    asAdmin("mallory")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries[0].comment.body", containsString("already working on this ticket"));
  }

  @Test
  public void anUnknownTicketIsA404AndNothingIsDispatched() {
    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/no-such-ticket/dispatch-agent")
        .then()
        .statusCode(404);
    assertTrue(dispatch.calls().isEmpty(), "a ticket that does not exist asks nothing of anybody");
  }

  @Test
  public void aFailedDispatchSurfacesAndLeavesTheThreadAlone() {
    String projectId = createProject("Dispatch Refused");
    String ticketId = createTicket(projectId, "Nothing answers", "BUG", "Nobody home.");
    dispatch.willFailWith(
        new DomainException(502, "Could not dispatch an agent onto ticket/nothing-answers: 503"));

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(502)
        .body("message", containsString("Could not dispatch an agent"));

    asAdmin("mallory")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body(
            "entries.size()",
            equalTo(0));
  }
}
