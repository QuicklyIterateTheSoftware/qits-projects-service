package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Resolving an epic tears its refinement down, wherever the move is made from. The move goes
 * through the plain REST door here on purpose: that is the door the epics board, the refining page
 * and any machine caller all reach, and the bug this pins was that the cleanup lived in one
 * browser screen instead — an epic marked Implemented from the board kept its container, volume,
 * credential and {@code refining/<slug>} branch for good.
 */
@QuarkusTest
public class EpicResolutionCleanupTest {

  @Inject FakeRefinementRuntime runtime;
  @Inject FakeRefinementCredentials credentials;
  @Inject RefinementService service;

  @BeforeEach
  void reset() {
    runtime.reset();
    credentials.reset();
  }

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return TestCriteria.give(
        WorkRequests.create(
                WorkRequests.map(
                    "archetype", "EPIC",
                    "project", projectId,
                    "title", title,
                    "description", "A draft."))
            .path("id"));
  }

  private long open(String epicId) {
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of())
            .when()
            .post("/projects/api/work/" + epicId + "/refinement")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  private io.restassured.response.Response transition(String epicId, String target) {
    return given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
        .contentType(ContentType.JSON)
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/work/" + epicId + "/status");
  }

  @Test
  public void abandoningAnEpicDiscardsItsRefinement() {
    String projectId = createProject("Resolve Abandon");
    String epicId = createEpic(projectId, "Abandoned Epic");
    long id = open(epicId);

    transition(epicId, "DROPPED").then().statusCode(200).body("status", equalTo("DROPPED"));

    assertTrue(runtime.calls().contains("delete:" + id), "the container is torn down");
    given().when().get("/projects/api/refinements/" + id).then().statusCode(404);
  }

  @Test
  public void markingAnEpicImplementedDiscardsItsRefinement() {
    String projectId = createProject("Resolve Implemented");
    String epicId = createEpic(projectId, "Implemented Epic");
    long id = open(epicId);

    // The freeze is not a resolution: the epic goes on being refined through it.
    transition(epicId, "REFINED").then().statusCode(200);
    transition(epicId, "READY_FOR_DEV").then().statusCode(200);
    given().when().get("/projects/api/refinements/" + id).then().statusCode(200);
    assertFalse(runtime.calls().contains("delete:" + id), "the freeze tears nothing down");

    transition(epicId, "IMPLEMENTED")
        .then()
        .statusCode(200)
        .body("status", equalTo("IMPLEMENTED"));

    assertTrue(runtime.calls().contains("delete:" + id));
    given().when().get("/projects/api/refinements/" + id).then().statusCode(404);
  }

  @Test
  public void supersedingAnEpicDiscardsItsRefinementAndLeavesTheSuccessorWithoutOne() {
    String projectId = createProject("Resolve Supersede");
    String epicId = createEpic(projectId, "Superseded Epic");
    long id = open(epicId);
    transition(epicId, "REFINED").then().statusCode(200);

    String successorId =
        transition(epicId, "SUPERSEDED")
            .then()
            .statusCode(200)
            .body("status", equalTo("DROPPED"))
            .body("supersededBy", notNullValue())
            .extract()
            .path("supersededBy");

    assertTrue(runtime.calls().contains("delete:" + id));
    given().when().get("/projects/api/refinements/" + id).then().statusCode(404);
    // The successor is a fresh draft; it gets its own refinement when somebody presses Refine.
    given()
        .when()
        .get("/projects/api/projects/" + projectId + "/refinements")
        .then()
        .statusCode(200)
        .body("refinements.findAll { it.epicId == '" + successorId + "' }.size()", equalTo(0));
  }

  @Test
  public void aRefusedMoveTearsNothingDown() {
    String projectId = createProject("Resolve Refused");
    String epicId = createEpic(projectId, "Refused Epic");
    long id = open(epicId);

    // REPORTED may only go to REFINED or DROPPED. The check runs before the teardown, so
    // a 409 leaves the refinement exactly where it was.
    transition(epicId, "IMPLEMENTED").then().statusCode(409);
    transition(epicId, "NOT_A_STATUS").then().statusCode(409);

    assertFalse(runtime.calls().contains("delete:" + id));
    given().when().get("/projects/api/refinements/" + id).then().statusCode(200);
  }

  @Test
  public void anEpicWithNoRefinementResolvesAsBefore() {
    String projectId = createProject("Resolve Bare");
    String epicId = createEpic(projectId, "Bare Epic");

    transition(epicId, "DROPPED").then().statusCode(200).body("status", equalTo("DROPPED"));
  }
}
