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
 * {@code POST /projects/api/entities/{id}/status} (qits-548): the lifecycle move of whatever
 * archetype the row is, made by the archetype's own door's path and under its roles.
 */
@QuarkusTest
class EntityStatusApiTest {

  private static ValidatableResponse move(RequestSpecification as, String id, Object target) {
    return as.contentType(ContentType.JSON)
        .body(map("target", target))
        .when()
        .post("/projects/api/entities/" + id + "/status")
        .then();
  }

  /** The dev user's move, as a person: the session a browser keeps beside it (qits-887). */
  private static ValidatableResponse move(String id, Object target) {
    return move(
        given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev")),
        id,
        target);
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
    given()
        .get("/projects/api/tickets/" + ticket)
        .then()
        .body("ticket.status", equalTo("REFINED"));
  }

  @Test
  void anIllegalMoveIsA409() {
    EntityFixtures.Project project = EntityFixtures.project("Status Illegal");
    String ticket = EntityFixtures.ticket(project.id());

    move(ticket, "DONE").statusCode(409);
    move(ticket, "NOPE").statusCode(409);
    move(ticket, null).statusCode(400);
    given().get("/projects/api/tickets/" + ticket).then().body("ticket.status", equalTo("REPORTED"));
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
    given().get("/projects/api/features/" + feature).then().body("feature.status", equalTo("REPORTED"));
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

    given().get("/projects/api/tasks/" + task).then().body("task.status", equalTo("VERIFIED"));
    given().get("/projects/api/tasks/" + sibling).then().body("task.status", equalTo("IMPLEMENTED"));
    given().get("/projects/api/features/" + feature).then().body("feature.status", equalTo("IMPLEMENTED"));
    given().get("/projects/api/epics/" + epic).then().body("epic.status", equalTo("IMPLEMENTED"));
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
    given().get("/projects/api/features/" + feature).then().body("feature.status", equalTo("REFINED"));
  }

  /** The epic and the campaign move by their own doors' path, for a person. */
  @Test
  void anAdminMovesAnEpicAndACampaign() {
    EntityFixtures.Project project = EntityFixtures.project("Status Epic");
    String epic = EntityFixtures.epic(project.id());
    String campaign = EntityFixtures.campaign(project.id());

    move(epic, "REFINED")
        .statusCode(200)
        .body("status", equalTo("REFINED"))
        .body("statusBefore", equalTo("REPORTED"));
    given().get("/projects/api/epics/" + epic).then().body("epic.status", equalTo("REFINED"));

    move(campaign, "REFINED").statusCode(200).body("status", equalTo("REFINED"));
    given()
        .get("/projects/api/campaigns/" + campaign)
        .then()
        .body("campaign.status", equalTo("REFINED"));
  }

  /**
   * An agent moving an epic is refused as {@code POST /epics/{id}/transition} refuses it, and
   * nothing is written. The forwarded agent carries no project claim either, so the message is what
   * tells the epic's rule from the binding: the epic's refusal comes first.
   */
  @Test
  void anAgentMovingAnEpicIsA403AndNothingMoves() {
    EntityFixtures.Project project = EntityFixtures.project("Status Agent Epic");
    String epic = EntityFixtures.epic(project.id());

    move(asForwardedAgent(), epic, "REFINED")
        .statusCode(403)
        .body("message", containsString("qits:admin alone"));
    given().get("/projects/api/epics/" + epic).then().body("epic.status", equalTo("REPORTED"));

    // And an id naming nothing is still a 404 for the agent: the row is resolved first.
    move(asForwardedAgent(), "no-such-entity", "REFINED").statusCode(404);
  }
}
