package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.control.TechnicalProcess;
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
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceChanged;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The host's live-{@code qits-workspace-daemon} directory for refinement containers, keyed by
 * refinement row id. The refinement twin of {@code agenthost/AgentDaemonRegistry}, speaking the
 * workspace protocol — {@link RefinementControlSocket} owns the WebSocket lifecycle and forwards
 * frames here.
 *
 * <h2>What is held, and for whom</h2>
 *
 * <ul>
 *   <li><b>The connection and its {@code Hello}</b> — {@code daemonConnectedAt}, {@code
 *       daemonVersion}, {@code daemonBuildTime}, {@code capabilityVersion} — for the status strip
 *       and for {@link RefinementTunnels}, which gates on the tunnel capability.
 *   <li><b>Git cleanliness</b>, daemon-reported and in-memory: the recreate gate and the status
 *       strip read it; it means nothing for a stopped container and is dropped with the connection.
 *   <li><b>Agent activity</b>, rolled up {@code BUSY > WAITING > IDLE > ENDED} across the
 *       container's live sessions, with {@code ENDED} entries aged out on read and every entry aged
 *       out at a longer horizon.
 *   <li><b>The quiet clock</b> — {@code lastAgentActivity}, when somebody last <em>used</em> this
 *       container, stamped for the frames {@link #evidencesUse} admits and no others. Read by the
 *       stale-image sweep, which stops a container running an outdated image while it is quiet.
 *   <li><b>The provision narration</b> — {@code CommandChunk}s tagged {@code provision} routed into
 *       the ensure's {@link TechnicalProcess}, and the terminal {@code Provisioned}/{@code
 *       ProvisionFailed} settling it. The failure is also recorded beside the connection, because a
 *       daemon that cannot provision usually drops its socket right after saying so.
 * </ul>
 *
 * <p>Everything else the daemon can say — bootstrap frames (autorun is off, so only the benign
 * {@code Bootstrapped}), service transitions (autostart is off), config views, command frames with
 * other correlation ids — is dropped without ceremony: a daemon must not be able to break its own
 * control socket by saying something this host has no view for.
 */
@ApplicationScoped
public class RefinementDaemonRegistry {

  private static final Logger LOG = Logger.getLogger(RefinementDaemonRegistry.class);

  @Inject RefinementMessageCodec codec;

  @Inject RefinementChangePublisher changes;

  /** An {@code Instance<>} to break the cycle, exactly as the agent harness does. */
  @Inject Instance<RefinementTunnels> tunnels;

  /** How long an {@code ENDED} session keeps a say in the activity rollup. */
  @ConfigProperty(name = "qits.projects.refinement.ended-activity-ttl-ms", defaultValue = "1800000")
  long endedActivityTtlMs;

  /**
   * How long an entry of <b>any</b> state keeps a say in the rollup — the horizon past which a
   * session stops being evidence that anything is live at all. Four hours.
   *
   * <h2>Why an entry that is not {@code ENDED} has to expire too</h2>
   *
   * <p>{@link #endedActivityTtlMs} alone ages out only the sessions that <em>announced</em> they
   * were over, which leaves a {@code BUSY} or {@code WAITING} entry immortal. A session whose agent
   * died, was killed, or whose container was replaced before its {@code Stop}/{@code SessionEnd}
   * hook fired leaves a {@code BUSY} nothing ever takes back — and the control socket's reconnect
   * adoption re-reports the daemon's retained per-command state, so the dead session is re-asserted
   * every time this service restarts.
   *
   * <p>That was survivable while the rollup only coloured a chip in the refinement UI, where a
   * stuck {@code BUSY} is something a person ignores. It stopped being survivable when the rollup
   * became a <em>veto</em> on the stale-image sweep: a {@code BUSY} nothing takes back is then a
   * permanent refusal to act, and it refuses hardest on exactly the long-lived refinement
   * containers the sweep exists to reach. The agent axis measured that live on 2026-09-18 — three
   * consecutive passes naming the same container in a WARN and never able to stop it.
   *
   * <h2>Why four hours, and why it must be longer than the quiet window</h2>
   *
   * <p>It is a bound on <b>how long a single agent turn can plausibly be</b>, not a guess at how
   * long a session lasts. The daemon's hooks fire at turn boundaries, so a live session refreshes
   * its entry at every one of them and an agent working all day is a stream of entries rather
   * than one old one. The only thing this horizon can cut short is a single turn still running
   * four hours after it began, and what that releases is lossless: the container is stopped, never
   * removed, and the checkout is on a volume nothing here discards.
   *
   * <p><b>It must be strictly longer than {@code qits.projects.refinement-stale-quiet-window}</b>
   * (PT30M). At equal values this horizon could never veto anything {@link #lastAgentActivity} had
   * not already vetoed — every entry old enough to survive as evidence would also be a stamp inside
   * the window — so the second condition would quietly stop meaning anything, with every test still
   * green. The rollup's whole reason for existing is the case the stamp gets wrong: an agent
   * thinking silently between two frames.
   */
  @ConfigProperty(
      name = "qits.projects.refinement.stale-activity-ttl-ms",
      defaultValue = "14400000")
  long staleActivityTtlMs;

