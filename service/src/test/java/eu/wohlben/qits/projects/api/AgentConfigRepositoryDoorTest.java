package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.security.NoDevUserProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/projects/{projectId}/agent-config-repository} (qits-1382): the one door
 * that mints a project's {@code qits-agent-config}, and who may press it.
 *
 * <p>Under {@link NoDevUserProfile} for {@link ProjectRepositoryCreateDoorTest}'s reason — the
 * {@code %test} dev user holds every role, so a refusal is only observable with it blanked — and
 * with that class's caller matrix, because the roles here are the create door's and must stay so.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
public class AgentConfigRepositoryDoorTest {

  private RequestSpecification as(String user, String roles) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", roles);
  }

  private RequestSpecification session() {
    return as("alice", "qits:admin");
  }

  private String createProject(String name) {
    return session()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private io.restassured.response.Response press(RequestSpecification as, String projectId) {
    return as.when().post("/projects/api/projects/" + projectId + "/agent-config-repository");
  }

  /**
   * 201 the first time, 200 with the same repository after — no body either way, and the answer is
   * the repository the listing shows: the kind, the name, no backup twin, no component.
   */
  @Test
  public void theFirstPressCreatesAndEveryLaterOneAnswersTheSameRepository() {
    String projectId = createProject("Agent Config Door");

    String id =
        press(session(), projectId)
            .then()
            .statusCode(Response.Status.CREATED.getStatusCode())
            .body("id", notNullValue())
            .body("name", equalTo("qits-agent-config"))
            .body("archetype", equalTo("AGENT_CONFIG"))
            .body("mainBranch", equalTo("main"))
            .body("backupUrl", nullValue())
            .body("component", nullValue())
            .extract()
            .path("id");

    press(session(), projectId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("id", equalTo(id))
        .body("archetype", equalTo("AGENT_CONFIG"));

    // Listed beside the wrapper, and the wrapper still declares nothing: no .gitmodules entry.
    session()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(2))
        .body("wrapper.entries", hasSize(0));
  }

  /** The create door's matrix: an admin and an agent in, a machine and a stranger out. */
  @Test
  public void onlyAnAdminAndAnAgentGetIn() {
    String projectId = createProject("Agent Config Door Matrix");

    press(as("dev-qits-bootstrap", "qits:system"), projectId)
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
    press(as("mallory", "qits:user"), projectId)
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
    // No identity is the challenge's 401 — and with no content type either, since the door takes
    // no body and so is not a 415 for want of one.
    given()
        .when()
        .post("/projects/api/projects/" + projectId + "/agent-config-repository")
        .then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());

    press(as("agent-qits-1382", "qits:agent"), projectId)
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());
    press(as("bob", "qits:admin-agent"), projectId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    press(session(), projectId).then().statusCode(Response.Status.OK.getStatusCode());
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .post("/projects/api/projects/" + projectId + "/agent-config-repository")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void anUnknownProjectIsA404() {
    press(session(), "no-such-project")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }
}
