package eu.wohlben.qits.projects.deskhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.agenthost.AgentContainerFactory;
import eu.wohlben.qits.projects.control.DeskRunners;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.entity.DeskRunnerCapabilities;
import eu.wohlben.qits.projects.persistence.DeskRunnerRepository;
import eu.wohlben.qits.projects.security.MockIdpTenant;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthCheck;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthChecked;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginPresence;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginState;
import eu.wohlben.qits.projectsdeskrunner.protocol.ProbeLogin;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The front-desk runner socket and the registry behind it (qits-767), driven by a real WebSocket
 * from a scripted {@link FakeDeskRunner} — qits-workspaces-service's {@code
 * WorkspaceRunnerSocketTest} arrangement.
 *
 * <p><b>Under the machine gate</b> ({@link MockIdpTenant}), because the runner is named by its
 * bearer's {@code sub} and with the gate off there is no token to read one from. The bearers are
 * RS256 JWTs the mock idp signs, so the upgrade's role check is quarkus-oidc's real one.
 */
@QuarkusTest
@WithTestResource(DeskRunnerMockIdpTenant.class)
class DeskRunnerSocketTest {

  private static final String PIN = DeskRunnerBinary.VERSION;

  private static final String OLD = "2000.101.1";

  private static final String DOMAIN = DeskRunnerAddressesFixture.DOMAIN;

  @TestHTTPResource(DeskRunnerProtocol.SOCKET_PATH)
  URI endpoint;

  @Inject Vertx vertx;

  @Inject DeskRunnerRegistry registry;

  @Inject DeskRunners runners;

  @Inject DeskRunnerRepository rows;

  @Inject AgentContainerFactory agentContainers;

  private final List<FakeDeskRunner> dialled = new ArrayList<>();

  private final List<UUID> created = new ArrayList<>();

  @BeforeEach
  void addressTheRunners() {
    QuarkusMock.installMockForType(
        DeskRunnerAddressesFixture.withDomain(DOMAIN), DeskRunnerAddresses.class);
  }

  @AfterEach
  void hangUp() {
    dialled.forEach(FakeDeskRunner::close);
    dialled.clear();
    registry.reconnectGrace(null);
    QuarkusTransaction.requiringNew().run(() -> created.forEach(rows::deleteById));
    created.clear();
  }

  // --- rows and dials -----------------------------------------------------------------------------

  /** A registered runner whose client is {@code clientId}, quarantined as registration leaves it. */
  private DeskRunner registered(String clientId, int slots) {
    UUID id = UUID.randomUUID();
    runners.create(id, "d-" + id.toString().substring(0, 8), null, slots, "tok-" + id, "sub-" + id);
    created.add(id);
    return runners.markRegistered(id, clientId, null);
  }

  /** {@link #registered}, then greenlit: a runner in service. */
  private DeskRunner eligible(String clientId, int slots) {
    return runners.greenlight(registered(clientId, slots).id);
  }

