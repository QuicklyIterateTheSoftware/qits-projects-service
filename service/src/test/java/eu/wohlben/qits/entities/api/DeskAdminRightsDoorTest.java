package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The delivery desk with {@code qits:desk-admin}</b> (epic qits-1621): its {@code desk-delivery}
 * token now carries the coordinator's platform rights beside {@code qits:agent} and {@code
 * qits:desk}. It reaches what {@code qits:admin-agent} reaches — the doors a plain desk was refused
 * included — yet stays bound to its own project wherever a door binds the desk, and never reaches
 * its own rights: its brief, its runners, the MCP catalogue, a project's create or delete.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}), as in {@link DeskCoordinatorRightsDoorTest}: the
 * desk is the token qits-idp mints for it, validated as deployed.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class DeskAdminRightsDoorTest {

  /** A person's browser session as the edge forwards it. */
  private static RequestSpecification person() {
    return given()
        .header("X-Qits-User", "ada")
        .header("X-Qits-Roles", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada"))
        .contentType(ContentType.JSON);
  }

  /** The project's delivery desk as qits-idp grants it since qits-1621. */
  private static RequestSpecification deskAdmin(String projectId) {
    return desk(projectId, "qits:agent", "qits:desk", "qits:desk-admin");
  }

  /** The desk as it was before qits-1621, for the contrast. */
  private static RequestSpecification plainDesk(String projectId) {
    return desk(projectId, "qits:agent", "qits:desk");
  }

  private static RequestSpecification desk(String projectId, String... roles) {
    String subject = "desk-of-" + projectId;
    String[] groups = java.util.Arrays.copyOf(roles, roles.length + 1);
    groups[roles.length] = "clients/" + subject;
    String token =
        MockIdp.attach()
            .token()
            .subject(subject)
            .audience("qits-platform")
            .groups(groups)
            .claim("context_kind", "desk-delivery")
            .claim("project", projectId)
            .mint();
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String project(String name) {
    return person()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private static String ticket(String projectId) {
    return person()
        .body(
            Map.of(
                "archetype", "TICKET",
                "project", projectId,
                "title", "Filed",
                "ticketType", "BUG",
                "impetus", "it occurs",
                "acceptanceCriteria", List.of("It no longer occurs.")))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  // --- what it gained ---------------------------------------------------------------------------

  /**
   * A door that admitted {@code qits:admin-agent} and not the desk — the front desk's last answer —
   * now lets the desk-admin past its role gate: an unknown project answers the door's own 404, where
   * the plain desk is still a 403. The MCP catalogue reads, as before.
   */
  @Test
  void theDeskAdminReachesTheCoordinatorsDoors() {
    String unknown = "no-such-project";
    plainDesk(unknown)
        .when()
        .get("/projects/api/projects/" + unknown + "/front-desk/last-answer")
        .then()
        .statusCode(403);
    deskAdmin(unknown)
        .when()
        .get("/projects/api/projects/" + unknown + "/front-desk/last-answer")
        .then()
        .statusCode(404);

    String projectId = project("Desk admin reads");
    deskAdmin(projectId).when().get("/projects/api/agent-mcp-catalog").then().statusCode(200);
  }

  // --- what stays bound ---------------------------------------------------------------------------

  /** A work write in another project is a 403: the desk-admin is still a desk, bound to its own. */
  @Test
  void theDeskAdminWritesWorkInItsOwnProjectOnly() {
    String projectId = project("Desk admin writes");
    String otherId = project("Desk admin writes elsewhere");
    String ticket = ticket(projectId);

    deskAdmin(otherId)
        .body(Map.of("title", "Taken over"))
        .when()
        .patch("/projects/api/work/" + ticket)
        .then()
        .statusCode(403);
    deskAdmin(otherId).when().delete("/projects/api/work/" + ticket).then().statusCode(403);

    deskAdmin(projectId)
        .body(Map.of("title", "Kept moving by its desk"))
        .when()
        .patch("/projects/api/work/" + ticket)
        .then()
        .statusCode(200);
  }

  // --- its own rights -----------------------------------------------------------------------------

  /**
   * Its brief is refused in the body, before the request is even validated; another surface is
   * still the coordinator's to edit, so the same malformed body answers that surface's 400.
   */
  @Test
  void theDeskAdminNeverEditsItsOwnBrief() {
    String projectId = project("Desk admin brief");
    Map<String, Object> body = Map.of("harness", "no-such-harness", "systemPrompt", "Anything.");

    deskAdmin(projectId)
        .body(body)
        .when()
        .put("/projects/api/agent-surfaces/project.delivery")
        .then()
        .statusCode(403)
        .body("message", containsString("own brief"));
    deskAdmin(projectId)
        .body(body)
        .when()
        .put("/projects/api/agent-surfaces/project.work")
        .then()
        .statusCode(400);
  }

  /**
   * A variant of its own brief's key — different case, padded with whitespace — must not slip past
   * the guard either (qits-1621 follow-up): the comparison is trimmed and case-insensitive, matching
   * how {@code AgentSurfaceConfigurationService} itself keys surfaces on the trimmed string.
   */
  @Test
  void theDeskAdminNeverEditsItsOwnBriefByADifferentlyCasedKey() {
    String projectId = project("Desk admin brief case variant");
    Map<String, Object> body = Map.of("harness", "no-such-harness", "systemPrompt", "Anything.");

    deskAdmin(projectId)
        .body(body)
        .when()
        .put("/projects/api/agent-surfaces/PROJECT.DELIVERY")
        .then()
        .statusCode(403)
        .body("message", containsString("own brief"));
    deskAdmin(projectId)
        .body(body)
        .when()
        .put("/projects/api/agent-surfaces/Project.Delivery")
        .then()
        .statusCode(403)
        .body("message", containsString("own brief"));
    deskAdmin(projectId)
        .body(body)
        .when()
        .put("/projects/api/agent-surfaces/{surface}", " project.delivery ")
        .then()
        .statusCode(403)
        .body("message", containsString("own brief"));
  }

  /**
   * The agent-container doors and the front desk's prompt/last-answer are bound to the desk-admin's
   * own project (qits-1621 follow-up): another project's container is a 403, its own is not refused
   * by the binding.
   */
  @Test
  void theDeskAdminManagesOnlyItsOwnProjectsContainer() {
    String projectId = project("Desk admin container");
    String otherId = project("Desk admin container elsewhere");

    deskAdmin(otherId)
        .when()
        .post("/projects/api/projects/" + projectId + "/agent-container/ensure")
        .then()
        .statusCode(403);
    deskAdmin(otherId)
        .when()
        .post("/projects/api/projects/" + projectId + "/agent-container/stop")
        .then()
        .statusCode(403);
    deskAdmin(otherId)
        .when()
        .delete("/projects/api/projects/" + projectId + "/agent-container")
        .then()
        .statusCode(403);
    deskAdmin(otherId)
        .body(Map.of("text", "hello"))
        .when()
        .post("/projects/api/projects/" + projectId + "/front-desk/prompt")
        .then()
        .statusCode(403);
    deskAdmin(otherId)
        .when()
        .get("/projects/api/projects/" + projectId + "/front-desk/last-answer")
        .then()
        .statusCode(403);

    // The binding never refuses the desk-admin acting on its own project: whatever each door
    // answers on an ordinary run (success, or an unreachable daemon/runner), it must not be the
    // binding's 403.
    assertNotEquals(
        403,
        deskAdmin(projectId)
            .when()
            .post("/projects/api/projects/" + projectId + "/agent-container/ensure")
            .then()
            .extract()
            .statusCode());
    assertNotEquals(
        403,
        deskAdmin(projectId)
            .when()
            .post("/projects/api/projects/" + projectId + "/agent-container/stop")
            .then()
            .extract()
            .statusCode());
    assertNotEquals(
        403,
        deskAdmin(projectId)
            .body(Map.of("text", "hello"))
            .when()
            .post("/projects/api/projects/" + projectId + "/front-desk/prompt")
            .then()
            .extract()
            .statusCode());
    assertNotEquals(
        403,
        deskAdmin(projectId)
            .when()
            .get("/projects/api/projects/" + projectId + "/front-desk/last-answer")
            .then()
            .extract()
            .statusCode());
    assertNotEquals(
        403,
        deskAdmin(projectId)
            .when()
            .delete("/projects/api/projects/" + projectId + "/agent-container")
            .then()
            .extract()
            .statusCode());
  }

  /** Its runners, the MCP catalogue and a project's create or delete stay shut to it. */
  @Test
  void theDeskAdminNeverChangesItsOwnRightsOrCommits() {
    String projectId = project("Desk admin rights");

    deskAdmin(projectId)
        .body(Map.of("name", "my-own-runner"))
        .when()
        .post("/projects/api/runners")
        .then()
        .statusCode(403);
    deskAdmin(projectId)
        .body(Map.of("displayName", "More tools", "url", "https://tools.example"))
        .when()
        .put("/projects/api/agent-mcp-catalog/more-tools")
        .then()
        .statusCode(403);
    deskAdmin(projectId).when().delete("/projects/api/agent-mcp-catalog/qits").then().statusCode(403);
    deskAdmin(projectId)
        .body(new ProjectController.CreateProjectRequest("Desk made", null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(403);
    deskAdmin(projectId).when().delete("/projects/api/projects/" + projectId).then().statusCode(403);
  }
}
