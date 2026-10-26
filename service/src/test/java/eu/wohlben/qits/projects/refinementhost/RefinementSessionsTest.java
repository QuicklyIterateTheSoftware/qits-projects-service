package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.agenthost.DaemonMessageCodec;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.deskhost.FrontDesks;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A refinement room as a session of its project's front desk (epic qits-770), against the real
 * doors, the real registry and a stub control socket: the {@code sessions} frame on hello and on
 * every change (open, ensure, stop, discard, a move, a block, a retitle), never below capability 3
 * and always after the room's credential; the {@code sessionReport} written back onto the rows; the
 * desk wanted for a room ({@link eu.wohlben.qits.projects.deskhost.RefinementDemand}); a campaign's
 * room; and the per-project cap.
 */
@QuarkusTest
class RefinementSessionsTest {

  @Inject AgentDaemonRegistry daemons;

  @Inject DaemonMessageCodec codec;

  @Inject FrontDesks frontDesks;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projectRows;

  @Inject RefinementRepository rooms;

  @Inject RefinementSessions sessions;

  @Inject FakeRefinementTokens refinementTokens;

  private final List<String> sent = new CopyOnWriteArrayList<>();

  private final List<Long> opened = new ArrayList<>();

  private String projectId;

  private WebSocketConnection connection;

  @BeforeEach
  void arrange() {
    refinementTokens.reset();
    sent.clear();
    opened.clear();
  }

