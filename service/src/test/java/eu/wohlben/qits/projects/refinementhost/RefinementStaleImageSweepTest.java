package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The refinement stale-image sweep, driven past its own quiet window by a fake clock.
 *
 * <p>Plain JUnit and hand-built collaborators, the shape {@code AgentStaleImageSweepTest}
 * established one harness over: the sweep's whole logic is a string comparison and two quietness
 * questions, and booting an application to shorten one window would prove less and cost a minute a
 * run. {@link RefinementStaleImageSweep#sweep(Instant)} takes the clock for exactly that reason,
 * and the scheduled entry point returns early outside a packaged run anyway.
 *
 * <p>The registry is the <b>real</b> {@link RefinementDaemonRegistry} rather than a stub, because
 * what is under test is largely what it records: which frames advance the heartbeat-free stamp, and
 * how the per-session states fold into one answer. A stub would let this file assert its own
 * assumptions about both, and the quiet guard — the thing this sweep is — would be tested nowhere.
 *
 * <p>{@link RefinementService#stopContainer} is overridden to record rather than run: it reaches a
 * database for the row, closes a tunnel and evicts registry state, and what this sweep decides is
 * <em>whether</em> to call it, never what that call then does.
 */
class RefinementStaleImageSweepTest {

  private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

  /** What this service pins — the tag {@link RefinementContainerFactory#imageVersion()} answers. */
  private static final String PIN = "2026.926.53146";

  private static final String OLD = "2026.901.120000";

  private final FakeRefinementRuntime runtime = new FakeRefinementRuntime();
  private final RefinementDaemonRegistry registry = new RefinementDaemonRegistry();
  private final RefinementContainerFactory factory = new RefinementContainerFactory();
  private final List<Project> projects = new ArrayList<>();
  private final List<Refinement> rows = new ArrayList<>();
  private final List<Long> stopped = new ArrayList<>();

  private final RefinementStaleImageSweep sweep =
      new RefinementStaleImageSweep() {
        @Override
        List<Refinement> liveRefinements() {
          return rows;
        }

        @Override
        List<Project> liveProjects() {
          return projects;
        }
      };

  RefinementStaleImageSweepTest() {
    RefinementMessageCodec codec = new RefinementMessageCodec();
    codec.objectMapper = new ObjectMapper();
    registry.codec = codec;
    registry.changes =
        new RefinementChangePublisher() {
          @Override
          public void fire(Long refinementId, RefinementChangeHint.Topic topic) {
            // The hint is RefinementDaemonRegistryTest's assertion, not this one's.
          }
        };
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
    // The emergency override IS the pin once it is set, and it is the one value imageVersion()
    // prefers — which is what lets this test name a tag without pinning itself to the released one.
    factory.imageVersionOverride = Optional.of(PIN);

    sweep.runtime = runtime;
    sweep.registry = registry;
    sweep.factory = factory;
    sweep.quietWindow = Duration.ofMinutes(30);
    sweep.refinements =
        new RefinementService() {
          @Override
          public Refinement stopContainer(long id) {
            stopped.add(id);
            return null;
          }
        };
  }

  /**
   * A live refinement of {@code epicSlug} in a live project, with its container in the given state
   * under the name {@link RefinementContainerFactory#containerName} derives for the pair.
   */
  private void refinement(long id, String projectSlug, String epicSlug, boolean running) {
    Project project = new Project();
    project.id = "project-" + projectSlug;
    project.slug = projectSlug;
    projects.add(project);

    Refinement refinement = new Refinement();
    refinement.id = id;
    refinement.projectId = project.id;
    refinement.branch = "refining/" + epicSlug;
    refinement.label = RefinementService.label(epicSlug);
    rows.add(refinement);

    runtime.place(id, factory.containerName(projectSlug, epicSlug), running);
  }

  /** A container in the orchestrator's listing that no refinement row answers to. */
  private void orphanContainer(long id, String name, boolean running) {
    runtime.place(id, name, running);
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
                  case "sendTextAndAwait" -> null;
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "toString" -> "connection " + id;
                  default -> null;
                });
  }

  /** A connected daemon for {@code refinementId} announcing {@code daemonVersion}. */
  private void daemon(long refinementId, String daemonVersion) {
    WebSocketConnection connection = connection("c-" + refinementId);
    registry.register(refinementId, connection);
    registry.onMessage(
        refinementId,
        connection,
        new Hello(
            "refinement-" + refinementId,
            "repo-1",
            "refining/slug",
            "main",
            DaemonProtocol.CAPABILITY_VERSION,
            daemonVersion,
            null));
  }

  /** An agent report for {@code refinementId}, at a wire {@code at} of the given age. */
  private void reports(long refinementId, String session, String state, Duration age) {
    registry.onMessage(
        refinementId,
        null,
        new AgentActivity(
            "cmd-" + session,
            session,
            state,
            "PreToolUse",
            null,
            null,
            System.currentTimeMillis() - age.toMillis()));
  }

  @Test
  void leavesAContainerOnThePinCompletelyAlone() {
    refinement(1L, "demo", "current", true);
    daemon(1L, PIN);
    registry.touchAgentActivity(1L, NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
    assertEquals(
        List.of(),
        runtime.calls(),
        "in particular no touch — there is no idle policy on this axis, so a stamp would be a write"
            + " with no reader, and the orchestrator's clock is not this sweep's to move");
  }

  @Test
  void stopsAQuietContainerWhoseDaemonIsNotThePin() {
    refinement(2L, "demo", "old", true);
    daemon(2L, OLD);
    registry.touchAgentActivity(2L, NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of(2L), stopped, "stopped, never removed — the wake replaces it");
  }

  /**
   * {@code daemonVersion} is on this protocol's {@code Hello} and has been since it had one, so an
   * image that announces none is older than the harness that would read it. Reading the absence as
   * "cannot tell" would make the oldest containers on the estate the only ones this sweep never
   * reaches.
   */
  @Test
  void aDaemonThatAnnouncesNoVersionIsBehind() {
    refinement(3L, "demo", "ancient", true);
    daemon(3L, null);
    registry.touchAgentActivity(3L, NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of(3L), stopped);
  }

  /**
   * The stamp's half of quiet. Nothing here is an agent session — an open terminal produces no
   * {@code AgentActivity} at all — so the rollup says nothing and the stamp is the only thing
   * standing between somebody's terminal and a stop.
   */
  @Test
  void leavesAStaleContainerSomethingJustHappenedIn() {
    refinement(4L, "demo", "busy-hands", true);
    daemon(4L, OLD);
    registry.touchAgentActivity(4L, NOW.minus(Duration.ofMinutes(5)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
  }

  /**
   * The rollup's half of quiet, and the case the stamp alone gets wrong: an agent in the middle of
   * a long tool call says nothing for minutes, so the stamp ages straight through a live turn. Both
   * conditions have to hold, and here only one does.
   */
  @Test
  void leavesAStaleContainerWhoseAgentIsStillBusy() {
    refinement(5L, "demo", "thinking", true);
    daemon(5L, OLD);
    reports(5L, "session-1", DaemonProtocol.AgentState.BUSY, Duration.ZERO);
    // After the frame, because handling one stamps: this is a container whose last word was hours
    // ago and whose agent is nonetheless mid-turn.
    registry.touchAgentActivity(5L, NOW.minus(Duration.ofHours(2)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped, "an agent thinking between frames is not a quiet container");
  }

  /**
   * <b>The agent axis's live 2026-09-18 scenario, on this harness.</b> The container is stale,
   * nothing has happened in it for hours, and its only session has said {@code BUSY} since long
   * before anyone could still be in that turn — a session whose agent died before its {@code Stop}
   * hook fired, re-asserted on every reconnect by the control socket's adoption of the daemon's
   * retained state. Without the second horizon such an entry never ages out, the sweep names the
   * container in a WARN on every pass and stops it on none of them.
   *
   * <p><b>This test and {@link #leavesAStaleContainerWhoseAgentIsStillBusy} are the whole rule, and
   * neither half states it alone.</b> A <em>recent</em> {@code BUSY} still protects the container —
   * that is the veto's entire job. An <em>ancient</em> one does not, because it is no longer
   * evidence of anything. Read either on its own and the rule looks like "the rollup always vetoes"
   * or "the rollup never does"; the pair is what says where the line is.
   *
   * <p><b>That line is strictly longer than this sweep's quiet window, on purpose.</b> At equal
   * values the rollup could never veto anything the stamp had not already vetoed, so the second
   * condition would quietly stop meaning anything and an agent silent mid-turn would go back to
   * being stopped on. The window here is thirty minutes and the horizon four hours; collapsing them
   * deletes the rollup's reason for existing with every test in this file still green.
   */
  @Test
  void stopsAStaleContainerWhoseOnlySessionHasBeenBusySinceBeforeTheHorizon() {
    refinement(6L, "demo", "stuck", true);
    daemon(6L, OLD);
    reports(6L, "session-1", DaemonProtocol.AgentState.BUSY, Duration.ofHours(6));
    registry.touchAgentActivity(6L, NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(
        List.of(6L), stopped, "a BUSY nobody has refreshed for six hours is not an agent mid-turn");
  }

  /** The same container once that session has ended is quiet, and is taken. */
  @Test
  void stopsItOnceThatAgentHasEnded() {
    refinement(7L, "demo", "thinking", true);
    daemon(7L, OLD);
    reports(7L, "session-1", DaemonProtocol.AgentState.ENDED, Duration.ZERO);
    registry.touchAgentActivity(7L, NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of(7L), stopped);
  }

  /**
   * No connected daemon is "we could not ask", which is not "behind" — the same four-answer
   * discipline {@link RefinementRuntime#inspect} carries. A container whose daemon is still booting
   * or has briefly dropped must not be stopped for saying nothing.
   */
  @Test
  void neverJudgesAContainerWithNoConnectedDaemon() {
    refinement(8L, "demo", "mute", true);
    registry.touchAgentActivity(8L, NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
    assertEquals(List.of(), runtime.calls());
  }

  /**
   * A container a discarded refinement left behind, or one whose project is gone. Its name resolves
   * to no row, and every action past the stop is addressed by a row id there is no longer one of.
   */
  @Test
  void skipsAContainerNoLiveRefinementIsNamedBy() {
    orphanContainer(9L, "qits-ref-demo-deleted", true);
    daemon(9L, OLD);
    registry.touchAgentActivity(9L, NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
  }

  /** A stopped container picks the pin up on its next wake; there is nothing here to do to it. */
  @Test
  void neverTouchesAContainerThatIsNotRunning() {
    refinement(10L, "demo", "down", false);
    daemon(10L, OLD);
    registry.touchAgentActivity(10L, NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
    assertEquals(List.of(), runtime.calls());
  }

  /**
   * Zero disables the sweep. It is the kill switch and emphatically not a deadline of now — read
   * the other way it would stop every refinement on the estate on the first pass after an image
   * release.
   */
  @Test
  void aZeroQuietWindowIsTheKillSwitch() {
    sweep.quietWindow = Duration.ZERO;
    refinement(11L, "demo", "old", true);
    daemon(11L, OLD);
    registry.touchAgentActivity(11L, NOW.minus(Duration.ofDays(30)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
    assertEquals(List.of(), runtime.calls());
  }

  /** And a negative one is the same switch, not an inverted window. */
  @Test
  void aNegativeQuietWindowIsTheSameSwitch() {
    sweep.quietWindow = Duration.ofMinutes(-30);
    refinement(12L, "demo", "old", true);
    daemon(12L, OLD);
    registry.touchAgentActivity(12L, NOW.minus(Duration.ofDays(30)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
  }

  /**
   * A container nothing has ever happened in is the quietest a container gets — one that outlived a
   * restart of this service, or whose daemon connected and did nothing since. It is not an unknown
   * to be cautious about, and treating it as one would leave the longest-stale containers untouched
   * for ever.
   *
   * <p>The fixture's {@code Hello} leaves the quiet clock untouched, which is the assertion here
   * rather than a detail of the setup: <b>a reconnect is not use.</b> Were it to stamp, every
   * redeploy of this service would blind the sweep for a whole quiet window.
   */
  @Test
  void aContainerThatWasNeverStampedIsQuiet() {
    refinement(13L, "demo", "fresh", true);
    daemon(13L, OLD);

    assertTrue(
        registry.lastAgentActivityAt(13L).isEmpty(),
        "the Hello is the daemon dialling home, not somebody using the container");

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of(13L), stopped);
  }

  /**
   * <b>The live 2026-09-18 scenario the allowlist was written for, carried over.</b> A container
   * relayed a supervised subprocess's retry loop as a {@code DaemonLog} every thirty seconds, for
   * ever — {@code checkout-daemon: … ConnectException; reconnecting in 30 s} — and heartbeats
   * twenty-second unconditionally on top of it. Under a denylist every one of those frames stamped
   * the quiet clock, so the container could never be more than twenty seconds' quiet however long
   * nobody touched it, and the stop below never happened.
   *
   * <p>The frames here are the container's <b>only</b> traffic since the stamp, which is what the
   * live case looked like — nobody had opened a terminal in it for hours.
   */
  @Test
  void stopsAStaleContainerWhoseOnlyTrafficHasBeenHeartbeatsHellosAndDaemonLogs() {
    refinement(14L, "demo", "chatty", true);
    daemon(14L, OLD);
    registry.touchAgentActivity(14L, NOW.minus(Duration.ofHours(2)));

    WebSocketConnection connection = connection("c-14");
    for (int beat = 0; beat < 10; beat++) {
      registry.onMessage(14L, connection, new Heartbeat("refinement-14"));
      registry.onMessage(
          14L,
          connection,
          new DaemonLog(
              "INFO",
              "checkout-daemon: Cannot reach http://dev-qits-events:8080/events/api/stream:"
                  + " ConnectException; reconnecting in 30 s"));
    }
    // And the redial that every daemon on the estate makes when this service restarts.
    daemon(14L, OLD);

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(
        List.of(14L),
        stopped,
        "a daemon talking about itself, beating and redialling is not somebody using the"
            + " container");
  }
}