  private DeskRunner row(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> rows.findById(id));
  }

  private static String bearer(String sub, String role, Duration ttl) {
    MockIdp.TokenBuilder token =
        MockIdp.attach().token().subject(sub).audience("qits-platform").groups(role);
    return (ttl == null ? token : token.ttl(ttl)).mint();
  }

  private FakeDeskRunner dial(String clientId) throws Exception {
    return dialWith(bearer(clientId, DeskRunnerSocket.RUNNER_ROLE, null));
  }

  private FakeDeskRunner dialWith(String bearer) throws Exception {
    FakeDeskRunner runner = FakeDeskRunner.connect(vertx, endpoint, bearer);
    dialled.add(runner);
    return runner;
  }

  /**
   * Dial, say hello at the pin, and read the ack of a runner in service — then wait until the
   * registry serves the connection: the ack leaves before the session is marked greeted, so a door
   * dialled the instant the ack is read can still find no serving session.
   */
  private FakeDeskRunner greeted(String clientId, int slots) throws Exception {
    FakeDeskRunner runner = dial(clientId);
    runner.send(FakeDeskRunner.hello(PIN, List.of()));
    assertEquals(slots, runner.expect(Ack.class).slots());
    UUID id =
        QuarkusTransaction.requiringNew()
            .call(() -> rows.findByClientId(clientId).orElseThrow().id);
    await(() -> registry.serving(id) != null, "greeted but not serving");
    return runner;
  }

  private String projectAgentImage() {
    return "registry.qits." + DOMAIN + "/qits/project-agent:" + agentContainers.imageVersion();
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(condition.getAsBoolean(), what);
  }

  // --- who may dial -------------------------------------------------------------------------------

  /** A runner bearer naming a deleted runner's client: the runner is told it was deleted. */
  @Test
  void aDeletedRowIsClosedRunnerDeleted() throws Exception {
    DeskRunner gone = registered("dr-deleted", 1);
    QuarkusTransaction.requiringNew().run(() -> rows.deleteById(gone.id));

    FakeDeskRunner runner = dial("dr-deleted");

    assertEquals("1008 RUNNER_DELETED", runner.awaitClose());
  }

  /**
   * A valid runner bearer whose subject is not this runner's client is no runner at all: closed
   * 1008, and the runner it is not stays disconnected.
   */
  @Test
  void aWrongSubjectIsRefused() throws Exception {
    DeskRunner row = eligible("dr-right", 1);

    FakeDeskRunner stranger = dial("dr-wrong");

    assertEquals("1008 RUNNER_DELETED", stranger.awaitClose());
    assertFalse(registry.isConnected(row.id));
  }

  /** A bearer without the runner role never reaches the socket at all. */
  @Test
  void aBearerWithoutTheRunnerRoleIsRefusedAtTheUpgrade() {
    eligible("dr-not-a-runner-role", 1);
    String token = bearer("dr-not-a-runner-role", "qits:desk-runner-registration", null);

    assertThrows(Exception.class, () -> dialWith(token));
  }

  /** A connected runner whose row is deleted is retired DELETED and closed 1008 RUNNER_DELETED. */
  @Test
  void aConnectedRunnerWhoseRowIsDeletedIsRetiredAndClosed() throws Exception {
    DeskRunner row = eligible("dr-retired", 1);
    FakeDeskRunner runner = greeted("dr-retired", 1);

    QuarkusTransaction.requiringNew().run(() -> rows.deleteById(row.id));
    registry.deleted(row.id);

    assertEquals(Retire.Kind.DELETED, runner.await(Retire.class).kind());
    assertEquals("1008 RUNNER_DELETED", runner.awaitClose());
  }

  // --- the greeting, the upgrade, quarantine ------------------------------------------------------

  /**
   * A freshly registered runner is quarantined: {@code ack{0}}, {@code quarantined}, then {@code
   * healthCheck} naming the pinned project-agent image. Its reserve is answered nothing. A passing
   * check lifts it — {@code reinstated} and {@code ack} with the row's slots — and its reserve is
   * still nothing, because no front desk is here yet.
   */
  @Test
  void aNewRunnerIsGreetedQuarantinedAndHealthChecked() throws Exception {
    DeskRunner row = registered("dr-fresh", 3);
    FakeDeskRunner runner = dial("dr-fresh");

    runner.send(FakeDeskRunner.hello(PIN, List.of()));

    assertEquals(0, runner.expect(Ack.class).slots());
    assertEquals(
        DeskRunners.AWAITING_FIRST_HEALTH_CHECK, runner.expect(Quarantined.class).reason());
    HealthCheck check = runner.expect(HealthCheck.class);
    assertEquals(projectAgentImage(), check.image());
    assertNotNull(check.requestId());
    runner.send(new Reserve());
    runner.expect(Nothing.class);
    assertTrue(registry.isConnected(row.id));
    assertNotNull(registry.connectedSince(row.id));
    assertEquals(
        PIN,
        DeskRunnerCapabilities.text(
            DeskRunnerCapabilities.decode(row(row.id).capabilities), DeskRunnerCapabilities.VERSION));

    runner.send(new HealthChecked(check.requestId(), true, "pulled, ran, removed"));

    runner.expect(Reinstated.class);
    assertEquals(3, runner.expect(Ack.class).slots());
    DeskRunner now = row(row.id);
    assertFalse(now.quarantined());
    assertEquals(Boolean.TRUE, now.lastHealthCheckOk);
    runner.send(new Reserve());
    runner.expect(Nothing.class);
  }

  /** A failed check keeps the runner out and says why. */
  @Test
  void aFailedHealthCheckQuarantinesTheRunner() throws Exception {
    DeskRunner row = eligible("dr-sick", 2);
    FakeDeskRunner runner = greeted("dr-sick", 2);
    String requestId = registry.requestHealthCheck(row.id).orElseThrow();
    assertEquals(requestId, runner.expect(HealthCheck.class).requestId());

    runner.send(new HealthChecked(requestId, false, "the image would not start"));

    Quarantined said = runner.expect(Quarantined.class);
    assertTrue(said.reason().contains("the image would not start"), said.reason());
    assertEquals(0, runner.expect(Ack.class).slots());
    assertTrue(row(row.id).quarantined());
    assertEquals(Boolean.FALSE, row(row.id).lastHealthCheckOk);
  }

  /** The greenlight door lifts a quarantine by hand: {@code reinstated}, then the row's slots. */
  @Test
  void aQuarantinedRunnerIsGreenlitByTheDoor() throws Exception {
    DeskRunner row = registered("dr-greenlit", 2);
    FakeDeskRunner runner = dial("dr-greenlit");
    runner.send(FakeDeskRunner.hello(PIN, List.of()));
    assertEquals(0, runner.expect(Ack.class).slots());
    runner.expect(Quarantined.class);
    runner.expect(HealthCheck.class);

    given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .post("/projects/api/runners/" + row.id + "/greenlight")
        .then()
        .statusCode(200);

    assertEquals("someone", runner.expect(Reinstated.class).by());
    assertEquals(2, runner.expect(Ack.class).slots());
    assertFalse(row(row.id).quarantined());
  }

  /**
   * A runner of another version is told to become the pin and holds no slot; its successor at the
   * pin is greeted, and the old connection is retired as superseded.
   */
  @Test
  void anOldVersionIsUpgradedAndRetiredByItsSuccessor() throws Exception {
    eligible("dr-rollover", 1);
    FakeDeskRunner old = dial("dr-rollover");
    old.send(FakeDeskRunner.hello(OLD, List.of()));

    Upgrade upgrade = old.expect(Upgrade.class);
    assertEquals(PIN, upgrade.version());
    assertEquals("registry.qits." + DOMAIN + "/qits/qits-projects-desk-runner:" + PIN, upgrade.image());
    assertNull(upgrade.sha256());
    assertEquals(0, old.expect(Ack.class).slots());
    old.send(new Reserve());
    old.expect(Nothing.class);

    FakeDeskRunner successor = greeted("dr-rollover", 1);

    assertEquals(Retire.Kind.SUPERSEDED, old.expect(Retire.class).kind());
    assertFalse(successor.isClosed());
  }

  /** The same runner at the same version dialling again replaces its first connection. */
  @Test
  void aSecondConnectionAtTheSameVersionReplacesTheFirst() throws Exception {
    eligible("dr-twice", 1);
    FakeDeskRunner first = greeted("dr-twice", 1);

    greeted("dr-twice", 1);

    assertEquals("1008 " + DeskRunnerRegistry.ALREADY_CONNECTED, first.awaitClose());
  }

  // --- the bearer's lifetime and presence ---------------------------------------------------------

  /**
   * The socket outlives its bearer's {@code exp}: without {@link SocketBearerLifetime},
   * websockets-next closes it "Authentication expired" at the token's expiry — five minutes after
   * every connect, at the edge's 300 s JWTs. Shrunk to seconds here.
   */
  @Test
  void theSocketOutlivesItsBearer() throws Exception {
    eligible("dr-long-lived", 1);
    FakeDeskRunner runner =
        dialWith(bearer("dr-long-lived", DeskRunnerSocket.RUNNER_ROLE, Duration.ofSeconds(3)));
    runner.send(FakeDeskRunner.hello(PIN, List.of()));
    runner.expect(Ack.class);

    Thread.sleep(5_000);

    assertFalse(runner.isClosed(), "closed at the bearer's exp");
    runner.drain();
    runner.send(new Reserve());
    runner.expect(Nothing.class);
  }

  /**
   * A dropped runner is disconnected from the moment it drops and present for the grace; then not.
   */
  @Test
  void aDroppedRunnerIsDisconnectedSinceAndPresentForTheGrace() throws Exception {
    registry.reconnectGrace(Duration.ofSeconds(2));
    DeskRunner row = eligible("dr-blink", 1);
    FakeDeskRunner runner = greeted("dr-blink", 1);
    assertNull(registry.disconnectedSince(row.id));

    runner.close();
    await(() -> !registry.isConnected(row.id), "still connected");

    assertNull(registry.connectedSince(row.id));
    assertNotNull(registry.disconnectedSince(row.id));
    assertTrue(registry.presence(row.id), "inside the grace");
    long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
    while (registry.presence(row.id) && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertFalse(registry.presence(row.id), "past the grace");
  }

  /** Every frame stamps the runner as seen. */
  @Test
  void aFrameStampsLastSeen() throws Exception {
    DeskRunner row = eligible("dr-seen", 1);
    registry.seenInterval(Duration.ZERO);
    try {
      greeted("dr-seen", 1);
      Instant before = row(row.id).lastSeenAt;
      Thread.sleep(20);
      dialled.get(0).send(new Heartbeat());

      await(() -> row(row.id).lastSeenAt.isAfter(before), "last_seen_at did not move");
    } finally {
      registry.seenInterval(DeskRunnerRegistry.SEEN_INTERVAL);
    }
  }

  // --- the login state and the login command ------------------------------------------------------

  /**
   * The login command is withheld until a {@code loginState} arrives on the current connection,
   * then composed for the runner's own dot-claude volume and the pinned project-agent image as the
   * agent container's user; the state lands on the row. A new connection withholds it again until
   * its own report. The login-check door sends {@code probeLogin}.
   */
  @Test
  void theLoginCommandIsShownOnceTheCurrentConnectionReportedItsLogin() throws Exception {
    DeskRunner row = eligible("dr-login", 1);
    FakeDeskRunner runner = greeted("dr-login", 1);
    assertNull(registry.loginCommand(row(row.id)), "shown before any loginState");

    given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .post("/projects/api/runners/" + row.id + "/login-check")
        .then()
        .statusCode(202);
    runner.expect(ProbeLogin.class);
    runner.send(new LoginState(LoginPresence.ABSENT));

    await(() -> "ABSENT".equals(row(row.id).loginState), "loginState not recorded");
    String volume = DeskRunnerLoginCommand.dotClaudeVolume(row.id);
    assertTrue(volume.startsWith("qits-projects-desk-runner-dot-claude-"), volume);
    assertEquals(
        "docker run --rm -it --user "
            + agentContainers.hostUid()
            + " --entrypoint claude -v "
            + volume
            + ":/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude "
            + projectAgentImage(),
        registry.loginCommand(row(row.id)));

    runner.close();
    await(() -> !registry.isConnected(row.id), "still connected");
    assertNull(registry.loginCommand(row(row.id)), "shown while disconnected");

    FakeDeskRunner again = greeted("dr-login", 1);
    assertNull(registry.loginCommand(row(row.id)), "shown on a new connection before its report");
    again.send(new LoginState(LoginPresence.PRESENT));
    await(() -> registry.loginCommand(row(row.id)) != null, "not shown after the new report");
    assertEquals("PRESENT", row(row.id).loginState);
  }

  /** A volume name docker would not accept, or a shell would read, is never spliced. */
  @Test
  void onlyAPlainVolumeNameIsSpliced() {
    assertTrue(DeskRunnerLoginCommand.splicable("qits-projects-desk-runner-dot-claude-1a2b3c4d"));
    assertFalse(DeskRunnerLoginCommand.splicable("vol; rm -rf /"));
    assertFalse(DeskRunnerLoginCommand.splicable("$(id)"));
    assertFalse(DeskRunnerLoginCommand.splicable(""));
    assertFalse(DeskRunnerLoginCommand.splicable(null));
  }
}
