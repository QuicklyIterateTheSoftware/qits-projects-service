package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/work/{id}/status} (qits-548, on the work family since qits-976): the
 * lifecycle move of whatever archetype the row is, under that archetype's roles.
 */
@QuarkusTest
class WorkStatusApiTest {

  private static ValidatableResponse move(RequestSpecification as, String id, Object target) {
    return as.contentType(ContentType.JSON)
        .body(map("target", target))
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
    return given().header("X-Qits-User", "someone").header("X-Qits-Roles", "qits:agent");
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

  /** The epic and the campaign move through the same door, for a person. */
  @Test
  void anAdminMovesAnEpicAndACampaign() {
    EntityFixtures.Project project = EntityFixtures.project("Status Epic");
    String epic = EntityFixtures.epic(project.id());
    String campaign = EntityFixtures.campaign(project.id());

    move(epic, "REFINED")
        .statusCode(200)
        .body("status", equalTo("REFINED"))
        .body("statusBefore", equalTo("REPORTED"));
    statusOf(epic, "REFINED");

    move(campaign, "REFINED").statusCode(200).body("status", equalTo("REFINED"));
    statusOf(campaign, "REFINED");
  }

  /**
   * An agent moving an epic is refused — an epic's status is {@code qits:admin} alone — and nothing
   * is written. The forwarded agent carries no project claim either, so the message is what
   * tells the epic's rule from the binding: the epic's refusal comes first.
   */
  @Test
  void anAgentMovingAnEpicIsA403AndNothingMoves() {
    EntityFixtures.Project project = EntityFixtures.project("Status Agent Epic");
    String epic = EntityFixtures.epic(project.id());

    move(asForwardedAgent(), epic, "REFINED")
        .statusCode(403)
        .body("message", containsString("qits:admin alone"));
    statusOf(epic, "REPORTED");

    // And an id naming nothing is still a 404 for the agent: the row is resolved first.
    move(asForwardedAgent(), "no-such-entity", "REFINED").statusCode(404);
  }
}
