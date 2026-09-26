package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapStep;
import eu.wohlben.qits.workspacedaemon.protocol.Bootstrapped;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.ConfigView;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.DescribeConfig;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.PullBranch;
import eu.wohlben.qits.workspacedaemon.protocol.RunBootstrap;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.ServiceTransition;
import eu.wohlben.qits.workspacedaemon.protocol.SignalService;
import eu.wohlben.qits.workspacedaemon.protocol.StartService;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceChanged;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The refinement registry's quiet clock and its activity rollup, off any container.
 *
 * <p>The real registry, deliberately, and not a stand-in for it: the thing under test <em>is</em>
 * the guard — which frames stamp the clock and which entries stop counting — so a fake would prove
 * only that the fake agreed with itself. The connection is a reflective stub because exactly three
 * of {@code WebSocketConnection}'s methods are ever reached from here, and a proxy answering those
 * three is both the whole fixture and an honest statement of what this class uses.
 */
class RefinementDaemonRegistryTest {

  private static final Long REFINEMENT = 7L;
  private static final String WORKSPACE = "refinement-7";

  private final List<RefinementChangeHint> fired = new ArrayList<>();
  private final List<String> sent = new ArrayList<>();
  private final RefinementDaemonRegistry registry = new RefinementDaemonRegistry();

  RefinementDaemonRegistryTest() {
    RefinementMessageCodec codec = new RefinementMessageCodec();
    codec.objectMapper = new ObjectMapper();
    registry.codec = codec;
    registry.changes =
        new RefinementChangePublisher() {
          @Override
          public void fire(Long refinementId, RefinementChangeHint.Topic topic) {
            fired.add(new RefinementChangeHint(refinementId, topic));
          }
        };
    // No tunnels in this fixture: unregister asks whether one is resolvable and skips it when not,
    // which is the same path a deployment takes before the first proxied request opens one.
    @SuppressWarnings("unchecked")
    Instance<RefinementTunnels> noTunnels =
        (Instance<RefinementTunnels>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Instance.class},
                (proxy, method, args) ->
                    "isResolvable".equals(method.getName()) ? Boolean.FALSE : null);
    registry.tunnels = noTunnels;
    registry.endedActivityTtlMs = Duration.ofMinutes(30).toMillis();
    registry.staleActivityTtlMs = Duration.ofHours(4).toMillis();
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

  /** {@code age} ago, as the wire's {@code at} spells it. */
  private static long agedBy(Duration age) {
    return System.currentTimeMillis() - age.toMillis();
  }

  private void report(WebSocketConnection connection, String session, String state, long at) {
    registry.onMessage(
        REFINEMENT,
        connection,
        new AgentActivity("cmd-" + session, session, state, "Stop", null, null, at));
  }

