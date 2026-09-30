package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/entities/{id}/blocked} (qits-592): the block of every archetype with a
 * lifecycle, over the rule the ticket door always had — the reason required, the phase refusal, the
 * remark on the entity's own thread. The ticket door's own cases stay in {@code TicketApiTest}; the
 * agent binding is {@code EntityAgentBoundsTest}'s, because a {@code @QuarkusTest} cannot put a
 * project claim in front of this service.
 */
@QuarkusTest
class EntityBlockApiTest {

  private static ValidatableResponse setBlocked(String id, boolean blocked, String reason) {
    return given()
        .contentType(ContentType.JSON)
        .body(map("blocked", blocked, "reason", reason))
        .when()
        .post("/projects/api/entities/" + id + "/blocked")
        .then();
  }

  private static void walk(String id, String... targets) {
    for (String target : targets) {
      given()
          .contentType(ContentType.JSON)
          .body(map("target", target))
          .when()
          .post("/projects/api/entities/" + id + "/status")
          .then()
          .statusCode(200);
    }
  }

  private static ValidatableResponse thread(String id) {
    return given().when().get("/projects/api/entities/" + id + "/comments").then().statusCode(200);
  }

  /**
   * An epic, named by its qualified id: the flag is set on the row and read back by the epic's own
   * door, the status does not move, and the reason lands on the epic's own thread.
   */
  @Test
  void anEpicIsBlockedAndUnblockedByItsQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Block Epic");
    String epic = EntityFixtures.epic(project.id());
    walk(epic, "REFINED");
    String qualified = EntityFixtures.qualifiedId(epic);

    setBlocked(qualified, true, "the sibling library has not released")
        .statusCode(200)
        .body("block.entityId", equalTo(epic))
        .body("block.archetype", equalTo("EPIC"))
        .body("block.status", equalTo("REFINED"))
        .body("block.blocked", equalTo(true));
    given()
        .get("/projects/api/epics/" + epic)
        .then()
        .body("epic.blocked", equalTo(true))
        .body("epic.status", equalTo("REFINED"));
    given().get("/projects/api/entities/" + epic).then().body("blocked", equalTo(true));
    thread(epic)
        .body("entries", hasSize(1))
        .body("entries[0].comment.body", equalTo("Blocked: the sibling library has not released"));

