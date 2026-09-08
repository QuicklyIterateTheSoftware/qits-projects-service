package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code WorkspaceAgentDispatch} absent, for the epic door — {@link
 * TicketDispatchWithNoWorkspacesTest}'s reasoning and its {@code quarkus.arc.exclude-types} profile,
 * reused rather than restated, because that is the only way to make the injection point genuinely
 * unresolvable in a suite that has both implementations on the classpath.
 *
 * <p>The second half of the assertion is what makes this worth its own class rather than a line in
 * the sibling: an assembly with no workspaces context answers <b>503</b>, and the epic is <b>still
 * REFINING</b>. The door transitions before it dispatches, so this is the one place that pins where
 * that "before" starts — a configuration that was never going to work is not a dispatch that failed,
 * and it must not freeze somebody's scope on the way to saying so.
 */
@QuarkusTest
@TestProfile(TicketDispatchWithNoWorkspacesTest.NoWorkspaceAgentDispatchProfile.class)
public class EpicDispatchWithNoWorkspacesTest {

  /** The premise of every assertion below, so a config override that stopped working says so. */
  @Inject Instance<WorkspaceAgentDispatch> port;

  private RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:admin");
  }

  @Test
  public void theDoorAnswers503AndTheEpicIsLeftRefining() {
    assertFalse(
        port.isResolvable(),
        "the point of this class is an unresolvable port; the exclude-types override stopped"
            + " working, and everything below would be asserting nothing");

    String projectId =
        asAdmin()
            .body(
                new ProjectController.CreateProjectRequest(
                    "No Workspaces For Epics", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    String epicId =
        asAdmin()
            .body(Map.of("title", "Nobody to dispatch", "description", "Nobody home."))
            .when()
            .post("/projects/api/projects/" + projectId + "/epics")
            .then()
            .statusCode(200)
            .extract()
            .path("epic.id");

    asAdmin()
        .when()
        .post("/projects/api/epics/" + epicId + "/dispatch-agent")
        .then()
        .statusCode(503)
        .body("message", containsString("No workspaces context is configured"));

    asAdmin()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("REFINING"));
  }
}
