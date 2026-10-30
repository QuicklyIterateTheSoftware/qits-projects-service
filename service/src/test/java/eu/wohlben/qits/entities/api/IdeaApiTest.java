package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.entities.control.IdeaService;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The REST round trip for {@code /projects/api/ideas} (qits-1387), and {@code inspiredBy} on {@code
 * /work}. The rules behind each write are {@code IdeaService}'s and asserted in its own test; this
 * class pins what the doors add: the body reading, the project by id or slug, the default listing
 * hiding the archived, the detail's {@code inspired}/{@code enables}, the roles (an agent writes in
 * its own project only, a platform service only reads) and the absence of a delete.
 */
@QuarkusTest
class IdeaApiTest {

  @Inject IdeaService ideas;

  @Inject EntityIdResolver ids;

  private static ValidatableResponse create(Map<String, Object> body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/projects/api/ideas")
        .then();
  }

  private static String idea(String project, String title) {
    return create(map("project", project, "title", title)).statusCode(201).extract().path("id");
  }

  private IdeaController door(SecurityIdentity caller) {
    IdeaController door = new IdeaController();
    door.ideas = ideas;
    door.ids = ids;
    door.identity = caller;
    return door;
  }

  @Test
  void anIdeaIsCreatedReadEditedArchivedAndUnarchived() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Lifecycle");

