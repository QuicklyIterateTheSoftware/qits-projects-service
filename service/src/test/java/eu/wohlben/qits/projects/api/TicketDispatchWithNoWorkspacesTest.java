package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code WorkspaceAgentDispatch} absent is a supported configuration</b>, and this is the only
 * place it can be shown: every other suite here has both the {@code @DefaultBean} HTTP adapter and
 * the recording double on the classpath, so the injection point is always resolvable there.
 *
 * <p>The absence is made by config rather than by a missing class — {@code
 * quarkus.arc.exclude-types} naming both implementations, {@code
 * ReleaseWithNoWorkspaceResolutionTest}'s shape — which costs one extra augmentation and is why this
 * is one small class instead of a second copy of the door's suite.
 *
 * <p>What it proves is the sentence in the port's javadoc, and the second half matters as much as
 * the first: an assembly with no workspaces context answers <b>503</b> naming what is missing, and
 * the ticket is left exactly as it was — no comment claiming an agent is on it.
 */
@QuarkusTest
@TestProfile(TicketDispatchWithNoWorkspacesTest.NoWorkspaceAgentDispatchProfile.class)
public class TicketDispatchWithNoWorkspacesTest {

  /** Both implementations gone, so the {@code Instance<T>} is genuinely unresolvable. */
  public static class NoWorkspaceAgentDispatchProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.arc.exclude-types",
          "eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch,"
              + "eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentDispatch");
    }
  }

  /** The premise of every assertion below, so a config override that stopped working says so. */
  @Inject Instance<WorkspaceAgentDispatch> port;

  private RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:admin");
  }

  @Test
  public void theDoorAnswers503AndTheTicketIsLeftAlone() {
    assertFalse(
        port.isResolvable(),
        "the point of this class is an unresolvable port; the exclude-types override stopped"
            + " working, and everything below would be asserting nothing");

    String projectId =
        asAdmin()
            .body(
                new ProjectController.CreateProjectRequest(
                    "No Workspaces Here", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    String ticketId =
        asAdmin()
            .body(Map.of("title", "Nobody to dispatch", "type", "BUG"))
            .when()
            .post("/projects/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(200)
            .extract()
            .path("ticket.id");

    asAdmin()
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dispatch-agent")
        .then()
        .statusCode(503)
        .body("message", containsString("No workspaces context is configured"));

    asAdmin()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));
  }
}
