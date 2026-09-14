package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The phase hand-off: a transition delivers the turn its <b>new</b> status starts into the workspace
 * standing on the ticket's branch, and the thread says what became of it.
 *
 * <p>What is pinned here is what this service owns — <b>which</b> prompt is delivered, <b>where</b>,
 * <b>when</b>, and what the ticket is left saying. The delivery itself is qits-workspaces' and is not
 * simulated; the three templates' own sentences are {@link TicketPhasePromptsTest}'s.
 *
 * <p>The caller is named with the real {@code X-Qits-*} pair rather than {@code @TestSecurity}, for
 * {@link TicketDispatchControllerTest}'s reason: the comment's {@code author} is one of the
 * assertions and the header is what produces it in a deployment.
 */
@QuarkusTest
public class TicketPhaseAdvanceTest {

  @Inject RecordingWorkspaceAgentTurns turns;

  @Inject eu.wohlben.qits.projects.control.ProjectService projects;

  @BeforeEach
  void resetThePort() {
    turns.reset();
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

  private String createTicket(String projectId, String title) {
    return asAdmin("setup")
        .body(
            Map.of(
                "title", title,
                "type", "BUG",
                "impetus", "something occurs in this project"))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  /** One step along the lifecycle, through the door a person presses. */
  private void transition(String ticketId, String target) {
    asAdmin("dana")
        .body(new Transition(target))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200);
  }

  /** The transition body, spelled here so this suite needs nothing of the epics module's API. */
  private record Transition(String target) {}

  private String wrapperIdOf(String projectId) {
    return projects
        .findWrapper(projectId)
        .orElseThrow(() -> new AssertionError("the fixture project has no wrapper"))
        .id;
  }

  /** The ticket's whole thread, oldest first, as the reader of it sees it. */
  private java.util.List<String> thread(String ticketId) {
    return asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .extract()
        .path("entries.comment.body");
  }

  // --- the rule: the prompt for a status is the work that starts from it ----------------------

  /**
   * <b>The whole rule, once per status.</b> Each move delivers the phase its new status begins, on
   * the ticket's own branch at the project's wrapper — and the two statuses that start nothing
   * deliver nothing at all, which is where the one remaining human decision lives.
   */
  @Test
  public void eachStatusDeliversThePromptThePhaseItStartsNeeds() {
    String projectId = createProject("Advance Walk");
    String ticketId = createTicket(projectId, "Walk me through");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    // REPORTED → REFINED: the implement phase is what runs while REFINED holds.
    transition(ticketId, "REFINED");
    assertEquals(1, turns.calls().size(), "one transition, one turn");
    RecordingWorkspaceAgentTurns.Spoken implement = turns.lastCall();
    assertEquals(
        wrapperIdOf(projectId),
        implement.repositoryId(),
        "a ticket names no repository, so the turn goes to the project's wrapper");
    assertEquals("ticket/walk-me-through", implement.branch());
    assertTrue(
        implement.text().startsWith("Implement ticket \""),
        "REFINED starts implementation: " + implement.text());

    transition(ticketId, "IMPLEMENTED");
    assertTrue(
        turns.lastCall().text().startsWith("Verify ticket \""),
        "IMPLEMENTED starts verification: " + turns.lastCall().text());

    // VERIFIED and DONE start no phase: the work is over and closing is a person's move.
    transition(ticketId, "VERIFIED");
    assertEquals(2, turns.calls().size(), "a move into VERIFIED starts nothing and says nothing");
    transition(ticketId, "DONE");
    assertEquals(2, turns.calls().size(), "and neither does a move into DONE");
  }

  /**
   * REPORTED is reached by <b>moving back</b> into it, since a ticket is created there rather than
   * transitioned into it — and the refine phase is what runs while it holds, whichever way the
   * ticket arrived.
   */
  @Test
  public void movingBackToReportedDeliversTheRefinePrompt() {
    String projectId = createProject("Advance Reopen");
    String ticketId = createTicket(projectId, "Back to the start");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "REFINED");
    transition(ticketId, "REPORTED");

    assertTrue(
        turns.lastCall().text().startsWith("Refine ticket \""),
        "REPORTED starts refinement: " + turns.lastCall().text());
  }

  /**
   * <b>Direction is not consulted, and this is the case that proves it.</b> A failed verification is
   * the ordinary backward move IMPLEMENTED → REFINED, and what has to start from it is
   * <em>implementation</em> — because REFINED means the ticket says what to do. A rule that asked
   * "forward or back?" would need a second table to answer from, and that table is the thing that
   * goes wrong.
   */
  @Test
  public void aFailedVerificationMovesBackAndStartsTheImplementPhase() {
    String projectId = createProject("Advance Backward");
    String ticketId = createTicket(projectId, "Still broken after all");
    transition(ticketId, "REFINED");
    transition(ticketId, "IMPLEMENTED");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "REFINED");

