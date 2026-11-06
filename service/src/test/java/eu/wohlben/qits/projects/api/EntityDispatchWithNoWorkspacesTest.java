package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.contracts.ContractBearers;
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
 * both cases now press {@code POST /work/{qualifiedId}/dispatch} (qits-976 retired {@code
 * /entities/{id}/dispatch}).
 */
@QuarkusTest
@TestProfile(NoWorkspacesContextProfile.class)
public class EntityDispatchWithNoWorkspacesTest {

  /** The premise of every assertion below, so a config override that stopped working says so. */
  @Inject Instance<WorkspaceAgentDispatch> port;

  private RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("mallory", "qits:admin"));
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
        WorkRequests.ticket(
            this::asAdmin,
            projectId,
            "Nobody to dispatch",
            "BUG",
            "something occurs in this project");

    asAdmin()
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + ticketId + "/dispatch")
        .then()
        .statusCode(503)
        .body("message", containsString("No workspaces context is configured"));

    asAdmin()
        .when()
        .get("/projects/api/work/" + ticketId + "/comments")
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
        WorkRequests.create(
                this::asAdmin,
                WorkRequests.map(
                    "archetype", "EPIC",
                    "project", projectId,
                    "title", "Nobody to dispatch",
                    "description", "Nobody home."))
            .path("id");

    asAdmin()
        .body(Map.of("mode", "PHASE"))
        .when()
        .post("/projects/api/work/" + epicId + "/dispatch")
        .then()
        .statusCode(503)
        .body("message", containsString("No workspaces context is configured"));

    asAdmin()
        .when()
        .get("/projects/api/work/" + epicId)
        .then()
        .statusCode(200)
        .body("status", equalTo("REPORTED"));
  }

  // --- needs and conditions (qits-1903): refused before the port is asked for -----------------

  /** A person, for the scheduling REFINED → READY_FOR_DEV (qits-887). */
  private RequestSpecification asPerson() {
    return asAdmin()
        .cookie(
            eu.wohlben.qits.projects.security.PersonCheck.SESSION_COOKIE,
            eu.wohlben.qits.projects.security.FakeSessionIntrospection.admin("mallory"));
  }

  private String project(String name) {
    return asAdmin()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String ticket(String projectId, String title) {
    String id = WorkRequests.ticket(this::asAdmin, projectId, title, "BUG", "something occurs here");
    patch(id, Map.of("acceptanceCriteria", eu.wohlben.qits.entities.api.TestCriteria.CRITERIA));
    return id;
  }

  private void patch(String id, Map<String, Object> body) {
    asAdmin()
        .contentType("application/merge-patch+json")
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then()
        .statusCode(200);
  }

  private void move(String id, String target) {
    WorkRequests.status(this::asPerson, id, target).then().statusCode(200);
  }

  private io.restassured.response.ValidatableResponse press(String id) {
    return asAdmin()
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + id + "/dispatch")
        .then();
  }

  /**
   * An unmet need at READY_FOR_DEV is the press's 409, decided before the port is asked for — so
   * even an assembly with no workspaces answers the need, not the 503; met, the press goes on to
   * the 503; and at REPORTED the need never stood in the way.
   */
  @Test
  public void anUnmetNeedRefusesBeforeThePortAndAMetOrAReportedOneReachesIt() {
    assertThePortIsAbsent();
    String projectId = project("No Workspaces Needs");
    String waiting = ticket(projectId, "Waits");
    String needed = ticket(projectId, "First");
    patch(waiting, Map.of("needs", java.util.List.of(Map.of("item", needed, "status", "REFINED"))));

    press(waiting)
        .statusCode(503)
        .body("message", containsString("No workspaces context is configured"));

    move(waiting, "REFINED");
    move(waiting, "READY_FOR_DEV");
    press(waiting)
        .statusCode(409)
        .body("message", containsString(" at REFINED (now REPORTED), so its implementation does"))
        .body("message", containsString("Dispatch it once those are met, or change its needs."));

    move(needed, "REFINED");
    press(waiting)
        .statusCode(503)
        .body("message", containsString("No workspaces context is configured"));
  }

  /** A condition is refused before the port is asked for, too. */
  @Test
  public void aConditionIsRefusedBeforeThePort() {
    assertThePortIsAbsent();
    String projectId = project("No Workspaces Condition");
    String condition = ticket(projectId, "Bootstrap test node");
    patch(condition, Map.of("tags", java.util.List.of("CONDITION")));

    press(condition)
        .statusCode(409)
        .body("message", containsString(" is tagged condition: other work waits for it"));
  }

  /** The premise of every assertion here, so a config override that stopped working says so. */
  private void assertThePortIsAbsent() {
    assertFalse(
        port.isResolvable(),
        "the point of this class is an unresolvable port; the exclude-types override stopped"
            + " working, and everything below would be asserting nothing");
  }
}
