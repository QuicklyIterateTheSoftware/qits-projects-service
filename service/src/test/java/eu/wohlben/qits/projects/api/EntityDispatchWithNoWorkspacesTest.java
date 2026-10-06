package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.workspacehost.NoWorkspacesContextProfile;
import io.quarkus.test.junit.QuarkusTest;
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
 * quarkus.arc.exclude-types} naming both implementations, in {@link NoWorkspacesContextProfile},
 * which {@code ReleaseWithNoWorkspaceResolutionTest} shares — which costs one extra augmentation and
 * is why this is one small class instead of a second copy of the door's suite.
 *
 * <p>What it proves is the sentence in the port's javadoc, and the second half matters as much as
 * the first: an assembly with no workspaces context answers <b>503</b> naming what is missing, and
 * the entity is left exactly as it was — no comment claiming an agent is on a ticket, and no status
 * moved on an epic.
 *
 * <p>One class for both archetypes, because since qits-399 there is one door: the ticket's and the
 * epic's retired {@code dispatch-agent} routes each had a class of their own on this profile, and
 * both cases now press {@code POST /entities/{id}/dispatch}.
 */
@QuarkusTest
@TestProfile(NoWorkspacesContextProfile.class)
public class EntityDispatchWithNoWorkspacesTest {

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
    assertThePortIsAbsent();

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
            .body(Map.of(
                    "title",
                    "Nobody to dispatch",
                    "type",
                    "BUG",
                    "impetus",
                    "something occurs in this project"))
            .when()
            .post("/projects/api/projects/" + projectId + "/tickets")
            .then()
            .statusCode(200)
            .extract()
            .path("ticket.id");

    asAdmin()
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/entities/" + ticketId + "/dispatch")
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

  @Test
  public void theDoorAnswers503AndTheEpicIsLeftWhereItWas() {
    assertThePortIsAbsent();

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
        .body(Map.of("mode", "PHASE"))
        .when()
        .post("/projects/api/entities/" + epicId + "/dispatch")
        .then()
        .statusCode(503)
        .body("message", containsString("No workspaces context is configured"));

    asAdmin()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.status", equalTo("REPORTED"));
  }

  /** The premise of every assertion here, so a config override that stopped working says so. */
  private void assertThePortIsAbsent() {
    assertFalse(
        port.isResolvable(),
        "the point of this class is an unresolvable port; the exclude-types override stopped"
            + " working, and everything below would be asserting nothing");
  }
}