    String id =
        create(
                map(
                    "project", project.slug(),
                    "title", "Faster builds",
                    "description", "Cache the dependencies.\n\nShare the cache."))
            .statusCode(201)
            .body("projectId", equalTo(project.id()))
            .body("status", equalTo("PLANNED"))
            .body("archived", equalTo(false))
            .body("enabledBy", nullValue())
            .extract()
            .path("id");

    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("title", "Much faster builds", "description", null))
        .patch("/projects/api/ideas/" + id)
        .then()
        .statusCode(200)
        .body("title", equalTo("Much faster builds"))
        .body("description", nullValue());

    given()
        .contentType(ContentType.JSON)
        .body(Map.of())
        .post("/projects/api/ideas/" + id + "/archive")
        .then()
        .statusCode(400)
        .body("message", containsString("reason is required"));
    given()
        .contentType(ContentType.JSON)
        .body(map("reason", "covered by the cache epic"))
        .post("/projects/api/ideas/" + id + "/archive")
        .then()
        .statusCode(200)
        .body("archived", equalTo(true))
        .body("archiveReason", equalTo("covered by the cache epic"))
        .body("archivedAt", notNullValue())
        .body("status", equalTo("PLANNED"));

    given()
        .post("/projects/api/ideas/" + id + "/unarchive")
        .then()
        .statusCode(200)
        .body("archived", equalTo(false))
        .body("archiveReason", nullValue())
        .body("archivedAt", nullValue());
  }

  @Test
  void theDefaultListingHidesTheArchivedAndArchivedTrueIncludesThem() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Listing");
    String open = idea(project.id(), "Open idea");
    String parked = idea(project.id(), "Parked idea");
    given()
        .contentType(ContentType.JSON)
        .body(map("reason", "not now"))
        .post("/projects/api/ideas/" + parked + "/archive")
        .then()
        .statusCode(200);

    given()
        .queryParam("project", project.slug())
        .get("/projects/api/ideas")
        .then()
        .statusCode(200)
        .body("ideas", hasSize(1))
        .body("ideas[0].id", equalTo(open));
    given()
        .queryParam("project", project.id())
        .queryParam("archived", true)
        .get("/projects/api/ideas")
        .then()
        .statusCode(200)
        .body("ideas", hasSize(2))
        .body("ideas.id", hasItem(parked));
    given()
        .queryParam("project", project.id())
        .queryParam("status", "QUEUED")
        .get("/projects/api/ideas")
        .then()
        .statusCode(200)
        .body("ideas", hasSize(0));
    given()
        .queryParam("project", project.id())
        .queryParam("status", "SOMEDAY")
        .get("/projects/api/ideas")
        .then()
        .statusCode(400);
    given().get("/projects/api/ideas").then().statusCode(400);
  }

  @Test
  void theDetailNamesTheWorkItInspiredAndTheIdeasItEnables() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Detail");
    String enabler = idea(project.id(), "Enabler");
    String enabled =
        create(map("project", project.id(), "title", "Enabled", "enabledBy", enabler))
            .statusCode(201)
            .body("enabledBy", equalTo(enabler))
            .extract()
            .path("id");

    String epic =
        WorkRequests.create(
                map("archetype", "EPIC", "project", project.id(), "title", "Inspired", "inspiredBy", enabler))
            .then()
            .body("inspiredBy", equalTo(enabler))
            .extract()
            .path("id");
    given().get("/projects/api/work/" + epic).then().statusCode(200).body("inspiredBy", equalTo(enabler));

    given()
        .get("/projects/api/ideas/" + enabler)
        .then()
        .statusCode(200)
        .body("status", equalTo("QUEUED"))
        .body("inspired", hasSize(1))
        .body("inspired[0].id", equalTo(epic))
        .body("inspired[0].qualifiedId", containsString(project.slug() + "-"))
        .body("inspired[0].archetype", equalTo("EPIC"))
        .body("inspired[0].status", equalTo("REPORTED"))
        .body("enables", hasSize(1))
        .body("enables[0].id", equalTo(enabled))
        .body("enabledByIdea", nullValue());
    given()
        .get("/projects/api/ideas/" + enabled)
        .then()
        .statusCode(200)
        .body("status", equalTo("PLANNED"))
        .body("enabledByIdea.id", equalTo(enabler))
        .body("enabledByIdea.archived", equalTo(false));

    // A merge patch's null clears the work's idea, and the idea is PLANNED again.
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("inspiredBy", null))
        .patch("/projects/api/work/" + epic)
        .then()
        .statusCode(200)
        .body("inspiredBy", nullValue());
    given().get("/projects/api/ideas/" + enabler).then().body("status", equalTo("PLANNED"));
    given().get("/projects/api/ideas/missing").then().statusCode(404);
  }

  @Test
  void aFeatureOrATaskNamingAnIdeaIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Feature");
    String idea = idea(project.id(), "Not for features");
    String epic = EntityFixtures.epic(project.id());

    given()
        .contentType(ContentType.JSON)
        .body(map("archetype", "FEATURE", "parent", epic, "title", "A part", "inspiredBy", idea))
        .post("/projects/api/work")
        .then()
        .statusCode(400)
        .body("message", containsString("only an EPIC, a TICKET or a CAMPAIGN"));
    String feature = EntityFixtures.feature(epic);
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("inspiredBy", idea))
        .patch("/projects/api/work/" + feature)
        .then()
        .statusCode(400)
        .body("message", containsString("only an EPIC, a TICKET or a CAMPAIGN"));
  }

  @Test
  void anIdeaOfAnotherProjectIsA400OnTheWork() {
    EntityFixtures.Project here = EntityFixtures.project("Ideas Here");
    EntityFixtures.Project there = EntityFixtures.project("Ideas There");
    String foreign = idea(there.id(), "Theirs");

    given()
        .contentType(ContentType.JSON)
        .body(
            map(
                "archetype", "TICKET",
                "project", here.id(),
                "title", "Mine",
                "ticketType", "BUG",
                "impetus", "it occurs",
                "inspiredBy", foreign))
        .post("/projects/api/work")
        .then()
        .statusCode(400)
        .body("message", containsString("same project"));
  }

  @Test
  void thereIsNoDelete() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas No Delete");
    String id = idea(project.id(), "Kept");

    given().delete("/projects/api/ideas/" + id).then().statusCode(405);
    given().get("/projects/api/ideas/" + id).then().statusCode(200);
  }

  @Test
  void aPlatformServiceReadsAndDoesNotWrite() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Machine");
    String id = idea(project.id(), "Read me");

    asForwarded("qits:system")
        .queryParam("project", project.id())
        .get("/projects/api/ideas")
        .then()
        .statusCode(200)
        .body("ideas", hasSize(1));
    asForwarded("qits:system").get("/projects/api/ideas/" + id).then().statusCode(200);
    asForwarded("qits:system")
        .body(map("project", project.id(), "title", "Not mine"))
        .post("/projects/api/ideas")
        .then()
        .statusCode(403);
    asForwarded("qits:system")
        .body(map("reason", "no"))
        .post("/projects/api/ideas/" + id + "/archive")
        .then()
        .statusCode(403);
  }

  @Test
  void anAgentWritesTheIdeasOfItsOwnProjectOnly() {
    EntityFixtures.Project own = EntityFixtures.project("Ideas Agent Own");
    EntityFixtures.Project foreign = EntityFixtures.project("Ideas Agent Foreign");
    SecurityIdentity agent = AgentTokens.token(Map.of("project", own.id()), "qits:agent");

    String id =
        ((IdeaController.IdeaDto)
                door(agent)
                    .create(new IdeaController.IdeaCreate(own.id(), null, "An agent's idea", null, null))
                    .getEntity())
            .id();
    assertEquals(
        "Renamed by the agent",
        door(agent)
            .patch(
                id,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode()
                    .put("title", "Renamed by the agent"))
            .title());
    assertEquals(true, door(agent).archive(id, new IdeaController.IdeaArchive("done with it")).archived());
    assertEquals(false, door(agent).unarchive(id).archived());

    String theirs = idea(foreign.id(), "Theirs");
    assertThrows(
        ForbiddenException.class,
        () -> door(agent).create(new IdeaController.IdeaCreate(foreign.id(), null, "Not mine", null, null)));
    assertThrows(
        ForbiddenException.class,
        () -> door(agent).archive(theirs, new IdeaController.IdeaArchive("not mine")));
    assertEquals(theirs, door(agent).get(theirs).id(), "an agent reads every idea");

    // A forwarded agent carries no token to bind, so it is refused every write.
    asForwarded("qits:agent")
        .body(map("project", own.id(), "title", "No token"))
        .post("/projects/api/ideas")
        .then()
        .statusCode(not(equalTo(201)));
  }

  private static RequestSpecification asForwarded(String role) {
    return given()
        .header("X-Qits-User", "somebody")
        .header("X-Qits-Roles", role)
        .contentType(ContentType.JSON);
  }
}