  /**
   * <b>Every frame in the sealed set, with the verdict written down.</b> One entry per permit of
   * {@code DaemonMessage}, driven through {@link RefinementDaemonRegistry#onMessage} and judged by
   * whether the quiet clock moved.
   *
   * <p>This is the test that makes the exhaustive {@code switch} worth having. The switch makes a
   * protocol addition fail the <em>compilation</em>; this makes the decision it was given visible,
   * so a verdict cannot be quietly flipped by a refactor that still compiles. Both halves are
   * needed: the compiler catches the frame nobody considered, and this catches the frame somebody
   * considered and then changed their mind about by accident.
   */
  @Test
  void everyFrameInTheSealedSetHasADecidedVerdictOnWhetherItIsUse() {
    WebSocketConnection connection = connection("c1");

    Map<DaemonMessage, Boolean> verdicts = new LinkedHashMap<>();
    // Evidence: a person or an agent is doing something in there.
    verdicts.put(
        new AgentActivity("cmd-1", "s-1", DaemonProtocol.AgentState.BUSY, "Stop", null, null, 0L),
        true);
    verdicts.put(new CommandChunk("cmd-1", Stream.STDOUT, "total 0\n"), true);
    verdicts.put(new CommandExit("cmd-1", 0), true);
    verdicts.put(new WorkspaceChanged(WORKSPACE, "COMMANDS"), true);
    // Not evidence: the daemon's own noise, this service's own doing, or our own outbound frames.
    verdicts.put(new Hello(WORKSPACE, "repo-1", "main", null, 1, "2026.926.1", null), false);
    verdicts.put(new Heartbeat(WORKSPACE), false);
    verdicts.put(new DaemonLog("INFO", "checkout-daemon: reconnecting in 30 s"), false);
    verdicts.put(new GitStatus(WORKSPACE, false, "abc1234"), false);
    verdicts.put(new Provisioned(WORKSPACE, "abc1234"), false);
    verdicts.put(new ProvisionFailed(WORKSPACE, "could not clone"), false);
    verdicts.put(new BootstrapStep(WORKSPACE, "maven", BootstrapStep.Phase.EXECUTE), false);
    verdicts.put(
        new BootstrapOutcome(WORKSPACE, "maven", BootstrapOutcome.Result.SUCCEEDED, 0), false);
    verdicts.put(new Bootstrapped(WORKSPACE, true), false);
    verdicts.put(
        new ServiceTransition(WORKSPACE, "api", ServiceTransition.State.READY, null), false);
    verdicts.put(new EditorState(EditorState.State.RUNNING), false);
    verdicts.put(new WorkspaceInfo(WORKSPACE, "repo-1", "main", null, "abc1234", true), false);
    verdicts.put(new ConfigView(WORKSPACE, "yaml", "{}", null), false);
    // The host->daemon half. None of these ever arrives on this socket — the daemon constructs no
    // outbound frame of its own — and they are here because the switch must still name them.
    verdicts.put(new Ack(), false);
    verdicts.put(new RunCommand("cmd-1", List.of("ls"), "/workspace", Map.of()), false);
    verdicts.put(new Describe(WORKSPACE), false);
    verdicts.put(new DescribeConfig(WORKSPACE), false);
    verdicts.put(new RunBootstrap(WORKSPACE, null), false);
    verdicts.put(new StartService(WORKSPACE, "api", "/workspace", Map.of()), false);
    verdicts.put(new SignalService(WORKSPACE, "api", "TERM"), false);
    verdicts.put(new PullBranch(WORKSPACE, "main"), false);
    verdicts.put(new OpenStream("nonce-1", "/api"), false);

    assertEquals(
        26,
        verdicts.size(),
        "one case per permit of DaemonMessage — a frame added to the protocol is a decision to make"
            + " here as well as in evidencesUse");

    for (Map.Entry<DaemonMessage, Boolean> verdict : verdicts.entrySet()) {
      // Forget between frames, so each one has to write the stamp itself rather than inherit the
      // one its predecessor left.
      registry.forget(REFINEMENT);
      registry.register(REFINEMENT, connection);
      Instant before = Instant.now();

      registry.onMessage(REFINEMENT, connection, verdict.getKey());

      String frame = verdict.getKey().getClass().getSimpleName();
      Optional<Instant> stamp = registry.lastAgentActivityAt(REFINEMENT);
      if (verdict.getValue()) {
        assertFalse(
            stamp.orElseThrow(() -> new AssertionError(frame + " must stamp the quiet clock"))
                .isBefore(before),
            frame + " is evidence somebody is using the container and must advance the clock");
      } else {
        assertTrue(
            stamp.isEmpty(), frame + " is not evidence of use and must leave the clock untouched");
      }
    }
  }

