package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The REST round-trip for {@code PATCH /projects/api/work/{id}} — the merge-patch field edit.
 *
 * <p>What this class is for: the wire's reading of the body (absent is unchanged, null clears), the
 * refusals a patch makes of its own, and the one rule it inherits and must not bypass — the scope
 * freeze. Every rule behind the write itself is {@code WorkEntityService.update}'s and asserted
 * where that service is.
 */
@QuarkusTest
class WorkPatchApiTest {

  private static String createProject() {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Patch Project", null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private static String createTicket(String projectId) {
    return TestCriteria.give(
        WorkRequests.create(
                WorkRequests.map(
                    "archetype", "TICKET",
                    "project", projectId,
                    "title", "The title",
                    "impetus", "it occurs",
                    "description", "the body",
                    "ticketType", "BUG",
                    "assignee", "somebody"))
            .path("id"));
  }

  private static String createEpic(String projectId) {
    return TestCriteria.give(
        WorkRequests.create(
                WorkRequests.map(
                    "archetype", "EPIC",
                    "project", projectId,
                    "title", "The plan",
                    "description", "The spine"))
            .path("id"));
  }

  private static String createFeature(String epicId) {
    return WorkRequests.feature(epicId, "The part");
  }

  private static ValidatableResponse patch(String id, Object body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then();
  }

  private static ValidatableResponse ticket(String id) {
    return given().when().get("/projects/api/work/" + id).then().statusCode(200);
  }

  /** A body with an explicit null in it — {@code Map.of} refuses null values. */
  private static Map<String, Object> nulling(String property) {
    Map<String, Object> body = new HashMap<>();
    body.put(property, null);
    return body;
  }

  /**
   * <b>Absent means unchanged</b>, and this is the assertion the door exists for: a retitle leaves
   * the body, the impetus, the type and the assignee exactly as they were.
   */
  @Test
  void aTitleOnlyPatchChangesOnlyTheTitle() {
    String ticketId = createTicket(createProject());

    patch(ticketId, Map.of("title", "x"))
        .statusCode(200)
        .body("id", equalTo(ticketId))
        .body("archetype", equalTo("TICKET"))
        .body("title", equalTo("x"))
        .body("description", equalTo("the body"))
        .body("qualifiedId", containsString("-"));

    ticket(ticketId)
        .body("title", equalTo("x"))
        .body("description", equalTo("the body"))
        .body("impetus", equalTo("it occurs"))
        .body("ticketType", equalTo("BUG"))
        .body("assignee", equalTo("somebody"))
        .body("status", equalTo("REPORTED"));
  }

  /** An explicit null clears a clearable property, and is refused on one that cannot be empty. */
  @Test
  void aNullClearsWhatMayBeClearedAndIsRefusedOnTheTitle() {
    String ticketId = createTicket(createProject());

    patch(ticketId, nulling("description")).statusCode(200).body("description", nullValue());
    ticket(ticketId)
        .body("description", nullValue())
        .body("impetus", equalTo("it occurs"));

    patch(ticketId, nulling("title"))
        .statusCode(400)
        .body("message", containsString("title cannot be cleared"));
    ticket(ticketId).body("title", equalTo("The title"));
  }

  /** A move is not an edit: each is refused with the door that makes it, and nothing moves. */
  @Test
  void aMoveIsRefusedWithTheDoorThatMakesIt() {
    String ticketId = createTicket(createProject());

    patch(ticketId, Map.of("status", "REFINED"))
        .statusCode(400)
        .body("message", containsString("POST /projects/api/work/{qualifiedId}/status"));
    patch(ticketId, Map.of("archetype", "EPIC"))
        .statusCode(400)
        .body("message", containsString("POST /projects/api/work/transition"));
    patch(ticketId, Map.of("membership", Map.of("parent", "somewhere")))
        .statusCode(400)
        .body("message", containsString("POST /projects/api/work/transition"));

    ticket(ticketId).body("status", equalTo("REPORTED"));
  }

  /** Every complaint in one 400, the transition door's shape, and none of the patch applied. */
  @Test
  void everyComplaintComesBackInOne400() {
    String ticketId = createTicket(createProject());

    patch(ticketId, Map.of("title", "Not applied", "slug", "s", "colour", "red", "status", "DONE"))
        .statusCode(400)
        .body(
            "message",
            allOf(
                containsString("slug is server-owned"),
                containsString("unknown property: colour"),
                containsString("status is not written by a patch")));

    ticket(ticketId).body("title", equalTo("The title"));
  }

  /** A property the archetype has no slot for is refused, not dropped. */
  @Test
  void anImpetusOnAnEpicIsRefused() {
    String epicId = createEpic(createProject());

    patch(epicId, Map.of("impetus", "why"))
        .statusCode(400)
        .body("message", containsString("has no impetus"));
  }

  /**
   * <b>The scope freeze stays in force.</b> A REFINED epic's scope is frozen, so a retitle of it —
   * or of a feature under it — is the service's 409, reached through this door as through any other.
   */
  @Test
  void aFrozenEpicsScopeIsA409() {
    String epicId = createEpic(createProject());
    String featureId = createFeature(epicId);
    WorkRequests.status(epicId, "REFINED").then().statusCode(200);

    patch(epicId, Map.of("title", "Rescoped")).statusCode(409);
    patch(featureId, Map.of("title", "Rescoped")).statusCode(409);

    given()
        .when()
        .get("/projects/api/work/" + epicId)
        .then()
        .statusCode(200)
        .body("title", equalTo("The plan"));
  }

  @Test
  void anUnknownIdIsA404() {
    patch("no-such-entity", Map.of("title", "x")).statusCode(404);
  }

  @Test
  void anEmptyPatchIsRefused() {
    String ticketId = createTicket(createProject());

    patch(ticketId, Map.of())
        .statusCode(400)
        .body("message", containsString("at least one property"));
  }

  @Test
  void aNonObjectBodyIsRefused() {
    String ticketId = createTicket(createProject());

    patch(ticketId, "[\"title\"]")
        .statusCode(400)
        .body("message", containsString("must be a JSON object"));
  }

  /** RFC 7396's own media type is accepted beside plain JSON. */
  @Test
  void theMergePatchMediaTypeIsAccepted() {
    String ticketId = createTicket(createProject());

    given()
        .contentType("application/merge-patch+json")
        .body("{\"assignee\":null,\"ticketType\":\"IMPROVEMENT\"}")
        .when()
        .patch("/projects/api/work/" + ticketId)
        .then()
        .statusCode(200)
        .body("assignee", nullValue())
        .body("ticketType", equalTo("IMPROVEMENT"));

    ticket(ticketId)
        .body("assignee", nullValue())
        .body("ticketType", equalTo("IMPROVEMENT"))
        .body("title", equalTo("The title"));
  }
}
