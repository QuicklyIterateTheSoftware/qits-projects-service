package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The REST round-trip for {@code POST /projects/api/entities/transition} — the write surface of the
 * merged model.
 *
 * <p>What this class is for, as distinct from {@code EntityTransitionServiceTest}: the route, the
 * body shape both ways, and the refusal reaching a caller as one 400 through {@link
 * EpicsExceptionMapper}. Every rule behind the refusal is asserted over there, where it costs no
 * HTTP round trip to state one.
 *
 * <p>The request body <b>is</b> the map — {@code {"<id>": {…}}} — and the answer is the same map
 * keyed the same way, which is the symmetry that lets a caller put its statement and the result side
 * by side.
 */
@QuarkusTest
class EntityTransitionApiTest {

  private String createProject() {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(
                "Transition Project", null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest(title, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("epic.id");
  }

  private String createFeature(String epicId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(new EpicController.CreateFeatureRequest(title, null, null))
        .when()
        .post("/projects/api/epics/" + epicId + "/features")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("feature.id");
  }

  /**
   * A feature is promoted to an epic in one request. The route answers the post-state keyed by id,
   * and the promoted row is a root with a phase of its own.
   */
  @Test
  void aPromotionIsOneRequestAndAnswersThePostStateKeyedById() {
    String projectId = createProject();
    String epicId = createEpic(projectId, "The plan");
    String featureId = createFeature(epicId, "The part");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        featureId,
        Map.of(
            "archetype", "EPIC",
            "title", "The part",
            "status", "REFINING",
            "membership", mapWithNullParent()));

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/projects/api/entities/transition")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("'" + featureId + "'.archetype", equalTo("EPIC"))
        .body("'" + featureId + "'.status", equalTo("REFINING"))
        .body("'" + featureId + "'.parent", nullValue())
        .body("'" + featureId + "'.slugScope", equalTo(projectId))
        .body("'" + featureId + "'.position", nullValue());
  }

  /**
   * A refused post-state is one 400 carrying every complaint, joined with {@code "; "} — including
   * the ones that are really "no such thing", which are deliberately not 404s thrown one at a time.
   */
  @Test
  void everyViolationComesBackInOne400() {
    String projectId = createProject();
    String epicId = createEpic(projectId, "The plan");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put(epicId, Map.of("archetype", "EPIC", "title", "The plan")); // no status stated
    body.put("ghost-id", Map.of("archetype", "EPIC", "title", "A ghost", "status", "REFINING"));

    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/projects/api/entities/transition")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("there is no ghost-id"))
        .body("message", containsString("requires status"));
  }

  /** An empty map is a malformed request rather than a transition that does nothing. */
  @Test
  void anEmptyTransitionIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of())
        .when()
        .post("/projects/api/entities/transition")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("at least one entity"));
  }

  /** {@code Map.of} refuses a null value, and an explicit null parent is the statement "a root". */
  private static Map<String, Object> mapWithNullParent() {
    Map<String, Object> membership = new LinkedHashMap<>();
    membership.put("parent", null);
    membership.put("position", null);
    return membership;
  }
}
