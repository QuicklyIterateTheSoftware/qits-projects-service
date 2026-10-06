package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle over HTTP: the transition endpoint's two-field response, the 409s it answers, and
 * the epics list's status filter. The rules themselves are pinned in the entities module's {@code
 * EpicLifecycleTest} — what is tested here is the wire shape the SPA is built against.
 */
@QuarkusTest
class EpicLifecycleApiTest {

  private String createProject() {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Lifecycle Project", null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest(title, "The spine"))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("REPORTED"))
        .body("epic.supersededByEpicId", nullValue())
        .extract()
        .path("epic.id");
  }

  private ValidatableResponse transition(String epicId, String target) {
    return given()
        .contentType(ContentType.JSON)
        .body(new EpicController.TransitionEpicRequest(target))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then();
  }

  @Test
  void freezingAnEpicReturnsItWithoutASuccessor() {
    String epicId = createEpic(createProject(), "Planning domain");

    transition(epicId, "REFINED")
        .statusCode(200)
        .body("epic.id", equalTo(epicId))
        .body("epic.status", equalTo("REFINED"))
        .body("successor", nullValue());

    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("REFINED"));
  }

  @Test
  void supersedingReturnsTheSuccessorDraftAndLinksTheOldEpicToIt() {
    String projectId = createProject();
    String epicId = createEpic(projectId, "Planning domain");
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("Feature A", null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(200);
    transition(epicId, "REFINED").statusCode(200);

    String successorId =
        transition(epicId, "SUPERSEDED")
            .statusCode(200)
            .body("epic.status", equalTo("DROPPED"))
            .body("epic.supersededByEpicId", notNullValue())
            .body("successor.id", not(equalTo(epicId)))
            .body("successor.status", equalTo("REPORTED"))
            .body("successor.projectId", equalTo(projectId))
            .body("successor.title", equalTo("Planning domain"))
            .body("successor.supersededByEpicId", nullValue())
            .extract()
            .path("successor.id");

    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.supersededByEpicId", equalTo(successorId));

    // The copied scope came with it, slugs kept.
    given()
        .when()
        .get("/projects/api/epics/" + successorId + "/features")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries[0].feature.slug", equalTo("feature-a"));
  }

  @Test
  void anIllegalOrUnknownTargetIs409() {
    String epicId = createEpic(createProject(), "Planning domain");

    // A draft has no scope to supersede.
    transition(epicId, "SUPERSEDED")
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", notNullValue());
    // DONE is a word of the lifecycle, but nothing is skipped: a draft is not closed in one move.
    transition(epicId, "DONE").statusCode(Response.Status.CONFLICT.getStatusCode());
    // A retired epic word names no status at all.
    transition(epicId, "REFINING").statusCode(Response.Status.CONFLICT.getStatusCode());
    // Freezing is reversible now, one step at a time — and never to where the epic already is.
    transition(epicId, "REFINED").statusCode(200);
    transition(epicId, "REPORTED").statusCode(200).body("epic.status", equalTo("REPORTED"));
    transition(epicId, "REPORTED").statusCode(Response.Status.CONFLICT.getStatusCode());
  }

  /** What qits-392 exists for: over the wire, an epic reaches VERIFIED and DONE. */
  @Test
  void anEpicWalksToVerifiedAndDone() {
    String epicId = createEpic(createProject(), "Planning domain");
    for (String target : new String[] {"REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE"}) {
      transition(epicId, target).statusCode(200).body("epic.status", equalTo(target));
    }
    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("DONE"));
  }

  /**
   * DONE is final: over the epic's own transition route every target is refused — the step back to
   * VERIFIED, DROPPED and the supersede operation included — and the refusal says a follow-up is a
   * new epic rather than a reopened one.
   */
  @Test
  void aDoneEpicRefusesEveryTarget() {
    String epicId = createEpic(createProject(), "Planning domain");
    for (String target : new String[] {"REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE"}) {
      transition(epicId, target).statusCode(200);
    }
    for (String target :
        new String[] {"REPORTED", "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED",
            "SUPERSEDED"}) {
      transition(epicId, target)
          .statusCode(Response.Status.CONFLICT.getStatusCode())
          .body("message", containsString("DONE is final"))
          .body("message", containsString("a follow-up is a new ticket or epic"));
    }
    given()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("DONE"))
        .body("epic.supersededByEpicId", nullValue());
  }

  /**
   * The scope freeze, reopened along the graph. Adding a feature is the structural write asked
   * here: the epic's own retitle no longer has a per-archetype door ({@code PUT /epics/{id}} went in
   * qits-399), and the freeze on it is the same {@code EntityLifecycle.requireReported} this call
   * meets — {@code update_epic}'s, pinned in the entities module's lifecycle suite.
   */
  @Test
  void movingAFrozenEpicBackToReportedReopensItsScope() {
    String epicId = createEpic(createProject(), "Planning domain");
    transition(epicId, "REFINED").statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("Feature A", null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", org.hamcrest.Matchers.containsString("REPORTED"));

    transition(epicId, "REPORTED").statusCode(200);

    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("Feature A", null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(200)
        .body("feature.title", equalTo("Feature A"));
  }

  @Test
  void aFrozenEpicRefusesStructuralEditsAndTakesItsMarkers() {
    String projectId = createProject();
    String epicId = createEpic(projectId, "Planning domain");
    String featureId =
        given()
            .contentType(ContentType.JSON)
            .body(new EpicController.CreateFeatureRequest("Feature A", null, null))
            .when()
            .post("/projects/api/epics/" + epicId + "/features")
            .then()
            .statusCode(200)
            .extract()
            .path("feature.id");

    // While a draft, the marker is the thing that is refused.
    given()
        .contentType(ContentType.JSON)
        .body(
            new FeatureController.UpdateFeatureRequest(
                null, null, null, false, Instant.parse("2026-07-25T10:15:30Z"), false))
        .when()
        .put("/projects/api/features/" + featureId)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());

    transition(epicId, "REFINED").statusCode(200);
    // Frozen, but not yet scheduled: the marker waits for a person (qits-887).
    given()
        .contentType(ContentType.JSON)
        .body(
            new FeatureController.UpdateFeatureRequest(
                null, null, null, false, Instant.parse("2026-07-25T10:15:30Z"), false))
        .when()
        .put("/projects/api/features/" + featureId)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());
    transition(epicId, "READY_FOR_DEV").statusCode(200);

    // Frozen and scheduled: a structural write is refused and the marker goes through.
    given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest("Feature B", null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());
    given()
        .contentType(ContentType.JSON)
        .body(
            new FeatureController.UpdateFeatureRequest(
                null, null, null, false, Instant.parse("2026-07-25T10:15:30Z"), false))
        .when()
        .put("/projects/api/features/" + featureId)
        .then()
        .statusCode(200)
        .body("feature.implementedOn", notNullValue());
  }

  @Test
  void theEpicsListFiltersByStatus() {
    String projectId = createProject();
    String draftId = createEpic(projectId, "Still drafting");
    String frozenId = createEpic(projectId, "Being built");
    transition(frozenId, "REFINED").statusCode(200);

    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries", hasSize(2));
    given()
        .queryParam("status", "REPORTED")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries.epic.id", hasItem(draftId));
    given()
        .queryParam("status", "REFINED")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries.epic.id", hasItem(frozenId));
    given()
        .queryParam("status", "DROPPED")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries", hasSize(0));
    // A typo must not read as "no epics".
    given()
        .queryParam("status", "done")
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  void aTransitionIsAuditedOnTheEpic() {
    String epicId = createEpic(createProject(), "Planning domain");
    transition(epicId, "DROPPED").statusCode(200);

    given()
        .when()
        .get("/projects/api/epics/" + epicId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("UPDATE"));
  }
}
