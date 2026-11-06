package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.contracts.ContractBearers;
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
 * <b>Scheduling needs a person, at the REST status door</b> (qits-887, qits-937): REFINED →
 * READY_FOR_DEV through {@code POST /work/{id}/status} — since qits-976 the one status door, for an
 * epic and a ticket alike — is made only by a caller qits-891's {@link PersonCheck}
 * verified — a browser session this service introspects, or a person's {@code
 * qits} CLI bearer — and it is recorded under the proof's name. An agent's bearer, a service client's
 * and asserted identity headers (alone, or beside a machine bearer) are a 409 naming the gate, or the
 * 403 the door's roles already answer for an epic. Unscheduling is anybody's.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}), so a bearer is validated against a mock idp
 * exactly as deployed, and the dev user is blanked, so a caller is what it sends.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class ScheduleNeedsAPersonDoorTest {

  /**
   * A person's browser session: the caller the suite names ({@link ContractBearers#caller}, what the
   * asserted header pair used to establish — no token), and the cookie.
   */
  private static RequestSpecification session(String asserted, String person) {
    return given()
        .header("Authorization", ContractBearers.caller(asserted, "qits:admin"))
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(person))
        .contentType(ContentType.JSON);
  }

  /** {@code qits:admin} asserted, with no token and no session — nothing this service can verify. */
  private static RequestSpecification headersOnly() {
    return given()
        .header("Authorization", ContractBearers.caller("mallory", "qits:admin"))
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification bearer(String token) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static String personsCli() {
    return MockIdp.attach()
        .token()
        .subject("user-7")
        .audience("qits-platform")
        .groups("qits:admin")
        .claim("credential_type", "cli")
        .mint();
  }

  private static String agent(String projectId) {
    return MockIdp.attach()
        .token()
        .subject("dev-qits-projects-agent-7")
        .audience("qits-platform")
        .groups("qits:agent")
        .claim("credential_type", "cli")
        .claim("context_kind", "agent-container")
        .claim("project", projectId)
        .mint();
  }

  private static String serviceClient() {
    return MockIdp.attach()
        .token()
        .subject("dev-qits-maintenance")
        .audience("qits-platform")
        .groups("qits:system", "clients/dev-qits-maintenance")
        .mint();
  }

  // --- fixtures, made by a person ------------------------------------------------------------------

  private static String project(String name) {
    return session("ada", "ada")
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  /** A REFINED entity of {@code archetype}, with acceptance criteria. */
  private static String refined(String projectId, String archetype) {
    Map<String, Object> body =
        archetype.equals("TICKET")
            ? Map.of(
                "archetype", "TICKET",
                "project", projectId,
                "title", "Filed",
                "ticketType", "BUG",
                "impetus", "it occurs",
                "acceptanceCriteria", List.of("It no longer occurs."))
            : Map.of(
                "archetype", archetype,
                "project", projectId,
                "title", "Planned",
                "acceptanceCriteria", List.of("It is planned."));
    String id =
        session("ada", "ada")
            .body(body)
            .when()
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    status(session("ada", "ada"), id, "REFINED").statusCode(200);
    return id;
  }


  private static ValidatableResponse status(RequestSpecification caller, String id, String target) {
    return caller
        .body(eu.wohlben.qits.entities.api.WorkRequests.statusBody(target))
        .when()
        .post("/projects/api/work/" + id + "/status")
        .then();
  }

  private static void stillRefined(String id) {
    session("ada", "ada")
        .when()
        .get("/projects/api/work/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("REFINED"));
  }

  private static final String REFUSAL = "PERSON_APPROVAL: scheduling (REFINED → READY_FOR_DEV) needs a person";

  // --- a person schedules -------------------------------------------------------------------------

  @Test
  void aPersonsSessionSchedulesEveryRootUnderTheSessionsName() {
    String projectId = project("Person session schedules");
    // The headers assert one admin and the session idp vouches for another: the proof's name wins.
    status(session("mallory", "ada"), refined(projectId, "TICKET"), "READY_FOR_DEV")
        .statusCode(200)
        .body("status", equalTo("READY_FOR_DEV"))
        .body("changedBy", equalTo("ada"));
    status(session("mallory", "ada"), refined(projectId, "EPIC"), "READY_FOR_DEV")
        .statusCode(200)
        .body("status", equalTo("READY_FOR_DEV"))
        .body("changedBy", equalTo("ada"));
  }

  @Test
  void aPersonsCliSchedulesUnderTheTokensName() {
    String projectId = project("Person CLI schedules");
    status(bearer(personsCli()), refined(projectId, "EPIC"), "READY_FOR_DEV")
        .statusCode(200)
        .body("changedBy", equalTo("user-7"));
    status(bearer(personsCli()), refined(projectId, "TICKET"), "READY_FOR_DEV")
        .statusCode(200)
        .body("changedBy", equalTo("user-7"));
  }

  // --- a machine does not -------------------------------------------------------------------------

  @Test
  void assertedHeadersAloneAreNotAPerson() {
    String projectId = project("Asserted headers");
    String id = refined(projectId, "TICKET");
    status(headersOnly(), id, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString(REFUSAL))
        .body("message", containsString("mallory is a machine credential"));
    String epicId = refined(projectId, "EPIC");
    status(headersOnly(), epicId, "READY_FOR_DEV").statusCode(409);
    // A session idp does not vouch for an admin is no person either.
    status(
            headersOnly()
                .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.cookie("ada", "qits:agent")),
            id,
            "READY_FOR_DEV")
        .statusCode(409);
    stillRefined(id);
    stillRefined(epicId);
  }

  @Test
  void anAgentsBearerIsRefusedAndMayStillUnschedule() {
    String projectId = project("Agent bearer");
    String id = refined(projectId, "TICKET");
    status(bearer(agent(projectId)), id, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString(REFUSAL));
    // An agent reaches an epic's status door of its own project since qits-1174, and meets the
    // same gate there.
    status(bearer(agent(projectId)), refined(projectId, "EPIC"), "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString(REFUSAL));
    stillRefined(id);

    // Unscheduling is a correction, and nobody's gate.
    status(session("ada", "ada"), id, "READY_FOR_DEV").statusCode(200);
    status(bearer(agent(projectId)), id, "REFINED")
        .statusCode(200)
        .body("status", equalTo("REFINED"));
  }

  @Test
  void aServiceClientAndAMachineBearerBesideASessionCookieAreRefused() {
    String projectId = project("Service client");
    String id = refined(projectId, "TICKET");
    status(bearer(serviceClient()), id, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString(REFUSAL));
    // The bearer is the credential: an admin's session cookie beside it asserts nothing. (Forwarded
    // admin headers used to ride along here too; forward-auth leaves with qits-1338.)
    status(
            bearer(serviceClient())
                .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada")),
            id,
            "READY_FOR_DEV")
        .statusCode(409);
    status(bearer(serviceClient()), refined(projectId, "EPIC"), "READY_FOR_DEV").statusCode(403);
    stillRefined(id);
  }

  // --- the close is not a scheduling (qits-1757) ---------------------------------------------------

  /**
   * CLOSE straight to DONE is no scheduling: PERSON_APPROVAL never judges it. An agent's bearer is
   * refused by CHILDREN_CLOSED alone, naming no scheduling gate, and a person closes the REFINED epic
   * whose one feature is dropped under the session's name.
   */
  @Test
  void aCloseIsNotJudgedByPersonApproval() {
    String projectId = project("Close is no scheduling");
    String epicId =
        session("ada", "ada")
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
    String featureId =
        session("ada", "ada")
            .body(Map.of("archetype", "FEATURE", "parent", epicId, "title", "Part"))
            .when()
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    status(session("ada", "ada"), epicId, "REFINED").statusCode(200);
    status(session("ada", "ada"), featureId, "DROPPED").statusCode(200);

    status(bearer(agent(projectId)), epicId, "DONE")
        .statusCode(409)
        .body("message", containsString("CHILDREN_CLOSED: closing to DONE needs a person or the delivery desk"))
        .body("message", org.hamcrest.Matchers.not(containsString("PERSON_APPROVAL")));
    stillRefined(epicId);

    status(session("mallory", "ada"), epicId, "DONE")
        .statusCode(200)
        .body("status", equalTo("DONE"))
        .body("statusBefore", equalTo("REFINED"))
        .body("changedBy", equalTo("ada"));
  }


  // --- the dispatch door's scheduling (qits-1075) -------------------------------------------------

  private static ValidatableResponse dispatch(RequestSpecification caller, String id) {
    return caller
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + id + "/dispatch")
        .then();
  }

  /**
   * <b>A Dispatch press at REFINED is a scheduling, so it needs a person too</b> (qits-1075): a
   * person's session or CLI schedules and starts implement, recorded under the proof's name; asserted
   * headers are a machine and keep the 409 REFINED answered before; an agent's and a service
   * client's bearer never reach the press at all.
   */
  @Test
  void theDispatchDoorSchedulesOnlyForAPerson() {
    String projectId = project("Dispatch schedules");
    String ticketId = refined(projectId, "TICKET");
    dispatch(headersOnly(), ticketId)
        .statusCode(409)
        .body("message", containsString("REFINED waits for a person to schedule it"));
    dispatch(bearer(agent(projectId)), ticketId).statusCode(403);
    dispatch(bearer(serviceClient()), ticketId).statusCode(403);
    stillRefined(ticketId);

    dispatch(session("mallory", "ada"), ticketId)
        .statusCode(200)
        .body("dispatch.phase", equalTo("implement"));
    session("ada", "ada")
        .when()
        .get("/projects/api/work/" + ticketId + "/audit")
        .then()
        .statusCode(200)
        .body("entries.find { it.snapshot.contains('READY_FOR_DEV') }.changedBy", equalTo("ada"));

    String epicId = refined(projectId, "EPIC");
    dispatch(bearer(personsCli()), epicId).statusCode(200).body("dispatch.phase", equalTo("implement"));
  }
}
