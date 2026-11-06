package eu.wohlben.qits.entities.api;

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
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The priority on the wire (qits-753): the column on every read, the patch that sets and clears it,
 * and the drop door {@code PUT /work/{qualifiedId}/priority} with its refusals. The arithmetic of the
 * drop is {@code SiblingPrioritiesTest}'s; what is asserted here is the door — who its siblings are,
 * what it answers, and that a client sorting the listing by the order rule sees the drop.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class WorkPriorityApiTest {

  @Inject WorkEntityService work;

  private static String project(String name) {
    return EntityFixtures.project(name).id();
  }

  private static String ticket(String projectId, String title) {
    return WorkRequests.ticket(projectId, title, "BUG", "it occurs");
  }

  private static String qualified(String id) {
    return WorkRequests.qualifiedId(id);
  }

  private static ValidatableResponse patch(String id, Map<String, Object> body) {
    return given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then();
  }

  private static ValidatableResponse drop(String id, Map<String, Object> body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .put("/projects/api/work/" + id + "/priority")
        .then();
  }

  private static Map<String, Object> priority(Integer value) {
    Map<String, Object> body = new HashMap<>();
    body.put("priority", value);
    return body;
  }

  /** Walks a ticket to {@code target} as a person, gates and all — the criteria first. */
  private void walk(String id, EntityStatus target) {
    TestCriteria.give(id);
    List<EntityStatus> walk = EntityStateMachine.walk();
    for (int step = 1; step <= walk.indexOf(target); step++) {
      work.transition(Archetype.TICKET, id, walk.get(step).name(), Mover.person("priority-test"));
    }
  }

  /**
   * The project's roots as a client draws them: the listing, sorted by the order rule — priority
   * descending, nulls last, then number.
   */
  private static List<String> rootsInOrder(String projectId) {
    List<Map<String, Object>> entities =
        given()
            .when()
            .get("/projects/api/projects/" + projectId + "/work")
            .then()
            .statusCode(200)
            .extract()
            .path("entities");
    List<Map<String, Object>> roots = new ArrayList<>();
    for (Map<String, Object> e : entities) {
      if (e.get("parent") == null && !"ISSUE".equals(e.get("archetype"))) {
        roots.add(e);
      }
    }
    roots.sort(
        Comparator.<Map<String, Object>, Integer>comparing(
                e -> (Integer) e.get("priority"), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparingLong(e -> ((Number) e.get("number")).longValue()));
    return roots.stream().map(e -> (String) e.get("qualifiedId")).toList();
  }

  @Test
  void everyAnswerCarriesThePriorityNullUntilRanked() {
    String projectId = project("Priority Reads");
    String ticket = ticket(projectId, "Unranked");

    given()
        .when()
        .get("/projects/api/work/" + ticket)
        .then()
        .statusCode(200)
        .body("containsKey('priority')", equalTo(true))
        .body("priority", nullValue());
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/work")
        .then()
        .statusCode(200)
        .body("entities.find { it.id == '" + ticket + "' }.containsKey('priority')", equalTo(true));

    patch(ticket, priority(7)).statusCode(200).body("priority", equalTo(7));
    given().when().get("/projects/api/work/" + ticket).then().body("priority", equalTo(7));
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/work")
        .then()
        .body("entities.find { it.id == '" + ticket + "' }.priority", equalTo(7));
  }

  @Test
  void aPatchSetsAndClearsThePriorityAndLeavesTheRestAlone() {
    String projectId = project("Priority Patch");
    String ticket = ticket(projectId, "Ranked by hand");

    patch(ticket, priority(5))
        .statusCode(200)
        .body("priority", equalTo(5))
        .body("title", equalTo("Ranked by hand"))
        .body("impetus", equalTo("it occurs"));
    patch(ticket, priority(null)).statusCode(200).body("priority", nullValue());
    given().when().get("/projects/api/work/" + ticket).then().body("priority", nullValue());

    patch(ticket, Map.of("priority", "high"))
        .statusCode(400)
        .body("message", containsString("priority must be an integer"));
    patch(ticket, Map.of("priority", 1.5))
        .statusCode(400)
        .body("message", containsString("priority must be an integer"));
  }

  /** A ranking is no scope: a feature of a REFINED epic is still ranked, past the epic's freeze. */
  @Test
  void aPatchedPriorityPassesTheScopeFreeze() {
    String projectId = project("Priority Freeze");
    String epic = WorkRequests.epic(projectId, "The plan");
    TestCriteria.give(epic);
    String feature = WorkRequests.feature(epic, "A part");
    work.transition(Archetype.EPIC, epic, "REFINED", Mover.person("priority-test"));

    patch(feature, Map.of("title", "renamed")).statusCode(409);
    patch(feature, priority(3)).statusCode(200).body("priority", equalTo(3));
  }

  @Test
  void aPriorityOnADoneOrDroppedItemIsA409() {
    String projectId = project("Priority Closed");
    String done = ticket(projectId, "Done work");
    walk(done, EntityStatus.DONE);
    String dropped = ticket(projectId, "Dropped work");
    WorkRequests.status(dropped, "DROPPED").then().statusCode(200);

    patch(done, priority(1)).statusCode(409).body("message", containsString("DONE"));
    patch(dropped, priority(1)).statusCode(409).body("message", containsString("DROPPED"));
    patch(dropped, priority(null)).statusCode(409);
  }

  @Test
  void theUpdateSchemaOfEveryArchetypeListsAClearablePriority() {
    for (String archetype : List.of("EPIC", "TICKET", "FEATURE", "TASK")) {
      given()
          .when()
          .get("/projects/api/work/archetypes/" + archetype + "/schemas/update")
          .then()
          .statusCode(200)
          .body("properties.priority.type", equalTo(List.of("integer", "null")));
    }
  }

  @Test
  void aDropRanksTheItemAndWhatIsAboveItAndTheListingSortsThatWay() {
    String projectId = project("Priority Drop");
    String a = ticket(projectId, "First");
    String b = WorkRequests.epic(projectId, "Second");
    String c = ticket(projectId, "Third");
    String d = ticket(projectId, "Fourth");
    patch(a, priority(2)).statusCode(200);
    String qa = qualified(a);
    String qb = qualified(b);
    String qc = qualified(c);
    String qd = qualified(d);
    assertEquals(List.of(qa, qb, qc, qd), rootsInOrder(projectId));

    // d dropped directly below a: a keeps its place, d takes the next, b and c are left alone.
    drop(qd, Map.of("below", qa))
        .statusCode(200)
        .body("items.qualifiedId", equalTo(List.of(qa, qd)))
        .body("items.priority", equalTo(List.of(2, 1)));
    assertEquals(List.of(qa, qd, qb, qc), rootsInOrder(projectId));
    given().when().get("/projects/api/work/" + b).then().body("priority", nullValue());

    // b dropped directly above a, by UUID: it goes over the top.
    drop(qb, Map.of("above", a))
        .statusCode(200)
        .body("items.qualifiedId", equalTo(List.of(qb)))
        .body("items.priority", equalTo(List.of(3)));
    assertEquals(List.of(qb, qa, qd, qc), rootsInOrder(projectId));
  }

  @Test
  void aDropAmongChildrenOrdersOneParentsChildren() {
    String projectId = project("Priority Children");
    String epic = WorkRequests.epic(projectId, "The plan");
    String first = WorkRequests.feature(epic, "First part");
    String second = WorkRequests.feature(epic, "Second part");

    drop(second, Map.of("above", qualified(first)))
        .statusCode(200)
        .body("items.qualifiedId", equalTo(List.of(qualified(second))))
        .body("items.priority", equalTo(List.of(1)));
  }

  @Test
  void aDropNamingBothAnchorsOrNeitherIsA400() {
    String projectId = project("Priority Anchors");
    String a = ticket(projectId, "One");
    String b = ticket(projectId, "Two");

    drop(a, Map.of("above", b, "below", b))
        .statusCode(400)
        .body("message", containsString("exactly one anchor"));
    drop(a, Map.of()).statusCode(400).body("message", containsString("exactly one anchor"));
  }

  @Test
  void anAnchorThatIsTheItemNamesNothingOrIsNoSiblingIsA400() {
    String projectId = project("Priority Siblings");
    String a = ticket(projectId, "Root ticket");
    String epic = WorkRequests.epic(projectId, "An epic");
    String feature = WorkRequests.feature(epic, "A child");
    String elsewhere = ticket(project("Priority Elsewhere"), "Another project's");
    String closed = ticket(projectId, "Closed");
    WorkRequests.status(closed, "DROPPED").then().statusCode(200);

    drop(a, Map.of("above", a)).statusCode(400).body("message", containsString("itself"));
    drop(a, Map.of("above", qualified(a))).statusCode(400).body("message", containsString("itself"));
    drop(a, Map.of("below", "nothing-999999"))
        .statusCode(400)
        .body("message", containsString("names no entity"));
    for (String notASibling : List.of(feature, elsewhere, closed)) {
      drop(a, Map.of("below", notASibling))
          .statusCode(400)
          .body("message", containsString("not a reorderable sibling"));
    }
    drop(feature, Map.of("below", a))
        .statusCode(400)
        .body("message", containsString("not a reorderable sibling"));
    given().when().get("/projects/api/work/" + a).then().body("priority", nullValue());
  }

  @Test
  void aDropOfADoneOrDroppedItemIsA409() {
    String projectId = project("Priority Drop Closed");
    String open = ticket(projectId, "Open");
    String done = ticket(projectId, "Done");
    walk(done, EntityStatus.DONE);
    String dropped = ticket(projectId, "Dropped");
    WorkRequests.status(dropped, "DROPPED").then().statusCode(200);

    drop(done, Map.of("above", open)).statusCode(409).body("message", containsString("DONE"));
    drop(dropped, Map.of("above", open)).statusCode(409).body("message", containsString("DROPPED"));
    drop("nothing-999999", Map.of("above", open)).statusCode(404);
  }
}
