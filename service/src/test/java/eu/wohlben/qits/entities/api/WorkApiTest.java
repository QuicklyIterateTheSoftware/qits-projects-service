package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The work family's routes ({@code /projects/api/work}, qits-969) over HTTP: every route reached by
 * a qualified id in its path, and every entity id a body carries accepted as a qualified id — the
 * create's {@code parent} and {@code dependsOn}, the patch's {@code dependsOn}, and on the PUT and
 * the bulk transition the keys, {@code membership.parent} and {@code supersededBy}. The rules behind
 * each write are the {@code /entities} doors' ({@link WorkEntityDoors}) and asserted in their own
 * suites; this class is about the addresses and the ids.
 *
 * <p>Fixtures are written through the older doors ({@link EntityFixtures}), never through the route
 * under test.
 */
@QuarkusTest
class WorkApiTest {

  private static final String WORK = "/projects/api/work/";

  private static ValidatableResponse send(String method, String path, Object body) {
    var request = given().contentType(ContentType.JSON);
    if (body != null) {
      request = request.body(body);
    }
    return request.when().request(method, path).then();
  }

  // --- GET /work/{qualifiedId} -----------------------------------------------------------------

  @Test
  void anEntityIsReadByItsQualifiedIdAndByItsUuid() {
    EntityFixtures.Project project = EntityFixtures.project("Work Read");
    String ticket = EntityFixtures.ticket(project.id());
    String qualified = EntityFixtures.qualifiedId(ticket);

    given()
        .when()
        .get(WORK + qualified)
        .then()
        .statusCode(200)
        .body("id", equalTo(ticket))
        .body("qualifiedId", equalTo(qualified))
        .body("archetype", equalTo("TICKET"));
    given().when().get(WORK + ticket).then().statusCode(200).body("qualifiedId", equalTo(qualified));
    given().when().get(WORK + project.slug() + "-99999").then().statusCode(404);
  }

  // --- POST /work ------------------------------------------------------------------------------

  @Test
  void aCreateTakesAProjectSlugAndAParentAndADependencyByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Create");

    String epicQualified =
        send(
                "POST",
                "/projects/api/work",
                map("archetype", "EPIC", "project", project.slug(), "title", "The plan"))
            .statusCode(201)
            .body("archetype", equalTo("EPIC"))
            .body("status", equalTo("REPORTED"))
            .body("qualifiedId", startsWith(project.slug() + "-"))
            .extract()
            .path("qualifiedId");
    String epicId = given().when().get(WORK + epicQualified).then().extract().path("id");

    ValidatableResponse first =
        send(
                "POST",
                "/projects/api/work",
                map("archetype", "FEATURE", "parent", epicQualified, "title", "First"))
            .statusCode(201)
            .body("parent", equalTo(epicId));
    String firstId = first.extract().path("id");
    String firstQualified = first.extract().path("qualifiedId");

