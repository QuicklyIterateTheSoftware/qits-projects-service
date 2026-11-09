package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.entities.control.EntityTransitionService;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.bus.RecordingWorkNeedAnnouncer;
import eu.wohlben.qits.projects.bus.WorkNeedMet;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A condition closes straight to DONE on the status door</b> (qits-1972): a ticket tagged
 * {@code CONDITION} is met by a person or the project's delivery desk moving it to DONE from an
 * open status (the ticket's CLOSE move, judged by {@code CONDITION_MET}), and the items that need
 * it at DONE see their need met. A plain agent is a 409 naming the gate; an untagged ticket keeps
 * qits-150's refusal word for word, on the status door and on the bulk door, and the bulk door
 * refuses a condition's DONE too.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}), as in {@link DeskCoordinatorRightsDoorTest}: the
 * desk is the token qits-idp mints for it, validated against a mock idp exactly as deployed.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
@WithTestResource(PersonGateIdpTenant.class)
class ConditionCloseDoorTest {

  @Inject RecordingWorkNeedAnnouncer announced;

  @BeforeEach
  void reset() {
    announced.reset();
  }

  /** A person's browser session as the edge forwards it. */
  private static RequestSpecification person() {
    return given()
        .header("Authorization", ContractBearers.caller("ada", "qits:admin"))
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

  /** A plain agent of the project: {@code qits:agent} and no desk role. */
  private static RequestSpecification agent(String projectId) {
    String token =
        MockIdp.attach()
            .token()
            .subject("dev-qits-projects-agent-7")
            .audience("qits-platform")
            .groups("qits:agent")
            .claim("credential_type", "cli")
            .claim("context_kind", "agent-container")
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

  private static String ticket(String projectId, String title) {
    return person()
        .body(
            Map.of(
                "archetype", "TICKET",
                "project", projectId,
                "title", title,
                "ticketType", "BUG",
                "impetus", "it must exist",
                "acceptanceCriteria", List.of("It exists.")))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private static String condition(String projectId, String title) {
    String id = ticket(projectId, title);
    patch(id, Map.of("tags", List.of("CONDITION")));
    return id;
  }

  private static void patch(String id, Object body) {
    person()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then()
        .statusCode(200);
  }

  private static ValidatableResponse status(RequestSpecification caller, String id, String target) {
    return caller
        .body(WorkRequests.statusBody(target))
        .when()
        .post("/projects/api/work/" + id + "/status")
        .then();
  }

  private static ValidatableResponse read(String id) {
    return person().when().get("/projects/api/work/" + id).then().statusCode(200);
  }

  private static String statusOf(String id) {
    return read(id).extract().path("status");
  }

  // --- (a) the desk closes a condition, and the item that needs it is met ---------------------------

  @Test
  void theDeskClosesAReportedConditionAndTheItemThatNeedsItIsMet() {
    String projectId = project("Condition closes");
    String condition = condition(projectId, "Bootstrap test node");
    String waiting = ticket(projectId, "Runs on the test node");
    patch(waiting, Map.of("needs", List.of(Map.of("item", condition, "status", "DONE"))));
    read(waiting).body("unmetNeeds", equalTo(1));
    assertEquals(List.of(), announced.metFor(waiting));

    status(desk(projectId), condition, "DONE")
        .statusCode(200)
        .body("status", equalTo("DONE"))
        .body("statusBefore", equalTo("REPORTED"))
        .body("changedBy", equalTo(deskSubject(projectId)));

    assertEquals("DONE", statusOf(condition));
    List<WorkNeedMet> met = announced.metFor(waiting);
    assertEquals(1, met.size(), "one need flipped: " + met);
    assertEquals(condition, met.get(0).neededEntityId());
    assertEquals("DONE", met.get(0).status());
    assertEquals("DONE", met.get(0).neededStatus());
    assertEquals(true, met.get(0).allMet());
    read(waiting).body("unmetNeeds", equalTo(0)).body("needs[0].met", equalTo(true));
  }

  /** A person closes one from further along the walk, through their browser session. */
  @Test
  void aPersonClosesARefinedCondition() {
    String projectId = project("Condition closes by a person");
    String condition = condition(projectId, "Owner decided");
    status(person(), condition, "REFINED").statusCode(200);

    status(person(), condition, "DONE")
        .statusCode(200)
        .body("status", equalTo("DONE"))
        .body("statusBefore", equalTo("REFINED"));
  }

  // --- (b) a plain agent is refused -----------------------------------------------------------------

  @Test
  void aPlainAgentIsRefusedNamingConditionMet() {
    String projectId = project("Condition agent refused");
    String condition = condition(projectId, "Bootstrap test node");

    status(agent(projectId), condition, "DONE")
        .statusCode(409)
        .body("message", containsString("CONDITION_MET: closing a condition to DONE needs a person"))
        .body("message", containsString("ask the desk with an @desk comment"))
        .body("message", not(containsString(EntityTransitionService.TICKET_DONE_IS_THE_PLATFORMS)));
    assertEquals("REPORTED", statusOf(condition));
  }

  // --- (c) an untagged ticket keeps qits-150 ---------------------------------------------------------

  @Test
  void anUntaggedTicketMovedToDoneByTheDeskKeepsTheQits150Refusal() {
    String projectId = project("Condition untagged");
    String ticket = ticket(projectId, "An ordinary bug");

    status(desk(projectId), ticket, "DONE")
        .statusCode(409)
        .body(
            "message",
            equalTo(
                "Ticket "
                    + ticket
                    + ": "
                    + EntityTransitionService.TICKET_DONE_IS_THE_PLATFORMS
                    + "."));
    assertEquals(
        "DONE is not a status anybody claims for a ticket — the platform sets it when the"
            + " workspace's wrapper release request carrying the ticket's branch is finalized",
        EntityTransitionService.TICKET_DONE_IS_THE_PLATFORMS);
    assertEquals("REPORTED", statusOf(ticket));
  }

  /** The bulk door refuses a ticket's DONE, a condition's included: it closes on the status door. */
  @Test
  void theBulkDoorRefusesATicketsDoneConditionOrNot() {
    String projectId = project("Condition bulk door");
    String condition = condition(projectId, "Bootstrap test node");
    String ticket = ticket(projectId, "An ordinary bug");

    for (String id : List.of(condition, ticket)) {
      Map<String, Object> entry = new java.util.LinkedHashMap<>();
      entry.put("archetype", "TICKET");
      entry.put("title", "Restated");
      entry.put("impetus", "it must exist");
      entry.put("ticketType", "BUG");
      entry.put("status", "DONE");
      entry.put("acceptanceCriteria", List.of("It exists."));
      if (id.equals(condition)) {
        entry.put("tags", List.of("CONDITION"));
      }
      person()
          .body(Map.of(id, entry))
          .when()
          .post("/projects/api/work/transition")
          .then()
          .statusCode(409)
          .body("message", containsString("would move from REPORTED to DONE"))
          .body("message", containsString(EntityTransitionService.TICKET_DONE_IS_THE_PLATFORMS));
      assertEquals("REPORTED", statusOf(id));
    }
  }
}
