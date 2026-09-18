package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Heartbeat;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectChanged;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.projectsdaemon.protocol.Provisioned;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The registry's four jobs, off any container: the handshake, the build stamp it caches, the
 * activity stamp the idle sweep reads, and the nudge translation.
 *
 * <p>The connection is a reflective stub rather than a mock. Three methods of {@code
 * WebSocketConnection} are reached from here and the rest never are, so a proxy that answers those
 * three is both the whole fixture and an honest statement of what this class uses.
 */
class AgentDaemonRegistryTest {

  private static final String PROJECT = "project-1";

  private final List<ProjectChangeHint> fired = new ArrayList<>();
  private final List<String> sent = new ArrayList<>();
  private final AgentDaemonRegistry registry = new AgentDaemonRegistry();

  AgentDaemonRegistryTest() {
    DaemonMessageCodec codec = new DaemonMessageCodec();
    codec.objectMapper = new ObjectMapper();
    registry.codec = codec;
    registry.changePublisher =
        new ProjectChangePublisher() {
          @Override
          public void fire(String projectId, ProjectChangeHint.Topic topic) {
            fired.add(new ProjectChangeHint(projectId, topic));
          }
        };
    // No tunnels in this fixture: unregister asks whether one is resolvable and skips it when not,
    // which is the same path a deployment takes before the first proxied request opens one.
    @SuppressWarnings("unchecked")
    Instance<AgentTunnels> noTunnels =
        (Instance<AgentTunnels>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Instance.class},
                (proxy, method, args) ->
                    "isResolvable".equals(method.getName()) ? Boolean.FALSE : null);
    registry.tunnels = noTunnels;
    registry.endedActivityTtlMs = java.time.Duration.ofMinutes(30).toMillis();
    registry.staleActivityTtlMs = java.time.Duration.ofHours(4).toMillis();
  }

  /** {@code millis} ago, as the wire's {@code at} spells it. */
  private static long agedBy(java.time.Duration age) {
    return System.currentTimeMillis() - age.toMillis();
  }

  /** A {@code WebSocketConnection} answering only what the registry actually calls on one. */
  private WebSocketConnection connection(String id) {
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

  @Test
  void acksAHelloAndCachesTheBuildStamp() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(
        PROJECT,
        connection,
        new Hello(PROJECT, "demo-demo", DaemonProtocol.CAPABILITY_VERSION, "2026.808.1", null));

    assertTrue(sent.get(0).contains("\"type\":\"ack\""), "the handshake is only complete on the Ack");
    AgentDaemonRegistry.DaemonInfo info = registry.lookup(PROJECT).orElseThrow();
    assertEquals("demo-demo", info.repoName());
    assertEquals("2026.808.1", info.daemonVersion());
    assertEquals(DaemonProtocol.CAPABILITY_VERSION, info.capabilityVersion());
    assertNull(info.daemonBuildTime(), "an unfiltered dev jar sends none, and that is not an error");
  }

  @Test
  void anUnparseableBuildTimeCostsTheStampAndNotTheRegistration() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new Hello(PROJECT, "demo-demo", 1, "1.0", "not a date"));

    assertNull(registry.lookup(PROJECT).orElseThrow().daemonBuildTime());
  }

  @Test
  void aHeartbeatIsLivenessAndNothingElse() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);
    registry.touch(PROJECT, Instant.parse("2026-08-08T00:00:00Z"));

    registry.onMessage(PROJECT, connection, new Heartbeat(PROJECT));

    assertTrue(
        registry.lastActivityAt(PROJECT).orElseThrow().isAfter(Instant.parse("2026-08-08T00:00:00Z")),
        "the stamp the idle sweep reads is the whole handling");
    assertEquals(List.of(), fired, "a heartbeat is not news for a browser");
  }

  /**
   * The load-bearing negative, and the whole reason there are two stamps.
   *
   * <p>A project agent's daemon heartbeats every twenty seconds for as long as its container runs.
   * If those frames counted as use, {@code lastAgentActivity} would never be more than twenty seconds
   * old on a container nobody has touched for a month — which is precisely what {@code lastActivity}
   * is, correctly, and precisely why the stale-image sweep cannot read it. Collapsing the two maps
   * reinstates the defect in one line.
   */
  @Test
  void aStreamOfHeartbeatsIsLivenessAndNeverUse() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);
    // Register itself stamps liveness only, so there is nothing on the use clock to start with.
    assertTrue(registry.lastAgentActivityAt(PROJECT).isEmpty());

    for (int beat = 0; beat < 5; beat++) {
      registry.onMessage(PROJECT, connection, new Heartbeat(PROJECT));
    }

    assertTrue(registry.lastActivityAt(PROJECT).isPresent(), "the daemon is plainly alive");
    assertTrue(
        registry.lastAgentActivityAt(PROJECT).isEmpty(),
        "and nothing has happened in the container — a heartbeat must never say otherwise");
  }

  /** Everything that is not a heartbeat or an Ack advances both clocks. */
  @Test
  void anythingElseAdvancesBothClocks() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);
    Instant before = Instant.now();

    registry.onMessage(
        PROJECT,
        connection,
        new AgentActivity("cmd-1", "session-1", DaemonProtocol.AgentState.BUSY, "Stop", null, null, 0L));

    assertFalse(registry.lastActivityAt(PROJECT).orElseThrow().isBefore(before));
    assertFalse(registry.lastAgentActivityAt(PROJECT).orElseThrow().isBefore(before));
  }

  /**
   * The rollup answers the busiest of a container's live sessions: one agent working while four sit
   * idle is a container somebody is using, and a stop taken on the majority would land in the middle
   * of that one's turn.
   */
  @Test
  void theRollupAnswersTheBusiestSession() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    report(connection, "session-idle", DaemonProtocol.AgentState.IDLE, 0L);
    report(connection, "session-waiting", DaemonProtocol.AgentState.WAITING, 0L);
    assertEquals(DaemonProtocol.AgentState.WAITING, registry.agentActivity(PROJECT).orElseThrow());

    report(connection, "session-busy", DaemonProtocol.AgentState.BUSY, 0L);
    assertEquals(DaemonProtocol.AgentState.BUSY, registry.agentActivity(PROJECT).orElseThrow());

    // The busy one finishes, and the answer falls back to what is still live rather than staying on
    // the high-water mark.
    report(connection, "session-busy", DaemonProtocol.AgentState.ENDED, 0L);
    assertEquals(DaemonProtocol.AgentState.WAITING, registry.agentActivity(PROJECT).orElseThrow());
  }

  /**
   * An {@code ENDED} session keeps a say for the TTL and then stops having one. Without the ageing
   * out, a container whose last agent ended in the spring would still be described by it.
   */
  @Test
  void anEndedSessionAgesOutOfTheRollup() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    report(connection, "session-1", DaemonProtocol.AgentState.ENDED, System.currentTimeMillis());
    assertEquals(DaemonProtocol.AgentState.ENDED, registry.agentActivity(PROJECT).orElseThrow());

    report(
        connection,
        "session-1",
        DaemonProtocol.AgentState.ENDED,
        System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis());
    assertTrue(
        registry.agentActivity(PROJECT).isEmpty(),
        "past the TTL it says nothing, rather than saying ENDED for ever");
  }

  /**
   * <b>The live defect, in one test.</b> A session whose agent died, was killed, or whose container
   * was replaced before its {@code Stop} hook fired leaves a {@code BUSY} nothing ever takes back.
   * With only the {@code ENDED} TTL ageing entries out that entry was immortal — and because it gates
   * {@link AgentStaleImageSweep}'s stop rather than merely colouring a chip in a UI, it was a
   * permanent veto on exactly the long-lived containers that sweep exists to reach. Observed on
   * 2026-09-18 as a WARN on three consecutive passes and a container that could never be stopped.
   */
  @Test
  void aBusyEntryOlderThanTheStaleHorizonStopsBeingEvidence() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    report(
        connection,
        "session-1",
        DaemonProtocol.AgentState.BUSY,
        agedBy(java.time.Duration.ofHours(5)));

    assertTrue(
        registry.agentActivity(PROJECT).isEmpty(),
        "a BUSY that outlived its agent is not a claim that something is running");
  }

  /** Inside the horizon it is still evidence, which is the whole point of having one at all. */
  @Test
  void aBusyEntryInsideTheStaleHorizonStillCounts() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    report(
        connection,
        "session-1",
        DaemonProtocol.AgentState.BUSY,
        agedBy(java.time.Duration.ofHours(1)));

    assertEquals(DaemonProtocol.AgentState.BUSY, registry.agentActivity(PROJECT).orElseThrow());
  }

  /** {@code WAITING} is the same kind of claim and expires on the same horizon. */
  @Test
  void aWaitingEntryOlderThanTheStaleHorizonDropsToo() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    report(
        connection,
        "session-1",
        DaemonProtocol.AgentState.WAITING,
        agedBy(java.time.Duration.ofHours(5)));

    assertTrue(registry.agentActivity(PROJECT).isEmpty());
  }

  /**
   * <b>The two horizons are two horizons, and this is the one test that can tell them apart.</b> One
   * age — two hours — is read twice, and the answer differs on the entry's state: an {@code ENDED}
   * session is already gone at it, because {@code endedActivityTtlMs} is half an hour, while a {@code
   * BUSY} session of the identical age is still evidence, because the horizon that governs it is four
   * hours away. {@link #anEndedSessionAgesOutOfTheRollup} proves the first half and cannot prove the
   * second; a single horizon of either length would fail one of these two assertions, which is
   * precisely what makes collapsing the pair into one value impossible to do quietly.
   */
  @Test
  void theEndedTtlStillGovernsAnEndedEntryAndOnlyAnEndedOne() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    report(
        connection,
        "session-ended",
        DaemonProtocol.AgentState.ENDED,
        agedBy(java.time.Duration.ofHours(2)));
    assertTrue(
        registry.agentActivity(PROJECT).isEmpty(),
        "the shorter TTL is the ENDED special case and still wins for an ENDED entry");

    report(
        connection,
        "session-busy",
        DaemonProtocol.AgentState.BUSY,
        agedBy(java.time.Duration.ofHours(2)));
    assertEquals(
        DaemonProtocol.AgentState.BUSY,
        registry.agentActivity(PROJECT).orElseThrow(),
        "and it governs nothing else — the same age says nothing about an entry that never ended");
  }

  /** A report with no session id is keyed on its command id rather than dropped. */
  @Test
  void aReportWithNoSessionIdStillCounts() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(
        PROJECT,
        connection,
        new AgentActivity("cmd-1", "  ", DaemonProtocol.AgentState.BUSY, "PreToolUse", null, null, 0L));

    assertEquals(DaemonProtocol.AgentState.BUSY, registry.agentActivity(PROJECT).orElseThrow());
  }

  /** A stop takes the whole container's record with it — both stamps and the rollup. */
  @Test
  void forgetClearsBothStampsAndTheRollup() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);
    report(connection, "session-1", DaemonProtocol.AgentState.BUSY, 0L);

    registry.forget(PROJECT);

    assertTrue(registry.lastActivityAt(PROJECT).isEmpty());
    assertTrue(registry.lastAgentActivityAt(PROJECT).isEmpty());
    assertTrue(
        registry.agentActivity(PROJECT).isEmpty(),
        "a restart starts every window afresh rather than inheriting the container before it");
  }

  private void report(WebSocketConnection connection, String sessionId, String state, long at) {
    registry.onMessage(
        PROJECT, connection, new AgentActivity("cmd-" + sessionId, sessionId, state, null, null, null, at));
  }

  @Test
  void translatesTheDaemonsCommandsNudgeOntoTheAgentActivityTopic() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProjectChanged(PROJECT, "COMMANDS"));

    assertEquals(
        List.of(new ProjectChangeHint(PROJECT, ProjectChangeHint.Topic.AGENT_ACTIVITY)),
        fired,
        "the daemon's commands list IS what the refinement panel renders");
  }

  @Test
  void passesThroughATopicThisBackendAlreadyHas() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProjectChanged(PROJECT, "EPICS"));

    assertEquals(List.of(new ProjectChangeHint(PROJECT, ProjectChangeHint.Topic.EPICS)), fired);
  }

  @Test
  void dropsATopicItHasNoViewFor() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProjectChanged(PROJECT, "SOMETHING_NEWER"));

    assertEquals(List.of(), fired, "a newer daemon nudging about something unknown costs nothing");
  }

  @Test
  void anAgentReportNudgesAndStamps() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(
        PROJECT,
        connection,
        new AgentActivity("cmd-1", "session-1", "BUSY", "UserPromptSubmit", null, null, 0L));

    assertEquals(
        List.of(new ProjectChangeHint(PROJECT, ProjectChangeHint.Topic.AGENT_ACTIVITY)), fired);
    assertTrue(registry.lastActivityAt(PROJECT).isPresent(), "a busy agent is not an idle project");
  }

  /**
   * The failure has to outlive the socket. A daemon that cannot clone the project usually drops
   * soon after saying so, and a failure held on the connection would vanish exactly when somebody
   * came to read it.
   */
  @Test
  void aFailedProvisionIsRecordedAndSurvivesTheSocketClosing() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProvisionFailed(PROJECT, "no such remote"));
    assertEquals("no such remote", registry.provisionFailure(PROJECT).orElseThrow());

    registry.unregister(PROJECT, connection);
    assertEquals(
        "no such remote",
        registry.provisionFailure(PROJECT).orElseThrow(),
        "the container is still up and still unusable");
  }

  @Test
  void aLaterProvisionedClearsIt() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);
    registry.onMessage(PROJECT, connection, new ProvisionFailed(PROJECT, "no such remote"));

    registry.onMessage(PROJECT, connection, new Provisioned(PROJECT, "abc123"));

    assertTrue(registry.provisionFailure(PROJECT).isEmpty());
  }

  @Test
  void aReconnectAndAStopBothClearIt() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);
    registry.onMessage(PROJECT, connection, new ProvisionFailed(PROJECT, "disk full"));

    registry.register(PROJECT, connection("c2"));
    assertTrue(
        registry.provisionFailure(PROJECT).isEmpty(),
        "a reconnecting daemon re-provisions; its next word on the subject is the current one");

    registry.onMessage(PROJECT, connection, new ProvisionFailed(PROJECT, "disk full"));
    registry.forget(PROJECT);
    assertTrue(registry.provisionFailure(PROJECT).isEmpty(), "the container is going away");
  }

  /** A missing reason is still a failed provision — it must not read as "provisioned". */
  @Test
  void aReasonlessFailureStillCountsAsOne() {
    WebSocketConnection connection = connection("c1");
    registry.register(PROJECT, connection);

    registry.onMessage(PROJECT, connection, new ProvisionFailed(PROJECT, null));

    assertFalse(registry.provisionFailure(PROJECT).orElseThrow().isBlank());
  }

  @Test
  void aLateCloseDoesNotEvictAReconnectedDaemon() {
    WebSocketConnection first = connection("c1");
    WebSocketConnection second = connection("c2");
    registry.register(PROJECT, first);
    registry.register(PROJECT, second);

    registry.unregister(PROJECT, first);

    assertTrue(registry.isDaemonLive(PROJECT), "the reconnect's socket is the live one");
    registry.unregister(PROJECT, second);
    assertFalse(registry.isDaemonLive(PROJECT));
  }
}
