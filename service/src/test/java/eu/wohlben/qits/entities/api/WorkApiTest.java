package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The work family's routes ({@code /projects/api/work}, qits-969) over HTTP: every entity id a body
 * carries accepted as a qualified id — the create's {@code parent} and {@code dependsOn}, the
 * patch's {@code dependsOn}, and on the PUT and the bulk transition the keys, {@code
 * membership.parent} and {@code supersededBy}. The rules behind each write are {@link
 * WorkEntityDoors}' and asserted in their own suites, which address by qualified id as well: the
 * read and the listing in {@code WorkReadApiTest}, the status move in {@code WorkStatusApiTest}, the
 * patch's refusals in {@code WorkPatchApiTest}, the block in {@code EntityBlockApiTest}, the thread
 * in {@code WorkCommentApiTest}, the registry and the schemas in {@code EntityArchetypesApiTest} and
 * {@code EntitySchemaApiTest}. This class is about the ids a body carries.
 *
 * <p>Fixtures are written through {@link EntityFixtures}.
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
}