  private final ConcurrentHashMap<Long, DaemonConnection> clients = new ConcurrentHashMap<>();

  /** Daemon-reported working-tree cleanliness, present only while a daemon is connected. */
  private final ConcurrentHashMap<Long, Boolean> gitClean = new ConcurrentHashMap<>();

  /** Why the last provision failed — outliving the socket that reported it. */
  private final ConcurrentHashMap<Long, String> provisionFailures = new ConcurrentHashMap<>();

  /** Per-refinement, per-session agent activity, for the rollup. */
  private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ActivityEntry>> activity =
      new ConcurrentHashMap<>();

  /**
   * When somebody last <em>used</em> each refinement's container — written for the frames {@link
   * #evidencesUse} admits, and for no others. Read by the stale-image sweep.
   *
   * <h2>This is the only clock here, and it must never become a liveness one</h2>
   *
   * <p>The agent harness keeps two stamps — one for "is this daemon alive", fed by everything
   * including the heartbeat, and one for "is anybody doing something in here". This registry has
   * <b>one</b>, and it is the second kind. Nothing on the refinement axis asks the liveness
   * question of a stamp: an open {@link WebSocketConnection} in {@code clients} is the liveness
   * answer, and
   * {@link #lookup} already gives it. So there is no second reader to serve and no reason to
   * widen what writes here.
   *
   * <p><b>Widening it is the one change that would destroy it.</b> {@code qits-workspace-daemon}
   * heartbeats every twenty seconds for as long as its container runs, unconditionally; a
   * {@link Heartbeat} that stamped this map would make it never more than twenty seconds old on a
   * container nobody has touched for a month, and every quiet window measured against it would be
   * permanently unsatisfiable. That is not a hypothetical — it is the defect this field was added
   * to fix, and it is why {@link Heartbeat} stays where it is in {@link #evidencesUse}. If a
   * liveness
   * stamp is ever genuinely wanted here, it is a <em>second</em> map, the way the agent harness has
   * two; it is never this one with more writers.
   *
   * <p>Not cleared on disconnect, deliberately, exactly as the agent harness's is not: a container
   * whose daemon has dropped is still a container a sweep has to reason about, and forgetting when
   * it was last useful would make it instantly reapable on nothing but a socket blip. It goes in
   * {@link #forget}, with the rollup and for the same reason — the container is stopped, and the
   * next one must start its window afresh.
   */
  private final ConcurrentHashMap<Long, Instant> lastAgentActivity = new ConcurrentHashMap<>();

  /** The ensure's live narration, routed to from provision frames. Set by the service. */
  private final ConcurrentHashMap<Long, TechnicalProcess> provisionProcesses =
      new ConcurrentHashMap<>();

  private record ActivityEntry(String state, long atMillis) {}

  /** What a connected daemon announced about itself. */
  public record DaemonInfo(
      Instant connectedAt, String daemonVersion, Instant daemonBuildTime, int capabilityVersion) {}

  public void register(Long refinementId, WebSocketConnection connection) {
    clients.put(refinementId, new DaemonConnection(connection));
    // A reconnecting daemon re-runs its provision walk; its next word on the subject is current.
    provisionFailures.remove(refinementId);
    LOG.debugf("workspace-daemon connected for refinement %s", refinementId);
  }

