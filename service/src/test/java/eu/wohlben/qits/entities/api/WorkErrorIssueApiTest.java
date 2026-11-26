package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A ticket names the qits-observability error issue it was made from (qits-2538): {@code
 * errorIssue}, a fingerprint of 32 lower-case hex digits, written by the create and the patch (null
 * clears it), read on {@code GET /work/{id}}, and a filter of the project's work list. A TICKET's
 * alone: on any other archetype the doors answer 400, and so they do for a value that is no
 * fingerprint. Each test stands in a project of its own.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class WorkErrorIssueApiTest {

  private static final String ISSUE = "0123456789abcdef0123456789abcdef";
  private static final String OTHER = "fedcba9876543210fedcba9876543210";

  private static String project(String name) {
    return EntityFixtures.project(name).id();
  }

  private static Map<String, Object> ticketBody(String projectId, String title) {
    Map<String, Object> body = new HashMap<>();
    body.put("archetype", "TICKET");
    body.put("project", projectId);
    body.put("title", title);
    body.put("ticketType", "BUG");
    body.put("impetus", "It occurs.");
    return body;
  }

  private static ValidatableResponse create(Map<String, Object> body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/projects/api/work")
        .then();
  }

  private static String ticketFrom(String projectId, String title, String errorIssue) {
    Map<String, Object> body = ticketBody(projectId, title);
    body.put("errorIssue", errorIssue);
    return create(body).statusCode(201).extract().path("id");
  }

  private static ValidatableResponse patch(String id, Map<String, Object> body) {
    return given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then();
  }

  private static Map<String, Object> errorIssue(Object value) {
    Map<String, Object> body = new HashMap<>();
    body.put("errorIssue", value);
    return body;
  }

  private static ValidatableResponse list(String projectId, String query) {
    return given().when().get("/projects/api/projects/" + projectId + "/work" + query).then();
  }

  // --- create ------------------------------------------------------------------------------------

  @Test
  void aTicketCreatedFromAnErrorIssueCarriesItOnTheAnswerAndTheRead() {
    String projectId = project("Error Issue Create");
    Map<String, Object> body = ticketBody(projectId, "Export fails");
    body.put("errorIssue", ISSUE);

    String id =
        create(body).statusCode(201).body("errorIssue", equalTo(ISSUE)).extract().path("id");

    WorkRequests.read(id).then().body("errorIssue", equalTo(ISSUE));
  }

  @Test
  void aTicketMadeFromNoErrorIssueLeavesTheKeyOffTheWire() {
    String projectId = project("Error Issue Absent");
    String id = WorkRequests.ticket(projectId, "Plain", "BUG", "It occurs.");

    WorkRequests.read(id).then().body("$", not(hasKey("errorIssue")));
  }

  @Test
  void aCreateOnAnotherArchetypeIsRefused() {
    String projectId = project("Error Issue Epic Create");

    create(
            Map.of(
                "archetype", "EPIC", "project", projectId, "title", "Plan", "errorIssue", ISSUE))
        .statusCode(400)
        .body(containsString("errorIssue is a ticket's alone"));
  }

  @Test
  void aCreateWithAMalformedFingerprintIsRefused() {
    String projectId = project("Error Issue Malformed Create");
    for (Object malformed :
        List.of(
            ISSUE.toUpperCase(),
            ISSUE.substring(1),
            ISSUE + "0",
            "0123456789abcdef0123456789abcdeg",
            " " + ISSUE.substring(1),
            32)) {
      Map<String, Object> body = ticketBody(projectId, "Malformed");
      body.put("errorIssue", malformed);
      create(body).statusCode(400).body(containsString("errorIssue"));
    }
    assertEquals(List.of(), list(projectId, "").statusCode(200).extract().path("entities.id"));
  }

  // --- patch -------------------------------------------------------------------------------------

  @Test
  void aPatchSetsChangesAndClearsIt() {
    String projectId = project("Error Issue Patch");
    String id = WorkRequests.ticket(projectId, "Later linked", "BUG", "It occurs.");

    patch(id, errorIssue(ISSUE)).statusCode(200).body("errorIssue", equalTo(ISSUE));
    WorkRequests.read(id).then().body("errorIssue", equalTo(ISSUE));

    patch(id, errorIssue(OTHER)).statusCode(200).body("errorIssue", equalTo(OTHER));

    patch(id, errorIssue(null)).statusCode(200).body("$", not(hasKey("errorIssue")));
    WorkRequests.read(id).then().body("$", not(hasKey("errorIssue")));
  }

  @Test
  void aPatchOnAnotherArchetypeIsRefused() {
    String projectId = project("Error Issue Epic Patch");
    String epic = WorkRequests.epic(projectId, "Plan");

    patch(epic, errorIssue(ISSUE))
        .statusCode(400)
        .body(containsString("errorIssue is a ticket's alone"));
    patch(epic, errorIssue(null))
        .statusCode(400)
        .body(containsString("errorIssue is a ticket's alone"));
  }

  @Test
  void aPatchWithAMalformedFingerprintIsRefusedAndWritesNothing() {
    String projectId = project("Error Issue Malformed Patch");
    String id = ticketFrom(projectId, "Linked", ISSUE);

    patch(id, errorIssue("not-a-fingerprint"))
        .statusCode(400)
        .body(containsString("errorIssue must be an error issue's fingerprint"));
    patch(id, errorIssue(7)).statusCode(400).body(containsString("errorIssue must be a string"));

    WorkRequests.read(id).then().body("errorIssue", equalTo(ISSUE));
  }

  // --- the list filter ---------------------------------------------------------------------------

  @Test
  void theListNarrowsToTheTicketsMadeFromOneErrorIssue() {
    String projectId = project("Error Issue List");
    String first = ticketFrom(projectId, "First", ISSUE);
    ticketFrom(projectId, "Another issue", OTHER);
    WorkRequests.ticket(projectId, "No issue", "BUG", "It occurs.");
    WorkRequests.epic(projectId, "A plan");
    String second = ticketFrom(projectId, "Second", ISSUE);

    assertEquals(
        List.of(first, second),
        list(projectId, "?errorIssue=" + ISSUE).statusCode(200).extract().path("entities.id"));
    // ANDed with the other filters.
    assertEquals(
        List.of(),
        list(projectId, "?errorIssue=" + ISSUE + "&archetype=EPIC")
            .statusCode(200)
            .extract()
            .path("entities.id"));
    assertEquals(
        List.of(),
        list(projectId, "?errorIssue=" + "a".repeat(32))
            .statusCode(200)
            .extract()
            .path("entities.id"));
  }

  @Test
  void theListRefusesAMalformedFingerprint() {
    String projectId = project("Error Issue List Malformed");

    list(projectId, "?errorIssue=" + ISSUE.toUpperCase())
        .statusCode(400)
        .body(containsString("errorIssue must be an error issue's fingerprint"));
    list(projectId, "?errorIssue=not-a-fingerprint").statusCode(400);
  }
}
