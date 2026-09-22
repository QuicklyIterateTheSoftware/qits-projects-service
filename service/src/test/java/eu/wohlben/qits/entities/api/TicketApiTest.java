package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REST round-trips for the tickets boundary — the twin of {@link EpicApiTest}, and the caller is
 * named with {@code @TestSecurity} for the reason that class gives: this test is about the ticket
 * lifecycle, so naming the caller directly keeps it independent of how the identity arrives.
 * {@link EntitiesAuditIdentityTest} is what vouches for the arrival.
 *
 * <p>Which makes the {@code createdBy}/{@code author} assertions here worth stating precisely: they
 * pin that the columns are taken from <em>whatever</em> identity the request has and never from the
 * body, not that the header path produces one.
 */
@QuarkusTest
class TicketApiTest {

  private String createProject() {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Tickets Project", null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createTicket(String projectId, String title, String type) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectTicketsController.CreateTicketRequest(
                title, "something occurs in this project", null, type, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("ticket.id");
  }

  private String addComment(String ticketId, String body) {
    return given()
        .contentType(ContentType.JSON)
        .body(new TicketController.CreateTicketCommentRequest(body))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("comment.id");
  }

  /** Walk a ticket along the pipeline, one adjacent move per step, asserting nothing else. */
  private void walkTo(String ticketId, String... targets) {
    for (String target : targets) {
      given()
          .contentType(ContentType.JSON)
          .body(new TicketController.TransitionTicketRequest(target))
          .when()
          .post("/projects/api/tickets/" + ticketId + "/transition")
          .then()
          .statusCode(Response.Status.OK.getStatusCode());
    }
  }

  /**
   * The blocked door with its answer left unasserted, so the one success and all three refusals
   * below go through a single spelling of the route and the body — the thing a copy per test is
   * free to drift on.
   */
  private ValidatableResponse setBlocked(String ticketId, boolean blocked, String reason) {
    return given()
        .contentType(ContentType.JSON)
        .body(new TicketController.SetTicketBlockedRequest(blocked, reason))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/blocked")
        .then();
  }

  // --- The whole round trip --------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void fullTicketAndCommentLifecycle() {
    String projectId = createProject();

