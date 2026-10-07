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
 * <b>Scheduling needs a person, at the REST status door</b> (qits-887, qits-937): REFINED →
 * READY_FOR_DEV through {@code POST /work/{id}/status} — since qits-976 the one status door, for an
 * epic, a ticket and a campaign alike — is made only by a caller qits-891's {@link PersonCheck}
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

  /** A person's browser session as the edge forwards it: the asserted pair, and the cookie. */
  private static RequestSpecification session(String asserted, String person) {
    return given()
        .header("X-Qits-User", asserted)
        .header("X-Qits-Roles", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(person))
        .contentType(ContentType.JSON);
  }

  /** {@code qits:admin} asserted in headers and nothing this service can verify. */
  private static RequestSpecification headersOnly() {
    return given()
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:admin")
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

  private static String refinedCampaign(String projectId) {
    String id =
        session("ada", "ada")
            .body(Map.of("archetype", "CAMPAIGN", "project", projectId, "title", "The order"))
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
        .body(Map.of("target", target))
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
    status(session("mallory", "ada"), refinedCampaign(projectId), "READY_FOR_DEV")
        .statusCode(200)
        .body("status", equalTo("READY_FOR_DEV"))
        .body("changedBy", equalTo("ada"));
  }

  /**
   * A campaign is ready for dev when its members are (qits-942): a person's move of the campaign is
   * refused while a member is still REFINED, naming it, and goes through once it is scheduled. A
   * machine is refused by both gates at once.
   */
  @Test
  void aCampaignIsScheduledByAPersonOnceItsMembersAre() {
    String projectId = project("Campaign members scheduled");
    String campaignId = refinedCampaign(projectId);
    String memberId = refined(projectId, "TICKET");
    session("ada", "ada")
        .body(Map.of("entityId", memberId))
        .when()
        .post("/projects/api/work/" + campaignId + "/members")
        .then()
        .statusCode(201);

    status(session("ada", "ada"), campaignId, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString("MEMBERS_SCHEDULED: a member is not READY_FOR_DEV yet: "))
        .body("message", containsString(" (REFINED)"));
    status(bearer(agent(projectId)), campaignId, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString("MEMBERS_SCHEDULED: "))
        .body("message", containsString(REFUSAL));

    status(session("ada", "ada"), memberId, "READY_FOR_DEV").statusCode(200);
    status(session("ada", "ada"), campaignId, "READY_FOR_DEV")
        .statusCode(200)
        .body("status", equalTo("READY_FOR_DEV"));
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
    String campaignId = refinedCampaign(projectId);
    status(bearer(agent(projectId)), campaignId, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString(REFUSAL));
    // An epic's status door is qits:admin alone, before any gate.
    status(bearer(agent(projectId)), refined(projectId, "EPIC"), "READY_FOR_DEV").statusCode(403);
    stillRefined(id);

    // Unscheduling is a correction, and nobody's gate.
    status(session("ada", "ada"), id, "READY_FOR_DEV").statusCode(200);
    status(bearer(agent(projectId)), id, "REFINED")
        .statusCode(200)
        .body("status", equalTo("REFINED"));
  }

  @Test
  void aServiceClientAndAMachineBearerUnderAssertedHeadersAreRefused() {
    String projectId = project("Service client");
    String id = refined(projectId, "TICKET");
    status(bearer(serviceClient()), id, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString(REFUSAL));
    // The bearer is the credential: admin headers beside it assert nothing.
    status(
            bearer(serviceClient())
                .header("X-Qits-User", "ada")
                .header("X-Qits-Roles", "qits:admin")
                .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada")),
            id,
            "READY_FOR_DEV")
        .statusCode(409);
    status(bearer(serviceClient()), refined(projectId, "EPIC"), "READY_FOR_DEV").statusCode(403);
    stillRefined(id);
  }
}
