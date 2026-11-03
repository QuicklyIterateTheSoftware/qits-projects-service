package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.IdeaService;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The REST round trip for {@code /projects/api/ideas} (qits-1387), and {@code inspiredBy} on {@code
 * /work}. The rules behind each write are {@code IdeaService}'s and asserted in its own test; this
 * class pins what the doors add: the body reading, the project by id or slug, the default listing
 * hiding the archived, the detail's {@code inspired}/{@code enables}, the roles (the desk writes in
 * its own project only, an admin anywhere; a plain agent and a platform service only read —
 * qits-1790) and the absence of a delete.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
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

  /** Every archetype carries an idea (qits-1792): a feature at its create, a task by a patch. */
  @Test
  void aFeatureAndATaskNameAnIdeaToo() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Feature");
    String idea = idea(project.id(), "For features too");
    String epic = EntityFixtures.epic(project.id());

    String feature =
        given()
            .contentType(ContentType.JSON)
            .body(map("archetype", "FEATURE", "parent", epic, "title", "A part", "inspiredBy", idea))
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .body("inspiredBy", equalTo(idea))
            .extract()
            .path("id");
    given().get("/projects/api/work/" + feature).then().statusCode(200).body("inspiredBy", equalTo(idea));

    String task = EntityFixtures.task(feature, EntityFixtures.repository(project.id()));
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("inspiredBy", idea))
        .patch("/projects/api/work/" + task)
        .then()
        .statusCode(200)
        .body("inspiredBy", equalTo(idea));

    given()
        .get("/projects/api/ideas/" + idea)
        .then()
        .statusCode(200)
        .body("status", equalTo("QUEUED"))
        .body("inspired", hasSize(2))
        .body("inspired.id", hasItem(feature))
        .body("inspired.id", hasItem(task))
        .body("inspired.archetype", hasItem("FEATURE"))
        .body("inspired.archetype", hasItem("TASK"));

    // A merge patch's null clears the task's idea; the feature still holds it.
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("inspiredBy", null))
        .patch("/projects/api/work/" + task)
        .then()
        .statusCode(200)
        .body("inspiredBy", nullValue());
    given()
        .get("/projects/api/ideas/" + idea)
        .then()
        .statusCode(200)
        .body("status", equalTo("QUEUED"))
        .body("inspired", hasSize(1))
        .body("inspired[0].id", equalTo(feature));
  }

  /**
   * A reshape keeps the idea (qits-1792): a ticket naming one, made a feature through the
   * whole-state door, still names it — carried like the slug, never stated by the transition.
   */
  @Test
  void aReshapeIntoAFeatureKeepsTheIdea() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Reshape");
    String idea = idea(project.id(), "Survives a reshape");
    String epic = EntityFixtures.epic(project.id());
    String ticket =
        given()
            .contentType(ContentType.JSON)
            .body(
                map(
                    "archetype", "TICKET",
                    "project", project.id(),
                    "title", "Turns out to be scope",
                    "ticketType", "BUG",
                    "impetus", "it occurs",
                    "inspiredBy", idea))
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .contentType(ContentType.JSON)
        .body(
            map(
                ticket,
                map(
                    "archetype", "FEATURE",
                    "title", "Turns out to be scope",
                    "status", "REPORTED",
                    "membership", map("parent", epic, "position", null))))
        .post("/projects/api/work/transition")
        .then()
        .statusCode(200)
        .body("'" + ticket + "'.archetype", equalTo("FEATURE"));

    given()
        .get("/projects/api/work/" + ticket)
        .then()
        .statusCode(200)
        .body("archetype", equalTo("FEATURE"))
        .body("inspiredBy", equalTo(idea));
    given()
        .get("/projects/api/ideas/" + idea)
        .then()
        .statusCode(200)
        .body("status", equalTo("QUEUED"))
        .body("inspired", hasSize(1))
        .body("inspired[0].archetype", equalTo("FEATURE"));
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

  /**
   * qits-1790: only the delivery desk and admins write ideas. A plain agent ({@code qits:agent}
   * without the desk roles) is refused every write door by the class roles, and still reads.
   */
  @Test
  void aPlainAgentIsRefusedEveryIdeaWriteAndStillReads() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Plain Agent");
    String id = idea(project.id(), "Not the agent's");
    String other = idea(project.id(), "Another");

    asForwarded("qits:agent")
        .body(map("project", project.id(), "title", "An agent's idea"))
        .post("/projects/api/ideas")
        .then()
        .statusCode(403);
    asForwarded("qits:agent")
        .body(map("title", "Renamed by the agent"))
        .patch("/projects/api/ideas/" + id)
        .then()
        .statusCode(403);
    asForwarded("qits:agent")
        .body(map("above", other))
        .put("/projects/api/ideas/" + id + "/priority")
        .then()
        .statusCode(403);
    asForwarded("qits:agent")
        .body(map("reason", "not mine"))
        .post("/projects/api/ideas/" + id + "/archive")
        .then()
        .statusCode(403);
    asForwarded("qits:agent")
        .post("/projects/api/ideas/" + id + "/unarchive")
        .then()
        .statusCode(403);

    asForwarded("qits:agent")
        .queryParam("project", project.id())
        .get("/projects/api/ideas")
        .then()
        .statusCode(200)
        .body("ideas", hasSize(2));
    asForwarded("qits:agent")
        .get("/projects/api/ideas/" + id)
        .then()
        .statusCode(200)
        .body("title", equalTo("Not the agent's"))
        .body("archived", equalTo(false));
  }

  /** qits-1790: the class roles are the desk's and the admins', the reads still admit the agent. */
  @Test
  void theWritesAdmitTheDeskAndTheAdminsAndTheReadsTheAgent() throws NoSuchMethodException {
    RolesAllowed writes = IdeaController.class.getAnnotation(RolesAllowed.class);
    assertEquals(
        Set.of("qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"),
        Set.of(writes.value()));
    assertEquals(4, writes.value().length);
    for (Method read :
        List.of(
            IdeaController.class.getMethod("get", String.class),
            listMethod())) {
      Set<String> roles = Set.of(read.getAnnotation(RolesAllowed.class).value());
      assertTrue(roles.contains("qits:agent"), read.getName() + " still admits qits:agent");
      assertTrue(roles.contains("qits:system"), read.getName() + " still admits qits:system");
    }
  }

  private static Method listMethod() {
    return java.util.Arrays.stream(IdeaController.class.getMethods())
        .filter(m -> m.isAnnotationPresent(jakarta.ws.rs.GET.class))
        .filter(m -> !m.isAnnotationPresent(jakarta.ws.rs.Path.class))
        .findFirst()
        .orElseThrow();
  }

  /** qits-1790: the delivery desk writes the ideas of its own project, and only those. */
  @Test
  void theDeskWritesTheIdeasOfItsOwnProjectOnly() {
    EntityFixtures.Project own = EntityFixtures.project("Ideas Desk Own");
    EntityFixtures.Project foreign = EntityFixtures.project("Ideas Desk Foreign");
    SecurityIdentity desk =
        AgentTokens.token(Map.of("project", own.id()), "qits:agent", "qits:desk", "qits:desk-admin");

    String id =
        ((IdeaController.IdeaDto)
                door(desk)
                    .create(new IdeaController.IdeaCreate(own.id(), null, "The desk's idea", null, null))
                    .getEntity())
            .id();
    assertEquals(
        "Renamed by the desk",
        door(desk)
            .patch(
                id,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode()
                    .put("title", "Renamed by the desk"))
            .title());
    assertEquals(true, door(desk).archive(id, new IdeaController.IdeaArchive("done with it")).archived());
    assertEquals(false, door(desk).unarchive(id).archived());

    String theirs = idea(foreign.id(), "Theirs");
    assertThrows(
        ForbiddenException.class,
        () -> door(desk).create(new IdeaController.IdeaCreate(foreign.id(), null, "Not mine", null, null)));
    assertThrows(
        ForbiddenException.class,
        () ->
            door(desk)
                .patch(
                    theirs,
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode()
                        .put("title", "Not mine")));
    assertThrows(
        ForbiddenException.class,
        () -> door(desk).archive(theirs, new IdeaController.IdeaArchive("not mine")));
    assertThrows(ForbiddenException.class, () -> door(desk).unarchive(theirs));
    assertEquals("Theirs", door(desk).get(theirs).title(), "nothing is written");
  }

  /** qits-1790: an admin writes ideas over the wire. */
  @Test
  void anAdminWritesIdeas() {
    EntityFixtures.Project project = EntityFixtures.project("Ideas Admin");
    String id =
        asForwarded("qits:admin")
            .body(map("project", project.id(), "title", "An admin's idea"))
            .post("/projects/api/ideas")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    asForwarded("qits:admin")
        .body(map("title", "Renamed by an admin"))
        .patch("/projects/api/ideas/" + id)
        .then()
        .statusCode(200)
        .body("title", equalTo("Renamed by an admin"));
    asForwarded("qits:admin")
        .body(map("reason", "done"))
        .post("/projects/api/ideas/" + id + "/archive")
        .then()
        .statusCode(200)
        .body("archived", equalTo(true));
    asForwarded("qits:admin")
        .post("/projects/api/ideas/" + id + "/unarchive")
        .then()
        .statusCode(200)
        .body("archived", equalTo(false));
  }

  private static RequestSpecification asForwarded(String role) {
    return given()
        .header("Authorization", ContractBearers.caller("somebody", role))
        .contentType(ContentType.JSON);
  }
}
