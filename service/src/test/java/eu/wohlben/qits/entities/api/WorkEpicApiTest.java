package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.WorkRequests.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An epic, its features and their tasks over the {@code /work} family (qits-976 ported this from
 * the per-archetype {@code EpicApiTest}): the create of each, the read, the whole-row edit, the
 * history, and the delete that cascades down the tree while the history outlives it.
 *
 * <p>The round trip names its caller with {@code @TestSecurity}, as the suite it was ported from
 * did: it is about the epic/feature/task tree, and naming the caller directly keeps it independent
 * of how the identity arrives. {@code WorkAuditIdentityTest} is what exercises the real header path.
 * The annotation names the role as well as the user, because it replaces the mechanism's identity
 * wholesale: a caller named with no role would be refused by the routes' {@code @RolesAllowed}.
 */
@QuarkusTest
class WorkEpicApiTest {

  private static final String WORK = "/projects/api/work/";

  private static ValidatableResponse create(Map<String, Object> body) {
    return given().contentType(ContentType.JSON).body(body).when().post("/projects/api/work").then();
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void anEpicItsFeatureAndItsTaskLiveAndDieTogether() {
    EntityFixtures.Project project = EntityFixtures.project("Epics Project");
    String repository = EntityFixtures.repository(project.id());

    String epic =
        create(
                map(
                    "archetype", "EPIC",
                    "project", project.id(),
                    "title", "Planning domain",
                    "description", "The spine"))
            .statusCode(201)
            .body("id", notNullValue())
            .body("projectId", equalTo(project.id()))
            .body("title", equalTo("Planning domain"))
            .body("slug", equalTo("planning-domain"))
            // A new epic is a draft, with no successor — the lifecycle's starting point.
            .body("status", equalTo("REPORTED"))
            .body("supersededBy", nullValue())
            .extract()
            .path("id");

    given()
        .when()
        .get(WORK + epic)
        .then()
        .statusCode(200)
        .body("id", equalTo(epic))
        .body("slug", equalTo("planning-domain"));
    given()
        .queryParam("archetype", "EPIC")
        .when()
        .get("/projects/api/projects/" + project.id() + "/work")
        .then()
        .statusCode(200)
        .body("entities.id", hasItem(epic));

    restate(epic, "Planning domain v2", "Longer").statusCode(200);
    given()
        .when()
        .get(WORK + epic)
        .then()
        .statusCode(200)
        .body("title", equalTo("Planning domain v2"))
        .body("description", equalTo("Longer"))
        // The slug names a branch, so a rename never touches it.
        .body("slug", equalTo("planning-domain"));

    String feature =
        create(map("archetype", "FEATURE", "parent", epic, "title", "Feature A"))
            .statusCode(201)
            .body("parent", equalTo(epic))
            .body("slug", equalTo("feature-a"))
            .extract()
            .path("id");
    String task =
        create(
                map(
                    "archetype", "TASK",
                    "parent", feature,
                    "repositoryId", repository,
                    "title", "Task 1"))
            .statusCode(201)
            .body("repositoryId", equalTo(repository))
            .body("parent", equalTo(feature))
            .body("slug", equalTo("task-1"))
            .extract()
            .path("id");

    given().when().get(WORK + feature).then().statusCode(200).body("slug", equalTo("feature-a"));
    given().when().get(WORK + task).then().statusCode(200).body("slug", equalTo("task-1"));

    // The epic's history is its subtree's, every write stamped with the caller.
    given()
        .when()
        .get(WORK + epic + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("CREATE"))
        .body("entries.operation", hasItem("UPDATE"))
        .body("entries.changedBy", hasItem("dev"))
        .body("entries.entityId", hasItem(task));

    // The delete cascades to the features and their tasks.
    given().when().delete(WORK + epic).then().statusCode(200).body("success", equalTo(true));
    given().when().get(WORK + epic).then().statusCode(404);
    given().when().get(WORK + feature).then().statusCode(404);
    given().when().get(WORK + task).then().statusCode(404);

    // The history survives the delete (read by the epic's UUID) and carries the DELETE rows.
    given()
        .when()
        .get(WORK + epic + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("DELETE"))
        .body("entries.entityId", hasItem(task));
  }

  @Test
  void aTaskCannotBindARepositoryOfAnotherProject() {
    EntityFixtures.Project projectA = EntityFixtures.project("Epics Project");
    EntityFixtures.Project projectB = EntityFixtures.project("Epics Project");
    String repositoryInB = EntityFixtures.repository(projectB.id());
    String feature = WorkRequests.feature(WorkRequests.epic(projectA.id(), "E"), "F");

    // The repository exists, but in projectB, not the epic's projectA.
    create(
            map(
                "archetype", "TASK",
                "parent", feature,
                "repositoryId", repositoryInB,
                "title", "T"))
        .statusCode(400);
  }

  @Test
  void anEpicUnderAnUnknownProjectIsA404() {
    create(map("archetype", "EPIC", "project", "ghost", "title", "X")).statusCode(404);
  }

  @Test
  void aBlankEpicTitleIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Epics Project");
    create(map("archetype", "EPIC", "project", project.id(), "title", "  ")).statusCode(400);
  }

  @Test
  void aTaskNamingAnUnknownRepositoryIsA404() {
    EntityFixtures.Project project = EntityFixtures.project("Epics Project");
    String feature = WorkRequests.feature(WorkRequests.epic(project.id(), "E"), "F");

    create(
            map(
                "archetype", "TASK",
                "parent", feature,
                "repositoryId", "no-such-repo",
                "title", "T"))
        .statusCode(404);
  }

  @Test
  void aFeatureDependingOnNothingIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Epics Project");
    String epic = WorkRequests.epic(project.id(), "E");

    create(map("archetype", "FEATURE", "parent", epic, "title", "F", "dependsOn", "ghost-feature"))
        .statusCode(400);
  }

  @Test
  void anEpicsHistoryIsNewestFirst() {
    EntityFixtures.Project project = EntityFixtures.project("Epics Project");
    String epic = WorkRequests.epic(project.id(), "E");
    restate(epic, "E2", null).statusCode(200);

    given()
        .when()
        .get(WORK + epic + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", contains("UPDATE", "CREATE"));
  }

  /** An epic's words rewritten through {@code PUT /work/{id}}, the whole-row edit. */
  private static ValidatableResponse restate(String epic, String title, String description) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            map(
                "archetype", "EPIC",
                "title", title,
                "description", description,
                "status", "REPORTED",
                "membership", Collections.singletonMap("parent", null)))
        .when()
        .put(WORK + epic)
        .then();
  }
}
