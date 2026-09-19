package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import io.quarkus.websockets.next.WebSocketConnection;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The stale-image sweep, driven past its own quiet window by a fake clock.
 *
 * <p>Plain JUnit and hand-built collaborators, the shape {@link AgentIdleSweepTest} established: the
 * sweep's whole logic is a string comparison and two quietness questions, and booting an application
 * to shorten one window would prove less and cost a minute a run. {@code sweep(Instant)} takes the
 * clock for exactly that reason, and the scheduled entry point returns early outside a packaged run
 * anyway.
 *
 * <p>The registry is the <b>real</b> one rather than a stub, because what is under test is largely
 * what it records: which frames advance the heartbeat-free stamp, and how the per-session states fold
 * into one answer. A stub would let this file assert its own assumptions about both.
 *
 * <p>{@link AgentContainers#stop} is overridden to record rather than run — it reaches a database for
 * the project row, and what this sweep decides is <em>whether</em> to call it, never what that call
 * then does. {@code AgentContainerLifecycleTest} is where the stop itself is proved.
 */
class AgentStaleImageSweepTest {

  private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

  /** What this service pins — the tag {@code AgentContainerFactory.imageVersion()} answers. */
  private static final String PIN = "2026.918.173625";

  private final FakeContainerRuntime runtime = new FakeContainerRuntime();
  private final AgentDaemonRegistry registry = new AgentDaemonRegistry();
  private final AgentContainerFactory factory = new AgentContainerFactory();
  private final List<Project> projects = new ArrayList<>();
  private final List<String> stopped = new ArrayList<>();
  private final List<String> sent = new ArrayList<>();

  private final AgentStaleImageSweep sweep =
      new AgentStaleImageSweep() {
        @Override
        List<Project> liveProjects() {
          return projects;
        }
      };

  AgentStaleImageSweepTest() {
    DaemonMessageCodec codec = new DaemonMessageCodec();
    codec.objectMapper = new ObjectMapper();
    registry.codec = codec;
    registry.changePublisher =
        new ProjectChangePublisher() {
          @Override
          public void fire(String projectId, ProjectChangeHint.Topic topic) {
            // The hint is AgentDaemonRegistryTest's assertion, not this one's.
          }
        };
    registry.endedActivityTtlMs = Duration.ofMinutes(30).toMillis();
    registry.staleActivityTtlMs = Duration.ofHours(4).toMillis();
    // The emergency override IS the pin once it is set, and it is the one value imageVersion()
    // prefers — which is what lets this test name a tag without pinning itself to the released one.
    factory.imageVersionOverride = Optional.of(PIN);

    sweep.runtime = runtime;
    sweep.registry = registry;
    sweep.factory = factory;
    sweep.quietWindow = Duration.ofMinutes(30);
    sweep.agentContainers =
        new AgentContainers() {
          @Override
          public AgentContainerState stop(String projectId) {
            stopped.add(projectId);
            return AgentContainerState.of(AgentRuntimeStatus.STOPPED);
          }
        };
  }

  /** A live project with the slug its agent container is named after. */
  private void project(String id, String slug) {
    Project project = new Project();
    project.id = id;
    project.slug = slug;
    projects.add(project);
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

  /** A connected daemon for {@code projectId} announcing {@code daemonVersion}. */
  private void daemon(String projectId, String daemonVersion) {
    WebSocketConnection connection = connection("c-" + projectId);
    registry.register(projectId, connection);
    registry.onMessage(
        projectId,
        connection,
        new Hello(projectId, "demo-demo", DaemonProtocol.CAPABILITY_VERSION, daemonVersion, null));
  }

  @Test
  void leavesAContainerOnThePinCompletelyAlone() {
    project("project-current", "current");
    runtime.given("project-current", "current", true);
    daemon("project-current", PIN);
    registry.touchAgentActivity("project-current", NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
    assertEquals(
        List.of(),
        runtime.calls(),
        "in particular no touch — that clock is the idle sweep's and wants one writer");
  }

  @Test
  void stopsAQuietContainerWhoseDaemonIsNotThePin() {
    project("project-old", "old");
    runtime.given("project-old", "old", true);
    daemon("project-old", "2026.901.120000");
    registry.touchAgentActivity("project-old", NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of("project-old"), stopped, "stopped, never removed — the wake replaces it");
  }

  /**
   * {@code daemonVersion} has been on this protocol's wire since its first commit, so an image that
   * announces none is older than the harness that would read it. Reading the absence as "cannot tell"
   * would make the oldest containers on the estate the only ones this sweep never reaches.
   */
  @Test
  void aDaemonThatAnnouncesNoVersionIsBehind() {
    project("project-ancient", "ancient");
    runtime.given("project-ancient", "ancient", true);
    daemon("project-ancient", null);
    registry.touchAgentActivity("project-ancient", NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of("project-ancient"), stopped);
  }

  /**
   * The stamp's half of quiet. Nothing here is an agent session — an open terminal produces no
   * {@code AgentActivity} at all — so the fold says nothing and the stamp is the only thing standing
   * between somebody's terminal and a stop.
   */
  @Test
  void leavesAStaleContainerSomethingJustHappenedIn() {
    project("project-busy-hands", "busy-hands");
    runtime.given("project-busy-hands", "busy-hands", true);
    daemon("project-busy-hands", "2026.901.120000");
    registry.touchAgentActivity("project-busy-hands", NOW.minus(Duration.ofMinutes(5)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
  }

  /**
   * The fold's half of quiet, and the case the stamp alone gets wrong: an agent in the middle of a
   * long tool call says nothing for minutes, so the stamp ages straight through a live turn. Both
   * conditions have to hold, and here only one does.
   */
  @Test
  void leavesAStaleContainerWhoseAgentIsStillBusy() {
    project("project-thinking", "thinking");
    runtime.given("project-thinking", "thinking", true);
    daemon("project-thinking", "2026.901.120000");
    registry.onMessage(
        "project-thinking",
        null,
        new AgentActivity(
            "cmd-1", "session-1", DaemonProtocol.AgentState.BUSY, "PreToolUse", null, null, 0L));
    // After the frame, because handling one stamps: this is a container whose last word was minutes
    // ago and whose agent is nonetheless mid-turn.
    registry.touchAgentActivity("project-thinking", NOW.minus(Duration.ofHours(2)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped, "an agent thinking between frames is not a quiet container");
  }

  /**
   * <b>The scenario observed live on 2026-09-18, which this sweep could not act on.</b> The container
   * is stale, nothing has happened in it for hours, and its only session has said {@code BUSY} since
   * long before anyone could still be in that turn — a session whose agent died before its {@code
   * Stop} hook fired, re-asserted on every reconnect by the control socket's adoption of the daemon's
   * retained state. The sweep named it in a WARN on three consecutive passes and stopped it on none
   * of them, because an entry that only ages out when it says {@code ENDED} never does.
   *
   * <p><b>This test and {@link #leavesAStaleContainerWhoseAgentIsStillBusy} are the whole rule, and
   * neither half states it alone.</b> A <em>recent</em> {@code BUSY} still protects the container —
   * that is the veto's entire job, and that test must keep passing, because an agent thinking between
   * two frames is exactly what the stamp reads straight through. An <em>ancient</em> one does not,
   * because it is no longer evidence of anything. Read either one on its own and the rule looks like
   * "the fold always vetoes" or "the fold never does"; the pair is what says where the line is.
   *
   * <p><b>That line is strictly longer than this sweep's quiet window, on purpose.</b> At equal
   * values the fold could never veto anything the stamp had not already vetoed — every entry old
   * enough to survive as evidence would also be a stamp inside the window — so the second condition
   * would quietly stop meaning anything and the case the rollup exists for, an agent silent
   * mid-turn, would go back to being stopped on. The sweep's window here is thirty minutes and the
   * horizon is four hours; collapsing them deletes the rollup's reason for existing with every test
   * in this file still green.
   */
  @Test
  void stopsAStaleContainerWhoseOnlySessionHasBeenBusySinceBeforeTheHorizon() {
    project("project-stuck", "stuck");
    runtime.given("project-stuck", "stuck", true);
    daemon("project-stuck", "2026.901.120000");
    registry.onMessage(
        "project-stuck",
        null,
        new AgentActivity(
            "cmd-1",
            "session-1",
            DaemonProtocol.AgentState.BUSY,
            "PreToolUse",
            null,
            null,
            System.currentTimeMillis() - Duration.ofHours(6).toMillis()));
    registry.touchAgentActivity("project-stuck", NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(
        List.of("project-stuck"),
        stopped,
        "a BUSY nobody has refreshed for six hours is not an agent mid-turn");
  }

  /** The same container once that session has ended is quiet, and is taken. */
  @Test
  void stopsItOnceThatAgentHasEnded() {
    project("project-thinking", "thinking");
    runtime.given("project-thinking", "thinking", true);
    daemon("project-thinking", "2026.901.120000");
    registry.onMessage(
        "project-thinking",
        null,
        new AgentActivity(
            "cmd-1", "session-1", DaemonProtocol.AgentState.ENDED, "Stop", null, null, 0L));
    registry.touchAgentActivity("project-thinking", NOW.minus(Duration.ofHours(2)));

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of("project-thinking"), stopped);
  }

  /**
   * No connected daemon is "we could not ask", which is not "behind" — the same four-answer
   * discipline {@code ContainerRuntime.inspect} carries. A container whose daemon is still booting or
   * has briefly dropped must not be stopped for saying nothing.
   */
  @Test
  void neverJudgesAContainerWithNoConnectedDaemon() {
    project("project-mute", "mute");
    runtime.given("project-mute", "mute", true);
    registry.touchAgentActivity("project-mute", NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
    assertEquals(List.of(), runtime.calls());
  }

  /**
   * A container a deleted project left behind. Its name resolves to nothing, and the stop is
   * addressed by a project id there is no longer one of — so it is skipped here exactly as it is in
   * the idle sweep.
   */
  @Test
  void skipsAContainerNoLiveProjectIsNamedBy() {
    runtime.given("project-deleted", "deleted", true);
    daemon("project-deleted", "2026.901.120000");
    registry.touchAgentActivity("project-deleted", NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
  }

  /** A stopped container picks the pin up on its next wake; there is nothing here to do to it. */
  @Test
  void neverTouchesAContainerThatIsNotRunning() {
    project("project-down", "down");
    runtime.given("project-down", "down", false);
    daemon("project-down", "2026.901.120000");
    registry.touchAgentActivity("project-down", NOW.minus(Duration.ofDays(3)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
  }

  /**
   * Zero disables the sweep. It is the kill switch and emphatically not a deadline of now — read the
   * other way it would stop every container on the estate on the first pass after an image release.
   */
  @Test
  void aZeroQuietWindowIsTheKillSwitch() {
    sweep.quietWindow = Duration.ZERO;
    project("project-old", "old");
    runtime.given("project-old", "old", true);
    daemon("project-old", "2026.901.120000");
    registry.touchAgentActivity("project-old", NOW.minus(Duration.ofDays(30)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
    assertEquals(List.of(), runtime.calls());
  }

  /** And a negative one is the same switch, not an inverted window. */
  @Test
  void aNegativeQuietWindowIsTheSameSwitch() {
    sweep.quietWindow = Duration.ofMinutes(-30);
    project("project-old", "old");
    runtime.given("project-old", "old", true);
    daemon("project-old", "2026.901.120000");
    registry.touchAgentActivity("project-old", NOW.minus(Duration.ofDays(30)));

    assertEquals(0, sweep.sweep(NOW));
    assertEquals(List.of(), stopped);
  }

  /**
   * A container nothing has ever happened in is the quietest a container gets — one that outlived a
   * restart of this service, or whose daemon connected and did nothing since. It is not an unknown to
   * be cautious about, and treating it as one would leave the longest-stale containers untouched for
   * ever.
   *
   * <p>The fixture's {@code Hello} leaves the use clock untouched, which is the rule as of
   * 2026-09-18 and is the assertion here rather than a detail of the setup: <b>a reconnect is not
   * use</b>. It used to stamp, so this test had to {@code forget} the container to reach the state
   * it is about — and every restart of this service blinded the sweep for a whole quiet window.
   */
  @Test
  void aContainerThatWasNeverStampedIsQuiet() {
    project("project-fresh", "fresh");
    runtime.given("project-fresh", "fresh", true);
    daemon("project-fresh", "2026.901.120000");

    assertTrue(
        registry.lastAgentActivityAt("project-fresh").isEmpty(),
        "the Hello is the daemon dialling home, not somebody using the container");

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(List.of("project-fresh"), stopped);
  }

  /**
   * <b>The scenario observed live on 2026-09-18, and the one this sweep could not act on at all.</b>
   * The project agent container relayed a supervised subprocess's retry loop as a {@code DaemonLog}
   * every thirty seconds, for ever — {@code checkout-daemon: … ConnectException; reconnecting in
   * 30 s}. Under the denylist that preceded the allowlist every one of those frames stamped the use
   * clock, so this container could never be more than thirty seconds' quiet however long nobody
   * touched it, and the stop below never happened: a self-generated periodic frame defeating a
   * quietness window, which is precisely the defect the heartbeat exclusion was written for, one
   * frame class over.
   *
   * <p>The frames here are the container's <b>only</b> traffic since the stamp, which is what the
   * live case looked like — nobody had opened a terminal in it for hours.
   */
  @Test
  void stopsAStaleContainerWhoseOnlyTrafficHasBeenDaemonLogs() {
    project("project-chatty", "chatty");
    runtime.given("project-chatty", "chatty", true);
    daemon("project-chatty", "2026.901.120000");
    registry.touchAgentActivity("project-chatty", NOW.minus(Duration.ofHours(2)));

    for (int line = 0; line < 10; line++) {
      registry.onMessage(
          "project-chatty",
          null,
          new DaemonLog(
              "INFO",
              "checkout-daemon: qits checkout-daemon: Cannot reach"
                  + " http://dev-qits-events:8080/events/api/stream?names=SCMRelease:"
                  + " ConnectException; reconnecting in 30 s"));
    }

    assertEquals(1, sweep.sweep(NOW));
    assertEquals(
        List.of("project-chatty"),
        stopped,
        "a daemon talking about itself is not somebody using the container");
  }
}
