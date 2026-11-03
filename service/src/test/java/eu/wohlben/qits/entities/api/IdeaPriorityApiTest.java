package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.projects.bus.IdeaUpdated;
import eu.wohlben.qits.projects.bus.RecordingIdeaEventAnnouncer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ranking of ideas on the wire (qits-1791): {@code priority} on every answer, the patch that
 * sets and clears it, the listing in the order rule, and the drop door {@code PUT
 * /ideas/{id}/priority} with its refusals and its announcements. The arithmetic of the drop is
 * {@code SiblingPrioritiesTest}'s and the service's rules are {@code IdeaServiceTest}'s; what is
 * asserted here is the door.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class IdeaPriorityApiTest {

  @Inject WorkEntityService work;

  @Inject RecordingIdeaEventAnnouncer announced;

  private static String idea(EntityFixtures.Project project, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(map("project", project.id(), "title", title))
        .post("/projects/api/ideas")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private static ValidatableResponse patch(String id, Map<String, Object> body) {
    return given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch("/projects/api/ideas/" + id)
        .then();
  }

  private static ValidatableResponse drop(String id, Map<String, Object> body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .put("/projects/api/ideas/" + id + "/priority")
        .then();
  }

  private static Map<String, Object> priority(Integer value) {
    Map<String, Object> body = new HashMap<>();
    body.put("priority", value);
    return body;
  }

  private static void archive(String id) {
    given()
        .contentType(ContentType.JSON)
        .body(map("reason", "not now"))
        .post("/projects/api/ideas/" + id + "/archive")
        .then()
        .statusCode(200);
  }

  /** The listing's ids, in the order the service answers them. */
  private static List<String> listed(EntityFixtures.Project project) {
    return given()
        .queryParam("project", project.id())
        .get("/projects/api/ideas")
        .then()
        .statusCode(200)
        .extract()
        .path("ideas.id");
  }

  /** An idea made DONE the real way: a ticket naming it, walked to DONE. */
  private String doneIdea(EntityFixtures.Project project, String title) {
    String id = idea(project, title);
    String ticket = WorkRequests.ticket(project.id(), "Built " + title, "IMPROVEMENT", "it should");
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("inspiredBy", id))
        .patch("/projects/api/work/" + ticket)
        .then()
        .statusCode(200);
    TestCriteria.give(ticket);
    List<EntityStatus> walk = EntityStateMachine.walk();
    for (int step = 1; step <= walk.indexOf(EntityStatus.DONE); step++) {
      work.transition(
          Archetype.TICKET, ticket, walk.get(step).name(), Mover.person("idea-priority-test"));
    }
    given().get("/projects/api/ideas/" + id).then().statusCode(200).body("status", equalTo("DONE"));
    return id;
  }

  @Test
  void everyAnswerCarriesThePriorityNullUntilRanked() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Reads");
    String id = idea(project, "Unranked");

    given()
        .get("/projects/api/ideas/" + id)
        .then()
        .statusCode(200)
        .body("containsKey('priority')", equalTo(true))
        .body("priority", nullValue());
    given()
        .queryParam("project", project.id())
        .get("/projects/api/ideas")
        .then()
        .body("ideas[0].containsKey('priority')", equalTo(true))
        .body("ideas[0].priority", nullValue());
  }

  @Test
  void aPatchSetsAndClearsThePriority() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Patch");
    String id = idea(project, "Ranked by hand");
    announced.reset();

    patch(id, priority(5))
        .statusCode(200)
        .body("priority", equalTo(5))
        .body("title", equalTo("Ranked by hand"));
    given().get("/projects/api/ideas/" + id).then().body("priority", equalTo(5));
    patch(id, priority(null)).statusCode(200).body("priority", nullValue());
    given().get("/projects/api/ideas/" + id).then().body("priority", nullValue());
    assertEquals(
        2,
        announced.published().stream()
            .filter(e -> e instanceof IdeaUpdated u && u.ideaId().equals(id))
            .count(),
        "every edit announces IdeaUpdated");

    patch(id, Map.of("priority", "high"))
        .statusCode(400)
        .body("message", containsString("priority must be an integer"));
    patch(id, Map.of("priority", 1.5))
        .statusCode(400)
        .body("message", containsString("priority must be an integer"));
    patch(id, Map.of("priority", true))
        .statusCode(400)
        .body("message", containsString("priority must be an integer"));
  }

  @Test
  void theListingAnswersInTheOrderRule() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Order");
    String first = idea(project, "First");
    String second = idea(project, "Second");
    String third = idea(project, "Third");
    assertEquals(List.of(first, second, third), listed(project));

    patch(third, priority(1)).statusCode(200);
    patch(second, priority(4)).statusCode(200);
    assertEquals(List.of(second, third, first), listed(project));
  }

  @Test
  void archivingClearsThePriority() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Archive");
    String id = idea(project, "Soon parked");
    patch(id, priority(3)).statusCode(200);

    given()
        .contentType(ContentType.JSON)
        .body(map("reason", "not now"))
        .post("/projects/api/ideas/" + id + "/archive")
        .then()
        .statusCode(200)
        .body("priority", nullValue());
    given().post("/projects/api/ideas/" + id + "/unarchive").then().body("priority", nullValue());
  }

  @Test
  void aPriorityOnAnArchivedOrDoneIdeaIsA409ForThePatchAndTheDrop() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Closed");
    String open = idea(project, "Open");
    String parked = idea(project, "Parked");
    archive(parked);
    String done = doneIdea(project, "Finished");

    patch(parked, priority(1)).statusCode(409).body("message", containsString("archived"));
    patch(done, priority(1)).statusCode(409).body("message", containsString("DONE"));
    patch(done, priority(null)).statusCode(409);
    drop(parked, Map.of("above", open)).statusCode(409).body("message", containsString("archived"));
    drop(done, Map.of("above", open)).statusCode(409).body("message", containsString("DONE"));
    drop("nothing-999999", Map.of("above", open)).statusCode(404);
  }

  @Test
  void aDropNamingBothAnchorsOrNeitherIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Anchors");
    String a = idea(project, "One");
    String b = idea(project, "Two");

    drop(a, Map.of("above", b, "below", b))
        .statusCode(400)
        .body("message", containsString("exactly one anchor"));
    drop(a, Map.of()).statusCode(400).body("message", containsString("exactly one anchor"));
    drop(a, Map.of("above", " ")).statusCode(400).body("message", containsString("exactly one anchor"));
  }

  @Test
  void anAnchorThatIsTheIdeaNamesNothingOrIsNotRankableIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Bad Anchors");
    String a = idea(project, "Mover");
    String parked = idea(project, "Parked");
    archive(parked);
    String done = doneIdea(project, "Finished");
    String elsewhere = idea(EntityFixtures.project("Idea Priority Elsewhere"), "Another project's");

    drop(a, Map.of("above", a)).statusCode(400).body("message", containsString("itself"));
    drop(a, Map.of("below", "nothing-999999"))
        .statusCode(400)
        .body("message", containsString("names no idea"));
    for (String notRankable : List.of(parked, done, elsewhere)) {
      drop(a, Map.of("below", notRankable))
          .statusCode(400)
          .body("message", containsString("not a rankable idea"));
    }
    given().get("/projects/api/ideas/" + a).then().body("priority", nullValue());
  }

  @Test
  void aDropRanksTheIdeaTheListingAnswersAsDroppedAndTheMovedOnesAreAnnounced() {
    EntityFixtures.Project project = EntityFixtures.project("Idea Priority Drop");
    String a = idea(project, "First");
    String b = idea(project, "Second");
    String c = idea(project, "Third");
    String d = idea(project, "Fourth");
    patch(a, priority(2)).statusCode(200);
    assertEquals(List.of(a, b, c, d), listed(project));
    announced.reset();

    // d dropped directly below a: a keeps its place, d takes the next, b and c are left alone.
    drop(d, Map.of("below", a))
        .statusCode(200)
        .body("items.id", equalTo(List.of(a, d)))
        .body("items.priority", equalTo(List.of(2, 1)));
    assertEquals(List.of(a, d, b, c), listed(project));
    given().get("/projects/api/ideas/" + b).then().body("priority", nullValue());
    assertEquals(
        List.of(d),
        updatedIdeas(),
        "a is written its own priority again and is not announced; d moved and is");

    // c dropped between a and d: c and a are renumbered above d, so both moved and both announce.
    announced.reset();
    drop(c, Map.of("above", d))
        .statusCode(200)
        .body("items.id", equalTo(List.of(a, c)))
        .body("items.priority", equalTo(List.of(3, 2)));
    assertEquals(List.of(a, c, d, b), listed(project));
    assertEquals(List.of(a, c), updatedIdeas());
  }

  private List<String> updatedIdeas() {
    return announced.published().stream()
        .filter(e -> e instanceof IdeaUpdated)
        .map(e -> ((IdeaUpdated) e).ideaId())
        .toList();
  }
}
