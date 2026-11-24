package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The work list's filters for a long-running agent (qits-2595): {@code assignee}, {@code
 * unassigned=true} (the {@code Takeable} rule, one clause per test row), {@code active=true}, {@code
 * order=priority} (the {@code BacklogOrder}), {@code limit}, and every refusal. Each test stands in a
 * project of its own, so the list answers exactly what the test filed.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class WorkListFiltersApiTest {

  @Inject WorkEntityService work;

  private static String project(String name) {
    return EntityFixtures.project(name).id();
  }

  private static String ticket(String projectId, String title, String type) {
    return TestCriteria.give(WorkRequests.ticket(projectId, title, type, "it occurs"));
  }

  private static ValidatableResponse list(String projectId, String query) {
    return given()
        .when()
        .get("/projects/api/projects/" + projectId + "/work" + query)
        .then();
  }

  /** The listing with {@code query} sent exactly as written, percent escapes included. */
  private static ValidatableResponse raw(String projectId, String query) {
    return given()
        .urlEncodingEnabled(false)
        .when()
        .get("/projects/api/projects/" + projectId + "/work" + query)
        .then();
  }

  private static List<String> ids(String projectId, String query) {
    return list(projectId, query).statusCode(200).extract().path("entities.id");
  }

  private static void patch(String id, Map<String, Object> body) {
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then()
        .statusCode(200);
  }

  private void walk(Archetype archetype, String id, EntityStatus target) {
    List<EntityStatus> walk = EntityStateMachine.walk();
    for (int step = 1; step <= walk.indexOf(target); step++) {
      work.transition(archetype, id, walk.get(step).name(), Mover.person("list-test"));
    }
  }

  // --- unassigned=true: each clause of the takeable rule ----------------------------------------

  @Test
  void unassignedKeepsOnlyTakeableWork() {
    String projectId = project("Takeable");
    String reported = ticket(projectId, "Reported", "BUG");
    String epic = EntityFixtures.epic(projectId);
    String scheduled = ticket(projectId, "Scheduled", "IMPROVEMENT");
    walk(Archetype.TICKET, scheduled, EntityStatus.READY_FOR_DEV);
    String implementing = ticket(projectId, "Implementing", "IMPROVEMENT");
    walk(Archetype.TICKET, implementing, EntityStatus.IMPLEMENTING);
    String implemented = ticket(projectId, "Implemented", "IMPROVEMENT");
    walk(Archetype.TICKET, implemented, EntityStatus.IMPLEMENTED);
    String verifying = ticket(projectId, "Verifying", "IMPROVEMENT");
    walk(Archetype.TICKET, verifying, EntityStatus.VERIFYING);

    // Each of these breaks exactly one clause.
    String assigned = ticket(projectId, "Assigned", "BUG");
    patch(assigned, WorkRequests.map("assignee", "developer"));
    String feature = EntityFixtures.feature(epic);
    String repository = EntityFixtures.repository(projectId);
    EntityFixtures.task(feature, repository);
    for (EntityStatus status :
        List.of(
            EntityStatus.REFINED, EntityStatus.VERIFIED, EntityStatus.DONE, EntityStatus.DROPPED)) {
      String finished = ticket(projectId, "At " + status, "BUG");
      if (status == EntityStatus.DROPPED) {
        WorkRequests.status(finished, "DROPPED").then().statusCode(200);
      } else {
        walk(Archetype.TICKET, finished, status);
      }
    }
    String blocked = ticket(projectId, "Blocked", "BUG");
    given()
        .contentType(ContentType.JSON)
        .body(WorkRequests.map("blocked", true, "reason", "in the way"))
        .when()
        .post("/projects/api/work/" + blocked + "/blocked")
        .then()
        .statusCode(200);
    String reserved = ticket(projectId, "Reserved", "BUG");
    patch(reserved, WorkRequests.map("tags", List.of("RESERVED_FOR_HUMAN")));
    String condition = ticket(projectId, "Condition", "BUG");
    patch(condition, WorkRequests.map("tags", List.of("CONDITION")));
    String needing = ticket(projectId, "Needing", "BUG");
    patch(
        needing,
        WorkRequests.map(
            "needs",
            List.of(
                WorkRequests.map(
                    "item", WorkRequests.qualifiedId(reported), "status", "VERIFIED"))));

    assertEquals(
        List.of(reported, epic, scheduled, implementing, implemented, verifying),
        ids(projectId, "?unassigned=true"));
  }

  // --- order=priority and limit ------------------------------------------------------------------

  @Test
  void orderPriorityPutsBugsFirstThenPriorityThenOldest() {
    String projectId = project("Backlog Order");
    String ranked = ticket(projectId, "Ranked improvement", "IMPROVEMENT");
    patch(ranked, WorkRequests.map("priority", 5));
    String older = ticket(projectId, "Older unranked", "IMPROVEMENT");
    String sunk = ticket(projectId, "Sunk", "IMPROVEMENT");
    patch(sunk, WorkRequests.map("priority", -1));
    String newer = ticket(projectId, "Newer unranked", "IMPROVEMENT");
    String bug = ticket(projectId, "Unranked bug", "BUG");

    // Without order: tree order, each root oldest first.
    assertEquals(List.of(ranked, older, sunk, newer, bug), ids(projectId, ""));
    assertEquals(List.of(bug, ranked, older, newer, sunk), ids(projectId, "?order=priority"));
    assertEquals(List.of(bug, ranked), ids(projectId, "?order=priority&limit=2"));
    assertEquals(List.of(ranked, older), ids(projectId, "?limit=2"));
  }

  // --- assignee and active -----------------------------------------------------------------------

  @Test
  void assigneeAndActiveAnswerTheHoldersUnfinishedWork() {
    String projectId = project("Held Work");
    String held = ticket(projectId, "Held", "BUG");
    patch(held, WorkRequests.map("assignee", "developer"));
    walk(Archetype.TICKET, held, EntityStatus.IMPLEMENTING);
    String finished = ticket(projectId, "Finished", "BUG");
    patch(finished, WorkRequests.map("assignee", "developer"));
    walk(Archetype.TICKET, finished, EntityStatus.DONE);
    String someoneElses = ticket(projectId, "Someone else's", "BUG");
    patch(someoneElses, WorkRequests.map("assignee", "reviewer"));
    String open = ticket(projectId, "Nobody's", "BUG");

    assertEquals(List.of(held, finished), ids(projectId, "?assignee=developer"));
    List<String> trimmed =
        given()
            .queryParam("assignee", " developer ")
            .when()
            .get("/projects/api/projects/" + projectId + "/work")
            .then()
            .statusCode(200)
            .extract()
            .path("entities.id");
    assertEquals(List.of(held, finished), trimmed);
    assertEquals(List.of(held), ids(projectId, "?assignee=developer&active=true"));
    assertEquals(List.of(held, someoneElses, open), ids(projectId, "?active=true"));
    assertEquals(List.of(), ids(projectId, "?assignee=develop"));
  }

  // --- refusals ----------------------------------------------------------------------------------

  @Test
  void everyMalformedFilterIsA400() {
    String projectId = project("List Refusals");
    // An empty value (?assignee=) reaches the door as no value at all, the way every listing filter
    // reads it; a value of only whitespace is the blank one refused.
    for (String blank : List.of("?assignee=%20", "?assignee=%20%20")) {
      raw(projectId, blank)
          .statusCode(400)
          .body("message", containsString("assignee must not be blank"));
    }
    list(projectId, "?assignee=developer&unassigned=true")
        .statusCode(400)
        .body("message", containsString("assignee and unassigned exclude each other"));
    for (String value : List.of("false", "yes", "TRUE")) {
      raw(projectId, "?unassigned=" + value)
          .statusCode(400)
          .body("message", containsString("unassigned must be true"));
      raw(projectId, "?active=" + value)
          .statusCode(400)
          .body("message", containsString("active must be true"));
    }
    for (String value : List.of("newest", "PRIORITY", "%20priority")) {
      raw(projectId, "?order=" + value)
          .statusCode(400)
          .body("message", containsString("order must be priority"));
    }
    for (String value : List.of("0", "201", "-1", "ten", "2.5")) {
      raw(projectId, "?limit=" + value)
          .statusCode(400)
          .body("message", containsString("limit must be an integer from 1 to 200"));
    }
    list(projectId, "?limit=200").statusCode(200);
    list(projectId, "?limit=1").statusCode(200);
  }
}