  public void unregister(Long refinementId, WebSocketConnection connection) {
    clients.computeIfPresent(
        refinementId,
        (id, existing) -> existing.connection.id().equals(connection.id()) ? null : existing);
    gitClean.remove(refinementId);
    if (tunnels.isResolvable()) {
      tunnels.get().onDaemonGone(refinementId);
    }
    LOG.debugf("workspace-daemon disconnected for refinement %s", refinementId);
  }

  /** What the refinement's connected daemon announced, or empty when none is connected. */
  public Optional<DaemonInfo> lookup(Long refinementId) {
    DaemonConnection client = clients.get(refinementId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty();
    }
    return Optional.of(
        new DaemonInfo(
            client.connectedAt,
            client.daemonVersion,
            client.daemonBuildTime,
            client.capabilityVersion));
  }

  /**
   * Whether this daemon's build is older than the newest one connected to this host — {@code TRUE}
   * or {@code null}, never {@code false}, the same three-valued answer the workspaces domain gives:
   * "outdated" is a claim, "not outdated" is only ever the absence of one.
   */
  public Boolean daemonOutdated(Long refinementId) {
    DaemonConnection mine = clients.get(refinementId);
    if (mine == null || !mine.connection.isOpen()) {
      return null;
    }
    Comparator<DaemonConnection> order =
        Comparator.comparing(
                (DaemonConnection c) -> c.daemonBuildTime,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(c -> c.daemonVersion, Comparator.nullsFirst(Comparator.naturalOrder()));
    DaemonConnection latest =
        clients.values().stream()
            .filter(c -> c.connection.isOpen())
            .max(order)
            .orElse(null);
    if (latest == null || order.compare(mine, latest) >= 0) {
      return null;
    }
    return Boolean.TRUE;
  }

  /** Daemon-reported cleanliness, or empty for a daemon that has not said (or is not there). */
  public Optional<Boolean> clean(Long refinementId) {
    return Optional.ofNullable(gitClean.get(refinementId));
  }

  /**
   * When somebody last did something in this refinement's container, or empty when nothing ever
   * has — the heartbeat-free stamp the stale-image sweep reads. See {@link #lastAgentActivity} for
   * why it is the only clock here and what it must not become.
   *
   * <p>Empty is a real and useful answer rather than a missing one: a container nothing has ever
   * happened in is the quietest a container gets, and a sweep reads it that way.
   */
  public Optional<Instant> lastAgentActivityAt(Long refinementId) {
    return Optional.ofNullable(lastAgentActivity.get(refinementId));
  }

  /**
   * Record that something happened in this refinement's container at {@code at} — the stamp's only
   * writer besides {@link #onMessage}, and here so a sweep test can drive a fake clock instead of
   * waiting out a quiet window. The agent harness's {@code touchAgentActivity} is the same seam for
   * the same reason.
   */
  void touchAgentActivity(Long refinementId, Instant at) {
    lastAgentActivity.put(refinementId, at);
  }

  /**
   * The refinement's rolled-up agent activity, or empty when no session has reported.
   *
   * <p><b>Two horizons, and an entry goes when it passes either.</b> An {@code ENDED} session is
   * over and expires at the short {@link #endedActivityTtlMs}; every entry, whatever it says,
   * expires at the long {@link #staleActivityTtlMs}, because a session can stop reporting without
   * ever saying it ended and a {@code BUSY} that outlives its agent is otherwise a permanent claim
   * that something is running. The second horizon carries the argument — see the field.
   */
  public Optional<String> agentActivity(Long refinementId) {
    Map<String, ActivityEntry> sessions = activity.get(refinementId);
    if (sessions == null || sessions.isEmpty()) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    sessions
        .entrySet()
        .removeIf(
            entry -> {
              long age = now - entry.getValue().atMillis();
              return (DaemonProtocol.AgentState.ENDED.equals(entry.getValue().state())
                      && age > endedActivityTtlMs)
                  || age > staleActivityTtlMs;
            });
    return sessions.values().stream()
        .map(ActivityEntry::state)
        .max(Comparator.comparingInt(RefinementDaemonRegistry::activityRank));
  }

  private static int activityRank(String state) {
    return switch (state) {
      case DaemonProtocol.AgentState.BUSY -> 4;
      case DaemonProtocol.AgentState.WAITING -> 3;
      case DaemonProtocol.AgentState.IDLE -> 2;
      case DaemonProtocol.AgentState.ENDED -> 1;
      default -> 0;
    };
  }

  /** Why this refinement's {@code /workspace} is not provisioned, or empty. */
  public Optional<String> provisionFailure(Long refinementId) {
    return Optional.ofNullable(provisionFailures.get(refinementId));
  }

  /** Route the current ensure's narration here — provision frames append to it. */
  public void attachProvisionProcess(Long refinementId, TechnicalProcess process) {
    provisionProcesses.put(refinementId, process);
  }

  /**
   * Forget everything about a refinement whose container is stopped or discarded.
   *
   * <p>{@link #lastAgentActivity} goes here and <b>only</b> here — not in {@link #unregister},
   * which drops {@link #gitClean} alone. The two are evicted on different events because they mean
   * different things: cleanliness is a claim about a daemon that is currently connected and is
   * meaningless the moment one is not, while the use stamp describes the <em>container</em> and has
   * to survive a socket blip, a daemon restart and a redeploy of this service. It is cleared with
   * the rollup, on the one event that really does end the thing it described.
   */
  public void forget(Long refinementId) {
    gitClean.remove(refinementId);
    provisionFailures.remove(refinementId);
    activity.remove(refinementId);
    lastAgentActivity.remove(refinementId);
    provisionProcesses.remove(refinementId);
  }

  /**
   * Ask a daemon to dial back and serve one stream — the reverse tunnel's only outbound message.
   * Fire-and-forget: this runs on a {@code NetServer} connect handler's event loop.
   */
  void requestStream(Long refinementId, String nonce, String path) {
    DaemonConnection client = clients.get(refinementId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("requestStream: no workspace-daemon live for refinement %s", refinementId);
      return;
    }
    client
        .connection
        .sendText(codec.encode(new OpenStream(nonce, path)))
        .subscribe()
        .with(
            ignored -> {},
            failure ->
                LOG.debugf(
                    "could not ask refinement %s for a stream: %s", refinementId, failure));
  }

  /**
   * Whether this frame is evidence that a <b>person or an agent is doing something</b> in the
   * refinement's container — the one question {@link #lastAgentActivity} stores the answer to, and
   * the only thing that may stamp it.
   *
   * <h2>Why an allowlist, and never a denylist</h2>
   *
   * <p><b>The protocol grows.</b> Under a denylist a frame nobody has considered yet defaults to
   * "somebody is using this container", so every future addition to {@link DaemonMessage} is a
   * chance to silently reinstate the defect this stamp exists to prevent, with every test still
   * green. Under an allowlist it defaults to not-use, and a frame that really is evidence is a
   * one-line addition somebody makes deliberately.
   *
   * <p><b>The two costs are not symmetric, which is what settles the direction.</b> A wrong "not in
   * use" is bounded: the container is stopped, never removed, the checkout is on a volume nothing
   * here discards, and the next ensure starts it again. A wrong "in use" is unbounded: the
   * container is stale for ever and no sweep can reach it. Default to the bounded mistake.
   *
   * <p>This is an <b>exhaustive {@code switch} with no {@code default} arm</b>, unlike {@link
   * #onMessage} one method down, and the difference is the whole point. {@code onMessage} has a
   * catch-all because a daemon must not be able to break its own control socket by saying something
   * this host has no view for — dropping an unknown frame there is correct. Here a catch-all would
   * be a decision made by omission, so adding a frame to the protocol <b>fails this compilation</b>
   * instead.
   *
   * <h2>What stamps</h2>
   *
   * <ul>
   *   <li>{@link AgentActivity} — an agent's own turn boundary, reported by its hooks. The frame
   *       exists for no other reason.
   *   <li>{@link CommandChunk} — a command producing output. Refinement containers are driven
   *       through {@code RefinementProxyRoute}, so a chunk is somebody's terminal or somebody's
   *       agent. The <em>provision</em> narration rides this same frame under {@link
   *       DaemonProtocol#PROVISION_CORRELATION_ID} and is this service's own doing, and it is
   *       deliberately not filtered out: a provision is a burst that ends, so counting it costs at
   *       worst one quiet window after a freshly provisioned container, where a periodic frame
   *       would have cost immunity for ever. The correlation filter in {@link #onCommandChunk} is
   *       about which narration to append to, not about who is in the container.
   *   <li>{@link CommandExit} — a command finishing, for the same reason.
   *   <li>{@link WorkspaceChanged} — the daemon's change nudge. Its only emitter is {@code
   *       ControlSocket.nudge}, called from {@code CommandLifecycleService} and the agent launch
   *       path alone, so it means a command started or ended rather than "something changed
   *       somewhere".
   * </ul>
   *
   * <h2>What does not, and why each one</h2>
   *
   * <ul>
   *   <li>{@link Heartbeat} — liveness, unconditional and every twenty seconds for as long as the
   *       container runs. <b>This is the entire defect being fixed</b>; see {@link
   *       #lastAgentActivity}.
   *   <li>{@link Hello} — <b>a reconnect is not use.</b> Every daemon on the estate redials when
   *       this service restarts, so counting it would make every quiet window start again after
   *       every redeploy — on an estate that redeploys hourly, most of the time a sweep could have
   *       been acting.
   *   <li>{@link DaemonLog} — <b>the daemon talking about itself</b>, a supervised subprocess's
   *       stderr included, and the one caught live. On 2026-09-18 a project agent container relayed
   *       {@code checkout-daemon: Cannot reach …; reconnecting in 30 s} as a {@code DaemonLog}
   *       every thirty seconds, for ever; under a denylist that stamped the clock twice a minute
   *       and the container became permanently unsweepable — the heartbeat's defect one frame class
   *       over. Daemon self-talk is unbounded by construction and must never mean "in use".
   *   <li>{@link GitStatus} — <b>the one worth arguing.</b> It is emitted by an inotify watcher on
   *       {@code /workspace}, so the tempting reading is that a tree which changed is somebody at
   *       work. It is refused, because <em>the same frame class</em> carries three different things
   *       and this host cannot tell them apart: the watcher's boot report at {@code start()}, the
   *       re-report the daemon makes on reconnect adoption, and a real marker move. The first two
   *       arrive on every daemon restart and every reconnect, which is the {@link Hello} objection
   *       verbatim; and a marker move is also what the provision's own self-clone produces, so a
   *       freshly provisioned container would stamp itself. The loss is small and bounded: an agent
   *       that edits files reports {@link AgentActivity} as well, a person editing through a
   *       terminal produces {@link CommandChunk}, and there is no editor surface on this axis at
   *       all. A file changing is evidence something wrote it; it is not evidence that the writer
   *       was a person.
   *   <li>{@link Provisioned}, {@link ProvisionFailed} — the result of a provision <em>this service
   *       asked for</em>. Counting them would make every freshly provisioned container immune for a
   *       whole quiet window on nothing but its own creation.
   *   <li>{@link BootstrapStep}, {@link BootstrapOutcome}, {@link Bootstrapped} — the same
   *       automated lifecycle one stage further on. Autorun is off for refinements, so only the
   *       benign {@code Bootstrapped} is expected at all, and an automated bootstrap is not
   *       somebody using the container either way.
   *   <li>{@link EditorState} — the supervised web editor's lifecycle, and a refinement host has no
   *       editor surface for it to answer. It arrives only because the same image serves
   *       qits-workspaces.
   *   <li>{@link WorkspaceInfo}, {@link ConfigView} — replies to a {@link Describe} / {@link
   *       DescribeConfig} <b>this host sent</b>. A reply to our own question is this service
   *       talking to itself.
   *   <li>{@link Ack}, {@link RunCommand}, {@link Describe}, {@link DescribeConfig}, {@link
   *       RunBootstrap}, {@link PullBranch}, {@link OpenStream} — <b>host→daemon frames, which
   *       never arrive here at all.</b> They are the outbound half of the protocol (the daemon only
   *       ever handles them; it constructs none of them), so they are named purely for the record.
   *       A supervised process's lifecycle frame and any other subtype this host does not name fall
   *       to the {@code default} arm below, which answers {@code false} exactly as they did named:
   *       the switch no longer enumerates every subtype because the workspace-daemon protocol is
   *       dropping the service-supervision ones, and naming them here would stop this file
   *       compiling against that jar. Were an echo of a host→daemon frame to turn up, it would be
   *       this host's own request coming back and still not use.
   * </ul>
   */
  private static boolean evidencesUse(DaemonMessage message) {
    return switch (message) {
      case AgentActivity ignored -> true;
      case CommandChunk ignored -> true;
      case CommandExit ignored -> true;
      case WorkspaceChanged ignored -> true;
      case Hello ignored -> false;
      case Heartbeat ignored -> false;
      case DaemonLog ignored -> false;
      case GitStatus ignored -> false;
      case Provisioned ignored -> false;
      case ProvisionFailed ignored -> false;
      case BootstrapStep ignored -> false;
      case BootstrapOutcome ignored -> false;
      case Bootstrapped ignored -> false;
      case EditorState ignored -> false;
      case WorkspaceInfo ignored -> false;
      case ConfigView ignored -> false;
      case Ack ignored -> false;
      case RunCommand ignored -> false;
      case Describe ignored -> false;
      case DescribeConfig ignored -> false;
      case RunBootstrap ignored -> false;
      case PullBranch ignored -> false;
      case OpenStream ignored -> false;
      default -> false;
    };
  }

  /** Handle a decoded workspace-daemon frame for {@code refinementId}. */
  public void onMessage(Long refinementId, WebSocketConnection connection, DaemonMessage message) {
    // The quiet clock, written only for the frames that are evidence somebody is using this
    // container. The rule is an allowlist and the argument for that is in evidencesUse.
    if (evidencesUse(message)) {
      lastAgentActivity.put(refinementId, Instant.now());
    }
    DaemonConnection client = clients.get(refinementId);
    switch (message) {
      case Hello hello -> {
        LOG.infof(
            "workspace-daemon HELLO for refinement %s (workspace %s, capability %d, daemon %s)",
            refinementId, hello.workspaceId(), hello.capabilityVersion(), hello.daemonVersion());
        if (client != null) {
          client.daemonVersion = hello.daemonVersion();
          client.daemonBuildTime = parseInstant(hello.daemonBuildTime());
          client.capabilityVersion = hello.capabilityVersion();
        }
        connection.sendTextAndAwait(codec.encode(new Ack()));
      }
      case Heartbeat ignored -> {
        /* liveness only — the open socket is the signal */
      }
      case DaemonLog log ->
          LOG.infof("[workspace-daemon %s] %s: %s", refinementId, log.level(), log.message());
      case GitStatus status -> onGitStatus(refinementId, status);
      case AgentActivity report -> onAgentActivity(refinementId, report);
      case WorkspaceChanged changed -> onWorkspaceChanged(refinementId, changed);
      case CommandChunk chunk -> onCommandChunk(refinementId, chunk);
      case Provisioned provisioned -> onProvisioned(refinementId, provisioned);
      case ProvisionFailed failed -> onProvisionFailed(refinementId, failed);
      case EditorState ignored -> {
        // Deliberately nothing. The frame announces the supervised web editor's lifecycle, and a
        // refinement host has no editor surface to gate with it: there is no editor proxy and no
        // splash here, so the state has nothing to answer. It arrives at all only because the same
        // qits-workspace-daemon image serves qits-workspaces, where it IS the capability
        // announcement. Named explicitly rather than left to the default arm so that dropping it is
        // a decision this file records, not an accident of the catch-all.
      }
      // Everything else — bootstrap frames, service transitions, config views, command exits,
      // echoes of host->daemon requests — is dropped rather than treated as an error.
      default -> LOG.tracef("dropped a %s frame for refinement %s", message.getClass(), refinementId);
    }
  }

  private void onGitStatus(Long refinementId, GitStatus status) {
    Boolean previous = gitClean.put(refinementId, status.clean());
    // Files changed whenever the tree did; the cleanliness flag itself only when it flipped. The
    // broadcaster debounces, so firing FILES on every report is a hint, not a storm.
    changes.fire(refinementId, RefinementChangeHint.Topic.FILES);
    if (previous == null || previous.booleanValue() != status.clean()) {
      changes.fire(refinementId, RefinementChangeHint.Topic.GIT_STATUS);
    }
  }

  private void onAgentActivity(Long refinementId, AgentActivity report) {
    String key =
        report.sessionId() != null && !report.sessionId().isBlank()
            ? report.sessionId()
            : report.commandId();
    if (key == null || key.isBlank() || report.state() == null) {
      return;
    }
    long at = report.at() > 0 ? report.at() : System.currentTimeMillis();
    activity
        .computeIfAbsent(refinementId, id -> new ConcurrentHashMap<>())
        .put(key, new ActivityEntry(report.state(), at));
    changes.fire(refinementId, RefinementChangeHint.Topic.AGENT_ACTIVITY);
  }

  /**
   * Relay a daemon-side change nudge onto the refinement's SSE stream. The frame carries a topic
   * name rather than an enum so a newer daemon can nudge about something this host has no view for
   * — and that must cost nothing.
   */
  private void onWorkspaceChanged(Long refinementId, WorkspaceChanged changed) {
    if (changed.topic() == null) {
      return;
    }
    try {
      changes.fire(refinementId, RefinementChangeHint.Topic.valueOf(changed.topic()));
    } catch (IllegalArgumentException unknown) {
      LOG.debugf(
          "Ignoring a workspace-daemon nudge for unknown topic '%s' (refinement %s)",
          changed.topic(), refinementId);
    }
  }

  /** Provision output, streamed into the ensure's narration. Other correlations are dropped. */
  private void onCommandChunk(Long refinementId, CommandChunk chunk) {
    if (!DaemonProtocol.PROVISION_CORRELATION_ID.equals(chunk.correlationId())) {
      return;
    }
    TechnicalProcess process = provisionProcesses.get(refinementId);
    if (process == null || process.isTerminal()) {
      return;
    }
    if (!process.isSegmentSettled(PROVISION_SEGMENT)) {
      process.openSegment(PROVISION_SEGMENT);
      process.appendLine(PROVISION_SEGMENT, chunk.text());
    }
  }

  /** The segment the daemon's self-clone output lands in. */
  static final String PROVISION_SEGMENT = "clone";

  private void onProvisioned(Long refinementId, Provisioned provisioned) {
    LOG.infof("workspace-daemon provisioned refinement %s at %s", refinementId, provisioned.head());
    provisionFailures.remove(refinementId);
    TechnicalProcess process = provisionProcesses.remove(refinementId);
    if (process != null && !process.isTerminal()) {
      if (!process.isSegmentSettled(PROVISION_SEGMENT)) {
        process.openSegment(PROVISION_SEGMENT);
        process.settleSegment(PROVISION_SEGMENT, true);
      }
      process.finishProvision(true);
    }
    changes.fire(refinementId, RefinementChangeHint.Topic.PROCESS);
    changes.fire(refinementId, RefinementChangeHint.Topic.FILES);
  }

  private void onProvisionFailed(Long refinementId, ProvisionFailed failed) {
    String reason =
        failed.message() == null || failed.message().isBlank()
            ? "The daemon reported a failed provision with no reason."
            : failed.message();
    LOG.warnf("workspace-daemon could not provision refinement %s: %s", refinementId, reason);
    provisionFailures.put(refinementId, reason);
    TechnicalProcess process = provisionProcesses.remove(refinementId);
    if (process != null && !process.isTerminal()) {
      process.failProvision(reason);
    }
    changes.fire(refinementId, RefinementChangeHint.Topic.PROCESS);
  }

  private static Instant parseInstant(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  /** One live client: its connection and what its {@link Hello} announced. */
  private static final class DaemonConnection {
    private final WebSocketConnection connection;
    private final Instant connectedAt = Instant.now();
    private volatile String daemonVersion;
    private volatile Instant daemonBuildTime;
    private volatile int capabilityVersion;

    DaemonConnection(WebSocketConnection connection) {
      this.connection = connection;
    }
  }
}