  /**
   * The load-bearing negative. {@code qits-workspace-daemon} heartbeats every twenty seconds for as
   * long as its container runs, unconditionally — so a heartbeat that stamped this clock would make
   * it never more than twenty seconds old on a container nobody has touched for a month, and every
   * quiet window measured against it would be permanently unsatisfiable. That is the whole defect
   * the clock exists to fix, and it is reinstated by one line.
   */
  @Test
  void aStreamOfHeartbeatsNeverMakesTheContainerLookUsed() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);
    assertTrue(registry.lastAgentActivityAt(REFINEMENT).isEmpty(), "nothing has happened yet");

    for (int beat = 0; beat < 10; beat++) {
      registry.onMessage(REFINEMENT, connection, new Heartbeat(WORKSPACE));
    }

    assertTrue(
        registry.lastAgentActivityAt(REFINEMENT).isEmpty(),
        "an open socket is the liveness answer here; a heartbeat must never say somebody is using"
            + " the container");
  }

  /**
   * <b>The live case this allowlist was written for.</b> On the platform on 2026-09-18 a project
   * agent container relayed its supervised subprocess's retry loop as a {@code DaemonLog}
   * <em>every thirty seconds, for ever</em> — {@code checkout-daemon: Cannot reach
   * …/events/api/stream: ConnectException; reconnecting in 30 s}. Under a denylist every one of
   * those stamped the clock, so the container was never quiet and no sweep could ever reach it: the
   * heartbeat's defect reappearing one frame class over. A daemon's self-talk is unbounded by
   * construction — nothing on this side decides how much of it there is — so it may never mean
   * "somebody is in here".
   */
  @Test
  void aStreamOfDaemonLogsIsTheDaemonTalkingAboutItselfAndNeverUse() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);

    for (int line = 0; line < 10; line++) {
      registry.onMessage(
          REFINEMENT,
          connection,
          new DaemonLog(
              "INFO",
              "checkout-daemon: Cannot reach http://dev-qits-events:8080/events/api/stream:"
                  + " ConnectException; reconnecting in 30 s"));
    }

    assertTrue(
        registry.lastAgentActivityAt(REFINEMENT).isEmpty(),
        "ten self-generated log lines are not ten people at work");
  }

  /** An agent's own turn boundary is the clearest evidence there is, and it must stamp. */
  @Test
  void anAgentActivityReportStampsTheQuietClock() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);
    Instant before = Instant.now();

    report(connection, "session-1", DaemonProtocol.AgentState.BUSY, 0L);

    assertFalse(
        registry.lastAgentActivityAt(REFINEMENT).orElseThrow().isBefore(before),
        "an agent reporting a turn boundary is somebody using this container");
  }

  /**
   * The seam a sweep test drives instead of waiting out a quiet window — and the reason it is on
   * the registry rather than in the sweep: the map it writes is private and has to stay that way.
   */
  @Test
  void theTestSeamWritesTheStampAtAGivenInstant() {
    Instant when = Instant.parse("2026-09-26T09:00:00Z");

    registry.touchAgentActivity(REFINEMENT, when);

    assertEquals(when, registry.lastAgentActivityAt(REFINEMENT).orElseThrow());
  }

  /**
   * The rollup answers the busiest of a container's live sessions: one agent working while others
   * sit idle is a container somebody is using, and a stop taken on the majority would land in the
   * middle of that one's turn.
   */
  @Test
  void theRollupAnswersTheBusiestSession() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);

    report(connection, "idle", DaemonProtocol.AgentState.IDLE, 0L);
    report(connection, "waiting", DaemonProtocol.AgentState.WAITING, 0L);
    assertEquals(
        DaemonProtocol.AgentState.WAITING, registry.agentActivity(REFINEMENT).orElseThrow());

    report(connection, "busy", DaemonProtocol.AgentState.BUSY, 0L);
    assertEquals(DaemonProtocol.AgentState.BUSY, registry.agentActivity(REFINEMENT).orElseThrow());
  }

  /**
   * The short horizon, unchanged by the second one: an {@code ENDED} session keeps a say for half
   * an hour — long enough that a session somebody is reading the tail of is still what this
   * container is described as — and then stops having one.
   */
  @Test
  void anEndedSessionStillAgesOutAtTheShortTtl() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);

    report(connection, "session-1", DaemonProtocol.AgentState.ENDED, System.currentTimeMillis());
    assertEquals(DaemonProtocol.AgentState.ENDED, registry.agentActivity(REFINEMENT).orElseThrow());

    report(connection, "session-1", DaemonProtocol.AgentState.ENDED, agedBy(Duration.ofHours(2)));
    assertTrue(
        registry.agentActivity(REFINEMENT).isEmpty(),
        "past the TTL it says nothing, rather than saying ENDED for ever");
  }

  /**
   * <b>The second horizon, in one test.</b> A session whose agent died, was killed, or whose
   * container was replaced before its {@code Stop} hook fired leaves a {@code BUSY} nothing ever
   * takes back, and the reconnect adoption re-asserts it on every restart of this service. With
   * only the {@code ENDED} TTL that entry was immortal — a permanent veto on the stale-image sweep,
   * vetoing hardest on exactly the long-lived containers it exists to reach.
   */
  @Test
  void anEntryOfAnyStateOlderThanTheStaleHorizonStopsBeingEvidence() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);

    for (String state :
        List.of(
            DaemonProtocol.AgentState.BUSY,
            DaemonProtocol.AgentState.WAITING,
            DaemonProtocol.AgentState.IDLE)) {
      registry.forget(REFINEMENT);
      report(connection, "session-" + state, state, agedBy(Duration.ofHours(5)));

      assertTrue(
          registry.agentActivity(REFINEMENT).isEmpty(),
          state + " five hours old is not a claim that anything is still running");
    }
  }

  /** Inside the horizon it is still evidence, which is the whole point of having one at all. */
  @Test
  void aBusyEntryInsideTheStaleHorizonIsStillEvidence() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);

    report(connection, "session-1", DaemonProtocol.AgentState.BUSY, agedBy(Duration.ofHours(3)));

    assertEquals(DaemonProtocol.AgentState.BUSY, registry.agentActivity(REFINEMENT).orElseThrow());
  }

  /**
   * The container is gone, so its window starts afresh — the clock is dropped with the rollup, the
   * cleanliness flag and the last provision failure, and the next container to take this
   * refinement's id inherits nothing.
   */
  @Test
  void forgettingARefinementDropsTheQuietClockWithEverythingElse() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);
    report(connection, "session-1", DaemonProtocol.AgentState.BUSY, 0L);
    assertTrue(registry.lastAgentActivityAt(REFINEMENT).isPresent());

    registry.forget(REFINEMENT);

    assertTrue(registry.lastAgentActivityAt(REFINEMENT).isEmpty());
    assertTrue(registry.agentActivity(REFINEMENT).isEmpty());
  }

  /**
   * <b>And a disconnect does NOT drop it</b>, which is the deliberate asymmetry with {@code
   * gitClean} beside it. Cleanliness is a claim about a daemon that is connected right now and
   * means nothing once one is not; the quiet clock describes the <em>container</em>, which outlives
   * a socket blip, a daemon restart and a redeploy of this service. Clearing it here would make a
   * container that has just been worked in read as one nothing has ever happened in — the quietest
   * a container gets — on nothing more than a dropped websocket.
   */
  @Test
  void aDisconnectLeavesTheQuietClockAlone() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);
    report(connection, "session-1", DaemonProtocol.AgentState.BUSY, 0L);
    registry.onMessage(REFINEMENT, connection, new GitStatus(WORKSPACE, true, "abc1234"));
    assertTrue(registry.clean(REFINEMENT).isPresent());

    registry.unregister(REFINEMENT, connection);

    assertTrue(registry.clean(REFINEMENT).isEmpty(), "cleanliness dies with the connection");
    assertTrue(
        registry.lastAgentActivityAt(REFINEMENT).isPresent(),
        "the container was used, and a dropped socket does not unsay that");
  }

  /** The nudge translation still works — the stamp rides alongside it and changes nothing. */
  @Test
  void aGitStatusStillFiresItsHints() {
    WebSocketConnection connection = connection("c1");
    registry.register(REFINEMENT, connection);

    registry.onMessage(REFINEMENT, connection, new GitStatus(WORKSPACE, false, "abc1234"));

    assertTrue(
        fired.contains(new RefinementChangeHint(REFINEMENT, RefinementChangeHint.Topic.FILES)));
    assertTrue(
        fired.contains(
            new RefinementChangeHint(REFINEMENT, RefinementChangeHint.Topic.GIT_STATUS)));
  }
}
