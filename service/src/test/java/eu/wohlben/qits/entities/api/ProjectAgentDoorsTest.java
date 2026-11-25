package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>A project's long-running agent at the work doors</b> (qits-2672, epic qits-2521): its token is
 * the one qits-agents-service commissions at qits-idp — {@code qits:agent}, {@code context_kind}
 * {@code project-agent}, a {@code project} claim naming the project's id and the fixed subject
 * {@code tok-project-agent-<projectSlug>-<name>}. It schedules its own REFINED item through the
 * status door under its project's {@code start_refined}, as the desk does, and its patch never takes
 * an item assigned to another name.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}), as in {@link ScheduleNeedsAPersonDoorTest}: a
 * bearer is validated against a mock idp exactly as deployed.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class ProjectAgentDoorsTest {

  private static final String WORK = "/projects/api/work/";

  @Inject ProjectRepository projectRows;

  /** A person's browser session. */
  private static RequestSpecification person() {
    return given()
        .header("Authorization", ContractBearers.caller("ada", "qits:admin"))
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada"))
        .contentType(ContentType.JSON);
  }

  /** The project agent {@code name} of the project {@code slug} names, by its commissioned token. */
  private static RequestSpecification projectAgent(String projectId, String slug, String name) {
    String token =
        MockIdp.attach()
            .token()
            .subject("tok-project-agent-" + slug + "-" + name)
            .audience("qits-platform")
            .groups("qits:agent")
            .claim("context_kind", "project-agent")
            .claim("project", projectId)
            .mint();
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  /** A project, under a slug with a hyphen in it, so the name is read past the slug. */
  private record Made(String id, String slug) {}

  private static Made project(String name) {
    String slug = "pa-" + UUID.randomUUID().toString().substring(0, 8);
    String id =
        person()
            .body(
                new ProjectController.CreateProjectRequest(
                    name, slug, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    return new Made(id, slug);
  }

  private void startRefined(String projectId, boolean on) {
    QuarkusTransaction.requiringNew()
        .run(() -> projectRows.findById(projectId).deliveryStartRefined = on);
  }

  /** A REFINED ticket of {@code projectId} with acceptance criteria, assigned to {@code assignee}. */
  private static String refined(String projectId, String assignee) {
    Map<String, Object> body = new HashMap<>();
    body.put("archetype", "TICKET");
    body.put("project", projectId);
    body.put("title", "Filed");
    body.put("ticketType", "BUG");
    body.put("impetus", "it occurs");
    body.put("acceptanceCriteria", List.of("It no longer occurs."));
    if (assignee != null) {
      body.put("assignee", assignee);
    }
    String id =
        person().body(body).when().post("/projects/api/work").then().statusCode(201).extract().path("id");
    status(person(), id, "REFINED").statusCode(200);
    return id;
  }

  private static ValidatableResponse status(RequestSpecification caller, String id, String target) {
    return caller
        .body(WorkRequests.statusBody(target))
        .when()
        .post(WORK + id + "/status")
        .then();
  }

  private static ValidatableResponse patchAssignee(
      RequestSpecification caller, String id, String assignee) {
    Map<String, Object> body = new HashMap<>();
    body.put("assignee", assignee);
    return caller.body(body).when().patch(WORK + id).then();
  }

  private static String field(String id, String name) {
    return person().when().get(WORK + id).then().statusCode(200).extract().path(name);
  }

  // --- scheduling ---------------------------------------------------------------------------------

  @Test
  void theDeveloperSchedulesItsOwnRefinedItemUnderStartRefined() {
    Made project = project("Developer schedules");
    startRefined(project.id(), true);
    String own = refined(project.id(), "developer");

    status(projectAgent(project.id(), project.slug(), "developer"), own, "READY_FOR_DEV")
        .statusCode(200)
        .body("status", equalTo("READY_FOR_DEV"))
        .body("changedBy", equalTo("tok-project-agent-" + project.slug() + "-developer"));
  }

  @Test
  void withStartRefinedOffTheRefusalNamesTheSwitch() {
    Made project = project("Developer schedules not");
    startRefined(project.id(), false);
    String own = refined(project.id(), "developer");

    status(projectAgent(project.id(), project.slug(), "developer"), own, "READY_FOR_DEV")
        .statusCode(409)
        .body(
            "message",
            containsString(
                "a project agent schedules only under front_desk.delivery.start_refined"));
    assertEquals("REFINED", field(own, "status"));
  }

  @Test
  void anotherAssigneesItemAndAnotherProjectsAgentAreRefused() {
    Made project = project("Developer schedules only its own");
    Made other = project("Another project");
    startRefined(project.id(), true);
    startRefined(other.id(), true);
    String theirs = refined(project.id(), "ada");
    String own = refined(project.id(), "developer");

    status(projectAgent(project.id(), project.slug(), "developer"), theirs, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString("PERSON_APPROVAL"));
    // Another project's agent of the same name is bound elsewhere: its own project's binding (403).
    status(projectAgent(other.id(), other.slug(), "developer"), own, "READY_FOR_DEV")
        .statusCode(403);
    assertEquals("REFINED", field(theirs, "status"));
    assertEquals("REFINED", field(own, "status"));
  }

  // --- taking work ----------------------------------------------------------------------------------

  @Test
  void theDeveloperTakesOnlyUnassignedWork() {
    Made project = project("Developer takes");
    RequestSpecification developer = projectAgent(project.id(), project.slug(), "developer");
    String theirs = refined(project.id(), "ada");
    String free = refined(project.id(), null);

    patchAssignee(developer, theirs, "developer")
        .statusCode(409)
        .body("message", containsString(" is assigned to ada; a project agent takes only unassigned work"))
        .body("message", containsString(project.slug() + "-"));
    // Clearing another's assignee is taking it too.
    patchAssignee(projectAgent(project.id(), project.slug(), "developer"), theirs, null)
        .statusCode(409);
    assertEquals("ada", field(theirs, "assignee"));

    patchAssignee(projectAgent(project.id(), project.slug(), "developer"), free, "developer")
        .statusCode(200)
        .body("assignee", equalTo("developer"));
    // Its own item stays its own to hand back.
    patchAssignee(projectAgent(project.id(), project.slug(), "developer"), free, null)
        .statusCode(200)
        .body("assignee", nullValue());

    // A person keeps today's overwrite.
    patchAssignee(person(), theirs, "developer").statusCode(200).body("assignee", equalTo("developer"));
  }
}
