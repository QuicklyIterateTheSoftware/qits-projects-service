package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.WorkRequests.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

/**
 * An epic's lifecycle over {@code POST /work/{qualifiedId}/status} (qits-976 ported this from the
 * per-archetype {@code EpicLifecycleApiTest}): the moves it answers and the 409s it refuses, the
 * supersede and the successor it leaves behind, the scope freeze a move opens and closes, and the
 * project listing's status filter. The rules themselves are pinned in the entities module's {@code
 * EpicLifecycleTest} — what is tested here is what reaches the wire.
 *
 * <p>Every move is made by a verified person (a session), because REFINED to READY_FOR_DEV is a
 * person's (qits-887) and an epic's status is {@code qits:admin} alone.
 */
@QuarkusTest
class WorkEpicLifecycleApiTest {

  private static final String WORK = "/projects/api/work/";

  private static String project() {
    return EntityFixtures.project("Lifecycle Project").id();
  }

  /** A REPORTED epic with criteria, so the ACCEPTANCE_CRITERIA gate is not what a test meets. */
  private static String epic(String projectId, String title) {
    String epic = WorkRequests.epic(projectId, title);
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("description", "The spine", "acceptanceCriteria", TestCriteria.CRITERIA))
        .when()
        .patch(WORK + epic)
        .then()
        .statusCode(200)
        .body("status", equalTo("REPORTED"))
        .body("supersededBy", nullValue());
    return epic;
  }

  private static ValidatableResponse move(String epic, String target) {
    return WorkRequests.status(
            () -> given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev")),
            epic,
            target)
        .then();
  }

  private static ValidatableResponse addFeature(String epic, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(map("archetype", "FEATURE", "parent", epic, "title", title))
        .when()
        .post("/projects/api/work")
        .then();
  }

  @Test
  void freezingAnEpicAnswersItMoved() {
    String epic = epic(project(), "Planning domain");

    move(epic, "REFINED")
        .statusCode(200)
        .body("id", equalTo(epic))
        .body("status", equalTo("REFINED"))
        .body("statusBefore", equalTo("REPORTED"))
        .body("supersededBy", nullValue());

    given().when().get(WORK + epic).then().statusCode(200).body("status", equalTo("REFINED"));
  }

  @Test
  void supersedingLinksTheOldEpicToADraftSuccessorThatCarriesTheScope() {
    String projectId = project();
    String epic = epic(projectId, "Planning domain");
    addFeature(epic, "Feature A").statusCode(201);
    move(epic, "REFINED").statusCode(200);

    // The move answers the dropped epic; the successor is read on its own.
    String successor =
        move(epic, "SUPERSEDED")
            .statusCode(200)
            .body("status", equalTo("DROPPED"))
            .body("supersededBy", notNullValue())
            .body("supersededBy", not(equalTo(epic)))
            .extract()
            .path("supersededBy");

    given().when().get(WORK + epic).then().statusCode(200).body("supersededBy", equalTo(successor));
    given()
        .when()
        .get(WORK + successor)
        .then()
        .statusCode(200)
        .body("archetype", equalTo("EPIC"))
        .body("status", equalTo("REPORTED"))
        .body("projectId", equalTo(projectId))
        .body("title", equalTo("Planning domain"))
        .body("supersededBy", nullValue());

    // The copied scope came with it, slugs kept.
    given()
        .when()
        .get(WORK + successor + "/children")
        .then()
        .statusCode(200)
        .body("children", hasSize(1))
        .body("children[0].slug", equalTo("feature-a"));
  }

  @Test
  void anIllegalOrUnknownTargetIsA409() {
    String epic = epic(project(), "Planning domain");

    // A draft has no scope to supersede.
    move(epic, "SUPERSEDED").statusCode(409).body("message", notNullValue());
    // DONE is a word of the lifecycle, but nothing is skipped: a draft is not closed in one move.
    move(epic, "DONE").statusCode(409);
    // A retired epic word names no status at all.
    move(epic, "REFINING").statusCode(409);
    // Freezing is reversible, one step at a time — and never to where the epic already is.
    move(epic, "REFINED").statusCode(200);
    move(epic, "REPORTED").statusCode(200).body("status", equalTo("REPORTED"));
    move(epic, "REPORTED").statusCode(409);
  }

  /** What qits-392 exists for: over the wire, an epic reaches VERIFIED and DONE. */
  @Test
  void anEpicWalksToVerifiedAndDone() {
    String epic = epic(project(), "Planning domain");
    for (String target : new String[] {"REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE"}) {
      move(epic, target).statusCode(200).body("status", equalTo(target));
    }
    given().when().get(WORK + epic).then().statusCode(200).body("status", equalTo("DONE"));
  }

  /**
   * DONE is final: every target is refused — the step back to VERIFIED, DROPPED and the supersede
   * operation included — and the refusal says a follow-up is a new epic rather than a reopened one.
   */
  @Test
  void aDoneEpicRefusesEveryTarget() {
    String epic = epic(project(), "Planning domain");
    for (String target : new String[] {"REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE"}) {
      move(epic, target).statusCode(200);
    }
    for (String target :
        new String[] {
          "REPORTED", "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED",
          "SUPERSEDED"
        }) {
      move(epic, target)
          .statusCode(409)
          .body("message", containsString("DONE is final"))
          .body("message", containsString("a follow-up is a new ticket or epic"));
    }
    given()
        .when()
        .get(WORK + epic)
        .then()
        .statusCode(200)
        .body("status", equalTo("DONE"))
        .body("supersededBy", nullValue());
  }

  /** The scope freeze, reopened along the graph: adding a feature is the structural write asked. */
  @Test
  void movingAFrozenEpicBackToReportedReopensItsScope() {
    String epic = epic(project(), "Planning domain");
    move(epic, "REFINED").statusCode(200);
    addFeature(epic, "Feature A").statusCode(409).body("message", containsString("REPORTED"));

    move(epic, "REPORTED").statusCode(200);

    addFeature(epic, "Feature A").statusCode(201).body("title", equalTo("Feature A"));
  }

  @Test
  void aFrozenEpicRefusesStructuralEditsAndTakesItsMarkers() {
    String epic = epic(project(), "Planning domain");
    String feature = addFeature(epic, "Feature A").statusCode(201).extract().path("id");
    var marker = map("implementedAt", "2026-07-25T10:15:30Z");

    // While a draft, the marker is the thing that is refused.
    markFeature(feature, marker).statusCode(409);

    move(epic, "REFINED").statusCode(200);
    // Frozen, but not yet scheduled: the marker waits for a person (qits-887).
    markFeature(feature, marker).statusCode(409);
    move(epic, "READY_FOR_DEV").statusCode(200);

    // Frozen and scheduled: a structural write is refused and the marker goes through.
    addFeature(epic, "Feature B").statusCode(409);
    markFeature(feature, marker)
        .statusCode(200)
        .body("implementedAt", notNullValue())
        .body("status", equalTo("IMPLEMENTED"));
  }

  private static ValidatableResponse markFeature(String feature, Object patch) {
    return given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(patch)
        .when()
        .patch(WORK + feature)
        .then();
  }

  @Test
  void theProjectsEpicsFilterByStatus() {
    String projectId = project();
    String draft = epic(projectId, "Still drafting");
    String frozen = epic(projectId, "Being built");
    move(frozen, "REFINED").statusCode(200);
    String listing = "/projects/api/projects/" + projectId + "/work";

    given()
        .queryParam("archetype", "EPIC")
        .when()
        .get(listing)
        .then()
        .statusCode(200)
        .body("entities", hasSize(2));
    given()
        .queryParam("archetype", "EPIC")
        .queryParam("status", "REPORTED")
        .when()
        .get(listing)
        .then()
        .statusCode(200)
        .body("entities", hasSize(1))
        .body("entities.id", hasItem(draft));
    given()
        .queryParam("archetype", "EPIC")
        .queryParam("status", "REFINED")
        .when()
        .get(listing)
        .then()
        .statusCode(200)
        .body("entities", hasSize(1))
        .body("entities.id", hasItem(frozen));
    given()
        .queryParam("archetype", "EPIC")
        .queryParam("status", "DROPPED")
        .when()
        .get(listing)
        .then()
        .statusCode(200)
        .body("entities", hasSize(0));
    // A typo must not read as "no epics".
    given()
        .queryParam("archetype", "EPIC")
        .queryParam("status", "DONNE")
        .when()
        .get(listing)
        .then()
        .statusCode(400);
  }

  @Test
  void aMoveIsAuditedOnTheEpic() {
    String epic = epic(project(), "Planning domain");
    move(epic, "DROPPED").statusCode(200);

    given()
        .when()
        .get(WORK + epic + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("UPDATE"));
  }
}
