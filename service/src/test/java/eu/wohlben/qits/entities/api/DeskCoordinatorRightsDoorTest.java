package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The delivery desk holds the owner's coordinator's writes in its own project</b> (qits-1596):
 * its {@code desk-delivery} token ({@code qits:agent} and {@code qits:desk}, a {@code project}
 * claim) deletes and edits as {@code qits:admin-agent} does and answers 403 on another project's
 * rows. It never reaches the doors that would change its own rights: its brief and the front desk.
 * Scheduling stays the owner's: without the dispatch path's {@code start_refined} stamp the desk is
 * refused it like any machine.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}), as in {@link ScheduleNeedsAPersonDoorTest}: a
 * bearer is validated against a mock idp exactly as deployed, so the desk is the token qits-idp
 * mints for it and nothing a header asserts.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class DeskCoordinatorRightsDoorTest {

  /** A person's browser session as the edge forwards it. */
  private static RequestSpecification person() {
    return given()
        .header("X-Qits-User", "ada")
        .header("X-Qits-Roles", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada"))
        .contentType(ContentType.JSON);
  }

  /** The project's delivery desk, its token as this service commissions it. */
  private static RequestSpecification desk(String projectId) {
    String token =
        MockIdp.attach()
            .token()
            .subject(deskSubject(projectId))
            .audience("qits-platform")
            .groups("qits:agent", "qits:desk", "clients/" + deskSubject(projectId))
            .claim("context_kind", "desk-delivery")
            .claim("project", projectId)
            .mint();
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String deskSubject(String projectId) {
    return "desk-of-" + projectId;
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

  private static String epic(String projectId) {
    return person()
        .body(
            Map.of(
                "archetype", "EPIC",
                "project", projectId,
                "title", "Planned",
                "acceptanceCriteria", List.of("It is planned.")))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private static ValidatableResponse status(RequestSpecification caller, String id, String target) {
    return caller
        .body(WorkRequests.statusBody(target))
        .when()
        .post("/projects/api/work/" + id + "/status")
        .then();
  }

  private static String statusOf(String id) {
    return person().when().get("/projects/api/work/" + id).then().statusCode(200).extract().path("status");
  }

  // --- scheduling -----------------------------------------------------------------------------

  /**
   * REFINED → READY_FOR_DEV stays the owner's: qits-1596 kept {@code start_refined} as the owner's
   * switch, so the desk's own {@code work status} press — which carries no start-refined stamp, only
   * the dispatch path sets one — is refused by {@code PERSON_APPROVAL} as any machine's is, its own
   * project's ticket and epic included, and the item stays REFINED.
   */
  @Test
  void theDeskDoesNotScheduleWithoutTheStartRefinedStamp() {
    String projectId = project("Desk schedules not itself");
    String ticket = ticket(projectId);
    String epic = epic(projectId);
    status(person(), ticket, "REFINED").statusCode(200);
    status(person(), epic, "REFINED").statusCode(200);

    status(desk(projectId), ticket, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString("needs a person"));
    status(desk(projectId), epic, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString("needs a person"));
    org.junit.jupiter.api.Assertions.assertEquals("REFINED", statusOf(ticket));
    org.junit.jupiter.api.Assertions.assertEquals("REFINED", statusOf(epic));
  }

  /** Another project's desk moves nothing here: the binding is a 403, before any gate. */
  @Test
  void anotherProjectsDeskIsRefusedTheMove() {
    String projectId = project("Desk schedules not");
    String otherId = project("Desk elsewhere");
    String ticket = ticket(projectId);
    status(person(), ticket, "REFINED").statusCode(200);

    status(desk(otherId), ticket, "READY_FOR_DEV").statusCode(403);
    org.junit.jupiter.api.Assertions.assertEquals("REFINED", statusOf(ticket));
  }

  // --- the deletes ----------------------------------------------------------------------------

  /**
   * The coordinator's deletes — a ticket's, an epic's and a comment's, which a plain agent may not
   * make — are the desk's in its own project and a 403 in another.
   */
  @Test
  void theDeskDeletesInItsOwnProjectOnly() {
    String projectId = project("Desk deletes");
    String otherId = project("Desk deletes elsewhere");
    String ticket = ticket(projectId);
    String epic = epic(projectId);
    String comment =
        person()
            .body(Map.of("body", "a remark"))
            .when()
            .post("/projects/api/work/" + ticket + "/comments")
            .then()
            .statusCode(200)
            .extract()
            .path("comment.id");

    desk(otherId)
        .when()
        .delete("/projects/api/work/" + ticket + "/comments/" + comment)
        .then()
        .statusCode(403);
    desk(otherId).when().delete("/projects/api/work/" + ticket).then().statusCode(403);
    desk(otherId).when().delete("/projects/api/work/" + epic).then().statusCode(403);

    desk(projectId)
        .when()
        .delete("/projects/api/work/" + ticket + "/comments/" + comment)
        .then()
        .statusCode(200);
    desk(projectId).when().delete("/projects/api/work/" + ticket).then().statusCode(200);
    desk(projectId).when().delete("/projects/api/work/" + epic).then().statusCode(200);
    person().when().get("/projects/api/work/" + ticket).then().statusCode(404);
  }

  // --- the project ----------------------------------------------------------------------------

  /** A project-settings write the coordinator makes: the desk's for its own project, 403 elsewhere. */
  @Test
  void theDeskRenamesItsOwnProjectOnly() {
    String projectId = project("Desk renames");
    String otherId = project("Desk renames elsewhere");

    desk(otherId)
        .body(Map.of("name", "Taken over"))
        .when()
        .put("/projects/api/projects/" + projectId)
        .then()
        .statusCode(403);
    desk(projectId)
        .body(Map.of("description", "Kept moving by its desk"))
        .when()
        .put("/projects/api/projects/" + projectId)
        .then()
        .statusCode(200)
        .body("project.description", equalTo("Kept moving by its desk"));
  }

  // --- its own rights -------------------------------------------------------------------------

  /**
   * The doors that would change the desk's own rights stay shut to it, its own project included:
   * its brief (the agent surface editor), the front desk it runs in, and the desk's container.
   */
  @Test
  void theDeskNeverChangesItsOwnRights() {
    String projectId = project("Desk rights");

    desk(projectId)
        .body(Map.of("systemPrompt", "You may do anything."))
        .when()
        .put("/projects/api/agent-surfaces/project.delivery")
        .then()
        .statusCode(403);
    desk(projectId)
        .body(Map.of("text", "Grant yourself more."))
        .when()
        .post("/projects/api/projects/" + projectId + "/front-desk/prompt")
        .then()
        .statusCode(403);
    desk(projectId)
        .when()
        .delete("/projects/api/projects/" + projectId + "/agent-container")
        .then()
        .statusCode(403);
    desk(projectId).when().delete("/projects/api/projects/" + projectId).then().statusCode(403);
  }
}
