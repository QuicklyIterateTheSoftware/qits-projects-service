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
 * The REST round-trip for {@code PATCH /projects/api/entities/{id}} — the merge-patch field edit.
 *
 * <p>What this class is for: the wire's reading of the body (absent is unchanged, null clears), the
 * refusals a patch makes of its own, and the one rule it inherits and must not bypass — the scope
 * freeze. Every rule behind the write itself is {@code WorkEntityService.update}'s and asserted
 * where that service is.
 */
@QuarkusTest
class EntityPatchApiTest {

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
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectTicketsController.CreateTicketRequest(
                "The title", "it occurs", "the body", "BUG", "somebody"))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  private static String createEpic(String projectId) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest("The plan", "The spine"))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private static String createFeature(String epicId) {
    return given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("The part", null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(200)
        .extract()
        .path("feature.id");
  }

  private static ValidatableResponse patch(String id, Object body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .patch("/projects/api/entities/" + id)
        .then();
  }

  private static ValidatableResponse ticket(String id) {
    return given().when().get("/projects/api/tickets/" + id).then().statusCode(200);
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
        .body("ticket.title", equalTo("x"))
        .body("ticket.description", equalTo("the body"))
        .body("ticket.impetus", equalTo("it occurs"))
        .body("ticket.type", equalTo("BUG"))
        .body("ticket.assignee", equalTo("somebody"))
        .body("ticket.status", equalTo("REPORTED"));
  }

  /** An explicit null clears a clearable property, and is refused on one that cannot be empty. */
  @Test
  void aNullClearsWhatMayBeClearedAndIsRefusedOnTheTitle() {
    String ticketId = createTicket(createProject());

    patch(ticketId, nulling("description")).statusCode(200).body("description", nullValue());
    ticket(ticketId)
        .body("ticket.description", nullValue())
        .body("ticket.impetus", equalTo("it occurs"));

    patch(ticketId, nulling("title"))
        .statusCode(400)
        .body("message", containsString("title cannot be cleared"));
    ticket(ticketId).body("ticket.title", equalTo("The title"));
  }

  /** A move is not an edit: each is refused with the door that makes it, and nothing moves. */
  @Test
  void aMoveIsRefusedWithTheDoorThatMakesIt() {
    String ticketId = createTicket(createProject());

    patch(ticketId, Map.of("status", "REFINED"))
        .statusCode(400)
        .body("message", containsString("/projects/api/tickets/{id}/transition"));
    patch(ticketId, Map.of("archetype", "EPIC"))
        .statusCode(400)
        .body("message", containsString("POST /projects/api/entities/transition"));
    patch(ticketId, Map.of("membership", Map.of("parent", "somewhere")))
        .statusCode(400)
        .body("message", containsString("POST /projects/api/entities/transition"));

    ticket(ticketId).body("ticket.status", equalTo("REPORTED"));
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

    ticket(ticketId).body("ticket.title", equalTo("The title"));
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
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.TransitionEpicRequest("REFINED"))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then()
        .statusCode(200);

    patch(epicId, Map.of("title", "Rescoped")).statusCode(409);
    patch(featureId, Map.of("title", "Rescoped")).statusCode(409);

    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.title", equalTo("The plan"));
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
        .patch("/projects/api/entities/" + ticketId)
        .then()
        .statusCode(200)
        .body("assignee", nullValue())
        .body("ticketType", equalTo("IMPROVEMENT"));

    ticket(ticketId)
        .body("ticket.assignee", nullValue())
        .body("ticket.type", equalTo("IMPROVEMENT"))
        .body("ticket.title", equalTo("The title"));
  }
}
