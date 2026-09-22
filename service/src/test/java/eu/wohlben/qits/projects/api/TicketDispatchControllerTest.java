package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
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
 * wrapper's row id, {@code ticket/<slug>}, the whole-estate {@code branchTree}, the ticket's id and
 * the turn the ticket's <b>status</b> picked — and <b>what the ticket is left saying</b> afterwards.
 * The dispatch itself is qits-workspaces' and is not simulated here, and the three templates'
 * sentences are {@link TicketPhasePromptsTest}'s.
 *
 * <p>The caller is named with the real {@code X-Qits-*} pair rather than {@code @TestSecurity},
 * because the comment's {@code author} is one of the assertions and the header is what produces it
 * in a deployment ({@code EntitiesAuditIdentityTest}'s reasoning, applied to the one comment this door
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
                "title",
                title,
                "type",
                type,
                "impetus",
                "something occurs in this project",
                "description",
                description,
                "assignee",
                "dana"))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  /** One step along the lifecycle, through the door a person presses. */
  private void transition(String ticketId, String target) {
    asAdmin("setup")
        .body(new TicketControllerTransition(target))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200);
  }

  /** The transition body, spelled here so this suite needs nothing of the entities module's API. */
  private record TicketControllerTransition(String target) {}

  /**
   * A block, through the door a person or an agent presses. It is the cheapest way to reach a
   * blocked row from here — the flag has exactly one writer — and what that door itself refuses is
   * argued where it lives rather than here.
   */
  private void block(String ticketId, String reason) {
    asAdmin("setup")
        .body(new TicketControllerBlock(true, reason))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/blocked")
        .then()
        .statusCode(200);
  }

  /** The block body, spelled here for {@link TicketControllerTransition}'s reason. */
  private record TicketControllerBlock(boolean blocked, String reason) {}

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

    // A freshly created ticket is REPORTED, so the turn it is given is the refine phase's — the
    // templates themselves are asserted sentence by sentence in TicketPhasePromptsTest.
    assertTrue(
        asked.instruction().contains("Refine ticket \""),
        "the status picks the phase, and a new ticket's phase is refinement: "
            + asked.instruction());
    assertTrue(
        asked.instruction().contains("transition_ticket to REFINED"),
        "and the phase ends with its own claim");
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
            containsString("Dispatched a coding agent to workspace `ticket/"))
        .body(
            "entries[0].comment.body",
            containsString("for the refine phase"));
  }

  /**
   * The resume: the door reads the status at the moment it is pressed, so a ticket that was refined
   * last week gets the implement turn rather than a second refinement of a description that is
   * already written. The phase is never passed in, so this is the only way it could be wrong.
   */
  @Test
  public void aTicketAlreadyRefinedIsResumedAtTheImplementPhase() {
    String projectId = createProject("Dispatch Resume");
    String ticketId = createTicket(projectId, "Resume me", "BUG", "It is refined already.");
    transition(ticketId, "REFINED");

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(200);

    assertTrue(
        dispatch.lastCall().instruction().contains("Implement ticket \""),
        "a REFINED ticket starts the implement phase: " + dispatch.lastCall().instruction());

    asAdmin("mallory")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries[0].comment.body", containsString("for the implement phase"));
  }

  /**
   * <b>DONE is past the work.</b> The refusal is decided before the port is asked for anything, so
   * what this pins is both halves: the 409 naming the status, and the fact that no workspace was
   * stood up and nothing landed on the thread for a caller that was always going to be refused.
   */
  @Test
  public void aDispatchOntoADoneTicketIsRefusedAndStartsNoWorkspace() {
    String projectId = createProject("Dispatch Finished");
    String ticketId = createTicket(projectId, "All over", "BUG", "It was fixed.");
    transition(ticketId, "REFINED");
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");
    transition(ticketId, "DONE");

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(409)
        .body("message", containsString("is DONE"))
        .body("message", containsString("no phase left to start"));

    assertTrue(
        dispatch.calls().isEmpty(),
        "a ticket past the work starts no workspace at all, so nothing is asked of the port");

    // The one comment on this thread is the lifecycle's own — the walk through VERIFIED says that
    // no workspace was standing on the branch, so no release was asked for. The refused dispatch
    // added nothing to it.
    asAdmin("mallory")
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(1))
        .body(
            "entries[0].comment.body",
            equalTo("No workspace is standing on `ticket/all-over`, so no release was asked for."));
  }

  /** VERIFIED is the other status that starts nothing: a person closes it, an agent does not. */
  @Test
  public void aDispatchOntoAVerifiedTicketIsRefusedToo() {
    String projectId = createProject("Dispatch Verified");
    String ticketId = createTicket(projectId, "Confirmed gone", "BUG", "Checked on the platform.");
    transition(ticketId, "REFINED");
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(409)
        .body("message", containsString("is VERIFIED"));

    assertTrue(dispatch.calls().isEmpty(), "nothing is dispatched onto work that is over");
  }

  /**
   * DROPPED is the third status that starts nothing, and it is the one that is not past the work at
   * all: nothing was implemented and nothing was verified, somebody decided against doing it. The
   * refusal is the same one for the same reason — there is no phase to start — and it names the
   * status, because "no phase left to start" on its own would read as a ticket that is finished.
   */
  @Test
  public void aDispatchOntoADroppedTicketIsRefusedToo() {
    String projectId = createProject("Dispatch Dropped");
    String ticketId =
        createTicket(projectId, "Not worth doing", "IMPROVEMENT", "We decided not to.");
    transition(ticketId, "DROPPED");

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(409)
        .body("message", containsString("is DROPPED"))
        .body("message", containsString("no phase left to start"));

    assertTrue(
        dispatch.calls().isEmpty(),
        "work that was decided against has no workspace stood up for it");
  }

  /**
   * <b>A block refuses a dispatch that the status alone would have allowed</b>, which is the whole
   * of what the flag adds to this door: REFINED plainly starts the implement phase, so the only
   * thing standing between this caller and a workspace is somebody having written down what is in
   * the way. An agent sent in anyway would walk into the same wall the last one did, with the
   * reason one read away on the thread and nothing telling it to look.
   *
   * <p>The refusal must say <em>blocked</em> and must not say there is no phase left to start: the
   * two sentences send a reader to opposite places — one to the thread to find the obstacle, the
   * other to the conclusion that the ticket is over — and a blocked ticket answering the second
   * would be this door lying about which of them is true.
   */
  @Test
  public void aDispatchOntoABlockedTicketIsRefusedNamingTheBlockAndNotTheStatus() {
    String projectId = createProject("Dispatch Blocked");
    String ticketId = createTicket(projectId, "Waiting on something", "BUG", "It is refined.");
    transition(ticketId, "REFINED");
    block(ticketId, "the sibling service has to release its fix first");
    dispatch.reset(); // the transition and the block are fixture; what follows is the subject

    asAdmin("mallory")
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(409)
        .body("message", containsString("blocked"))
        .body("message", containsString(ticketId))
        .body("message", not(containsString("no phase left to start")));

    assertTrue(
        dispatch.calls().isEmpty(),
        "the block is decided before the port is asked for anything, so no workspace was stood up");
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