  @AfterEach
  void tidy() {
    for (Long room : opened) {
      given().contentType(ContentType.JSON).post("/projects/api/refinements/" + room + "/discard");
    }
    if (connection != null) {
      daemons.unregister(projectId, connection);
      connection = null;
    }
    if (projectId != null) {
      frontDesks.removeDesk(projectId);
    }
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private String createProject(String name) {
    projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    return projectId;
  }

  private static String createEpic(String projectId, String title) {
    return TestCriteria.give(
        WorkRequests.create(
                WorkRequests.map(
                    "archetype", "EPIC",
                    "project", projectId,
                    "title", title,
                    "description", "A draft."))
            .path("id"));
  }

  private static Response open(String entityId) {
    return given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/work/" + entityId + "/refinement");
  }

  private long openRoom(String entityId) {
    Number id = open(entityId).then().statusCode(200).extract().path("refinement.id");
    opened.add(id.longValue());
    return id.longValue();
  }

  private static Response door(long room, String verb) {
    return given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/refinements/" + room + "/" + verb);
  }

  private WebSocketConnection stubConnection() {
    String id = "c-" + UUID.randomUUID();
    return (WebSocketConnection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {WebSocketConnection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id" -> id;
                  case "isOpen" -> Boolean.TRUE;
                  case "sendTextAndAwait" -> {
                    sent.add(String.valueOf(args[0]));
                    yield null;
                  }
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "toString" -> "connection " + id;
                  default -> null;
                });
  }

  /** The project's desk daemon, connected and greeted at {@code capability}. */
  private void connect(int capability) {
    frontDesks.ensureRow(projectId);
    connection = stubConnection();
    daemons.register(projectId, connection);
    daemons.onMessage(
        projectId, connection, new Hello(projectId, "wrapper", capability, "2026.1025.1", null));
  }

  private List<Sessions> frames() {
    return sent.stream()
        .map(codec::decode)
        .filter(Sessions.class::isInstance)
        .map(Sessions.class::cast)
        .toList();
  }

  private Sessions last() {
    List<Sessions> frames = frames();
    assertFalse(frames.isEmpty(), "no sessions frame was sent");
    return frames.get(frames.size() - 1);
  }

  private Refinement row(long id) {
    return QuarkusTransaction.requiringNew().call(() -> rooms.findById(id));
  }

  private FrontDesk desk() {
    return QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId));
  }

  private void lifecycle(FrontDeskLifecycle lifecycle) {
    QuarkusTransaction.requiringNew()
        .run(() -> projectRows.findById(projectId).frontDeskLifecycle = lifecycle);
  }

  // --- the frame ---------------------------------------------------------------------------------

  @Test
  void aCapabilityThreeDaemonIsSentTheRoomAsARunningSessionAfterItsCredential() {
    createProject("Sessions Push");
    connect(3);
    assertEquals(List.of(), last().sessions(), "an ON_DEMAND desk with no room wants nothing");

    String epicId = createEpic(projectId, "Session Epic");
    long room = openRoom(epicId);

    Sessions.Session session = last().find(epicId).orElseThrow();
    assertEquals(RefinementSessions.ROOM_SURFACE, session.surface());
    assertEquals(DaemonProtocol.DesiredState.RUNNING, session.state());
    assertNotNull(session.entity());
    assertEquals(WorkRequests.qualifiedId(epicId), session.entity().qualifiedId());
    assertEquals("Session Epic", session.entity().title());
    assertEquals("REPORTED", session.entity().status());
    assertFalse(session.entity().blocked());

    // The credential frame naming the key went out before the first sessions frame naming it.
    List<Object> decoded = sent.stream().map(codec::decode).map(Object.class::cast).toList();
    int credential = -1;
    int entry = -1;
    for (int i = 0; i < decoded.size(); i++) {
      if (credential < 0
          && decoded.get(i) instanceof SessionCredentials frame
          && frame.find(epicId).isPresent()) {
        credential = i;
      }
      if (entry < 0 && decoded.get(i) instanceof Sessions frame && frame.find(epicId).isPresent()) {
        entry = i;
      }
    }
    assertTrue(credential >= 0 && credential < entry, "credential first: " + decoded);

    // The room answers as a session room.
    given()
        .when()
        .get("/projects/api/refinements/" + room)
        .then()
        .statusCode(200)
        .body("refinement.branch", nullValue())
        .body("refinement.parent", nullValue())
        .body("refinement.runtimeStatus", equalTo("STARTING"))
        .body("refinement.deskBase", equalTo("/projects/container/" + projectId + "/"))
        .body("refinement.deskStatus", equalTo("QUEUED"));
  }

  @Test
  void anAlwaysOnDeskLeadsTheListWithItsOwnSession() {
    createProject("Sessions Always On");
    lifecycle(FrontDeskLifecycle.ALWAYS_ON);
    connect(3);

    Sessions frame = last();
    assertEquals(SessionCredentials.FRONT_DESK_KEY, frame.sessions().get(0).key());
    assertEquals(DaemonProtocol.DesiredState.RUNNING, frame.sessions().get(0).state());
  }

  @Test
  void aDaemonBelowCapabilityThreeIsNeverSentTheFrame() {
    createProject("Sessions Old Daemon");
    connect(2);
    openRoom(createEpic(projectId, "Old Daemon Epic"));

    assertEquals(List.of(), frames());
    assertFalse(sessions.push(projectId));
  }

  @Test
  void aMoveABlockAndARetitleAreSentAgain() {
    createProject("Sessions Entity Changes");
    connect(3);
    String epicId = createEpic(projectId, "Changing Epic");
    openRoom(epicId);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("blocked", true, "reason", "the owner decides the scope"))
        .when()
        .post("/projects/api/work/" + epicId + "/blocked")
        .then()
        .statusCode(200);
    Sessions.Entity blocked = last().find(epicId).orElseThrow().entity();
    assertTrue(blocked.blocked());
    assertEquals("EXPLICIT", blocked.blockSource());
    assertNotNull(blocked.qualifiedId(), "the announced facts keep the qualified id");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("blocked", false))
        .when()
        .post("/projects/api/work/" + epicId + "/blocked")
        .then()
        .statusCode(200);
    assertFalse(last().find(epicId).orElseThrow().entity().blocked());

    given()
        .contentType("application/merge-patch+json")
        .body(Map.of("title", "Renamed Epic"))
        .when()
        .patch("/projects/api/work/" + epicId)
        .then()
        .statusCode(200);
    assertEquals("Renamed Epic", last().find(epicId).orElseThrow().entity().title());

    WorkRequests.status(epicId, "REFINED").then().statusCode(200);
    assertEquals("REFINED", last().find(epicId).orElseThrow().entity().status());
  }

  // --- the report --------------------------------------------------------------------------------

  @Test
  void theReportLandsOnTheRowsAndAnAbsentKeyIsStopped() {
    createProject("Sessions Report");
    connect(3);
    String a = createEpic(projectId, "Reported A");
    String b = createEpic(projectId, "Reported B");
    long roomA = openRoom(a);
    long roomB = openRoom(b);

    daemons.onMessage(
        projectId,
        connection,
        new SessionReport(
            List.of(
                new SessionReport.Session(
                    SessionCredentials.FRONT_DESK_KEY, "STOPPED", null, null, null),
                new SessionReport.Session(a, "RUNNING", "cmd-1", "sess-1", null),
                new SessionReport.Session(b, "FAILED", null, null, "the harness is not signed in"))));

    Refinement first = row(roomA);
    assertEquals("RUNNING", first.sessionState);
    assertEquals("cmd-1", first.sessionCommandId);
    assertEquals("sess-1", first.sessionId);
    Refinement second = row(roomB);
    assertEquals("FAILED", second.sessionState);
    assertEquals("the harness is not signed in", second.sessionDetail);
    given()
        .when()
        .get("/projects/api/refinements/" + roomA)
        .then()
        .body("refinement.runtimeStatus", equalTo("RUNNING"))
        .body("refinement.sessionId", equalTo("sess-1"))
        .body("refinement.commandId", equalTo("cmd-1"));
    given()
        .when()
        .get("/projects/api/refinements/" + roomB)
        .then()
        .body("refinement.runtimeStatus", equalTo("FAILED"))
        .body("refinement.runtimeError", equalTo("the harness is not signed in"));

    // A key the next report leaves out is not running; its session id is kept to resume.
    daemons.onMessage(projectId, connection, new SessionReport(List.of()));
    Refinement gone = row(roomA);
    assertEquals("STOPPED", gone.sessionState);
    assertNull(gone.sessionCommandId);
    assertEquals("sess-1", gone.sessionId);
    sessions.push(projectId);
    assertEquals("sess-1", last().find(a).orElseThrow().resumeSessionId());
  }

  // --- the doors and the desk --------------------------------------------------------------------

  @Test
  void ensureAndStopMoveTheSessionAndTheOnDemandDesk() {
    createProject("Sessions Doors");
    connect(3);
    String epicId = createEpic(projectId, "Door Epic");
    long room = openRoom(epicId);
    assertEquals(FrontDeskDesired.RUNNING, desk().desired, "a room starts an ON_DEMAND desk");

    door(room, "stop").then().statusCode(200).body("refinement.runtimeStatus", equalTo("STOPPED"));
    assertNull(row(room).lastDemandAt);
    assertEquals(DaemonProtocol.DesiredState.STOPPED, last().find(epicId).orElseThrow().state());
    assertEquals(FrontDeskDesired.STOPPED, desk().desired, "no room wants it any more");

    door(room, "ensure").then().statusCode(200).body("refinement.id", equalTo((int) room));
    assertNotNull(row(room).lastDemandAt);
    assertEquals(DaemonProtocol.DesiredState.RUNNING, last().find(epicId).orElseThrow().state());
    assertEquals(FrontDeskDesired.RUNNING, desk().desired);

    // ensure-container stays for the CLI's pact (qits-1316): a settled narration, the session asked
    // for. Its three siblings are retired (RefinementContainersRetiredTest).
    door(room, "stop").then().statusCode(200);
    assertNull(row(room).lastDemandAt);
    String process =
        door(room, "ensure-container").then().statusCode(200).extract().path("technicalProcessId");
    assertNotNull(process);
    assertNotNull(row(room).lastDemandAt);

    // The idle window closing is noticed: an aged demand is STOPPED, and so is the desk.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rooms.update(
                    "lastDemandAt = ?1 where id = ?2",
                    Instant.now().minus(Duration.ofHours(5)),
                    room));
    sessions.push(projectId);
    assertEquals(DaemonProtocol.DesiredState.STOPPED, last().find(epicId).orElseThrow().state());
    frontDesks.reconcileAndPush(projectId, Instant.now());
    assertEquals(FrontDeskDesired.STOPPED, desk().desired);
  }

  @Test
  void discardStopsTheSessionAndDeletesTheRow() {
    createProject("Sessions Discard");
    connect(3);
    String epicId = createEpic(projectId, "Discarded Epic");
    long room = openRoom(epicId);
    String tokenId = row(room).tokenId;
    assertNotNull(tokenId);

    door(room, "discard").then().statusCode(200);
    opened.remove(Long.valueOf(room));

    assertNull(row(room));
    assertTrue(last().find(epicId).isEmpty(), "the key leaves the list, which stops it");
    assertFalse(refinementTokens.isLive(tokenId), "the room's token is revoked");
    assertEquals(FrontDeskDesired.STOPPED, desk().desired);
  }

  @Test
  void aCampaignOpensARoomLikeAnEpic() {
    createProject("Sessions Campaign");
    String campaignId = WorkRequests.campaign(projectId, "Quarter Close");

    open(campaignId)
        .then()
        .statusCode(200)
        .body("refinement.entityId", equalTo(campaignId))
        .body("refinement.branch", nullValue());
    Refinement room = QuarkusTransaction.requiringNew().call(() -> rooms.findByEntity(campaignId))
        .orElseThrow();
    opened.add(room.id);
    assertTrue(sessions.wantsRunning(room, Instant.now()));
  }

  @Test
  void aFifthRunningRoomIsRefusedNamingTheFour() {
    createProject("Sessions Cap");
    List<String> qualified = new ArrayList<>();
    long first = -1;
    for (int i = 1; i <= 4; i++) {
      String epicId = createEpic(projectId, "Capped " + i);
      long room = openRoom(epicId);
      if (first < 0) {
        first = room;
      }
      qualified.add(WorkRequests.qualifiedId(epicId));
    }
    String fifth = createEpic(projectId, "Capped 5");

    Response refused = open(fifth);
    refused
        .then()
        .statusCode(409)
        .body("error", equalTo(RefinementService.REFINEMENT_SESSIONS_FULL));
    for (String id : qualified) {
      refused.then().body("message", containsString(id));
    }
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> rooms.findByEntity(fifth)).isEmpty(),
        "nothing is half-made");

    // A stopped room's ensure is refused at the cap too; stopping one makes room.
    door(first, "stop").then().statusCode(200);
    long fifthRoom = openRoom(fifth);
    door(first, "ensure").then().statusCode(409);
    door(fifthRoom, "stop").then().statusCode(200);
    door(first, "ensure").then().statusCode(200);
  }
}
