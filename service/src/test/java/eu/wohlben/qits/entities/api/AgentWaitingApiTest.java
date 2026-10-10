package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityBlockState;
import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import eu.wohlben.qits.projects.api.AgentWaiting;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentEntities;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentEntities.Told;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/work/{id}/agent-waiting} and the derived block it feeds (qits-895): an
 * agent session waiting for a person reads as BLOCKED once the wait has stood for the debounce,
 * beside — never instead of — the explicit block, and it never stops a dispatch.
 *
 * <p>The debounce is the shipped 60s; a test does not wait it out but moves the recorded wait back
 * in time ({@link #backdate}), which is the one write here that does not go through a door.
 */
@QuarkusTest
class AgentWaitingApiTest {

  @Inject RecordingWorkspaceAgentEntities agents;

  @Inject AgentWaiting waiting;

  @Inject WorkEntityRepository rows;

  @Inject EntityDispatchService entities;

  @Inject ProjectService projects;

  @BeforeEach
  void forgetWhatTheAgentsWereTold() {
    agents.reset();
  }

  // --- the calls ---------------------------------------------------------------------------------

  private static RequestSpecification asSystem() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "dev-qits-workspaces")
        .header("X-Qits-Roles", "qits:system");
  }

  private static ValidatableResponse report(String id, Map<String, Object> body) {
    return asSystem().body(body).when().post("/projects/api/work/" + id + "/agent-waiting").then();
  }

  /** A Stop: the session ended its turn with nothing in flight, stamped now. */
  private static void stop(String id) {
    report(id, map("waiting", true, "cause", "Stop", "sessionId", "s-1")).statusCode(204);
  }

  /** The session took a prompt again. */
  private static void resume(String id) {
    report(id, map("waiting", false, "cause", "UserPromptSubmit", "sessionId", "s-1"))
        .statusCode(204);
  }

  private static ValidatableResponse read(String id) {
    return given().get("/projects/api/work/" + id).then().statusCode(200);
  }

  private static ValidatableResponse setBlocked(String id, boolean blocked, String reason) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "dana")
        .header("X-Qits-Roles", "qits:admin")
        .body(map("blocked", blocked, "reason", reason))
        .when()
        .post("/projects/api/work/" + id + "/blocked")
        .then();
  }

  private static void walk(String id, String... targets) {
    for (String target : targets) {
      given()
          .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
          .contentType(ContentType.JSON)
          .body(map("target", target))
          .when()
          .post("/projects/api/work/" + id + "/status")
          .then()
          .statusCode(200);
    }
  }

  /** Moves the recorded wait {@code seconds} back, as if it had stood that long already. */
  private void backdate(String id, int seconds) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.getEntityManager()
                    .createNativeQuery(
                        "update entity set agent_waiting_since = agent_waiting_since - make_interval(secs => "
                            + seconds
                            + ") where id = '"
                            + id
                            + "'")
                    .executeUpdate());
  }

  private WorkEntity row(String id) {
    return entities.fresh(id);
  }

  /**
   * What the agents of {@code project}'s entities were told with this flag — filtered by the
   * project's wrapper, because a sweep announces every wait in its window, other suites' included.
   */
  private List<Told> toldBlocked(EntityFixtures.Project project, boolean blocked) {
    String wrapper = projects.findWrapper(project.id()).orElseThrow().id;
    return agents.calls().stream()
        .filter(told -> told.repositoryId().equals(wrapper) && told.blocked() == blocked)
        .toList();
  }

  private static int commentsOn(String id) {
    return given()
        .get("/projects/api/work/" + id + "/comments")
        .then()
        .statusCode(200)
        .extract()
        .path("entries.size()");
  }

  private static int auditOf(String id) {
    return given()
        .get("/projects/api/work/" + id + "/audit")
        .then()
        .statusCode(200)
        .extract()
        .path("entries.size()");
  }

  /** The entry of {@code id} in its project's listing. */
  private static String inListing(String id) {
    return "entities.find { it.id == '" + id + "' }";
  }

  // --- the debounce ------------------------------------------------------------------------------

  /**
   * A Stop becomes a block only once the wait has stood for the debounce: before it, nothing reads
   * blocked and nobody is told; after it, the read and the project's listing carry the derived
   * block with its source and its fixed sentence, the sweep tells the agents, and nothing was
   * written on the thread or in the audit log.
   */
  @Test
  void aStopBecomesABlockOnlyAfterTheDebounce() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Debounce");
    String ticket = EntityFixtures.ticket(project.id());
    int comments = commentsOn(ticket);
    int audit = auditOf(ticket);
    agents.reset();

    stop(ticket);
    read(ticket)
        .body("blocked", equalTo(false))
        .body("$", not(hasKey("blockSource")))
        .body("$", not(hasKey("blockReason")))
        .body("$", not(hasKey("blockedBy")));
    assertNotNull(row(ticket).agentWaitingSince, "the wait is recorded at once");
    assertEquals("Stop", row(ticket).agentWaitingCause);
    assertFalse(row(ticket).blocked, "and the explicit flag is never touched");
    assertEquals(List.of(), agents.calls(), "a wait inside the debounce tells nobody");

    backdate(ticket, 70);
    read(ticket)
        .body("blocked", equalTo(true))
        .body("blockSource", equalTo("AGENT_WAITING"))
        .body("blockReason", equalTo(EntityBlockState.AGENT_WAITING_REASON))
        .body("$", not(hasKey("blockedBy")));
    given()
        .get("/projects/api/projects/" + project.id() + "/work")
        .then()
        .statusCode(200)
        .body(inListing(ticket) + ".blocked", equalTo(true))
        .body(inListing(ticket) + ".blockSource", equalTo("AGENT_WAITING"))
        .body(inListing(ticket) + ".blockReason", equalTo(EntityBlockState.AGENT_WAITING_REASON));
    assertFalse(row(ticket).blocked, "the derived block is read, never stored as the flag");

    waiting.forgetLastSweep();
    waiting.sweep(Instant.now());
    assertEquals(1, toldBlocked(project, true).size(), "the sweep told the agents: " + agents.calls());
    assertEquals("REPORTED", toldBlocked(project, true).get(0).status());

    assertEquals(comments, commentsOn(ticket), "a derived block writes no comment");
    assertEquals(audit, auditOf(ticket), "and no audit row");
  }

  /** A turn answered inside the debounce never reads as blocked, and the sweep has nothing to say. */
  @Test
  void aWaitEndedInsideTheDebounceNeverBlocks() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Ended Early");
    String ticket = EntityFixtures.ticket(project.id());
    agents.reset();

    stop(ticket);
    resume(ticket);
    backdate(ticket, 70); // nothing to move: the wait is gone

    read(ticket).body("blocked", equalTo(false)).body("$", not(hasKey("blockSource")));
    assertNull(row(ticket).agentWaitingSince);
    waiting.forgetLastSweep();
    waiting.sweep(Instant.now());
    assertEquals(List.of(), toldBlocked(project, true));
  }

  /** The session working again clears a derived block that stood, and the agents are told at once. */
  @Test
  void resumingClearsAnEffectiveDerivedBlock() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Cleared");
    String ticket = EntityFixtures.ticket(project.id());
    stop(ticket);
    backdate(ticket, 70);
    read(ticket).body("blocked", equalTo(true));
    agents.reset();

    resume(ticket);

    read(ticket).body("blocked", equalTo(false)).body("$", not(hasKey("blockSource")));
    assertNull(row(ticket).agentWaitingSince);
    assertNull(row(ticket).agentWaitingCause);
    assertEquals(1, toldBlocked(project, false).size(), "the end of the block is said: " + agents.calls());
  }

  // --- the two sources ---------------------------------------------------------------------------

  /**
   * A derived block never clears the explicit one: both stand as BOTH with the explicit reason and
   * actor, and the session resuming leaves the explicit block exactly as it was.
   */
  @Test
  void aDerivedBlockNeverClearsTheExplicitOne() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Beside Explicit");
    String ticket = EntityFixtures.ticket(project.id());
    setBlocked(ticket, true, "the owner has to choose").statusCode(200)
        .body("block.blocked", equalTo(true))
        .body("block.blockSource", equalTo("EXPLICIT"))
        .body("block.blockReason", equalTo("the owner has to choose"))
        .body("block.blockedBy", equalTo("dana"));

    stop(ticket);
    backdate(ticket, 70);
    read(ticket)
        .body("blocked", equalTo(true))
        .body("blockSource", equalTo("BOTH"))
        .body("blockReason", equalTo("the owner has to choose"))
        .body("blockedBy", equalTo("dana"));

    resume(ticket);
    read(ticket)
        .body("blocked", equalTo(true))
        .body("blockSource", equalTo("EXPLICIT"))
        .body("blockReason", equalTo("the owner has to choose"))
        .body("blockedBy", equalTo("dana"));
    WorkEntity stored = row(ticket);
    assertTrue(stored.blocked);
    assertEquals("dana", stored.blockedBy);
    assertEquals("the owner has to choose", stored.blockedReason);
  }

  /**
   * An explicit unblock clears both — and when only the derived block stood it is still a change:
   * the "Unblocked." remark lands and the agents are told.
   */
  @Test
  void anExplicitUnblockClearsBoth() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Unblocked");
    String ticket = EntityFixtures.ticket(project.id());
    stop(ticket);
    backdate(ticket, 70);
    read(ticket).body("blocked", equalTo(true)).body("blockSource", equalTo("AGENT_WAITING"));
    int comments = commentsOn(ticket);
    agents.reset();

    setBlocked(ticket, false, null)
        .statusCode(200)
        .body("block.blocked", equalTo(false))
        .body("block", not(hasKey("blockSource")));

    read(ticket).body("blocked", equalTo(false));
    assertNull(row(ticket).agentWaitingSince, "the unblock cleared the derived wait");
    assertEquals(comments + 1, commentsOn(ticket), "the unblock is said on the thread");
    assertEquals(1, toldBlocked(project, false).size(), "and the agents are told: " + agents.calls());

    // Both at once, cleared by one unblock.
    setBlocked(ticket, true, "waiting on a release").statusCode(200);
    stop(ticket);
    backdate(ticket, 70);
    read(ticket).body("blockSource", equalTo("BOTH"));
    setBlocked(ticket, false, null).statusCode(200);
    read(ticket).body("blocked", equalTo(false)).body("$", not(hasKey("blockSource")));
    WorkEntity stored = row(ticket);
    assertFalse(stored.blocked);
    assertNull(stored.blockedBy);
    assertNull(stored.blockedReason);
    assertNull(stored.agentWaitingSince);
  }

  // --- transitions -------------------------------------------------------------------------------

  /** The status door clears both blocks, and stamps the activity a stale frame is judged against. */
  @Test
  void theStatusDoorClearsBoth() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Status Door");
    String ticket = EntityFixtures.ticket(project.id());
    setBlocked(ticket, true, "the owner has to choose").statusCode(200);
    stop(ticket);
    backdate(ticket, 70);
    read(ticket).body("blockSource", equalTo("BOTH"));

    walk(ticket, "REFINED");

    read(ticket).body("blocked", equalTo(false)).body("$", not(hasKey("blockSource")));
    WorkEntity stored = row(ticket);
    assertFalse(stored.blocked);
    assertNull(stored.blockedBy);
    assertNull(stored.blockedReason);
    assertNull(stored.agentWaitingSince);
    assertNull(stored.agentWaitingCause);
    assertNotNull(stored.agentActivityAt, "the move stamps the activity");
  }

  /**
   * The PUT-shaped transition ({@code EntityTransitionService}, behind {@code PUT /work/{q}}, {@code
   * POST /work/transition} and {@code transition_entities}) clears both on a status move too.
   */
  @Test
  void thePutShapedTransitionClearsBoth() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Put Door");
    String ticket = EntityFixtures.ticket(project.id());
    setBlocked(ticket, true, "the owner has to choose").statusCode(200);
    stop(ticket);
    backdate(ticket, 70);
    read(ticket).body("blockSource", equalTo("BOTH"));

    given()
        .contentType(ContentType.JSON)
        .body(
            map(
                "archetype", "TICKET",
                "title", "The ticket",
                "status", "DROPPED",
                "ticketType", "BUG",
                "impetus", "it occurs",
                "acceptanceCriteria", TestCriteria.CRITERIA))
        .when()
        .put("/projects/api/work/" + ticket)
        .then()
        .statusCode(200)
        .body("status", equalTo("DROPPED"))
        .body("blocked", equalTo(false))
        .body("$", not(hasKey("blockSource")));

    WorkEntity stored = row(ticket);
    assertFalse(stored.blocked);
    assertNull(stored.blockedBy);
    assertNull(stored.blockedReason);
    assertNull(stored.agentWaitingSince);
    assertNotNull(stored.agentActivityAt);
  }

  /**
   * A frame stamped before a transition arrives after it and is ignored: it cannot re-derive a block
   * at the status the move landed on. A frame stamped now still applies.
   */
  @Test
  void aStaleFrameFromBeforeATransitionIsIgnored() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Stale Frame");
    String ticket = EntityFixtures.ticket(project.id());
    walk(ticket, "REFINED", "READY_FOR_DEV");

    long beforeTheMove = Instant.now().minusSeconds(60).toEpochMilli();
    report(ticket, map("waiting", true, "cause", "Stop", "sessionId", "s-1", "at", beforeTheMove))
        .statusCode(204);
    assertNull(row(ticket).agentWaitingSince, "the stale frame changed nothing");

    report(ticket, map("waiting", true, "cause", "Stop", "sessionId", "s-1",
            "at", Instant.now().toEpochMilli()))
        .statusCode(204);
    assertNotNull(row(ticket).agentWaitingSince, "a current frame at READY_FOR_DEV applies");
  }

  // --- where a wait counts -----------------------------------------------------------------------

  /** A status that starts no phase derives nothing; neither does a feature. Each frame is a 204. */
  @Test
  void statusesWithNoPhaseAndPlanPiecesAreIgnored() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting No Phase");

    String refined = EntityFixtures.ticket(project.id());
    walk(refined, "REFINED");
    String verified = EntityFixtures.ticket(project.id());
    walk(verified, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED");
    String done = EntityFixtures.ticket(project.id());
    walk(done, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");
    String dropped = EntityFixtures.ticket(project.id());
    walk(dropped, "DROPPED");
    String epic = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(epic);

    for (String id : List.of(refined, verified, done, dropped, feature)) {
      stop(id);
      assertNull(row(id).agentWaitingSince, id + " derives nothing");
      read(id).body("$", not(hasKey("blockSource")));
    }
  }

  // --- the gates read the explicit flag only -----------------------------------------------------

  /**
   * A derived block never refuses a dispatch: the read says blocked and dispatchable at once, and
   * the press goes through.
   */
  @Test
  void aDerivedBlockNeverRefusesADispatch() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Dispatch");
    String ticket = EntityFixtures.ticket(project.id());
    stop(ticket);
    backdate(ticket, 70);

    given()
        .get("/projects/api/work/" + ticket + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.blocked", equalTo(true))
        .body("state.blockSource", equalTo("AGENT_WAITING"))
        .body("state.dispatchable", equalTo(true));
    given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
        .contentType(ContentType.JSON)
        .body(map("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + ticket + "/dispatch")
        .then()
        .statusCode(200)
        .body("dispatch.phase", equalTo("refine"));
  }

  // --- the door ----------------------------------------------------------------------------------

  /** {@code qits:system} alone; an unknown id is a 404 and a frame without {@code waiting} a 400. */
  @Test
  void theDoorIsAPlatformServicesAlone() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Door");
    String ticket = EntityFixtures.ticket(project.id());

    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "agent-qits-895")
        .header("X-Qits-Roles", "qits:agent")
        .body(map("waiting", true))
        .when()
        .post("/projects/api/work/" + ticket + "/agent-waiting")
        .then()
        .statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:admin")
        .body(map("waiting", true))
        .when()
        .post("/projects/api/work/" + ticket + "/agent-waiting")
        .then()
        .statusCode(403);
    assertNull(row(ticket).agentWaitingSince, "nothing refused was written");

    report("00000000-0000-4000-8000-00000000dead", map("waiting", true)).statusCode(404);
    report(ticket, map("cause", "Stop")).statusCode(400);
    report(ticket, map("waiting", true)).statusCode(204);
    assertNotNull(row(ticket).agentWaitingSince);
  }

  /** The thread stays empty however many frames arrive. */
  @Test
  void framesWriteNothingOnTheThread() {
    EntityFixtures.Project project = EntityFixtures.project("Waiting Thread");
    String ticket = EntityFixtures.ticket(project.id());
    stop(ticket);
    resume(ticket);
    stop(ticket);
    given()
        .get("/projects/api/work/" + ticket + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(0));
  }
}