    assertTrue(
        turns.lastCall().text().startsWith("Implement ticket \""),
        "a move BACK to REFINED starts implementation again: " + turns.lastCall().text());
  }

  // --- what lands on the thread ---------------------------------------------------------------

  @Test
  public void aDeliveredTurnSaysWhichPhaseStartedAndWhereTheAgentIs() {
    String projectId = createProject("Advance Delivered");
    String ticketId = createTicket(projectId, "Tell the agent");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "REFINED");

    asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(1))
        .body("entries[0].comment.author", equalTo("dana"))
        .body(
            "entries[0].comment.body",
            equalTo(
                "Started the implement phase: the agent working in the workspace on"
                    + " `ticket/tell-the-agent` was told."));
  }

  /** A launched agent is a different fact from a told one, and the thread carries which. */
  @Test
  public void aLaunchedAgentIsSaidToBeALaunchAndNotAHandOff() {
    String projectId = createProject("Advance Launched");
    String ticketId = createTicket(projectId, "Nobody was home");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.LAUNCHED, "started one");

    transition(ticketId, "REFINED");

    assertEquals(
        java.util.List.of(
            "Started the implement phase: no agent was running in the workspace on"
                + " `ticket/nobody-was-home`, so one was launched to take it."),
        thread(ticketId));
  }

  /**
   * <b>No workspace is not a failure and gets no comment at all.</b> A person walking a ticket
   * through its statuses by hand must not have their thread filled with "there was nobody to tell",
   * once per move — which is also the resting state of every other ticket suite in this repository.
   */
  @Test
  public void aTicketWithNoWorkspaceIsAskedAndThenLeftAlone() {
    String projectId = createProject("Advance Nowhere");
    String ticketId = createTicket(projectId, "Nobody is on this");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.NO_WORKSPACE, "no workspace stands on that branch");

    transition(ticketId, "REFINED");
    transition(ticketId, "IMPLEMENTED");

    assertEquals(2, turns.calls().size(), "the far side is still asked — it is the only thing that knows");
    asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));

    // And the transition itself stands, which is the half that must never depend on any of this.
    asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("IMPLEMENTED"));
  }

  /**
   * A far side that refused: the move stands, and the thread gets the honest sentence — the reason,
   * the status the ticket now holds, and <b>nothing claiming an agent is on it</b>.
   */
  @Test
  public void aRefusedDeliveryLeavesTheTransitionAndSaysSoPlainly() {
    String projectId = createProject("Advance Refused");
    String ticketId = createTicket(projectId, "Nothing answers here");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.COULD_NOT, "qits-workspaces answered 503");

    transition(ticketId, "REFINED");

    assertEquals(
        java.util.List.of(
            "Could not start the implement phase: qits-workspaces answered 503. The ticket is"
                + " REFINED and nothing is running on it."),
        thread(ticketId));
    asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("REFINED"));
  }

  /**
   * <b>A port that throws although its contract forbids it.</b> The transition has already been
   * recorded by the time the delivery runs, so a port bug may not undo it, may not reach the caller
   * as an error, and still owes the thread an answer.
   */
  @Test
  public void aPortThatThrowsCannotUndoTheTransitionAndStillSaysSomething() {
    String projectId = createProject("Advance Thrown");
    String ticketId = createTicket(projectId, "The port misbehaves");
    turns.willThrow(new IllegalStateException("the adapter is broken"));

    transition(ticketId, "REFINED"); // a 200, or this line fails

    assertEquals(
        java.util.List.of(
            "Could not start the implement phase: the delivery failed unexpectedly. The ticket is"
                + " REFINED and nothing is running on it."),
        thread(ticketId));
    asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("REFINED"));
  }

  // --- when it runs ---------------------------------------------------------------------------

  /**
   * <b>The delivery happens after the transition is committed, so a transition that never happened
   * speaks to nobody.</b> A non-adjacent target is refused by the lifecycle, and what this pins is
   * that the refusal costs nothing outward: the port is not asked, and no thread is stamped about a
   * phase that was never entered.
   */
  @Test
  public void aRefusedTransitionSpeaksToNobody() {
    String projectId = createProject("Advance Refused Move");
    String ticketId = createTicket(projectId, "Too far in one step");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    asAdmin("dana")
        .body(new Transition("IMPLEMENTED")) // REPORTED → IMPLEMENTED is two steps
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(409);

    assertTrue(turns.calls().isEmpty(), "a move that was refused started no phase");
    asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));
  }

  /** A ticket that does not exist is a 404 and asks nothing of anybody. */
  @Test
  public void anUnknownTicketSpeaksToNobodyEither() {
    asAdmin("dana")
        .body(new Transition("REFINED"))
        .when()
        .post("/projects/api/tickets/no-such-ticket/transition")
        .then()
        .statusCode(404);

    assertTrue(turns.calls().isEmpty(), "there was no transition, so there is no phase to start");
  }

  /**
   * The dispatch door and this flow have to arrive at the <b>same</b> address, or the hand-off talks
   * to a branch nobody made. Both resolve through {@code TicketWorkspaces}; this asserts the answer
   * rather than the sharing, because the answer is what the far side sees.
   */
  @Test
  public void theTurnGoesToTheSameWrapperAndBranchTheDispatchDoorStandsUp() {
    String projectId = createProject("Advance Same Address");
    String ticketId = createTicket(projectId, "One address");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "REFINED");

    assertEquals(wrapperIdOf(projectId), turns.lastCall().repositoryId());
    assertEquals("ticket/one-address", turns.lastCall().branch());
    assertTrue(
        turns.lastCall().text().contains(ticketId),
        "and the turn tells the agent where to read the ticket itself");
  }

  /** The comment is the dispatch comment's twin, so it is stamped from the caller like any other. */
  @Test
  public void theCommentIsStampedFromTheCallerThatMovedTheTicket() {
    String projectId = createProject("Advance Stamped");
    String ticketId = createTicket(projectId, "Who said that");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    asAdmin("mallory")
        .body(new Transition("REFINED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200);

    asAdmin("dana")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries[0].comment.author", equalTo("mallory"))
        .body("entries[0].comment.body", containsString("Started the implement phase"));
  }
}
