package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * <b>Who may press {@code POST /projects/api/projects/{projectId}/repositories}.</b> An agent may
 * add a component to a project; that is the widening this class is about, and the half that matters
 * more is the one that says who still may not.
 *
 * <p><b>It runs with the dev user blanked, and that is what makes any of it observable.</b> The
 * {@code %test} dev user qits-auth-core shipped held every platform role, so a plain {@code given()}
 * was an administrator and a route refusing a caller could not be seen. The suite's test config
 * blanks it (epic qits-543; the user itself goes with qits-1338), so an identity is exactly the
 * caller a request names ({@code ContractBearers.caller}), which is the deployed posture.
 *
 * <p><b>Both halves in one class, deliberately.</b> A method-level {@code @RolesAllowed} REPLACES
 * the class-level {@code qits:admin} rather than adding to it, and this repository has shipped that
 * defect in both directions — so asserting only that an agent gets in would pass just as well
 * against a route annotated {@code qits:agent} alone, which is a 403 for every person and for the
 * SPA's create form. {@link #onlyAnAdminAndAnAgentGetIn} drives four different callers at the one
 * route and each has to land where it is supposed to.
 */
@QuarkusTest
public class ProjectRepositoryCreateDoorTest {

  /** A browser session as the edge forwards it. */
  private RequestSpecification session() {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("alice", "qits:admin"));
  }

  /** An agent's own credential: {@code qits:agent} and nothing beside it. */
  private RequestSpecification agent() {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("agent-qits-291", "qits:agent"));
  }

  /** A machine bearer — the bootstrap's kind of caller, whose door here is {@code …/adopt}. */
  private RequestSpecification machine() {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("dev-qits-bootstrap", "qits:system"));
  }

  /** Authenticated and holding nothing this surface names. */
  private RequestSpecification stranger() {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("mallory", "qits:user"));
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

  private io.restassured.response.Response create(
      RequestSpecification as, String projectId, String name) {
    return as.body(new ProjectController.CreateProjectRepositoryRequest(null, name, null, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories");
  }

  /**
   * The grant, and it is a real create rather than a status code: the row exists, the archetype is
   * the one stated and the wrapper declares it. A door that answered 200 and did nothing would
   * satisfy a bare status assertion.
   */
  @Test
  public void anAgentCreatesAComponentOfItsProject() {
    String projectId = createProject("Agent Create Grant");

    agent()
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                null,
                "storefront-app",
                eu.wohlben.qits.projects.entity.RepositoryArchetype.APP,
                "storefront"))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repository.id", notNullValue())
        .body("repository.archetype", equalTo("APP"))
        .body("wrapperPath", equalTo("components/storefront/storefront-app"));

    // The wrapper really names it — the create's last step ran, not just its first.
    agent()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("wrapper.entries[0].name", equalTo("storefront-app"));
  }

  /**
   * The four callers at the one route. The two refusals are what stops a later careless edit
   * widening the whole class, and the two grants are what stops one narrowing it: dropping {@code
   * qits:admin} from the method list is a 403 for every browser at exactly this route and nowhere
   * else, which is invisible until a person tries to add a component.
   */
  @Test
  public void onlyAnAdminAndAnAgentGetIn() {
    String projectId = createProject("Create Door Matrix");

    create(session(), projectId, "by-admin-service")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    create(agent(), projectId, "by-agent-service")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // qits:system is the adopt door's role, not this one — and it is not in the method's list.
    create(machine(), projectId, "by-machine-service")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
    create(stranger(), projectId, "by-stranger-service")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());

    // No identity at all is the other door: 401 at the mechanism's challenge, not 403.
    given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRepositoryRequest(null, "by-nobody-service", null, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());

    // And the refusals refused rather than merely answered: only the two grants left a row.
    session()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("wrapper.entries", hasSize(2));
  }

  /**
   * The class-level default did not move. {@code adoptRepository} is {@code qits:system} alone and
   * still refuses the agent this feature let through the neighbouring route — the two annotations
   * are independent, and a widening that leaked would show up here first.
   */
  @Test
  public void theAdoptDoorIsUnmovedAndStillRefusesTheAgent() {
    String projectId = createProject("Adopt Door Unmoved");

    agent()
        .body(
            new ProjectController.AdoptProjectRepositoryRequest(
                "some-storage-id",
                "adopted",
                null,
                eu.wohlben.qits.projects.entity.RepositoryArchetype.SERVICE))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories/adopt")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }
}
