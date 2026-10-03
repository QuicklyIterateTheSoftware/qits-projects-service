package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The merged reads of qits-548: {@code GET /projects/api/entities/{id}} for a row of any archetype,
 * named by UUID or by qualified id, and {@code GET /projects/api/projects/{projectId}/entities} with
 * its three filters.
 */
@QuarkusTest
class EntityReadApiTest {

  private EntityFixtures.Project project;
  private String epic;
  private String feature;
  private String task;
  private String ticket;
  private String campaign;

  @BeforeEach
  void seed() {
    project = EntityFixtures.project("Entity Reads");
    String repository = EntityFixtures.repository(project.id());
    epic = EntityFixtures.epic(project.id());
    feature = EntityFixtures.feature(epic);
    task = EntityFixtures.task(feature, repository);
    ticket = EntityFixtures.ticket(project.id());
    campaign = EntityFixtures.campaign(project.id());
  }

  @Test
  void everyArchetypeReadsByUuidAndByQualifiedId() {
    Map<String, String> archetypes =
        Map.of(
            epic, "EPIC", feature, "FEATURE", task, "TASK", ticket, "TICKET", campaign, "CAMPAIGN");
    archetypes.forEach(
        (id, archetype) -> {
          String qualified =
              given()
                  .get("/projects/api/entities/" + id)
                  .then()
                  .statusCode(200)
                  .body("id", equalTo(id))
                  .body("archetype", equalTo(archetype))
                  .body("projectId", equalTo(project.id()))
                  .body("qualifiedId", containsString(project.slug() + "-"))
                  .extract()
                  .path("qualifiedId");
          given()
              .get("/projects/api/entities/" + qualified)
              .then()
              .statusCode(200)
              .body("id", equalTo(id))
              .body("archetype", equalTo(archetype));
        });
  }

  /**
   * The edge is on the row, and every kind with a lifecycle carries {@code blocked} — a ticket, and
   * since qits-592 an epic — while a feature or a task, with no phase of its own, does not.
   */
  @Test
  void aNodeCarriesItsEdgeAndALifecycleKindItsBlock() {
    given()
        .get("/projects/api/entities/" + task)
        .then()
        .body("parent", equalTo(feature))
        .body("position", equalTo(0))
        .body("$", not(org.hamcrest.Matchers.hasKey("blocked")));
    given()
        .get("/projects/api/entities/" + epic)
        .then()
        .body("parent", nullValue())
        .body("blocked", equalTo(false));
    given()
        .get("/projects/api/entities/" + feature)
        .then()
        .body("$", not(org.hamcrest.Matchers.hasKey("blocked")));

    given().get("/projects/api/entities/" + ticket).then().body("blocked", equalTo(false));
    given()
        .contentType(ContentType.JSON)
        .body(map("blocked", true, "reason", "waiting on the idp"))
        .post("/projects/api/tickets/" + ticket + "/blocked")
        .then()
        .statusCode(200);
    given().get("/projects/api/entities/" + ticket).then().body("blocked", equalTo(true));
  }

  @Test
  void anUnknownIdIsA404() {
    given().get("/projects/api/entities/no-such-entity").then().statusCode(404);
    given().get("/projects/api/entities/" + project.slug() + "-9999").then().statusCode(404);
  }

  @Test
  void theListIsTheWholeTreeInTreeOrder() {
    String path = "/projects/api/projects/" + project.slug() + "/entities";
    given()
        .get(path)
        .then()
        .statusCode(200)
        .body("entities.id", contains(epic, feature, task, ticket, campaign))
        .body("entities.qualifiedId", not(org.hamcrest.Matchers.hasItem(nullValue())));
    // The project by its id answers the same list.
    given()
        .get("/projects/api/projects/" + project.id() + "/entities")
        .then()
        .body("entities.id", contains(epic, feature, task, ticket, campaign));
  }

  @Test
  void theFiltersNarrowIt() {
    String path = "/projects/api/projects/" + project.slug() + "/entities";
    given().get(path + "?archetype=epic").then().body("entities.id", contains(epic));
    given()
        .get(path + "?parent=" + EntityFixtures.qualifiedId(epic))
        .then()
        .body("entities.id", contains(feature));
    given().get(path + "?parent=" + feature).then().body("entities.id", contains(task));

    given()
        .contentType(ContentType.JSON)
        .body(map("target", "REFINED"))
        .post("/projects/api/tickets/" + ticket + "/transition")
        .then()
        .statusCode(200);
    given().get(path + "?status=REFINED").then().body("entities.id", contains(ticket));
    given()
        .get(path + "?archetype=TICKET&status=REPORTED")
        .then()
        .body("entities", hasSize(0));
    // Every archetype is matched by its own status — a feature's and a task's too since qits-763,
    // still REPORTED here under their draft epic — in the tree's order.
    given()
        .get(path + "?status=REPORTED")
        .then()
        .body("entities.id", equalTo(List.of(epic, feature, task, campaign)));
    given()
        .get(path + "?archetype=TASK&status=REPORTED")
        .then()
        .body("entities.id", contains(task));
  }

  @Test
  void aFilterNamingNothingIsRefused() {
    String path = "/projects/api/projects/" + project.slug() + "/entities";
    given().get(path + "?archetype=STORY").then().statusCode(400);
    given().get(path + "?status=OPEN").then().statusCode(400);
    given().get(path + "?parent=no-such-entity").then().statusCode(404);
    given().get("/projects/api/projects/no-such-project/entities").then().statusCode(404);
  }
}