    send(
            "POST",
            "/projects/api/work",
            map(
                "archetype",
                "FEATURE",
                "parent",
                epicQualified,
                "title",
                "Second",
                "dependsOn",
                firstQualified))
        .statusCode(201)
        .body("dependsOn", equalTo(firstId));
  }

  // --- PATCH /work/{qualifiedId} ---------------------------------------------------------------

  @Test
  void aPatchAddressesByQualifiedIdAndTakesADependencyByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Patch");
    String epic = EntityFixtures.epic(project.id());
    String first = EntityFixtures.feature(epic);
    String second = EntityFixtures.feature(epic);

    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("dependsOn", EntityFixtures.qualifiedId(first), "title", "Renamed"))
        .when()
        .patch(WORK + EntityFixtures.qualifiedId(second))
        .then()
        .statusCode(200)
        .body("id", equalTo(second))
        .body("title", equalTo("Renamed"))
        .body("dependsOn", equalTo(first));
  }

  @Test
  void aPatchNamingAStatusIsPointedAtTheWorkStatusDoor() {
    EntityFixtures.Project project = EntityFixtures.project("Work Patch Status");
    String ticket = EntityFixtures.ticket(project.id());

    send("PATCH", WORK + EntityFixtures.qualifiedId(ticket), map("status", "REFINED"))
        .statusCode(400)
        .body("message", containsString("POST /projects/api/work/{qualifiedId}/status"));
  }

  // --- PUT /work/{qualifiedId} -----------------------------------------------------------------

  @Test
  void aPutStatesTheEntityInFullAndClearsWhatItLeavesOut() {
    EntityFixtures.Project project = EntityFixtures.project("Work Put");
    String ticket = EntityFixtures.ticket(project.id());
    send("PATCH", WORK + ticket, map("description", "the body", "assignee", "somebody"))
        .statusCode(200);

    send(
            "PUT",
            WORK + EntityFixtures.qualifiedId(ticket),
            map(
                "archetype",
                "TICKET",
                "title",
                "Restated",
                "status",
                "REPORTED",
                "ticketType",
                "BUG",
                "impetus",
                "it occurs",
                "acceptanceCriteria",
                TestCriteria.CRITERIA))
        .statusCode(200)
        .body("id", equalTo(ticket))
        .body("title", equalTo("Restated"))
        .body("description", nullValue())
        .body("assignee", nullValue())
        .body("qualifiedId", equalTo(EntityFixtures.qualifiedId(ticket)));
  }

  @Test
  void aPutReparentsUnderAParentNamedByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Put Parent");
    String from = EntityFixtures.epic(project.id());
    String to = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(from);

    send(
            "PUT",
            WORK + EntityFixtures.qualifiedId(feature),
            map(
                "archetype",
                "FEATURE",
                "title",
                "The part",
                "status",
                "REPORTED",
                "membership",
                map("parent", EntityFixtures.qualifiedId(to))))
        .statusCode(200)
        .body("parent", equalTo(to));
  }

  // --- POST /work/transition -------------------------------------------------------------------

  @Test
  void aTransitionTakesQualifiedIdsAndAnswersKeyedAsSent() {
    EntityFixtures.Project project = EntityFixtures.project("Work Transition");
    String from = EntityFixtures.epic(project.id());
    String to = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(from);
    String featureQualified = EntityFixtures.qualifiedId(feature);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        featureQualified,
        map(
            "archetype",
            "FEATURE",
            "title",
            "The part",
            "status",
            "REPORTED",
            "membership",
            map("parent", EntityFixtures.qualifiedId(to))));

    send("POST", WORK + "transition", body)
        .statusCode(200)
        .body("'" + featureQualified + "'.id", equalTo(feature))
        .body("'" + featureQualified + "'.parent", equalTo(to))
        .body("'" + featureQualified + "'.qualifiedId", equalTo(featureQualified));
  }

  @Test
  void aTransitionTakesItsSuccessorByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Supersede");
    String dropped = EntityFixtures.epic(project.id());
    String successor = EntityFixtures.epic(project.id());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        EntityFixtures.qualifiedId(dropped),
        map(
            "archetype",
            "EPIC",
            "title",
            "The plan",
            "status",
            "DROPPED",
            "supersededBy",
            EntityFixtures.qualifiedId(successor),
            "acceptanceCriteria",
            TestCriteria.CRITERIA));

    send("POST", WORK + "transition", body)
        .statusCode(200)
        .body("'" + EntityFixtures.qualifiedId(dropped) + "'.supersededBy", equalTo(successor));
  }

  @Test
  void aTransitionNamingOneEntityTwiceOrNothingIsOne400() {
    EntityFixtures.Project project = EntityFixtures.project("Work Transition Refusals");
    String epic = EntityFixtures.epic(project.id());
    Map<String, Object> state = map("archetype", "EPIC", "title", "The plan", "status", "REPORTED");

    Map<String, Object> twice = new LinkedHashMap<>();
    twice.put(epic, state);
    twice.put(EntityFixtures.qualifiedId(epic), state);
    send("POST", WORK + "transition", twice)
        .statusCode(400)
        .body("message", containsString("name the same entity"));

    Map<String, Object> ghost = new LinkedHashMap<>();
    ghost.put(project.slug() + "-99999", state);
    send("POST", WORK + "transition", ghost)
        .statusCode(400)
        .body("message", containsString("there is no " + project.slug() + "-99999"));
  }

  // --- POST /work/{qualifiedId}/status and /blocked ---------------------------------------------

  @Test
  void aStatusMoveAddressesByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Status");
    String ticket = EntityFixtures.ticket(project.id());

    send("POST", WORK + EntityFixtures.qualifiedId(ticket) + "/status", map("target", "REFINED"))
        .statusCode(200)
        .body("id", equalTo(ticket))
        .body("status", equalTo("REFINED"))
        .body("statusBefore", equalTo("REPORTED"));
  }

  @Test
  void aBlockAddressesByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Block");
    String ticket = EntityFixtures.ticket(project.id());

    send(
            "POST",
            WORK + EntityFixtures.qualifiedId(ticket) + "/blocked",
            map("blocked", true, "reason", "waiting on finance"))
        .statusCode(200)
        .body("block.entityId", equalTo(ticket))
        .body("block.blocked", equalTo(true));
    given().when().get(WORK + ticket).then().body("blocked", equalTo(true));
  }

  // --- the thread ------------------------------------------------------------------------------

  @Test
  void theThreadIsReadWrittenEditedAndDeletedUnderTheQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Thread");
    String ticket = EntityFixtures.ticket(project.id());
    String other = EntityFixtures.ticket(project.id());
    String thread = WORK + EntityFixtures.qualifiedId(ticket) + "/comments";

    String commentId =
        send("POST", thread, map("body", "First remark."))
            .statusCode(200)
            .body("comment.entityId", equalTo(ticket))
            .body("comment.body", equalTo("First remark."))
            .extract()
            .path("comment.id");
    send("POST", thread, map("body", " ")).statusCode(400);

    given()
        .when()
        .get(thread)
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries[0].comment.id", equalTo(commentId));

    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("body", "Corrected remark."))
        .when()
        .patch(thread + "/" + commentId)
        .then()
        .statusCode(200)
        .body("comment.body", equalTo("Corrected remark."));

    // The path names the pair: the comment is not on the other ticket's thread.
    String elsewhere = WORK + EntityFixtures.qualifiedId(other) + "/comments/" + commentId;
    send("PATCH", elsewhere, map("body", "x")).statusCode(404);
    send("DELETE", elsewhere, null).statusCode(404);

    send("DELETE", thread + "/" + commentId, null).statusCode(200).body("success", equalTo(true));
    given().when().get(thread).then().statusCode(200).body("entries", hasSize(0));
  }

  // --- the registry ----------------------------------------------------------------------------

  @Test
  void theRegistryAndTheSchemasAreServedUnderWork() {
    given()
        .when()
        .get(WORK + "archetypes")
        .then()
        .statusCode(200)
        .body("archetypes.size()", equalTo(5));
    given()
        .when()
        .get(WORK + "archetypes/ticket/schemas/create")
        .then()
        .statusCode(200)
        .body("type", equalTo("object"));
    given().when().get(WORK + "archetypes/nothing/schemas/create").then().statusCode(404);
    given().when().get(WORK + "archetypes/ticket/schemas/nothing").then().statusCode(404);
  }

  // --- GET /projects/{project}/work ------------------------------------------------------------

  @Test
  void aProjectsWorkIsListedBySlugWithTheArchetypeAsAFilter() {
    EntityFixtures.Project project = EntityFixtures.project("Work List");
    String epic = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(epic);
    String ticket = EntityFixtures.ticket(project.id());
    String listing = "/projects/api/projects/" + project.slug() + "/work";

    given().when().get(listing).then().statusCode(200).body("entities", hasSize(3));
    given()
        .queryParam("archetype", "ticket")
        .when()
        .get(listing)
        .then()
        .statusCode(200)
        .body("entities", hasSize(1))
        .body("entities[0].id", equalTo(ticket))
        .body("entities[0].qualifiedId", equalTo(EntityFixtures.qualifiedId(ticket)));
    given()
        .queryParam("parent", EntityFixtures.qualifiedId(epic))
        .when()
        .get("/projects/api/projects/" + project.id() + "/work")
        .then()
        .statusCode(200)
        .body("entities", hasSize(1))
        .body("entities[0].id", equalTo(feature));
    given().queryParam("status", "NOPE").when().get(listing).then().statusCode(400);
    given().when().get("/projects/api/projects/no-such-project/work").then().statusCode(404);
  }
}
