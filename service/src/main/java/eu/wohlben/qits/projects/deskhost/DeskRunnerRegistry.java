package eu.wohlben.qits.projects.deskhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.projects.control.DeskRunners;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.entity.DeskRunnerCapabilities;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthChecked;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginState;
import eu.wohlben.qits.projectsdeskrunner.protocol.ProbeLogin;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Every front-desk runner holding a socket to this process (qits-767): one {@link Session} per
 * connection and what each was told. {@link DeskRunnerSocket} owns the WebSocket lifecycle and
 * forwards frames here. A copy of qits-workspaces-service's {@code WorkspaceRunnerRegistry}, in the
 * desk spelling, without the workspace rows: the desks themselves — {@code estate}, {@code take},
 * {@code remove}, the inventory — are {@link DeskRunnerWork}'s ({@link FrontDeskWork}). What
 * lands here greets, upgrades and quarantines, and answers {@code nothing} to a {@code reserve} the
 * runner may not make.
 *
 * <p><b>It is the {@link DeskRunnerSessions} the runner doors read</b>: connected, connected-since,
 * the pin, the login command,
 * and the frames a door sends (a health check, {@code probeLogin}, {@code reinstated}, a re-sent
 * {@code ack}, a deleted runner's {@code retire}).
 *
 * <p><b>A session is one connection, and a runner has at most one per version.</b> A second
 * connection that says {@code hello} in the <em>same</em> version replaces the first, which is
 * closed 1008 {@link #ALREADY_CONNECTED}: the likeliest second dial is the same runner coming back
 * before this host noticed its old socket was dead.
 *
 * <p><b>Two versions side by side are a self-update.</b> A runner whose {@code hello} is not the
 * pinned version ({@link DeskRunnerPins}) is sent {@code Upgrade(pin,
 * registry.qits.<d>/qits/qits-projects-desk-runner:<pin>, null)} and {@code ack{slots: 0}}: that
 * connection is <b>draining</b> and takes nothing. Its successor dials as a second connection; once
 * one in the pinned version has said {@code hello}, every other connection of the runner is sent
 * {@link Retire}.
 *
 * <p><b>What a greeted connection is told, in order</b>: {@code ack} with the row's slots — 0 while
 * the runner is quarantined, followed by {@code quarantined} — then whatever the front desk sends
 * ({@link DeskRunnerWork#greeted}), then {@code backlog}; and {@code healthCheck} when the runner is
 * still awaiting its first one or has just come back ({@link DeskRunnerHealth}, which owns every
 * check).
 *
 * <p><b>{@code backlog} is what makes a runner reserve</b> (qits-1110): the runner toolkit's slot
 * ledger sends {@code reserve} only while a slot is free and the last {@code backlog} was above
 * zero, and a {@code nothing} parks it until the next {@code backlog}. It is sent to a session that
 * may take work — greeted at the pin, not draining, its runner in service — and to no other: after
 * the greeting's estate, after every re-sent {@code ack}, whenever a desk joins or leaves the queue
 * ({@link #backlogChanged}), and with every periodic estate push.
 *
 * <p><b>Presence.</b> "Connected" means a live session. A runner that lost its last socket is
 * remembered as disconnected since that moment ({@link #disconnectedSince}), and still counts as
 * present for {@code qits.projects.desk-runner.reconnect-grace} ({@link #presence}) — what the
 * front desk's {@code UNAVAILABLE} is computed from. A runner whose first socket comes after the
 * grace has <b>come back</b>, and is quarantined until a health check passes.
 *
 * <p><b>{@code last_seen_at} is stamped on every inbound frame</b>, at most once per {@link
 * #SEEN_INTERVAL} per connection — the column is read at a minute's grain, and a frame that also
 * writes the row ({@code hello}, {@code loginState}) stamps it itself.
 *
 * <p><b>Nothing here waits without a deadline</b>: a frame is sent bounded at {@link
 * #SEND_TIMEOUT}, a close at {@link #CLOSE_TIMEOUT}.
 */
@ApplicationScoped
public class DeskRunnerRegistry implements DeskRunnerSessions {

  private static final Logger LOG = Logger.getLogger(DeskRunnerRegistry.class);

  /** The close code every refusal and every replaced session gets: 1008, "policy violation". */
  public static final int CLOSE_POLICY = RunnerWire.CLOSE_POLICY_VIOLATION;

  /** The close reason a session replaced by a newer dial of the same runner is given. */
  public static final String ALREADY_CONNECTED = "ALREADY_CONNECTED";

  /** How long one frame may take to leave. */
  static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

  /** How long a close may take before the host stops caring. */
  static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  /** How often a frame may reach the row: {@code last_seen_at} is read at a minute's grain. */
  static final Duration SEEN_INTERVAL = Duration.ofMinutes(1);

  @Inject DeskRunnerMessageCodec codec;

  @Inject DeskRunners runners;

  @Inject DeskRunnerPins pins;

  @Inject DeskRunnerAddresses addresses;

  @Inject DeskRunnerHealth health;

  @Inject DeskRunnerWork work;

  @Inject DeskRunnerLoginCommand loginCommands;

  @Inject ObjectMapper objectMapper;

  /** How long a dropped runner still counts as present. */
  @ConfigProperty(name = "qits.projects.desk-runner.reconnect-grace")
  Duration configuredGrace;

  /** Every open session of each runner, oldest first; mutated only inside {@code compute}. */
  private final ConcurrentHashMap<UUID, List<Session>> sessions = new ConcurrentHashMap<>();

  /** Admission order, so "the newest session" does not depend on a clock's resolution. */
  private final AtomicLong admitted = new AtomicLong();

  /** Since when each connected runner has held at least one socket without a break. */
  private final ConcurrentHashMap<UUID, Instant> connectedSince = new ConcurrentHashMap<>();

  /** When each runner lost its last socket; the grace runs from here. */
  private final ConcurrentHashMap<UUID, Instant> droppedAt = new ConcurrentHashMap<>();

  /** When each runner's newest {@code loginState} arrived: what gates its login command. */
  private final ConcurrentHashMap<UUID, Instant> loginReportedAt = new ConcurrentHashMap<>();

  private volatile Duration seenInterval = SEEN_INTERVAL;

  private volatile Duration reconnectGrace;

  /** A suite proving a frame reaches the row cannot wait a minute. */
  void seenInterval(Duration interval) {
    this.seenInterval = interval;
  }

  /** A suite proving the grace runs out cannot wait two minutes either; null restores the key. */
  void reconnectGrace(Duration grace) {
    this.reconnectGrace = grace;
  }

  private Duration grace() {
    Duration override = reconnectGrace;
    return override != null ? override : configuredGrace;
  }

  /** One runner's connection. */
  public static final class Session {

    private final UUID runnerId;
    private final String runnerName;
    private final WebSocketConnection connection;
    private final long order;
    private volatile boolean closed;
    private volatile boolean greeted;
    private volatile boolean draining;
    private volatile String runnerVersion;
    private volatile Instant seenWrittenAt;

    Session(DeskRunner runner, WebSocketConnection connection, long order) {
      this.runnerId = runner.id;
      this.runnerName = runner.name;
      this.connection = connection;
      this.order = order;
    }

    public UUID runnerId() {
      return runnerId;
    }

    public String runnerName() {
      return runnerName;
    }

    /** Whether its {@code hello} was answered with slots: the pinned version, not draining. */
    public boolean greeted() {
      return greeted;
    }

    /** Whether it was told to {@link Upgrade}: it takes nothing more. */
    public boolean draining() {
      return draining;
    }

    /** What its {@code hello} said it is; null before one. */
    public String runnerVersion() {
      return runnerVersion;
    }

    public boolean isOpen() {
      return !closed;
    }
  }

  /** How a {@code hello} was received. */
  public enum Greeting {
    GREETED,
    /** The pinned version speaking another capability: nothing to update it to; closed 1008. */
    VERSION_MISMATCH,
    /** The row went away between the dial and the {@code hello}. */
    RUNNER_GONE
  }

  // --- the socket side --------------------------------------------------------------------------

  /**
   * Bind a connection to its runner, beside any session it already has — which one stays is decided
   * at its {@code hello}, where its version is known. The row was resolved from the bearer by the
   * caller.
   */
  public Session admit(DeskRunner runner, WebSocketConnection connection) {
    Session fresh = new Session(runner, connection, admitted.incrementAndGet());
    boolean[] first = {false};
    sessions.compute(
        runner.id,
        (id, present) -> {
          List<Session> next = new ArrayList<>();
          if (present != null) {
            present.stream().filter(Session::isOpen).forEach(next::add);
          }
          first[0] = next.isEmpty();
          next.add(fresh);
          return List.copyOf(next);
        });
    if (first[0]) {
      Instant now = Instant.now();
      connectedSince.put(runner.id, now);
      Instant dropped = droppedAt.remove(runner.id);
      if (dropped != null && !dropped.plus(grace()).isAfter(now)) {
        // Away longer than the grace: a comeback, which is proved healthy before it takes work.
        health.cameBack(runner.id, runner.name, Duration.between(dropped, now));
      }
    }
    LOG.infof("Runner %s (%s) connected (connection %s)", runner.name, runner.id, connection.id());
    return fresh;
  }

  /**
   * The runner said who it is: the version against the pin, then the capability, then what it said
   * is recorded, the same-version rule settled, and it is answered — {@link Upgrade} and {@code
   * ack{0}} for a runner not at the pin, else {@code ack}, {@code quarantined} if it is, the front
   * desk's greeting and, while it awaits its first one or has just come back, {@code healthCheck}.
   * Every other version's connection of the runner is retired after.
   *
   * <p>The slots are the row's, never the runner's ({@link Hello#slots()} is advisory), and 0 while
   * it is quarantined.
   */
  public Greeting onHello(Session session, Hello hello) {
    String pin = pins.version();
    boolean current = pin.equals(hello.runnerVersion());
    boolean speaks = hello.capabilityVersion() == DeskRunnerProtocol.CAPABILITY_VERSION;
    if (current && !speaks) {
      LOG.warnf(
          "Runner %s announced capability version %d and this host speaks %d — refusing it",
          session.runnerName, hello.capabilityVersion(), DeskRunnerProtocol.CAPABILITY_VERSION);
      return Greeting.VERSION_MISMATCH;
    }
    DeskRunner row = runners.recordCapabilities(session.runnerId, said(hello, speaks));
    if (row == null) {
      return Greeting.RUNNER_GONE;
    }
    session.seenWrittenAt = Instant.now();
    session.runnerVersion = hello.runnerVersion();
    session.draining = !current;
    List<Session> retiring = settle(session);
    if (!current) {
      String image;
      try {
        image = addresses.runnerImage(pin);
      } catch (DomainException unconfigured) {
        LOG.errorf(
            "Runner %s said hello as %s and the pin is %s, and it cannot be upgraded: %s",
            row.name, hello.runnerVersion(), pin, unconfigured.getMessage());
        if (speaks) {
          send(session, ack(0, List.of()));
        }
        return Greeting.GREETED;
      }
      LOG.infof(
          "Runner %s said hello as %s and the pin is %s — upgrading it to %s; this connection"
              + " takes nothing more",
          row.name, hello.runnerVersion(), pin, image);
      send(session, new Upgrade(pin, image, null));
      if (speaks) {
        send(session, ack(0, List.of()));
      }
      return Greeting.GREETED;
    }
    int slots = slots(row);
    LOG.infof(
        "Runner %s said hello: %s, %d slot(s)%s",
        row.name,
        hello.runnerVersion(),
        slots,
        row.quarantined() ? " — quarantined: " + row.quarantineReason : "");
    send(session, ack(slots, adopted(session, hello.held())));
    if (row.quarantined()) {
      send(session, quarantinedFrame(row.quarantineReason, row.quarantinedAt));
    }
    session.greeted = true;
    greetedByWork(session);
    sendBacklog(session);
    health.onGreeted(row);
    catchUp(session, row, slots);
    for (Session old : retiring) {
      LOG.infof(
          "Runner %s's %s connection %s is superseded by %s; retiring it",
          row.name, old.runnerVersion, old.connection.id(), hello.runnerVersion());
      send(old, new Retire("superseded by " + hello.runnerVersion()));
    }
    return Greeting.GREETED;
  }

  /** What a {@code hello} says about the machine, as the capabilities column keeps it. */
  private ObjectNode said(Hello hello, boolean speaks) {
    ObjectNode said = objectMapper.createObjectNode();
    said.put(DeskRunnerCapabilities.VERSION, hello.runnerVersion());
    RunnerCapabilities machine = speaks ? hello.capabilities() : null;
    if (machine != null) {
      said.put(RunnerWire.Field.DOCKER, machine.docker());
      said.put(RunnerWire.Field.ARCH, machine.arch());
      said.put(RunnerWire.Field.OS, machine.os());
    }
    return said;
  }

  /** The front desk's word on which held desks stay; null when it could not say. */
  private List<String> adopted(Session session, List<String> held) {
    if (held == null || held.isEmpty()) {
      return List.of();
    }
    try {
      return work.adopted(session, held);
    } catch (RuntimeException e) {
      LOG.debugf("Could not decide runner %s's held desks: %s", session.runnerName, e.getMessage());
      return null;
    }
  }

  private void greetedByWork(Session session) {
    try {
      work.greeted(session);
    } catch (RuntimeException e) {
      LOG.warnf("The front desk's greeting of runner %s failed: %s", session.runnerName, e.getMessage());
    }
  }

  /**
   * The same-version rule, once a session has said which version it is: every other open session
   * of the runner in the same version is replaced, closed {@link #ALREADY_CONNECTED}. Returned are
   * the sessions of other versions when this one is at the pin — the ones to {@link Retire}.
   */
  private List<Session> settle(Session session) {
    List<Session> replaced = new ArrayList<>();
    List<Session> retiring = new ArrayList<>();
    sessions.computeIfPresent(
        session.runnerId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>();
          for (Session other : present) {
            if (other == session || other.runnerVersion == null) {
              kept.add(other);
            } else if (other.runnerVersion.equals(session.runnerVersion)) {
              replaced.add(other);
            } else {
              kept.add(other);
              if (!session.draining) {
                retiring.add(other);
              }
            }
          }
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    for (Session previous : replaced) {
      LOG.warnf(
          "Runner %s said hello again as %s on connection %s; closing its previous connection %s %s",
          session.runnerName,
          session.runnerVersion,
          session.connection.id(),
          previous.connection.id(),
          ALREADY_CONNECTED);
      previous.closed = true;
      closeBounded(
          previous.connection,
          new CloseReason(CLOSE_POLICY, ALREADY_CONNECTED),
          "the replaced connection of runner " + session.runnerName);
    }
    return retiring;
  }

  /**
   * Any inbound frame: the runner was heard from. The row hears about it at most once a {@link
   * #SEEN_INTERVAL} per connection.
   */
  public void onFrame(Session session) {
    Instant now = Instant.now();
    Instant written = session.seenWrittenAt;
    if (written != null && written.plus(seenInterval).isAfter(now)) {
      return;
    }
    session.seenWrittenAt = now;
    try {
      runners.touchSeen(session.runnerId);
    } catch (RuntimeException e) {
      LOG.debugf("Could not stamp runner %s as seen: %s", session.runnerName, e.getMessage());
    }
  }

  /**
   * A {@code reserve}: {@code nothing} unless the session may take work now — greeted at the pin,
   * not draining, and its runner in service (a quarantined runner is sent no {@code take}) — and
   * otherwise whatever the front desk answers ({@link DeskRunnerWork#reserve}).
   */
  public void onReserve(Session session) {
    RunnerMessage answer = reserveAnswer(session);
    send(session, answer);
    if (!(answer instanceof Nothing)) {
      // A desk left the queue: every runner in service is told the count it left behind.
      backlogChanged();
    }
  }

  private RunnerMessage reserveAnswer(Session session) {
    if (!session.greeted || session.draining || !session.isOpen()) {
      return new Nothing();
    }
    DeskRunner row;
    try {
      row = runners.get(session.runnerId);
    } catch (RuntimeException gone) {
      return new Nothing();
    }
    if (row.quarantined()) {
      return new Nothing();
    }
    try {
      RunnerMessage answer = work.reserve(session);
      return answer == null ? new Nothing() : answer;
    } catch (RuntimeException e) {
      LOG.warnf("The front desk could not answer runner %s's reserve: %s", session.runnerName, e.getMessage());
      return new Nothing();
    }
  }

  /**
   * The node's agent login, as last probed: kept on the row while the runner is offline. The row is
   * written <em>before</em> the report is stamped in memory, so a login command shown on the strength
   * of a report never sits beside a row still holding the previous state.
   */
  public void onLoginState(Session session, LoginState login) {
    Instant arrived = Instant.now();
    session.seenWrittenAt = arrived;
    try {
      runners.recordLoginState(session.runnerId, login.state().name());
    } catch (RuntimeException e) {
      LOG.warnf("Runner %s's login state was not recorded: %s", session.runnerName, e.getMessage());
    }
    loginReportedAt.put(session.runnerId, arrived);
  }

  /** A health check settled: {@link DeskRunnerHealth} records it and acts on it. */
  public void onHealthChecked(Session session, HealthChecked checked) {
    health.onHealthChecked(session.runnerId, checked);
  }

  /** A desk frame: the front desk's ({@link DeskRunnerWork#onFrame}). */
  public void onDeskFrame(Session session, DeskRunnerMessage frame) {
    try {
      work.onFrame(session, frame);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Runner %s's %s was not handled: %s",
          session.runnerName, frame.getClass().getSimpleName(), e.getMessage());
    }
  }

  /**
   * A connection went away. Only its own session is dropped — a replaced connection closing late
   * must not take the one that replaced it. When it was the runner's last, it is disconnected from
   * now, and present for the reconnect grace.
   */
  public void onClose(Session session) {
    boolean[] last = {false};
    sessions.computeIfPresent(
        session.runnerId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>(present);
          kept.remove(session);
          kept.removeIf(other -> !other.isOpen());
          last[0] = kept.isEmpty();
          if (last[0]) {
            // Inside the runner's compute, before the session reads closed: whoever sees it
            // disconnected sees since when, and an admit of the same runner is ordered after.
            connectedSince.remove(id);
            droppedAt.put(id, Instant.now());
          }
          session.closed = true;
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    session.closed = true;
    if (last[0]) {
      LOG.infof(
          "Runner %s (%s) disconnected (connection %s); it is waited for %ss",
          session.runnerName, session.runnerId, session.connection.id(), grace().toSeconds());
    }
  }

  // --- presence -----------------------------------------------------------------------------------

  /** Whether the runner holds at least one open socket right now. */
  public boolean isConnected(UUID runnerId) {
    return runnerId != null && !open(runnerId).isEmpty();
  }

  @Override
  public Boolean connected(UUID runnerId) {
    return isConnected(runnerId);
  }

  @Override
  public Instant connectedSince(UUID runnerId) {
    return runnerId == null || !isConnected(runnerId) ? null : connectedSince.get(runnerId);
  }

  /**
   * Since when the runner has held no socket to this process; null while it holds one, and null for
   * a runner this process has never seen drop (one it has not seen at all since it started, too).
   */
  public Instant disconnectedSince(UUID runnerId) {
    return runnerId == null || isConnected(runnerId) ? null : droppedAt.get(runnerId);
  }

  /** Connected, or dropped less than the reconnect grace ago. */
  public boolean presence(UUID runnerId) {
    if (isConnected(runnerId)) {
      return true;
    }
    Instant dropped = runnerId == null ? null : droppedAt.get(runnerId);
    return dropped != null && dropped.plus(grace()).isAfter(Instant.now());
  }

  /** The reconnect grace in force: how long {@link #presence} outlasts the last socket. */
  public Duration reconnectGrace() {
    return grace();
  }

  /** Every runner with at least one open connection, in memory and with no row read. */
  public Set<UUID> connectedRunnerIds() {
    Set<UUID> connected = new HashSet<>();
    for (UUID runnerId : List.copyOf(sessions.keySet())) {
      if (isConnected(runnerId)) {
        connected.add(runnerId);
      }
    }
    return Set.copyOf(connected);
  }

  /**
   * The runner's current session: greeted in the pinned version when there is one, else its newest
   * open session, else null.
   */
  public Session current(UUID runnerId) {
    List<Session> open = open(runnerId);
    return open.stream()
        .filter(s -> s.greeted && !s.draining)
        .findFirst()
        .orElseGet(() -> open.isEmpty() ? null : open.get(open.size() - 1));
  }

  List<Session> open(UUID runnerId) {
    List<Session> present = runnerId == null ? null : sessions.get(runnerId);
    if (present == null) {
      return List.of();
    }
    return present.stream()
        .filter(Session::isOpen)
        .sorted(Comparator.comparingLong(s -> s.order))
        .toList();
  }

  /** The session a runner's slots are granted on — greeted, pinned, open — or null. */
  public Session serving(UUID runnerId) {
    Session session = current(runnerId);
    return session != null && session.greeted && !session.draining && session.isOpen()
        ? session
        : null;
  }

  // --- DeskRunnerSessions: what the runner doors read and send ------------------------------------

  @Override
  public String pinnedVersion() {
    return pins.version();
  }

  /**
   * The login command for {@code runner}'s node ({@link DeskRunnerLoginCommand}), shown only once a
   * {@code loginState} has arrived since the runner's current connection began: the runner's own
   * probe runs the project-agent image, so its answer is the proof the image is on the node.
   */
  @Override
  public String loginCommand(DeskRunner runner) {
    if (runner == null) {
      return null;
    }
    Instant since = connectedSince(runner.id);
    Instant reported = loginReportedAt.get(runner.id);
    boolean proven = since != null && reported != null && !reported.isBefore(since);
    return proven ? loginCommands.claude(runner.id) : null;
  }

  @Override
  public Optional<String> requestHealthCheck(UUID runnerId) {
    return Optional.ofNullable(health.request(runnerId));
  }

  @Override
  public boolean probeLogin(UUID runnerId) {
    Session session = serving(runnerId);
    return session != null && send(session, new ProbeLogin());
  }

  /** The quarantine was lifted: {@code reinstated}, then {@code ack} with the row's slots again. */
  @Override
  public void reinstated(UUID runnerId, String by) {
    Session session = serving(runnerId);
    if (session != null) {
      send(session, new Reinstated(by));
      reAck(session);
    }
  }

  /** The runner was quarantined: {@code quarantined}, then {@code ack{0}}. */
  public void quarantined(UUID runnerId, String reason, Instant since) {
    Session session = serving(runnerId);
    if (session != null) {
      send(session, quarantinedFrame(reason, since));
      reAck(session);
    }
  }

  /** What it may hold moved (an operator's slots), so its {@code ack} is sent again. */
  @Override
  public void slotsChanged(UUID runnerId) {
    Session session = serving(runnerId);
    if (session != null) {
      reAck(session);
    }
  }

  /**
   * The runner's row was deleted: every open connection of it is sent {@code retire{DELETED}}, on
   * which the runner removes its own namespace from its node, and is closed 1008 {@code
   * RUNNER_DELETED} — the frame first, so it is read first.
   */
  @Override
  public void deleted(UUID runnerId) {
    for (Session session : open(runnerId)) {
      LOG.infof(
          "Runner %s (%s) was deleted; retiring its connection %s for good",
          session.runnerName, session.runnerId, session.connection.id());
      send(session, Retire.deleted("the runner was deleted"));
      closeBounded(
          session.connection,
          new CloseReason(CLOSE_POLICY, RunnerWire.CloseReason.RUNNER_DELETED),
          "the connection of deleted runner " + session.runnerName);
    }
    droppedAt.remove(runnerId);
    loginReportedAt.remove(runnerId);
    health.forget(runnerId);
  }

  // --- frames -------------------------------------------------------------------------------------

  /**
   * A runner learns its slots only from an {@code ack}, so every change of what a greeted runner
   * may hold re-sends one with the value it has now, then {@code backlog}, which un-parks a runner
   * that was answered {@code nothing} earlier.
   */
  private void reAck(Session session) {
    DeskRunner row;
    try {
      row = runners.get(session.runnerId);
    } catch (RuntimeException gone) {
      return;
    }
    int slots = slots(row);
    LOG.infof("Runner %s may hold %d desk(s) now; re-sending its ack", row.name, slots);
    send(session, ack(slots, null));
    sendBacklog(session);
  }

  /**
   * The queue of placeable desks changed (or the estate interval came round): every runner with a
   * session that may take work is told its {@code backlog}, which un-parks one answered {@code
   * nothing} earlier. Never throws.
   */
  public void backlogChanged() {
    for (UUID runnerId : connectedRunnerIds()) {
      Session session = serving(runnerId);
      if (session != null) {
        sendBacklog(session);
      }
    }
  }

  /**
   * {@code backlog{queued}} to a session that may take work now — greeted at the pin, not draining,
   * open, its runner in service — and nothing to any other: a quarantined runner is answered {@code
   * nothing} whatever it reserves, so a backlog would only make it ask. Nothing is sent when the
   * count could not be read.
   */
  void sendBacklog(Session session) {
    if (!session.greeted || session.draining || !session.isOpen()) {
      return;
    }
    long queued;
    try {
      if (runners.get(session.runnerId).quarantined()) {
        return;
      }
      queued = work.backlog(session);
    } catch (RuntimeException e) {
      LOG.debugf("Could not count runner %s's backlog: %s", session.runnerName, e.getMessage());
      return;
    }
    send(session, new Backlog((int) Math.min(Integer.MAX_VALUE, queued)));
  }

  /** A quarantine or slot change that landed between the hello's row read and its greeting. */
  private void catchUp(Session session, DeskRunner answered, int ackedSlots) {
    DeskRunner now;
    try {
      now = runners.get(session.runnerId);
    } catch (RuntimeException gone) {
      return;
    }
    boolean moved = false;
    if (now.quarantined() && !answered.quarantined()) {
      send(session, quarantinedFrame(now.quarantineReason, now.quarantinedAt));
      moved = true;
    }
    if (moved || slots(now) != ackedSlots) {
      reAck(session);
    }
  }

  private static int slots(DeskRunner row) {
    return row.quarantined() ? 0 : Math.max(0, row.slots);
  }

  private static Ack ack(int slots, List<String> adopted) {
    return new Ack(DeskRunnerProtocol.CAPABILITY_VERSION, slots, adopted, Map.of());
  }

  private static Quarantined quarantinedFrame(String reason, Instant since) {
    return new Quarantined(
        reason == null ? "" : reason, since == null ? Instant.now().toString() : since.toString());
  }

  /** Send {@code frame} to the runner's current session, if it has one. False when none took it. */
  public boolean send(UUID runnerId, RunnerMessage frame) {
    Session session = current(runnerId);
    return session != null && send(session, frame);
  }

  /** Send one frame on a session, bounded. False when it could not leave. */
  public boolean send(Session session, RunnerMessage message) {
    WebSocketConnection connection = session.connection;
    if (!session.isOpen() || !connection.isOpen()) {
      LOG.debugf(
          "No live socket for runner %s — dropped %s",
          session.runnerName, message.getClass().getSimpleName());
      return false;
    }
    try {
      connection.sendText(codec.encode(message)).await().atMost(SEND_TIMEOUT);
      return true;
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not send %s to runner %s: %s",
          message.getClass().getSimpleName(), session.runnerName, e.getMessage());
      return false;
    }
  }

  /** A close with a deadline on it. */
  static void closeBounded(WebSocketConnection connection, CloseReason reason, String what) {
    try {
      connection.close(reason).await().atMost(CLOSE_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.debugf("Closing the socket of %s did not complete: %s", what, e.getMessage());
    }
  }
}
