package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projectsdaemon.protocol.Ack;
import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.CommandChunk;
import eu.wohlben.qits.projectsdaemon.protocol.CommandExit;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonLog;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Describe;
import eu.wohlben.qits.projectsdaemon.protocol.Heartbeat;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.OpenStream;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectChanged;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectInfo;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.projectsdaemon.protocol.Provisioned;
import eu.wohlben.qits.projectsdaemon.protocol.RunCommand;
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
 * The host's live-{@code qits-projects-daemon} directory: which projects have an open control
 * socket, keyed by {@code projectId}, plus the build stamp each one announced and when it was last
 * heard from. The in-JVM half of the control plane — {@link AgentControlSocket} owns the WebSocket
 * lifecycle and forwards frames here. Adapted from qits-workspaces' {@code
 * WorkspaceDaemonRegistry}, and much smaller than it.
 *
 * <h2>What was trimmed, and why each one has no caller here</h2>
 *
 * <p>The reference correlates {@code RunCommand}/{@code Describe} round-trips, drives bootstrap
 * chains and dev-server supervision, and rolls agent activity up per workspace. None of that
 * applies: the browser drives this daemon over its own HTTP API through the reverse tunnel, so
 * nothing on the host sends a {@code RunCommand}, and a project agent runs no services and no
 * bootstrap chain. The frames those replies would arrive on are handled defensively below and
 * dropped, because a daemon must never be able to break this socket by saying something unexpected.
 *
 * <p>What is kept is exactly what the host needs: the handshake, the build stamp (so the UI can say
 * which daemon build a container is on), a last-heard-from stamp (so the idle sweep can stop a
 * container nobody is using), the change nudges, and {@link #requestStream} — the reverse tunnel's
 * one outbound message.
 */
@ApplicationScoped
public class AgentDaemonRegistry {

  private static final Logger LOG = Logger.getLogger(AgentDaemonRegistry.class);

  @Inject DaemonMessageCodec codec;

  @Inject ProjectChangePublisher changePublisher;

  /**
   * The reverse tunnel's host end. An {@code Instance<>} to break the cycle — {@link AgentTunnels}
   * asks this registry which daemons can serve a stream, and this registry tells it when one goes
   * away.
   */
  @Inject Instance<AgentTunnels> tunnels;

  /**
   * The harness capability relay, fired once per {@link Hello}. An {@code Instance<>} for the same
   * reason as {@link #tunnels}: it reaches back through {@link AgentTunnels} to this registry, and it
   * must be optional so a topology without it is a registry that still works.
   */
  @Inject Instance<AgentCapabilityRelay> capabilityRelay;

  private final ConcurrentHashMap<String, DaemonConnection> clients = new ConcurrentHashMap<>();

  /**
   * When each project's agent was last known to be doing something — a {@link Hello}, a {@link
   * Heartbeat}, an {@link AgentActivity} report, or a host-side start. Read by {@link
   * AgentIdleSweep} and by nothing else.
   *
   * <p>Deliberately <b>not</b> cleared on disconnect. A container whose daemon has dropped is still
   * a container the sweep has to be able to reason about, and forgetting when it was last useful
   * would make it either immortal or instantly reapable depending on which way the absence was
   * read. It is dropped only when the container is stopped.
   */
  private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();

  /**
   * When anything last <em>happened</em> in each project's container — every inbound frame except a
   * {@link Heartbeat} and an {@link Ack}.
   *
   * <h2>Why this is a second map and must never be collapsed into {@link #lastActivity}</h2>
   *
   * <p>The two answer different questions and only one of them can be answered by each map.
   * {@link #lastActivity} answers <b>"is this daemon alive"</b>, and it includes the heartbeat on
   * purpose — which is exactly what makes it useless for the other question. A project agent's daemon
   * heartbeats every twenty seconds unconditionally, for as long as its container runs, so
   * {@link #lastActivity} is never more than twenty seconds old on a container nobody has touched for
   * a month. Anything that asks "is anyone using this" against it gets "yes", for ever.
   *
   * <p>This map answers <b>"has anything happened here"</b>. The heartbeat and the {@code Ack} are
   * the two frames a daemon emits while nothing at all is going on, so they are the two frames that
   * do not write it; everything else — the {@link Hello}, an {@link AgentActivity} report, a
   * {@link ProjectChanged} nudge, a provision result, a log line — does.
   *
   * <p><b>Collapsing them is the defect this exists to end.</b> {@link AgentIdleSweep}'s window never
   * elapsed because it reads {@link #lastActivity}, and that is correct for what it measures; making
   * the heartbeat stop writing that map would instead make every live container look reapable. Two
   * clocks, two readers, and neither one is a refinement of the other.
   *
   * <p>Not cleared on disconnect, for the same reason {@link #lastActivity} is not: a container whose
   * daemon has dropped is still a container a sweep has to reason about. It goes in {@link #forget}.
   */
  private final ConcurrentHashMap<String, Instant> lastAgentActivity = new ConcurrentHashMap<>();

  /**
   * Per-project, per-session agent lifecycle state, rolled up by {@link #agentActivity}.
   *
   * <p>The same shape {@code RefinementDaemonRegistry} carries on the other axis, deliberately: both
   * hosts are answering "is an agent running in this container right now" from the same
   * {@link AgentActivity} frame, and a second design for one question would be a second thing to keep
   * true. Sessions rather than one value per project because a container serves several at once and
   * the busiest of them is the answer — see {@link #activityRank}.
   */
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, ActivityEntry>> agentActivity =
      new ConcurrentHashMap<>();

  /**
   * How long an {@code ENDED} session keeps a say in the rollup, mirroring {@code
   * qits.projects.refinement.ended-activity-ttl-ms} and shipped at the same half hour. Long enough
   * that a session somebody is reading the tail of still reads as this container's most recent state,
   * short enough that a container whose last agent ended before lunch is not still described by it.
   */
  @ConfigProperty(name = "qits.projects.agent.ended-activity-ttl-ms", defaultValue = "1800000")
  long endedActivityTtlMs;

  /**
   * How long an entry in <em>any</em> state keeps a say in the rollup — the backstop against a
   * session that died or lied, and the second of two horizons that are deliberately not one number
   * (ticket 5f52c45b).
   *
   * <h2>Why two horizons, and why this one is eight times the other</h2>
   *
   * <p><b>The two say different things.</b> An {@code ENDED} entry merely describes the past, so it
   * can be dropped as soon as it stops being the most recent thing that happened — half an hour, and
   * it vetoes nothing while it lives because {@code ENDED} ranks below every other state. A {@code
   * BUSY}/{@code WAITING} entry is a claim that something is <b>live</b>, and it vetoes
   * {@link AgentStaleImageSweep}'s rollup half on its own. Dropping that early would un-veto a
   * genuinely long turn, which is the exact case that half exists for — a long tool call is minutes
   * of silence in the middle of a turn. So this horizon has to be longer than any plausible turn: it
   * is a backstop against a session that died or lied, <b>never a timeout on work</b>.
   *
   * <p><b>Four hours is borrowed rather than invented.</b> {@link AgentIdleSweep#idleTimeout} already
   * treats {@code PT4H} as the point at which a container nobody has come back to is gone, so this
   * reuses that estate's existing reading of "long enough that this cannot be live any more" instead
   * of minting a second number for the same judgement.
   *
   * <p><b>What it costs nothing.</b> {@code AgentStaleImageSweep.isQuiet}'s <em>stamp</em> half is
   * untouched by this and must stay so. A truly busy session emits frames — {@link CommandChunk},
   * {@link CommandExit}, {@link AgentActivity} — and every one of them stamps
   * {@link #lastAgentActivity}, which vetoes on its own inside that sweep's quiet window. Widening
   * this prune therefore removes a veto that <b>silence alone</b> was holding; it cannot make the
   * sweep stop a container somebody is using.
   *
   * <p><b>The invariant, and where it is reported.</b> This has to stay strictly longer than {@code
   * qits.projects.agent-stale-quiet-window}, or the rollup can only ever veto what the stamp has
   * already vetoed and its whole reason for existing is gone with every test still green. That is not
   * enforceable here — both values are a deployment's — so
   * {@code startup/AgentActivityHorizonAudit} says so at boot.
   *
   * <p><b>The defect this ends.</b> The prune used to test {@code ENDED} alone, so {@code BUSY(4)}
   * and {@code WAITING(3)} never aged out at all while the one state that can veto nothing was the
   * only one with a horizon. A Claude Code session launched through the agent surface stays {@code
   * RUNNING} for ever and emits no {@code ENDED}, so its last {@code WAITING} answered for the
   * container permanently and no moved image pin could ever reach it. Measured live 2026-09-19: three
   * sessions left open for 2h40m held one container un-sweepable across two sweep passes.
   */
  @ConfigProperty(name = "qits.projects.agent.stale-activity-ttl-ms", defaultValue = "14400000")
  long staleActivityTtlMs;

  /** One session's last reported state and when it said so. */
  private record ActivityEntry(String state, long atMillis) {}

  /**
   * Why each project's last {@link ProvisionFailed} said its {@code /workspace} is not there.
   *
   * <p>A sibling map next to {@link #lastActivity} rather than a field on the connection, and that
   * is the whole point: a daemon that cannot provision usually drops its socket soon after saying
   * so, and {@link #lookup} answers empty from that moment. A failure recorded on the connection
   * would disappear exactly when somebody came to read it.
   *
   * <p>Cleared on a {@link Provisioned}, on {@link #register} (a reconnecting daemon re-provisions
   * and its next word on the subject is the current one) and in {@link #forget} (the container is
   * going away with the volume the failure was about).
   */
  private final ConcurrentHashMap<String, String> provisionFailures = new ConcurrentHashMap<>();

  /** What a connected daemon announced about itself, for the lifecycle read and the UI. */
  public record DaemonInfo(
      Instant connectedAt,
      String repoName,
      String daemonVersion,
      Instant daemonBuildTime,
      int capabilityVersion) {}

  /** Register a freshly-connected client, replacing any stale entry for the same project. */
  public void register(String projectId, WebSocketConnection connection) {
    clients.put(projectId, new DaemonConnection(connection));
    touch(projectId);
    provisionFailures.remove(projectId);
    LOG.debugf(
        "projects-daemon connected for project %s (connection %s)", projectId, connection.id());
  }

  /**
   * Drop the client for {@code projectId}, but only if it is still the given connection — a
   * reconnect that registered a newer socket must not be evicted by the old one's late close.
   */
  public void unregister(String projectId, WebSocketConnection connection) {
    clients.computeIfPresent(
        projectId,
        (id, existing) -> existing.connection.id().equals(connection.id()) ? null : existing);
    // Pending tunnel nonces are waiting on a daemon that is no longer there; live tunnels are NOT
    // torn down, deliberately — each stream is its own TCP connection, so an open terminal survives
    // a control-socket reconnect, which is the whole reason those calls do not ride this socket.
    if (tunnels.isResolvable()) {
      tunnels.get().onDaemonGone(projectId);
    }
    LOG.debugf(
        "projects-daemon disconnected for project %s (connection %s)", projectId, connection.id());
  }

  /** Whether a project's daemon currently holds an open control socket. */
  public boolean isDaemonLive(String projectId) {
    DaemonConnection client = clients.get(projectId);
    return client != null && client.connection.isOpen();
  }

  /** What the project's connected daemon announced, or empty when none is connected. */
  public Optional<DaemonInfo> lookup(String projectId) {
    DaemonConnection client = clients.get(projectId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty();
    }
    return Optional.of(
        new DaemonInfo(
            client.connectedAt,
            client.repoName,
            client.daemonVersion,
            client.daemonBuildTime,
            client.capabilityVersion));
  }

  /** When this project's agent was last heard from, or empty when it never has been. */
  public Optional<Instant> lastActivityAt(String projectId) {
    return Optional.ofNullable(lastActivity.get(projectId));
  }

  /**
   * When anything last happened in this project's container, or empty when nothing ever has — the
   * heartbeat-free stamp, read by {@link AgentStaleImageSweep}. See {@link #lastAgentActivity} for
   * why it is not {@link #lastActivityAt}.
   *
   * <p>Empty is a real and useful answer here rather than a missing one: a container nothing has ever
   * happened in is the quietest a container gets, and the sweep reads it that way.
   */
  public Optional<Instant> lastAgentActivityAt(String projectId) {
    return Optional.ofNullable(lastAgentActivity.get(projectId));
  }

  /**
   * This project's rolled-up agent state — the busiest of its live sessions — or empty when no
   * session has reported one.
   *
   * <p>Entries are aged out on read rather than on a timer, exactly as the refinement registry does
   * it: the rollup has no reader but this one, so a pass over it costs nothing between reads and there
   * is no second thread to reason about.
   *
   * <p><b>Two horizons, and an entry goes when EITHER has passed.</b> An {@code ENDED} entry older
   * than {@link #endedActivityTtlMs} (half an hour) stops describing the container's most recent
   * state; an entry in <em>any</em> state older than {@link #staleActivityTtlMs} (four hours) is a
   * claim nobody has refreshed and is dropped whatever it claims. The second field carries the whole
   * argument for why these are two numbers and not one, and why collapsing them is a regression in
   * both directions.
   *
   * <p><b>{@code RefinementDaemonRegistry} keeps the {@code ENDED}-only prune, and that is
   * deliberate.</b> On its axis the same code is not a defect: a refinement container is discarded
   * when its epic resolves, so a stale entry dies with the container. A project agent container has no
   * such turnover, which is what turns the identical prune into this bug here. Do not "fix" both for
   * symmetry — if the refinement axis ever grows long-lived containers, it is one file over.
   */
  public Optional<String> agentActivity(String projectId) {
    Map<String, ActivityEntry> sessions = agentActivity.get(projectId);
    if (sessions == null || sessions.isEmpty()) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    sessions
        .entrySet()
        .removeIf(
            entry ->
                (DaemonProtocol.AgentState.ENDED.equals(entry.getValue().state())
                        && now - entry.getValue().atMillis() > endedActivityTtlMs)
                    || now - entry.getValue().atMillis() > staleActivityTtlMs);
    return sessions.values().stream()
        .map(ActivityEntry::state)
        .max(Comparator.comparingInt(AgentDaemonRegistry::activityRank));
  }

  /**
   * The ranking the rollup folds with, and it is {@code RefinementDaemonRegistry}'s verbatim: a
   * container with one busy session and four idle ones is busy. An unknown state — a newer daemon's
   * word this host has no view for — ranks below every known one rather than being dropped, so it can
   * never outrank a {@code BUSY} it does not understand.
   */
  private static int activityRank(String state) {
    return switch (state) {
      case DaemonProtocol.AgentState.BUSY -> 4;
      case DaemonProtocol.AgentState.WAITING -> 3;
      case DaemonProtocol.AgentState.IDLE -> 2;
      case DaemonProtocol.AgentState.ENDED -> 1;
      default -> 0;
    };
  }

  /** Record activity now — called on every inbound frame and when the host starts a container. */
  public void touch(String projectId) {
    touch(projectId, Instant.now());
  }

  /** {@link #touch(String)} at a given instant, so a sweep test can drive a fake clock. */
  public void touch(String projectId, Instant at) {
    lastActivity.put(projectId, at);
  }

  /**
   * Record that something happened in this project's container at {@code at} — the {@link
   * #lastAgentActivity} stamp's only writer besides {@link #onMessage}, and there so a sweep test can
   * drive a fake clock the same way {@link #touch(String, Instant)} lets one drive the other.
   */
  public void touchAgentActivity(String projectId, Instant at) {
    lastAgentActivity.put(projectId, at);
  }

  /**
   * Record {@code at} only if this project has no stamp yet, and answer the stamp in force. The
   * sweep's entry point for a container it has found on the host but never heard from — one that
   * predates this process, or whose daemon has never connected. Without it such a container would
   * be either immortal (no stamp, never idle) or reaped on sight.
   */
  public Instant touchIfAbsent(String projectId, Instant at) {
    return lastActivity.computeIfAbsent(projectId, id -> at);
  }

  /**
   * Why this project's {@code /workspace} is not provisioned, or empty when nothing said so.
   *
   * <p>Read by {@link AgentContainers} on every lifecycle answer: a container whose self-clone
   * failed is running and useless, and reporting it {@code RUNNING} sends a browser to open a
   * terminal on an empty checkout.
   */
  public Optional<String> provisionFailure(String projectId) {
    return Optional.ofNullable(provisionFailures.get(projectId));
  }

  /**
   * Forget a project's activity stamps, its agent rollup and its last provision failure — its
   * container is stopped.
   *
   * <p>Both stamps and the rollup go together on purpose: they describe one container, and a restart
   * has to start every window afresh rather than inherit a stale one from the container before it.
   */
  public void forget(String projectId) {
    lastActivity.remove(projectId);
    lastAgentActivity.remove(projectId);
    agentActivity.remove(projectId);
    provisionFailures.remove(projectId);
  }

  /**
   * Ask a daemon to dial back and serve one stream — the reverse tunnel's only outbound message.
   *
   * <p>Sent <b>without awaiting</b>, unlike an ordinary send: this is called from a {@code
   * NetServer} connect handler, which runs on an event loop, and the blocking form would be
   * rejected by Mutiny's blocking guard there. A failure is logged and nothing else — the parked
   * socket's own TTL closes it, so a lost {@code OpenStream} degrades to a request that fails
   * rather than one that hangs.
   */
  void requestStream(String projectId, String nonce, String path) {
    DaemonConnection client = clients.get(projectId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("requestStream: no projects-daemon live for %s", projectId);
      return;
    }
    client
        .connection
        .sendText(codec.encode(new OpenStream(nonce, path)))
        .subscribe()
        .with(
            ignored -> {},
            failure ->
                LOG.debugf("could not ask project %s for a stream: %s", projectId, failure));
  }

  /** Handle a decoded frame from {@code qits-projects-daemon} for {@code projectId}. */
  public void onMessage(String projectId, WebSocketConnection connection, DaemonMessage message) {
    touch(projectId);
    // The second stamp, and the one frame pair that does NOT write it. A heartbeat and an Ack are
    // what a daemon says while nothing is going on, so counting them here would reproduce exactly the
    // blindness lastActivity has by design — see the field.
    if (!(message instanceof Heartbeat) && !(message instanceof Ack)) {
      lastAgentActivity.put(projectId, Instant.now());
    }
    DaemonConnection client = clients.get(projectId);
    switch (message) {
      case Hello hello -> {
        LOG.infof(
            "projects-daemon HELLO for project %s (repo %s, capability %d, daemon %s built %s)",
            hello.projectId(),
            hello.repoName(),
            hello.capabilityVersion(),
            hello.daemonVersion(),
            hello.daemonBuildTime());
        if (client != null) {
          client.repoName = hello.repoName();
          client.daemonVersion = hello.daemonVersion();
          client.daemonBuildTime = parseInstant(hello.daemonBuildTime());
          client.capabilityVersion = hello.capabilityVersion();
        }
        connection.sendTextAndAwait(codec.encode(new Ack()));
        // A container has started and its daemon is reachable — the one moment both are true, and
        // the only one from which the harness capability report can be read at all (the daemon's
        // API binds loopback). It returns at once and runs off this event loop; see the relay.
        // Null when this registry was built by hand rather than by the container — the unit suite
        // does exactly that, and a hello it drives must not die on an optional collaborator.
        if (capabilityRelay != null && capabilityRelay.isResolvable()) {
          capabilityRelay.get().onDaemonHello(projectId);
        }
      }
      case Heartbeat ignored -> {
        /* liveness only — the touch above is the whole handling */
      }
      case DaemonLog log ->
          LOG.infof("[projects-daemon %s] %s: %s", projectId, log.level(), log.message());
      case AgentActivity activity -> onAgentActivity(projectId, activity);
      case ProjectChanged changed -> onProjectChanged(projectId, changed);
      case Provisioned provisioned -> {
        LOG.infof("projects-daemon provisioned project %s at %s", projectId, provisioned.head());
        provisionFailures.remove(projectId);
      }
      case ProvisionFailed failed -> {
        LOG.warnf(
            "projects-daemon could not provision project %s: %s", projectId, failed.message());
        // Recorded, not just logged: the container stays up and would otherwise read RUNNING while
        // its /workspace is empty. A null or blank message still counts as a failure — the state is
        // what matters and a missing reason must not read as "provisioned".
        provisionFailures.put(
            projectId,
            failed.message() == null || failed.message().isBlank()
                ? "The daemon reported a failed provision with no reason."
                : failed.message());
      }
      // Replies to frames this host never sends, and qits -> daemon requests echoed back. Both are
      // dropped rather than treated as errors: a daemon must not be able to break its own control
      // socket by saying something this backend has no view for.
      case CommandChunk ignored -> {}
      case CommandExit ignored -> {}
      case ProjectInfo ignored -> {}
      case Ack ignored -> {}
      case RunCommand ignored -> {}
      case Describe ignored -> {}
      case OpenStream ignored -> {}
    }
  }

  /**
   * A coding agent's lifecycle state changed in the container. The browser still reads the agent
   * surface through the proxy, so the hint that says "re-read it" is unchanged and is fired for every
   * report, whatever the frame carries.
   *
   * <p><b>What is new is the rollup</b>, kept so {@link AgentStaleImageSweep} can ask whether
   * anything is running in this container <em>right now</em>. The stamp one method up cannot answer
   * that: an agent thinking between two frames leaves a stamp that keeps ageing while it works, and a
   * container stopped in the middle of that loses the turn.
   *
   * <p><b>A frame with no session id is keyed on its {@code commandId} instead</b>, rather than being
   * skipped. The two identify the same thing from opposite ends — a command is what a session is
   * running — so a daemon that reports one and not the other still contributes one entry rather than
   * none, and the fold is right either way. Only a frame with <em>neither</em>, or with no state at
   * all, is dropped: there is nothing to key it on and nothing to rank.
   *
   * <p>The frame's own {@code at} is preferred over this host's clock, so a report that queued behind
   * a slow socket is aged from when it happened; a daemon that sends none falls back to now.
   */
  private void onAgentActivity(String projectId, AgentActivity activity) {
    LOG.debugf(
        "projects-daemon agent activity for project %s: command %s is %s",
        projectId, activity.commandId(), activity.state());
    String key =
        activity.sessionId() != null && !activity.sessionId().isBlank()
            ? activity.sessionId()
            : activity.commandId();
    if (key != null && !key.isBlank() && activity.state() != null) {
      long at = activity.at() > 0 ? activity.at() : System.currentTimeMillis();
      agentActivity
          .computeIfAbsent(projectId, id -> new ConcurrentHashMap<>())
          .put(key, new ActivityEntry(activity.state(), at));
    }
    changePublisher.fire(projectId, ProjectChangeHint.Topic.AGENT_ACTIVITY);
  }

  /**
   * Relay a daemon-side change nudge onto the project's SSE stream.
   *
   * <p>The daemon owns state the host does not hold — the commands it ran, the transcripts it
   * imported — so it is the only thing that knows when a view went stale. The frame carries a topic
   * name rather than an enum precisely so a newer daemon can nudge about something this backend has
   * no view for, and that must cost nothing.
   *
   * <p><b>{@code COMMANDS} maps to {@code AGENT_ACTIVITY}</b>, and that is a translation rather than
   * a rename: the daemon's commands list <em>is</em> what the refinement panel renders, so the hint
   * that re-fetches that panel is the one the browser needs. A topic that names a {@link
   * ProjectChangeHint.Topic} outright is passed through, so a future daemon nudging {@code EPICS}
   * needs no change here. Anything else is dropped with a debug line.
   */
  private void onProjectChanged(String projectId, ProjectChanged changed) {
    ProjectChangeHint.Topic topic = topicOf(changed.topic());
    if (topic == null) {
      LOG.debugf(
          "Ignoring a projects-daemon change nudge for unknown topic '%s' (project %s)",
          changed.topic(), projectId);
      return;
    }
    changePublisher.fire(projectId, topic);
  }

  private static ProjectChangeHint.Topic topicOf(String wireTopic) {
    if (wireTopic == null) {
      return null;
    }
    if ("COMMANDS".equals(wireTopic)) {
      return ProjectChangeHint.Topic.AGENT_ACTIVITY;
    }
    try {
      return ProjectChangeHint.Topic.valueOf(wireTopic);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  /**
   * Parse the daemon's ISO-8601 build-time string, tolerating {@code null} (an older image or an
   * unfiltered dev jar) and a malformed value — either yields {@code null}, surfaced as "unknown
   * build time". A registration must never fail over a cosmetic field.
   */
  private static Instant parseInstant(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (java.time.format.DateTimeParseException e) {
      LOG.debugf("projects-daemon reported an unparseable build time '%s': %s", iso, e.getMessage());
      return null;
    }
  }

  /** One live client: its connection and what its {@link Hello} announced. */
  private static final class DaemonConnection {
    private final WebSocketConnection connection;

    /** When this control socket registered — also what a tunnel is keyed on across reconnects. */
    private final Instant connectedAt = Instant.now();

    private volatile String repoName;
    private volatile String daemonVersion;
    private volatile Instant daemonBuildTime;

    /**
     * The wire-contract version announced in {@link Hello}; 0 until one arrives. Recorded because
     * the reverse tunnel branches on it, and "has not said yet" has to read as "not capable".
     */
    private volatile int capabilityVersion;

    DaemonConnection(WebSocketConnection connection) {
      this.connection = connection;
    }
  }
}
