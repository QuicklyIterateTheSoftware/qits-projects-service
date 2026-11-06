package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/work/{id}/status} (qits-548, on the work family since qits-976): the
 * lifecycle move of whatever archetype the row is, under that archetype's roles.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class WorkStatusApiTest {

  private static ValidatableResponse move(RequestSpecification as, String id, Object target) {
    return as.contentType(ContentType.JSON)
        .body(
            "DROPPED".equals(target)
                ? WorkRequests.statusBody("DROPPED")
                : WorkRequests.map("target", target))
        .when()
        .post("/projects/api/work/" + id + "/status")
        .then();
  }

  /** The dev user's move, as a person: the session a browser keeps beside it (qits-887). */
  private static ValidatableResponse move(String id, Object target) {
    return move(
        given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev")),
        id,
        target);
  }

  /** The row's status, read back through {@code GET /work/{id}}. */
  private static void statusOf(String id, String status) {
    given().get("/projects/api/work/" + id).then().statusCode(200).body("status", equalTo(status));
  }

  /** What the edge sends for an agent: the role, and no token — see {@code EntityAgentBoundsTest}. */
  private static RequestSpecification asForwardedAgent() {
    return given().header("Authorization", ContractBearers.caller("someone", "qits:agent"));
  }

  /**
   * An admin workspace's agent (qits-628 follow-up): {@code qits:admin-agent} and nothing else —
   * no {@code qits:admin}, so this is the identity the follow-up actually widens the door for.
   */
  private static RequestSpecification asForwardedAdminAgent() {
    return given().header("Authorization", ContractBearers.caller("someone", "qits:admin-agent"));
  }

  @Test
  void aTicketMovesFromReportedToRefined() {
    EntityFixtures.Project project = EntityFixtures.project("Status Ticket");
    String ticket = EntityFixtures.ticket(project.id());

    move(EntityFixtures.qualifiedId(ticket), "REFINED")
        .statusCode(200)
        .body("id", equalTo(ticket))
        .body("archetype", equalTo("TICKET"))
        .body("status", equalTo("REFINED"))
        .body("statusBefore", equalTo("REPORTED"))
        .body("qualifiedId", startsWith(project.slug() + "-"))
        .body("blocked", equalTo(false));
    statusOf(ticket, "REFINED");
  }

  @Test
  void anIllegalMoveIsA409() {
    EntityFixtures.Project project = EntityFixtures.project("Status Illegal");
    String ticket = EntityFixtures.ticket(project.id());

    move(ticket, "DONE").statusCode(409);
    move(ticket, "NOPE").statusCode(409);
    move(ticket, null).statusCode(400);
    statusOf(ticket, "REPORTED");
  }

  /** qits-763: a feature's own move waits for its epic to leave REPORTED — the plan is a draft. */
  @Test
  void aFeatureDoesNotMoveWhileItsEpicIsADraft() {
    EntityFixtures.Project project = EntityFixtures.project("Status Feature");
    String epic = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(epic);

    move(feature, "REFINED")
        .statusCode(409)
        .body("message", containsString("is REPORTED"));
    statusOf(feature, "REPORTED");
  }

  /**
   * qits-763: a task holds the one lifecycle of its own, so it is verified while its epic and its
   * sibling stay IMPLEMENTED — the epic's move carried both tasks there, and nothing moves them on.
   */
  @Test
  void aTaskIsVerifiedOnItsOwnWhileItsEpicStaysImplemented() {
    EntityFixtures.Project project = EntityFixtures.project("Status Task");
    String repository = EntityFixtures.repository(project.id());
    String epic = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(epic);
    String task = EntityFixtures.task(feature, repository);
    String sibling = EntityFixtures.task(feature, repository);
    move(epic, "REFINED").statusCode(200);
    move(epic, "READY_FOR_DEV").statusCode(200);
    move(epic, "IMPLEMENTED").statusCode(200);

    move(EntityFixtures.qualifiedId(task), "VERIFYING")
        .statusCode(200)
        .body("archetype", equalTo("TASK"))
        .body("statusBefore", equalTo("IMPLEMENTED"))
        .body("status", equalTo("VERIFYING"))
        .body("$", not(hasKey("blocked")));
    move(task, "VERIFIED").statusCode(200).body("status", equalTo("VERIFIED"));

    statusOf(task, "VERIFIED");
    statusOf(sibling, "IMPLEMENTED");
    statusOf(feature, "IMPLEMENTED");
    statusOf(epic, "IMPLEMENTED");
  }

  /**
   * A task's move takes a ticket's roles, not an epic's: an agent is refused only by the project
   * binding (this forwarded agent carries no project claim), never by "qits:admin alone".
   */
  @Test
  void anAgentMovingATaskIsBoundToItsProjectAndNotRefusedAsForAnEpic() {
    EntityFixtures.Project project = EntityFixtures.project("Status Agent Task");
    String epic = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(epic);
    move(epic, "REFINED").statusCode(200);

    move(asForwardedAgent(), feature, "IMPLEMENTED")
        .statusCode(403)
        .body("message", containsString("its own project"));
    statusOf(feature, "REFINED");
  }

  /** The epic moves through the same door, for a person. */
  @Test
  void anAdminMovesAnEpic() {
    EntityFixtures.Project project = EntityFixtures.project("Status Epic");
    String epic = EntityFixtures.epic(project.id());

    move(epic, "REFINED")
        .statusCode(200)
        .body("status", equalTo("REFINED"))
        .body("statusBefore", equalTo("REPORTED"));
    statusOf(epic, "REFINED");
  }

  /**
   * An agent moving an epic gets past the epic's rule since qits-1174 and meets the binding: the
   * forwarded agent carries no project claim, so it covers no project, and nothing is written. The
   * through-case, with a claim, is {@code EntityAgentBoundsTest}'s.
   */
  @Test
  void anAgentMovingAnEpicIsA403AndNothingMoves() {
    EntityFixtures.Project project = EntityFixtures.project("Status Agent Epic");
    String epic = EntityFixtures.epic(project.id());

    move(asForwardedAgent(), epic, "REFINED")
        .statusCode(403)
        .body("message", containsString("its own project"));
    statusOf(epic, "REPORTED");

    // And an id naming nothing is still a 404 for the agent: the row is resolved first.
    move(asForwardedAgent(), "no-such-entity", "REFINED").statusCode(404);
  }

  /**
   * {@code qits:admin-agent} may move an epic exactly as {@code qits:admin} may (qits-628
   * follow-up, owner's rule: for now it may use everything {@code qits:admin} may use) — with no
   * {@code qits:admin} on the identity at all — while {@code qits:agent} alone still answers 403
   * for the identical move, as asserted above.
   */
  @Test
  void anAdminAgentMovesAnEpicWithNoAdminRole() {
    EntityFixtures.Project project = EntityFixtures.project("Status Admin Agent Epic");
    String epic = EntityFixtures.epic(project.id());

    move(asForwardedAdminAgent(), epic, "REFINED")
        .statusCode(200)
        .body("status", equalTo("REFINED"))
        .body("statusBefore", equalTo("REPORTED"));
    statusOf(epic, "REFINED");
  }

  /** A ticket of {@code projectId} walked to VERIFIED through this door, as a person. */
  private static String verifiedTicket(String projectId) {
    String ticket = EntityFixtures.ticket(projectId);
    for (String target : java.util.List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED")) {
      move(ticket, target).statusCode(200);
    }
    return ticket;
  }

  /**
   * qits-150: no caller moves a ticket to DONE through this door — a person, qits:admin,
   * qits:admin-agent and a platform service alike get the same 409 — because the platform sets it
   * once the workspace's wrapper release request carrying the ticket's branch is finalized.
   * DROPPED stays open, and an epic's DONE is untouched.
   */
  @Test
  void noCallerMovesATicketToDone() {
    EntityFixtures.Project project = EntityFixtures.project("Status Ticket Done");
    String ticket = verifiedTicket(project.id());

    for (RequestSpecification caller :
        java.util.List.of(
            given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev")),
            given().header("Authorization", ContractBearers.caller("ada", "qits:admin")),
            asForwardedAdminAgent(),
            given().header("Authorization", ContractBearers.caller("svc", "qits:system")))) {
      move(caller, ticket, "DONE")
          .statusCode(409)
          .body(
              "message",
              containsString(
                  "the platform sets it when the workspace's wrapper release request carrying the"
                      + " ticket's branch is finalized"));
    }
    statusOf(ticket, "VERIFIED");

    move(ticket, "DROPPED").statusCode(200).body("status", equalTo("DROPPED"));
  }

  /** qits-150: the PUT-shaped transition door refuses a ticket's DONE in the same words. */
  @Test
  void theTransitionDoorRefusesATicketsDoneToo() {
    EntityFixtures.Project project = EntityFixtures.project("Transition Ticket Done");
    String ticket = verifiedTicket(project.id());
    java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
    entry.put("archetype", "TICKET");
    entry.put("title", "Done by nobody");
    entry.put("impetus", "it occurs");
    entry.put("ticketType", "BUG");
    entry.put("status", "DONE");
    entry.put("acceptanceCriteria", TestCriteria.CRITERIA);

    given()
        .header("Authorization", ContractBearers.caller("ada", "qits:admin", "qits:system"))
        .contentType(ContentType.JSON)
        .body(java.util.Map.of(ticket, entry))
        .when()
        .post("/projects/api/work/transition")
        .then()
        .statusCode(409)
        .body("message", containsString("would move from VERIFIED to DONE"))
        .body("message", containsString("the platform sets it"));
    statusOf(ticket, "VERIFIED");
  }
}