    String ticketId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectTicketsController.CreateTicketRequest(
                    "Login button does nothing",
                    "clicking the login button does nothing on the sign-in page",
                    "It just sits there",
                    "BUG",
                    "alice"))
            .when()
            .post("/projects/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("ticket.id", notNullValue())
            .body("ticket.projectId", equalTo(projectId))
            .body("ticket.title", equalTo("Login button does nothing"))
            .body("ticket.slug", equalTo("login-button-does-nothing"))
            .body("ticket.type", equalTo("BUG"))
            // A filed ticket has been REPORTED and no more — the lifecycle's starting point.
            .body("ticket.status", equalTo("REPORTED"))
            .body(
                "ticket.impetus",
                equalTo("clicking the login button does nothing on the sign-in page"))
            .body("ticket.assignee", equalTo("alice"))
            // Stamped from the identity, never from the body.
            .body("ticket.createdBy", equalTo("dev"))
            .body("ticket.createdAt", notNullValue())
            .extract()
            .path("ticket.id");

    // Get + list.
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.id", equalTo(ticketId))
        .body("ticket.slug", equalTo("login-button-does-nothing"));
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .body("entries.ticket.id", hasItem(ticketId));

    // Update: a retitle plus a re-type, leaving the nullable fields alone.
    given()
        .contentType(ContentType.JSON)
        .body(
            new TicketController.UpdateTicketRequest(
                "Login button is inert", null, false, null, false, "IMPROVEMENT", null, false))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.title", equalTo("Login button is inert"))
        .body("ticket.type", equalTo("IMPROVEMENT"))
        .body("ticket.description", equalTo("It just sits there"))
        .body(
            "ticket.impetus",
            equalTo("clicking the login button does nothing on the sign-in page"))
        .body("ticket.assignee", equalTo("alice"))
        // The slug is the row's stable address, so a rename never touches it.
        .body("ticket.slug", equalTo("login-button-does-nothing"));

    // Transition, one adjacent step at a time, and back again.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("REFINED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("REFINED"));
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("IMPLEMENTED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("IMPLEMENTED"));
    // A failed verification is this ordinary backward move and not a verb of its own.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("REFINED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("REFINED"));

    // Comments: created under the ticket, then edited on their own root.
    String commentId =
        given()
            .contentType(ContentType.JSON)
            .body(new TicketController.CreateTicketCommentRequest("I can reproduce it"))
            .when()
            .post("/projects/api/tickets/" + ticketId + "/comments")
            .then()
            .statusCode(200)
            .body("comment.ticketId", equalTo(ticketId))
            .body("comment.body", equalTo("I can reproduce it"))
            .body("comment.author", equalTo("dev"))
            .extract()
            .path("comment.id");

    given()
        .contentType(ContentType.JSON)
        .body(new TicketCommentController.UpdateTicketCommentRequest("I can reproduce it, in dev"))
        .when()
        .put("/projects/api/ticket-comments/" + commentId)
        .then()
        .statusCode(200)
        .body("comment.body", equalTo("I can reproduce it, in dev"))
        // An edit records who changed it in the audit log; it does not rewrite who wrote it.
        .body("comment.author", equalTo("dev"));

    given()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries.comment.id", hasItem(commentId));

    given()
        .when()
        .delete("/projects/api/ticket-comments/" + commentId)
        .then()
        .statusCode(200)
        .body("success", equalTo(true));
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(0));

    // Delete the ticket.
    given()
        .when()
        .delete("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("success", equalTo(true));
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  // --- Listing ---------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theListIsOldestFirstAndScopedToItsProject() {
    String projectA = createProject();
    String projectB = createProject();
    String first = createTicket(projectA, "First", "BUG");
    String second = createTicket(projectA, "Second", "IMPROVEMENT");
    String elsewhere = createTicket(projectB, "Elsewhere", "BUG");

    given()
        .when()
        .get("/projects/api/projects/" + projectA + "/tickets")
        .then()
        .statusCode(200)
        .body("entries.ticket.id", contains(first, second))
        .body("entries.ticket.title", contains("First", "Second"));

    given()
        .when()
        .get("/projects/api/projects/" + projectB + "/tickets")
        .then()
        .statusCode(200)
        .body("entries.ticket.id", contains(elsewhere));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theListFiltersByStatusAndRefusesATypo() {
    String projectId = createProject();
    String reported = createTicket(projectId, "Still broken", "BUG");
    String refined = createTicket(projectId, "Described", "BUG");
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("REFINED"))
        .when()
        .post("/projects/api/tickets/" + refined + "/transition")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets?status=REPORTED")
        .then()
        .statusCode(200)
        .body("entries.ticket.id", contains(reported));
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets?status=REFINED")
        .then()
        .statusCode(200)
        .body("entries.ticket.id", contains(refined));

    // A typo must not read as "no tickets", and the retired vocabulary is now one.
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets?status=REFIND")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets?status=OPEN")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void commentsComeBackOldestFirst() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Threaded", "BUG");
    String first = addComment(ticketId, "one");
    String second = addComment(ticketId, "two");
    String third = addComment(ticketId, "three");

    // A conversation is read from the start — the opposite of the audit log's newest-first.
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.comment.id", contains(first, second, third))
        .body("entries.comment.body", contains("one", "two", "three"));
  }

  // --- Partial updates -------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theClearFlagsAreWhatEmptyTheNullableFields() {
    String projectId = createProject();
    String ticketId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectTicketsController.CreateTicketRequest(
                    "Assigned", "the list is unsorted", "a body", "BUG", "alice"))
            .when()
            .post("/projects/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(200)
            .extract()
            .path("ticket.id");

    // A title-only edit touches none of the three — the defect the flags exist to stop.
    given()
        .contentType(ContentType.JSON)
        .body(
            new TicketController.UpdateTicketRequest(
                "Renamed", null, false, null, false, null, null, false))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.impetus", equalTo("the list is unsorted"))
        .body("ticket.description", equalTo("a body"))
        .body("ticket.assignee", equalTo("alice"));

    given()
        .contentType(ContentType.JSON)
        .body(
            new TicketController.UpdateTicketRequest(null, null, true, null, true, null, null, true))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.impetus", nullValue())
        .body("ticket.description", nullValue())
        .body("ticket.assignee", nullValue());
  }

  // --- Refusals --------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void anIllegalTransitionTargetIs409() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Reported already", "BUG");

    // Already REPORTED: the move is refused rather than being a no-op.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("REPORTED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());

    // And so is a status that exists but is not a neighbour: moves are one step at a time.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("IMPLEMENTED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());

    // A target naming no status at all is the same kind of answer.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("CLOSED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());

    // An absent one is malformed, not refused.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest(null))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void deletingATicketTakesItsCommentsWithIt() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Doomed", "BUG");
    String commentId = addComment(ticketId, "goes with it");

    given().when().delete("/projects/api/tickets/" + ticketId).then().statusCode(200);

    given()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .contentType(ContentType.JSON)
        .body(new TicketCommentController.UpdateTicketCommentRequest("still here?"))
        .when()
        .put("/projects/api/ticket-comments/" + commentId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void slugsCollideIntoSuffixesWithinAProject() {
    String projectA = createProject();
    String projectB = createProject();

    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest(
                "Broken login", "the login page is broken", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectA + "/tickets")
        .then()
        .statusCode(200)
        .body("ticket.slug", equalTo("broken-login"));
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest(
                "Broken   LOGIN!", "the login page is broken", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectA + "/tickets")
        .then()
        .statusCode(200)
        .body("ticket.slug", equalTo("broken-login-2"));

    // Another project is another scope, so the clean slug is free again.
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest(
                "Broken login", "the login page is broken", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectB + "/tickets")
        .then()
        .statusCode(200)
        .body("ticket.slug", equalTo("broken-login"));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void createTicketUnderUnknownProjectIs404() {
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest(
                "X", "something occurs", null, "BUG", null))
        .when()
        .post("/projects/api/projects/ghost/tickets")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .when()
        .get("/projects/api/projects/ghost/tickets")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void unknownTicketAndCommentIdsAre404() {
    given()
        .when()
        .get("/projects/api/tickets/ghost")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.CreateTicketCommentRequest("hello"))
        .when()
        .post("/projects/api/tickets/ghost/comments")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .when()
        .delete("/projects/api/ticket-comments/ghost")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blankTitlesTypesAndBodiesAreRejected() {
    String projectId = createProject();

    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest(
                "  ", "something occurs", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest(
                "T", "something occurs", null, "  ", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    // The impetus is required at intake: a REPORTED ticket is an impetus and nothing else.
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest("T", null, null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest("T", "  ", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));

    // A type that is present and names nothing is the service's 400, not the validator's.
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest(
                "T", "something occurs", null, "DEFECT", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());

    String ticketId = createTicket(projectId, "Live", "BUG");
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.CreateTicketCommentRequest(" "))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    // A supplied-but-blank title on the partial update is NotBlankIfPresent's refusal.
    given()
        .contentType(ContentType.JSON)
        .body(
            new TicketController.UpdateTicketRequest(
                "  ", null, false, null, false, null, null, false))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
  }

  // --- Blocking ----------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blockingARefinedTicketIsAnsweredBlockedAndReadsBackBlocked() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Stuck on a sibling", "BUG");
    walkTo(ticketId, "REFINED");

    // Two claims in one walk, because they are two different ways to be wrong. The door answers
    // the row it just wrote, so a caller never has to re-read to learn what it did; and the flag
    // is on the row rather than on that answer, so the detail GET — which is how every listing
    // screen and every agent actually reads a ticket — carries it too.
    setBlocked(ticketId, true, "qits-eventstream has not released the watchdog this needs")
        .statusCode(Response.Status.OK.getStatusCode())
        .body("ticket.blocked", equalTo(true))
        // A block is not a status: the status still names the phase to resume.
        .body("ticket.status", equalTo("REFINED"));
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.blocked", equalTo(true))
        .body("ticket.status", equalTo("REFINED"));

    setBlocked(ticketId, false, "it released this morning")
        .statusCode(Response.Status.OK.getStatusCode())
        .body("ticket.blocked", equalTo(false));
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.blocked", equalTo(false));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blockingATicketWhoseStatusStartsNoPhaseIsRefused() {
    String projectId = createProject();

    // Three statuses reached three different ways, and one refusal for all of them, because the
    // rule is "no phase runs while this status holds" rather than a list of three words: VERIFIED
    // and DONE are the far end of the pipeline, DROPPED is the exit off it and is reached without
    // touching either. A block there would name a phase that is not running and that nothing will
    // ever resume, so the ticket would read as waiting on something for good.
    String verified = createTicket(projectId, "Verified already", "BUG");
    walkTo(verified, "REFINED", "IMPLEMENTED", "VERIFIED");
    String done = createTicket(projectId, "Closed", "BUG");
    walkTo(done, "REFINED", "IMPLEMENTED", "VERIFIED", "DONE");
    String dropped = createTicket(projectId, "Decided against", "IMPROVEMENT");
    walkTo(dropped, "DROPPED");

    for (String ticketId : List.of(verified, done, dropped)) {
      // The message names what is missing rather than the status alone: a 409 on a ticket that
      // plainly exists otherwise leaves the caller guessing whether the block was refused or the
      // ticket was.
      setBlocked(ticketId, true, "waiting on somebody")
          .statusCode(Response.Status.CONFLICT.getStatusCode())
          .body("message", containsString("no phase is running and there is nothing to block"));
    }
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blockingWithNoStatedBlockerIsRejectedAndLeavesNothingBehind() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Blocked by nothing in particular", "BUG");

    // Absent and blank are one case: what is asked for is a sentence somebody could act on, and
    // whitespace is not less of an answer than nothing at all.
    for (String reason : Arrays.asList(null, "   ")) {
      setBlocked(ticketId, true, reason)
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
          .body("message", containsString("stated blocker"));
    }

    // Both writes are on the far side of that refusal, and asserting it is what makes the ordering
    // real: a blocked ticket whose thread says nothing is precisely the state this rule exists to
    // prevent, so a refusal that had already flipped the flag would have created it.
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.blocked", equalTo(false));
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(0));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theBlockerAndTheUnblockAreBothSaidOnTheThread() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Waiting on the registry", "BUG");
    walkTo(ticketId, "REFINED");

    setBlocked(ticketId, true, "the npm registry refuses the scope, only an operator can add it")
        .statusCode(Response.Status.OK.getStatusCode());
    // A blocker is a remark with an author and a time, which is what the thread already is — so it
    // lands there in the caller's own words, stamped like any other comment, instead of in a column
    // that would go stale the moment the conversation moved past it.
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries[0].comment.body", containsString("the npm registry refuses the scope"))
        .body("entries[0].comment.author", equalTo("dev"));

    // An unblock with nothing to add still says so. A thread that simply goes quiet leaves the next
    // reader unable to tell a cleared blocker from one nobody mentioned again.
    setBlocked(ticketId, false, null).statusCode(Response.Status.OK.getStatusCode());
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(2))
        .body("entries[1].comment.body", equalTo("Unblocked."));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void aTransitionClearsTheBlockTheDoorSet() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Blocked then moved on", "BUG");
    walkTo(ticketId, "REFINED");
    setBlocked(ticketId, true, "the change it depends on is not released")
        .statusCode(Response.Status.OK.getStatusCode());

    // A block says the phase running NOW cannot finish, so the phase changing is what ends it.
    // Read through the door rather than off the row, because the flag reaching the wire is what a
    // board draws from: a transition that cleared the column while the DTO went on reporting the
    // old value would leave every screen showing work as stuck after it moved.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("IMPLEMENTED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("IMPLEMENTED"))
        .body("ticket.blocked", equalTo(false));
    given()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.blocked", equalTo(false));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void unblockingIsAllowedAtAStatusThatCouldNotHaveBeenBlocked() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Blocked on the way to verified", "BUG");
    walkTo(ticketId, "REFINED", "IMPLEMENTED");
    setBlocked(ticketId, true, "the deployment has not gone out")
        .statusCode(Response.Status.OK.getStatusCode());
    walkTo(ticketId, "VERIFIED");

    // Only the blocking direction is refused, and the asymmetry is deliberate rather than an
    // oversight: block-then-transition is the one path that leads here, this door produced it, and
    // refusing to unblock at VERIFIED would mean refusing to tidy up a state of its own making.
    // That it is already false is the point — the ask is for something true, not for a move.
    // The refusal in the other direction is pinned by
    // blockingATicketWhoseStatusStartsNoPhaseIsRefused.
    setBlocked(ticketId, false, "it deployed")
        .statusCode(Response.Status.OK.getStatusCode())
        .body("ticket.blocked", equalTo(false))
        .body("ticket.status", equalTo("VERIFIED"));
  }
}
