package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
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
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * REST round-trips for the tickets boundary — the twin of {@link EpicApiTest}, and the caller is
 * named with {@code @TestSecurity} for the reason that class gives: this test is about the ticket
 * lifecycle, so naming the caller directly keeps it independent of how the identity arrives.
 * {@link EpicsAuditIdentityTest} is what vouches for the arrival.
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
        .body(new ProjectTicketsController.CreateTicketRequest(title, null, type, null))
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
                    "Login button does nothing", "It just sits there", "BUG", "alice"))
            .when()
            .post("/projects/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("ticket.id", notNullValue())
            .body("ticket.projectId", equalTo(projectId))
            .body("ticket.title", equalTo("Login button does nothing"))
            .body("ticket.slug", equalTo("login-button-does-nothing"))
            .body("ticket.type", equalTo("BUG"))
            // A new ticket is open — the lifecycle's starting point.
            .body("ticket.status", equalTo("OPEN"))
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
                "Login button is inert", null, false, "IMPROVEMENT", null, false))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.title", equalTo("Login button is inert"))
        .body("ticket.type", equalTo("IMPROVEMENT"))
        .body("ticket.description", equalTo("It just sits there"))
        .body("ticket.assignee", equalTo("alice"))
        // The slug is the row's stable address, so a rename never touches it.
        .body("ticket.slug", equalTo("login-button-does-nothing"));

    // Transition both ways.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("RESOLVED"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("RESOLVED"));
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("OPEN"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200)
        .body("ticket.status", equalTo("OPEN"));

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
    String open = createTicket(projectId, "Still broken", "BUG");
    String resolved = createTicket(projectId, "Fixed", "BUG");
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("RESOLVED"))
        .when()
        .post("/projects/api/tickets/" + resolved + "/transition")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets?status=OPEN")
        .then()
        .statusCode(200)
        .body("entries.ticket.id", contains(open));

    // A typo must not read as "no tickets".
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets?status=RESOLVD")
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
                    "Assigned", "a body", "BUG", "alice"))
            .when()
            .post("/projects/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(200)
            .extract()
            .path("ticket.id");

    // A title-only edit touches neither — this is the defect the flags exist to stop.
    given()
        .contentType(ContentType.JSON)
        .body(
            new TicketController.UpdateTicketRequest("Renamed", null, false, null, null, false))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.description", equalTo("a body"))
        .body("ticket.assignee", equalTo("alice"));

    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.UpdateTicketRequest(null, null, true, null, null, true))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.description", nullValue())
        .body("ticket.assignee", nullValue());
  }

  // --- Refusals --------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void anIllegalTransitionTargetIs409() {
    String projectId = createProject();
    String ticketId = createTicket(projectId, "Open already", "BUG");

    // Already OPEN: the move is refused rather than being a no-op.
    given()
        .contentType(ContentType.JSON)
        .body(new TicketController.TransitionTicketRequest("OPEN"))
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
        .body(new ProjectTicketsController.CreateTicketRequest("Broken login", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectA + "/tickets")
        .then()
        .statusCode(200)
        .body("ticket.slug", equalTo("broken-login"));
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest("Broken   LOGIN!", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectA + "/tickets")
        .then()
        .statusCode(200)
        .body("ticket.slug", equalTo("broken-login-2"));

    // Another project is another scope, so the clean slug is free again.
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest("Broken login", null, "BUG", null))
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
        .body(new ProjectTicketsController.CreateTicketRequest("X", null, "BUG", null))
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
        .body(new ProjectTicketsController.CreateTicketRequest("  ", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest("T", null, "  ", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
    // A type that is present and names nothing is the service's 400, not the validator's.
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectTicketsController.CreateTicketRequest("T", null, "DEFECT", null))
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
        .body(new TicketController.UpdateTicketRequest("  ", null, false, null, null, false))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(anyOf(equalTo(Response.Status.BAD_REQUEST.getStatusCode()), equalTo(422)));
  }
}