    setBlocked(epic, false, null).statusCode(200).body("block.blocked", equalTo(false));
    given().get("/projects/api/epics/" + epic).then().body("epic.blocked", equalTo(false));
    thread(epic).body("entries", hasSize(2)).body("entries[1].comment.body", equalTo("Unblocked."));
  }

  /** A campaign, the same way: its own reads carry the flag. */
  @Test
  void aCampaignIsBlockedAndUnblocked() {
    EntityFixtures.Project project = EntityFixtures.project("Block Campaign");
    String campaign = EntityFixtures.campaign(project.id());
    walk(campaign, "REFINED");

    setBlocked(campaign, true, "an operator has to widen the runner pool first")
        .statusCode(200)
        .body("block.archetype", equalTo("CAMPAIGN"))
        .body("block.blocked", equalTo(true));
    given().get("/projects/api/campaigns/" + campaign).then().body("campaign.blocked", equalTo(true));
    given()
        .get("/projects/api/projects/" + project.id() + "/campaigns")
        .then()
        .body("campaigns[0].blocked", equalTo(true));
    thread(campaign)
        .body("entries[0].comment.body", containsString("widen the runner pool"));

    setBlocked(campaign, false, "it was widened").statusCode(200).body("block.blocked", equalTo(false));
    given()
        .get("/projects/api/campaigns/" + campaign)
        .then()
        .body("campaign.blocked", equalTo(false));
  }

  /** Absent and blank are one 400, and nothing is written — neither the flag nor the thread. */
  @Test
  void blockingWithNoStatedBlockerIsA400() {
    EntityFixtures.Project project = EntityFixtures.project("Block No Reason");
    String epic = EntityFixtures.epic(project.id());

    for (String reason : Arrays.asList(null, "   ")) {
      setBlocked(epic, true, reason)
          .statusCode(400)
          .body("message", containsString("A blocked epic needs a stated blocker"));
    }
    given().get("/projects/api/epics/" + epic).then().body("epic.blocked", equalTo(false));
    thread(epic).body("entries", hasSize(0));
  }

  /** VERIFIED starts no phase, so there is nothing to block; the refusal names the epic. */
  @Test
  void aVerifiedEpicIsA409() {
    EntityFixtures.Project project = EntityFixtures.project("Block Verified");
    String epic = EntityFixtures.epic(project.id());
    walk(epic, "REFINED", "IMPLEMENTED", "VERIFIED");
    // The move into VERIFIED says its own sentence on the thread; the refusal must add nothing.
    int before = thread(epic).extract().path("entries.size()");

    setBlocked(epic, true, "waiting on somebody")
        .statusCode(409)
        .body("message", containsString("Epic " + epic))
        .body("message", containsString("no phase is running and there is nothing to block"));
    thread(epic).body("entries", hasSize(before));
  }

  /** A feature has no lifecycle of its own: its phase is its epic's. */
  @Test
  void aFeatureIsA409() {
    EntityFixtures.Project project = EntityFixtures.project("Block Feature");
    String feature = EntityFixtures.feature(EntityFixtures.epic(project.id()));

    setBlocked(feature, true, "waiting on somebody")
        .statusCode(409)
        .body("message", containsString("Feature " + feature))
        .body("message", containsString("no lifecycle of its own"));
  }

  @Test
  void anIdNamingNothingIsA404() {
    setBlocked("no-such-entity", true, "anything").statusCode(404);
  }

  /**
   * A blocked epic is not dispatchable, and the press says why: the block, naming the epic — not
   * that no phase is left, which would send the reader to the opposite conclusion.
   */
  @Test
  void aBlockedEpicIsNotDispatchable() {
    EntityFixtures.Project project = EntityFixtures.project("Block Dispatch Epic");
    String epic = EntityFixtures.epic(project.id());
    walk(epic, "REFINED");
    setBlocked(epic, true, "the dossier owes a decision only a person can take").statusCode(200);

    given()
        .get("/projects/api/entities/" + epic + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.blocked", equalTo(true))
        .body("state.dispatchable", equalTo(false))
        .body("state.nextPhase", equalTo("implement"));
    given()
        .contentType(ContentType.JSON)
        .body(map("mode", "FLOW"))
        .when()
        .post("/projects/api/entities/" + epic + "/dispatch")
        .then()
        .statusCode(409)
        .body("message", containsString("Epic " + epic + " is blocked"))
        .body("message", not(containsString("no phase left to start")));
  }

  /** A blocked campaign reads not dispatchable, and its start press is a 409 naming the block. */
  @Test
  void aBlockedCampaignIsNotStarted() {
    EntityFixtures.Project project = EntityFixtures.project("Block Dispatch Campaign");
    String campaign = EntityFixtures.campaign(project.id());
    walk(campaign, "REFINED");
    setBlocked(campaign, true, "the pilot has to finish first").statusCode(200);

    given()
        .get("/projects/api/entities/" + campaign + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.blocked", equalTo(true))
        .body("state.dispatchable", equalTo(false));
    given()
        .contentType(ContentType.JSON)
        .body(map("mode", "FLOW"))
        .when()
        .post("/projects/api/entities/" + campaign + "/dispatch")
        .then()
        .statusCode(409)
        .body("message", containsString("Campaign " + campaign + " is blocked"));
    given()
        .get("/projects/api/campaigns/" + campaign)
        .then()
        .body("campaign.start", org.hamcrest.Matchers.nullValue());

    setBlocked(campaign, false, null).statusCode(200);
    given()
        .get("/projects/api/entities/" + campaign + "/dispatch")
        .then()
        .body("state.blocked", equalTo(false))
        .body("state.dispatchable", equalTo(true));
  }
}
