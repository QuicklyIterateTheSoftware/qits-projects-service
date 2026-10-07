package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The REST round-trip for {@code POST /projects/api/work} (qits-548, qits-976) — one create for every
 * archetype. What is asserted here is the door's own reading of the body and its own two rules (a
 * parent of the right kind, a repository in the project); every rule behind the write is {@code
 * WorkEntityService.create}'s and asserted where that service is. That the door accepts exactly what
 * its schema requires is {@code EntitySchemaApiTest}'s drift pin.
 */
@QuarkusTest
class WorkCreateApiTest {

  private static ValidatableResponse create(Map<String, Object> body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/projects/api/work")
        .then();
  }

  @Test
  void aTicketWithAnImpetusIsFiledWithAQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Create Ticket");

    String id =
        create(
                map(
                    "archetype", "ticket",
                    "project", project.slug(),
                    "title", "Login button does nothing",
                    "ticketType", "BUG",
                    "impetus", "clicking the login button does nothing",
                    "assignee", "alice"))
            .statusCode(201)
            .body("archetype", equalTo("TICKET"))
            .body("projectId", equalTo(project.id()))
            .body("qualifiedId", startsWith(project.slug() + "-"))
            .body("status", equalTo("REPORTED"))
            .body("ticketType", equalTo("BUG"))
            .body("impetus", equalTo("clicking the login button does nothing"))
            .body("blocked", equalTo(false))
            .body("parent", nullValue())
            .extract()
            .path("id");

    // The same row, read back.
    given()
        .get("/projects/api/work/" + id)
        .then()
        .statusCode(200)
        .body("title", equalTo("Login button does nothing"))
        .body("ticketType", equalTo("BUG"))
        .body("assignee", equalTo("alice"));
  }

  @Test
  void aTicketWithNoImpetusIsA400NamingIt() {
    EntityFixtures.Project project = EntityFixtures.project("Create No Impetus");

    create(map("archetype", "TICKET", "project", project.id(), "title", "t", "ticketType", "BUG"))
        .statusCode(400)
        .body("message", containsString("impetus is required"));
    given()
        .queryParam("archetype", "ticket")
        .get("/projects/api/projects/" + project.id() + "/work")
        .then()
        .statusCode(200)
        .body("entities", hasSize(0));
  }

  /** Every complaint in one 400, joined with "; " — the other entity doors' habit. */
  @Test
  void everyComplaintComesBackInOneMessage() {
    EntityFixtures.Project project = EntityFixtures.project("Create Complaints");

    create(
            map(
                "archetype", "EPIC",
                "project", project.slug(),
                "title", " ",
                "impetus", "epics have none",
                "status", "DONE",
                "nonsense", "x"))
        .statusCode(400)
        .body(
            "message",
            allOf(
                containsString("impetus"),
                containsString("status is not written at create"),
                containsString("unknown property: nonsense"),
                containsString("title must not be blank"),
                containsString("; ")));
  }

  @Test
  void anUnknownOrMissingArchetypeIsA400() {
    create(map("title", "t")).statusCode(400).body("message", containsString("archetype"));
    create(map("archetype", "STORY", "title", "t"))
        .statusCode(400)
        .body("message", containsString("Unknown archetype: STORY"));
  }

  @Test
  void aFeatureUnderATaskIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Create Feature Under Task");
    String repository = EntityFixtures.repository(project.id());
    String epic = EntityFixtures.epic(project.id());
    String task = EntityFixtures.task(EntityFixtures.feature(epic), repository);

    create(map("archetype", "FEATURE", "parent", task, "title", "misplaced"))
        .statusCode(400)
        .body("message", containsString("a FEATURE's parent must be an EPIC"));
    given()
        .get("/projects/api/work/" + epic + "/children")
        .then()
        .statusCode(200)
        .body("children", hasSize(1));
  }

  @Test
  void aTaskWhoseRepositoryIsInAnotherProjectIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Create Task Here");
    EntityFixtures.Project elsewhere = EntityFixtures.project("Create Task Elsewhere");
    String foreignRepository = EntityFixtures.repository(elsewhere.id());
    String feature = EntityFixtures.feature(EntityFixtures.epic(project.id()));

    create(
            map(
                "archetype", "TASK",
                "parent", feature,
                "title", "wrong repository",
                "repositoryId", foreignRepository))
        .statusCode(400)
        .body("message", containsString("is not in this entity's project"));
    given()
        .get("/projects/api/work/" + feature + "/children")
        .then()
        .statusCode(200)
        .body("children", hasSize(0));
  }

  /** A parent named by its qualified id resolves, and the node lands under it. */
  @Test
  void aQualifiedIdParentResolves() {
    EntityFixtures.Project project = EntityFixtures.project("Create Qualified Parent");
    String repository = EntityFixtures.repository(project.id());
    String epic = EntityFixtures.epic(project.id());
    String epicQualified = EntityFixtures.qualifiedId(epic);

    String feature =
        create(map("archetype", "FEATURE", "parent", epicQualified, "title", "Part A"))
            .statusCode(201)
            .body("parent", equalTo(epic))
            .body("position", equalTo(0))
            .body("blocked", nullValue())
            .extract()
            .path("id");
    String featureQualified = EntityFixtures.qualifiedId(feature);

    create(
            map(
                "archetype", "TASK",
                "parent", featureQualified,
                "title", "Step 1",
                "repositoryId", repository))
        .statusCode(201)
        .body("parent", equalTo(feature))
        .body("repositoryId", equalTo(repository))
        .body("qualifiedId", notNullValue());
  }

  @Test
  void aCampaignIsARootInItsProject() {
    EntityFixtures.Project project = EntityFixtures.project("Create Campaign");

    create(map("archetype", "CAMPAIGN", "project", project.slug(), "title", "The order"))
        .statusCode(201)
        .body("archetype", equalTo("CAMPAIGN"))
        .body("status", equalTo("REPORTED"));
    create(map("archetype", "CAMPAIGN", "parent", "anything", "title", "The order"))
        .statusCode(400)
        .body("message", containsString("parent is not taken"));
  }

  @Test
  void aPlacementNamingNothingIsA404() {
    create(map("archetype", "EPIC", "project", "no-such-project", "title", "t")).statusCode(404);
    create(map("archetype", "FEATURE", "parent", "no-such-epic", "title", "t")).statusCode(404);
  }
}
