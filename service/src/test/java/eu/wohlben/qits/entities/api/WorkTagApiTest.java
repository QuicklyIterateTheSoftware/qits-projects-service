package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkTag;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tags on the wire (qits-634): {@code tags} on every answer, the merge patch that replaces and
 * clears them at every status and past every scope freeze, the create that takes them, the
 * listing's {@code tag=} filter, the schemas that list the vocabulary, and a transition — a move or
 * a reshape — that leaves them where they were. What a tag <em>does</em> (the dispatch refusal, the
 * phase advance skipping it) is {@code TicketFlowDispatchTest}'s, {@code CampaignExecutorTest}'s and
 * {@code PhaseAdvanceTest}'s.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class WorkTagApiTest {

  private static final String RESERVED = WorkTag.RESERVED_FOR_HUMAN.name();

  @Inject WorkEntityService work;

  private static String project(String name) {
    return EntityFixtures.project(name).id();
  }

  private static String ticket(String projectId, String title) {
    return WorkRequests.ticket(projectId, title, "BUG", "it occurs");
  }

  private static ValidatableResponse patch(String id, Map<String, Object> body) {
    return given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then();
  }

  private static Map<String, Object> tags(Object value) {
    Map<String, Object> body = new HashMap<>();
    body.put("tags", value);
    return body;
  }

  private static ValidatableResponse list(String projectId, String query) {
    return given()
        .when()
        .get("/projects/api/projects/" + projectId + "/work" + query)
        .then();
  }

  /** Walks a ticket to {@code target} as a person, gates and all — the criteria first. */
  private void walk(String id, EntityStatus target) {
    TestCriteria.give(id);
    List<EntityStatus> walk = EntityStateMachine.walk();
    for (int step = 1; step <= walk.indexOf(target); step++) {
      work.transition(Archetype.TICKET, id, walk.get(step).name(), Mover.person("tag-test"));
    }
  }

  @Test
  void everyAnswerCarriesTheTagsEmptyUntilTagged() {
    String projectId = project("Tag Reads");
    String ticket = ticket(projectId, "Untagged");

    WorkRequests.read(ticket).then().statusCode(200).body("tags", empty());
    list(projectId, "")
        .statusCode(200)
        .body("entities.find { it.id == '" + ticket + "' }.tags", empty());

    patch(ticket, tags(List.of(RESERVED))).statusCode(200).body("tags", contains(RESERVED));
    WorkRequests.read(ticket).then().body("tags", contains(RESERVED));
    list(projectId, "")
        .body("entities.find { it.id == '" + ticket + "' }.tags", contains(RESERVED));
  }

  @Test
  void aPatchReplacesAndClearsTheTagsAndLeavesTheRestAlone() {
    String projectId = project("Tag Patch");
    String ticket = ticket(projectId, "Tagged by hand");

    patch(ticket, tags(List.of(RESERVED)))
        .statusCode(200)
        .body("tags", contains(RESERVED))
        .body("title", equalTo("Tagged by hand"));
    // Restated, and named twice: still one tag.
    patch(ticket, tags(List.of(RESERVED, RESERVED))).statusCode(200).body("tags", contains(RESERVED));
    // A patch that does not name the tags leaves them alone.
    patch(ticket, Map.of("title", "Renamed")).statusCode(200).body("tags", contains(RESERVED));
    // An empty array clears them…
    patch(ticket, tags(List.of())).statusCode(200).body("tags", empty());
    // …and so does null.
    patch(ticket, tags(List.of(RESERVED))).statusCode(200);
    patch(ticket, tags(null)).statusCode(200).body("tags", empty());
    WorkRequests.read(ticket).then().body("tags", empty()).body("title", equalTo("Renamed"));
  }

  @Test
  void anUnknownTagOrANonArrayIsA400NamingTheLegalTags() {
    String projectId = project("Tag Refusals");
    String ticket = ticket(projectId, "Not tagged wrongly");

    patch(ticket, tags(List.of("RESERVED_FOR_ROBOTS")))
        .statusCode(400)
        .body("message", containsString("RESERVED_FOR_ROBOTS"))
        .body("message", containsString(RESERVED));
    patch(ticket, tags(RESERVED))
        .statusCode(400)
        .body("message", containsString("tags must be an array"))
        .body("message", containsString(RESERVED));
    patch(ticket, tags(List.of(1)))
        .statusCode(400)
        .body("message", containsString(RESERVED));
    WorkRequests.read(ticket).then().body("tags", empty());
  }

  /** A tag is no scope: a REFINED epic and a feature under it are still tagged, past the freeze. */
  @Test
  void aTagsOnlyPatchPassesTheScopeFreeze() {
    String projectId = project("Tag Freeze");
    String epic = WorkRequests.epic(projectId, "The plan");
    TestCriteria.give(epic);
    String feature = WorkRequests.feature(epic, "A part");
    work.transition(Archetype.EPIC, epic, "REFINED", Mover.person("tag-test"));

    patch(epic, Map.of("title", "renamed")).statusCode(409);
    patch(epic, tags(List.of(RESERVED))).statusCode(200).body("tags", contains(RESERVED));
    patch(feature, tags(List.of(RESERVED))).statusCode(200).body("tags", contains(RESERVED));
  }

  /** No status refuses a tag: scheduled work and finished work alike. */
  @Test
  void aTagsOnlyPatchPassesAtReadyForDevAndAtDone() {
    String projectId = project("Tag Statuses");
    String scheduled = ticket(projectId, "Scheduled work");
    walk(scheduled, EntityStatus.READY_FOR_DEV);
    String done = ticket(projectId, "Done work");
    walk(done, EntityStatus.DONE);

    patch(scheduled, tags(List.of(RESERVED))).statusCode(200).body("tags", contains(RESERVED));
    patch(done, tags(List.of(RESERVED))).statusCode(200).body("tags", contains(RESERVED));
    patch(done, tags(List.of())).statusCode(200).body("tags", empty());
  }

  /** The tag outlives a status move — it is cleared by nothing but a patch, unlike the block. */
  @Test
  void aTagSurvivesAStatusMove() {
    String projectId = project("Tag Move");
    String ticket = ticket(projectId, "Moved by hand");
    TestCriteria.give(ticket);
    patch(ticket, tags(List.of(RESERVED))).statusCode(200);

    WorkRequests.status(ticket, "REFINED")
        .then()
        .statusCode(200)
        .body("status", equalTo("REFINED"))
        .body("tags", contains(RESERVED));
    WorkRequests.read(ticket).then().body("tags", contains(RESERVED));
  }

  /**
   * The whole-state transition restates the registry's properties and clears the ones it omits —
   * the tags are none of them, so a reshape that never names them keeps them.
   */
  @Test
  void aTagSurvivesAWholeStateReshape() {
    String projectId = project("Tag Reshape");
    String ticket = ticket(projectId, "Reshaped");
    patch(ticket, tags(List.of(RESERVED))).statusCode(200);

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("archetype", "TICKET");
    row.put("title", "Reshaped, restated");
    row.put("impetus", "it occurs");
    row.put("ticketType", "IMPROVEMENT");
    row.put("status", "REPORTED");
    row.put("membership", Collections.singletonMap("parent", null));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of(ticket, row))
        .when()
        .post("/projects/api/work/transition")
        .then()
        .statusCode(200);
    WorkRequests.read(ticket)
        .then()
        .body("title", equalTo("Reshaped, restated"))
        .body("ticketType", equalTo("IMPROVEMENT"))
        .body("tags", contains(RESERVED));

    // The PUT of one row is the same full-state door.
    row.put("title", "Restated once more");
    given()
        .contentType(ContentType.JSON)
        .body(row)
        .when()
        .put("/projects/api/work/" + ticket)
        .then()
        .statusCode(200)
        .body("tags", contains(RESERVED));
  }

  @Test
  void theListingFiltersByTag() {
    String projectId = project("Tag Filter");
    String reserved = ticket(projectId, "A person's work");
    String open = ticket(projectId, "An agent's work");
    patch(reserved, tags(List.of(RESERVED))).statusCode(200);

    for (String word : List.of(RESERVED, "reserved-for-human", "reserved_for_human")) {
      List<String> ids =
          list(projectId, "?tag=" + word).statusCode(200).extract().path("entities.id");
      assertEquals(List.of(reserved), ids, word);
    }
    list(projectId, "?tag=" + RESERVED + "&archetype=EPIC")
        .statusCode(200)
        .body("entities", empty());
    list(projectId, "").body("entities.id", hasItem(open));
    list(projectId, "?tag=" + RESERVED).body("entities.id", not(hasItem(open)));
  }

  @Test
  void anUnknownTagFilterIsA400() {
    String projectId = project("Tag Filter Refusal");
    list(projectId, "?tag=PARKED")
        .statusCode(400)
        .body("message", containsString("Unknown tag: PARKED"))
        .body("message", containsString(RESERVED));
  }

  @Test
  void aCreateTakesTheTags() {
    String projectId = project("Tag Create");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("archetype", "TICKET");
    body.put("project", projectId);
    body.put("title", "Filed for a person");
    body.put("ticketType", "IMPROVEMENT");
    body.put("impetus", "it needs a person");
    body.put("tags", List.of(RESERVED));
    String id = WorkRequests.create(body).path("id");
    WorkRequests.read(id).then().body("tags", contains(RESERVED));

    body.put("title", "Filed with a word nobody declared");
    body.put("tags", List.of("PARKED"));
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(400)
        .body("message", containsString("PARKED"))
        .body("message", containsString(RESERVED));
  }

  @Test
  void theCreateAndUpdateSchemasListTheTags() {
    for (String archetype : List.of("EPIC", "TICKET", "FEATURE", "TASK", "CAMPAIGN")) {
      given()
          .when()
          .get("/projects/api/work/archetypes/" + archetype + "/schemas/create")
          .then()
          .statusCode(200)
          .body("properties.tags.type", equalTo("array"))
          .body("properties.tags.items.enum", contains(RESERVED));
      given()
          .when()
          .get("/projects/api/work/archetypes/" + archetype + "/schemas/update")
          .then()
          .statusCode(200)
          .body("properties.tags.type", contains("array", "null"))
          .body("properties.tags.items.enum", contains(RESERVED));
      given()
          .when()
          .get("/projects/api/work/archetypes/" + archetype + "/schemas/transition")
          .then()
          .statusCode(200)
          .body("properties", not(org.hamcrest.Matchers.hasKey("tags")));
    }
  }
}
