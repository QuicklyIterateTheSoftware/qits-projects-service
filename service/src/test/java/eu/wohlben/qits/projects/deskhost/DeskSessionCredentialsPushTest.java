package eu.wohlben.qits.projects.deskhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.agenthost.DaemonMessageCodec;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.control.ProjectConfig;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.refinementhost.FakeRefinementTokens;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials.Credential;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When a desk's daemon is handed its sessions' credentials (qits-1482), against the real registry,
 * the real token flows and a stub control socket: on hello, and again on every change — the delivery
 * loop toggled (its token minted and revoked), a refinement room opened (its token minted) and
 * discarded — and never to a daemon below capability 2.
 */
@QuarkusTest
class DeskSessionCredentialsPushTest {

  @Inject AgentDaemonRegistry daemons;

  @Inject DaemonMessageCodec codec;

  @Inject FrontDesks frontDesks;

  @Inject FrontDeskHooks hooks;

  @Inject DeskSessionCredentials sessionCredentials;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projectRows;

  @Inject FakeFrontDeskTokens tokens;

  @Inject FakeRefinementTokens refinementTokens;

  private final List<String> sent = new CopyOnWriteArrayList<>();

  private String projectId;

  private WebSocketConnection connection;

  @BeforeEach
  void arrange() {
    tokens.reset();
    refinementTokens.reset();
    sent.clear();
  }

  @AfterEach
  void tidy() {
    if (connection != null) {
      daemons.unregister(projectId, connection);
      connection = null;
    }
    if (projectId != null) {
      frontDesks.removeDesk(projectId);
    }
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private static String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
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

  private static long openRefinement(String epicId) {
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of())
            .when()
            .post("/projects/api/work/" + epicId + "/refinement")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  /** A control socket that records what is sent on it and answers nothing else. */
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

  /** A desk with its session token, and its daemon connected and greeted at {@code capability}. */
  private void connectedDesk(String name, int capability) {
    projectId = createProject(name);
    frontDesks.ensureRow(projectId);
    assertNotNull(row().sessionTokenId, "the session token is minted with the row");
    connection = stubConnection();
    daemons.register(projectId, connection);
    daemons.onMessage(
        projectId, connection, new Hello(projectId, "wrapper", capability, "2026.1022.1", null));
  }

  private FrontDesk row() {
    return QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId));
  }

  private void setDelivery(boolean enabled) {
    QuarkusTransaction.requiringNew()
        .run(() -> projectRows.findById(projectId).deliveryEnabled = enabled);
  }

  private static ProjectConfig.DeliverySettings delivery(boolean enabled) {
    return new ProjectConfig.DeliverySettings(
        enabled, 2, false, Set.of(), Duration.ofMinutes(10));
  }

  private List<SessionCredentials> frames() {
    return sent.stream()
        .map(codec::decode)
        .filter(SessionCredentials.class::isInstance)
        .map(SessionCredentials.class::cast)
        .toList();
  }

  private SessionCredentials last() {
    List<SessionCredentials> frames = frames();
    assertFalse(frames.isEmpty(), "no sessionCredentials frame was sent");
    return frames.get(frames.size() - 1);
  }

  private static Map<String, String> subjects(SessionCredentials frame) {
    return frame.credentials().stream()
        .collect(Collectors.toMap(Credential::key, Credential::subject));
  }

  // --- the tests ---------------------------------------------------------------------------------

  @Test
  void aCapabilityTwoDaemonIsSentTheListOnHelloAndOnEveryChange() {
    connectedDesk("Session Push", 2);
    String session = row().sessionTokenSubject;

    // On hello: the session token under both desk keys (the loop is off).
    assertEquals(1, frames().size(), "the list follows the ack at once");
    assertEquals(
        Map.of(
            SessionCredentials.FRONT_DESK_KEY, session, SessionCredentials.DESK_AGENTS_KEY, session),
        subjects(last()));

    // The loop on: its token is minted and stands under front-desk at once.
    setDelivery(true);
    int before = frames().size();
    hooks.onDeliveryChanged(projectId, delivery(true));
    String delivery = row().deliveryTokenSubject;
    assertNotNull(delivery);
    assertTrue(frames().size() > before, "the toggle re-sends the list");
    assertEquals(
        Map.of(
            SessionCredentials.FRONT_DESK_KEY,
            delivery,
            SessionCredentials.DESK_AGENTS_KEY,
            session),
        subjects(last()));
    assertEquals(
        "qits_tok_" + row().deliveryTokenId,
        last().find(SessionCredentials.FRONT_DESK_KEY).orElseThrow().token());

    // The loop off: the token is revoked and front-desk is back on the session token.
    String deliveryTokenId = row().deliveryTokenId;
    setDelivery(false);
    before = frames().size();
    hooks.onDeliveryChanged(projectId, delivery(false));
    assertFalse(tokens.isLive(deliveryTokenId));
    assertTrue(frames().size() > before);
    assertEquals(
        Map.of(
            SessionCredentials.FRONT_DESK_KEY, session, SessionCredentials.DESK_AGENTS_KEY, session),
        subjects(last()));

    // A refinement room opened: its token is minted and its entity id joins the list.
    String epicId = createEpic(projectId, "Pushed Epic");
    long room = openRefinement(epicId);
    SessionCredentials withRoom = last();
    assertEquals(
        Set.of(SessionCredentials.FRONT_DESK_KEY, SessionCredentials.DESK_AGENTS_KEY, epicId),
        subjects(withRoom).keySet());
    assertEquals(
        refinementTokens.mintsFor(room).get(0).tokenId(),
        withRoom.find(epicId).orElseThrow().token().substring("qits_tok_".length()));

    // …and discarded: the entity's key leaves the list.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/projects/api/refinements/" + room + "/discard")
        .then()
        .statusCode(200);
    assertEquals(
        Set.of(SessionCredentials.FRONT_DESK_KEY, SessionCredentials.DESK_AGENTS_KEY),
        subjects(last()).keySet());

    // The sweep's safety net re-sends the list unchanged.
    before = frames().size();
    assertTrue(sessionCredentials.push(projectId));
    assertEquals(before + 1, frames().size());
  }

  @Test
  void aSessionTokenMintedLateIsSentAtOnce() {
    tokens.failSession(true);
    projectId = createProject("Session Push Late");
    frontDesks.ensureRow(projectId);
    connection = stubConnection();
    daemons.register(projectId, connection);
    daemons.onMessage(projectId, connection, new Hello(projectId, "wrapper", 2, "2026.1022.1", null));
    assertTrue(last().credentials().isEmpty(), "no session token yet: no desk key at all");

    tokens.failSession(false);
    frontDesks.mintSessionIfMissing(projectId);

    assertEquals(
        Set.of(SessionCredentials.FRONT_DESK_KEY, SessionCredentials.DESK_AGENTS_KEY),
        subjects(last()).keySet());
  }

  @Test
  void aDaemonBelowCapabilityTwoIsNeverSentTheFrame() {
    connectedDesk("Session Push Old", 1);

    setDelivery(true);
    hooks.onDeliveryChanged(projectId, delivery(true));
    assertFalse(sessionCredentials.push(projectId));

    List<DaemonMessage> all = sent.stream().map(codec::decode).toList();
    assertEquals(1, all.size(), "the ack and nothing else: " + all);
    assertEquals(List.of(), frames());
  }
}
