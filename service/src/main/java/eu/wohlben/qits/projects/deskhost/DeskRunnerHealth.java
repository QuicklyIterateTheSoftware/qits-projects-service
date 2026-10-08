package eu.wohlben.qits.projects.deskhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.projects.control.DeskRunners;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthCheck;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthChecked;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>A front-desk runner's health check, and how it gates the runner</b> (qits-767): when one is
 * asked for, how its answer is settled, and what a quarantine or a reinstatement tells the runner.
 * A copy of qits-workspaces-service's {@code WorkspaceRunnerHealth}, keyed {@code
 * qits.projects.desk-runner.healthcheck.*}.
 *
 * <p><b>The check is the runner's.</b> {@code healthCheck{requestId, image}} asks it to pull {@code
 * image} — the pinned project-agent image's public reference ({@link
 * DeskRunnerAddresses#projectAgentImage} at {@link DeskRunnerPins#projectAgentVersion()}) — run it,
 * see it running and remove it; {@code healthChecked{requestId, ok, detail}} is its answer. The
 * verdict is kept on the row ({@code last_health_check_at}/{@code _ok}) and the report as the
 * capabilities' {@code health} key.
 *
 * <p><b>Quarantine.</b> A quarantined runner's slots are 0 — its {@code ack} carries 0 and its
 * {@code reserve} is answered {@code nothing} — while every desk it already runs is left as it is.
 * A runner is quarantined:
 *
 * <ul>
 *   <li>at its registration ({@link DeskRunners#AWAITING_FIRST_HEALTH_CHECK}): it has proved it
 *       holds a token, not that it can run a desk;
 *   <li>when it <b>comes back</b> — its first socket after it held none for longer than the
 *       reconnect grace ({@link #RECONNECTED});
 *   <li>when a check fails ({@code health check failed: <detail>}), or is not answered within
 *       {@code qits.projects.desk-runner.healthcheck.timeout} ({@value #NO_ANSWER}). A runner already
 *       out keeps its {@code quarantined_at}, so the schedule carries on, and takes the newer reason.
 * </ul>
 *
 * <p><b>A check is sent</b> when a runner awaiting its first one or coming back is greeted, when
 * somebody asks ({@link #request}), and by {@link #sweep} for each quarantined, connected runner
 * with none pending once it is <b>due</b>. A passing check reinstates a quarantined runner ({@code
 * reinstated{by: "health check"}}, then {@code ack} with its slots); an administrator's greenlight
 * does the same by hand.
 *
 * <p><b>The schedule backs off, counted from the quarantine.</b> {@code
 * qits.projects.desk-runner.healthcheck.schedule} is a list of offsets from {@code quarantined_at},
 * and after its last one more every {@link #AFTER_SCHEDULE}. A check is due at the first slot after
 * the newest check sent or settled since the quarantine ({@link #nextSlot}).
 *
 * <p><b>A pending check is memory, keyed by its {@code requestId}</b>, one per runner. A restart
 * forgets every pending check, which costs at most one check sent again.
 *
 * <p><b>Nothing here may fail the frame or the request that caused it</b>: a check that could not
 * be recorded is logged, and corrected by the next check or the next sweep.
 */
@ApplicationScoped
public class DeskRunnerHealth {

  private static final Logger LOG = Logger.getLogger(DeskRunnerHealth.class);

  /** The gap between checks once the schedule's offsets are spent. */
  static final Duration AFTER_SCHEDULE = Duration.ofHours(1);

  /** {@code reinstated.by} when the runner's own health check passed. */
  public static final String BY_HEALTH_CHECK = "health check";

  /** The quarantine reason of a runner that came back after the reconnect grace. */
  public static final String RECONNECTED = "reconnected after being offline";

  /** The detail of a check its runner did not answer in time. */
  public static final String NO_ANSWER = "no answer";

  /** The prefix of a failed check's quarantine reason. */
  static final String FAILED = "health check failed";

  @Inject DeskRunners runners;

  @Inject DeskRunnerRegistry registry;

  @Inject DeskRunnerAddresses addresses;

  @Inject DeskRunnerPins pins;

  @Inject ObjectMapper objectMapper;

  /** How long a sent check is waited for before it is settled {@value #NO_ANSWER}. */
  @ConfigProperty(name = "qits.projects.desk-runner.healthcheck.timeout")
  Duration timeout;

  /** The schedule's offsets from a runner's quarantine — see the class javadoc. */
  @ConfigProperty(name = "qits.projects.desk-runner.healthcheck.schedule")
  List<Duration> schedule;

  /** A check sent and not yet settled: its id, when it went, and the connection it went on. */
  record Pending(String requestId, Instant sentAt, DeskRunnerRegistry.Session session) {}

  /** The one pending check per runner. */
  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

  /** When each runner was last sent a check by this process — the schedule's "newest check". */
  private final ConcurrentHashMap<UUID, Instant> newestSent = new ConcurrentHashMap<>();

  /** The runners that came back and are owed a check at their next greeting at the pin. */
  private final Set<UUID> owedOnGreeting = ConcurrentHashMap.newKeySet();

  // --- the triggers -------------------------------------------------------------------------------

  /**
   * Asks a connected runner for a health check now and answers the check's {@code requestId}; null
   * when no connection of it is greeted at the pin (the door answers that 409 {@code
   * RUNNER_UNAVAILABLE}). A runner with one pending is answered that one's id and sent nothing more.
   */
  public String request(UUID runnerId) {
    return sendUnlessPending(runnerId, Instant.now());
  }

  /**
   * The registry admitted the first socket of a runner that held none for longer than the reconnect
   * grace: it is quarantined ({@value #RECONNECTED}) — unless it already is — and owed a check at
   * its greeting. Never throws.
   */
  void cameBack(UUID runnerId, String name, Duration away) {
    try {
      owedOnGreeting.add(runnerId);
      DeskRunner quarantined = runners.quarantine(runnerId, RECONNECTED);
      LOG.infof(
          "Runner %s came back after %ss offline%s; it takes no desk until a health check passes",
          name, away.toSeconds(), quarantined == null ? " (already quarantined)" : ", quarantined");
    } catch (RuntimeException e) {
      LOG.warnf(e, "Runner %s came back and could not be quarantined", name);
    }
  }

  /**
   * A connection of the runner was greeted at the pin as {@code row}: a runner awaiting its first
   * check, or one that came back, is sent one unless it has one pending on a live connection.
   */
  void onGreeted(DeskRunner row) {
    boolean owed = owedOnGreeting.remove(row.id);
    if (!row.quarantined()
        || !(owed || DeskRunners.AWAITING_FIRST_HEALTH_CHECK.equals(row.quarantineReason))) {
      return;
    }
    if (sendUnlessPending(row.id, Instant.now()) == null) {
      LOG.warnf("Runner %s was greeted and its health check could not be sent", row.name);
    }
  }

  /** The runner's row was deleted: nothing about it is waited for any more. */
  void forget(UUID runnerId) {
    pending.remove(runnerId);
    newestSent.remove(runnerId);
    owedOnGreeting.remove(runnerId);
  }

  // --- a settled check ----------------------------------------------------------------------------

  /** The runner answered: its pending check is settled, and the answer is recorded and acted on. */
  void onHealthChecked(UUID runnerId, HealthChecked checked) {
    Pending settled = claim(runnerId, checked.requestId());
    String requestId = checked.requestId();
    if (requestId == null && settled != null) {
      requestId = settled.requestId();
    }
    String detail = checked.detail() == null ? "" : checked.detail();
    Instant now = Instant.now();
    settle(runnerId, checked.ok(), detail, report(now, checked.ok(), detail, requestId), now);
  }

  /**
   * The pending check {@code requestId} answers, removed: the one it names, or the runner's pending
   * one when it names none. Null when it names one that is not pending.
   */
  private Pending claim(UUID runnerId, String requestId) {
    if (requestId == null) {
      return pending.remove(runnerId);
    }
    Pending[] claimed = {null};
    pending.computeIfPresent(
        runnerId,
        (id, waiting) -> {
          if (requestId.equals(waiting.requestId())) {
            claimed[0] = waiting;
            return null;
          }
          return waiting;
        });
    return claimed[0];
  }

  /**
   * A check's verdict reached its runner's row: recorded first, whatever follows; a pass lifts a
   * quarantine ({@code reinstated}, then {@code ack} with the row's slots), a failure begins or
   * keeps one ({@code quarantined}, then {@code ack{0}}). Never throws.
   */
  private void settle(UUID runnerId, boolean ok, String detail, ObjectNode report, Instant at) {
    try {
      DeskRunner row = runners.recordHealthCheck(runnerId, ok, at, report);
      if (row == null) {
        return;
      }
      if (ok) {
        LOG.infof("Health check of runner %s passed", row.name);
        if (row.quarantined()) {
          runners.greenlight(runnerId);
          LOG.infof("Runner %s passed its health check; it takes desks now", row.name);
          registry.reinstated(runnerId, BY_HEALTH_CHECK);
        }
        return;
      }
      String reason = truncate(detail.isBlank() ? FAILED : FAILED + ": " + detail);
      LOG.warnf("Health check of runner %s failed: %s", row.name, detail);
      DeskRunners.Quarantine out = runners.quarantineFor(runnerId, reason);
      if (out.runner() != null && (out.began() || out.reasonChanged())) {
        registry.quarantined(runnerId, out.runner().quarantineReason, out.runner().quarantinedAt);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Health check of runner %s could not be recorded", runnerId);
    }
  }

  /** The report the row keeps: {@code {at, ok, detail, requestId, checks:[]}}. */
  private ObjectNode report(Instant at, boolean ok, String detail, String requestId) {
    ObjectNode report = objectMapper.createObjectNode();
    report.put("at", at.toString());
    report.put("ok", ok);
    report.put("detail", detail);
    report.put("requestId", requestId);
    report.putArray("checks");
    return report;
  }

  // --- the schedule -------------------------------------------------------------------------------

  /**
   * Every {@code qits.projects.desk-runner.healthcheck.sweep-interval}, a {@link #sweep}. {@link
   * Scheduled.ConcurrentExecution#SKIP}, and delayed by one interval so a booting process sends no
   * check before any runner could have dialled back.
   */
  @Scheduled(
      every = "{qits.projects.desk-runner.healthcheck.sweep-interval}",
      delayed = "{qits.projects.desk-runner.healthcheck.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepTick() {
    sweep(Instant.now());
  }

  /**
   * One pass, as of {@code now} — package-private so a test drives it. First every check pending
   * longer than the timeout is settled failed ({@value #NO_ANSWER}); then every quarantined runner
   * greeted at the pin with no check pending on a live connection that is {@link #due} is sent one.
   */
  void sweep(Instant now) {
    expireUnanswered(now);
    List<DeskRunner> quarantined;
    try {
      quarantined = runners.list().stream().filter(DeskRunner::quarantined).toList();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the quarantined runners");
      return;
    }
    for (DeskRunner runner : quarantined) {
      if (registry.serving(runner.id) == null || livePending(runner.id) != null || !due(runner, now)) {
        continue;
      }
      if (sendUnlessPending(runner.id, now) == null) {
        LOG.warnf("Runner %s is due a health check and it could not be sent", runner.name);
      }
    }
  }

  /** Whether a quarantined runner's next check has come — see {@link #nextSlot}. */
  private boolean due(DeskRunner runner, Instant now) {
    Instant since = runner.quarantinedAt;
    if (since == null) {
      return false;
    }
    Instant newest = latestSince(since, newestSent.get(runner.id), runner.lastHealthCheckAt);
    return !nextSlot(since, newest).isAfter(now);
  }

  private static Instant latestSince(Instant since, Instant a, Instant b) {
    Instant newest = null;
    for (Instant at : new Instant[] {a, b}) {
      if (at != null && !at.isBefore(since) && (newest == null || at.isAfter(newest))) {
        newest = at;
      }
    }
    return newest;
  }

  /**
   * When a runner quarantined at {@code since} is next due a check: its first slot ({@code since}
   * plus an {@link #offset}) after {@code newest}, or its first slot of all when there is none.
   */
  Instant nextSlot(Instant since, Instant newest) {
    long k = 0;
    if (newest != null) {
      while (!since.plus(offset(k)).isAfter(newest)) {
        k++;
      }
    }
    return since.plus(offset(k));
  }

  /**
   * The k-th slot's offset from the quarantine: the schedule's k-th entry, and after its last one
   * more {@link #AFTER_SCHEDULE} per slot. An empty schedule is a slot every {@link #AFTER_SCHEDULE}.
   */
  Duration offset(long k) {
    List<Duration> offsets = schedule == null ? List.of() : schedule;
    if (k < offsets.size()) {
      return offsets.get((int) k);
    }
    Duration last = offsets.isEmpty() ? Duration.ZERO : offsets.get(offsets.size() - 1);
    return last.plus(AFTER_SCHEDULE.multipliedBy(k - offsets.size() + 1));
  }

  private void expireUnanswered(Instant now) {
    for (Map.Entry<UUID, Pending> entry : List.copyOf(pending.entrySet())) {
      Pending waiting = entry.getValue();
      if (waiting.sentAt().plus(timeout).isAfter(now) || !pending.remove(entry.getKey(), waiting)) {
        continue;
      }
      LOG.warnf(
          "Health check %s of runner %s was not answered within %s",
          waiting.requestId(), entry.getKey(), timeout);
      settle(entry.getKey(), false, NO_ANSWER, report(now, false, NO_ANSWER, waiting.requestId()), now);
    }
  }

  // --- internals ----------------------------------------------------------------------------------

  /** The id of the runner's pending check, or null: what a suite waits on. */
  String pendingCheck(UUID runnerId) {
    Pending waiting = pending.get(runnerId);
    return waiting == null ? null : waiting.requestId();
  }

  /** The runner's pending check, unless the connection it went on has closed since. */
  private Pending livePending(UUID runnerId) {
    Pending waiting = pending.get(runnerId);
    return waiting != null && waiting.session().isOpen() ? waiting : null;
  }

  /** The image a check runs: the pinned project-agent image's public reference; null unconfigured. */
  private String checkImage() {
    try {
      return addresses.projectAgentImage(pins.projectAgentVersion());
    } catch (DomainException unconfigured) {
      return null;
    }
  }

  /**
   * Sends {@code healthCheck{requestId, image}} to the runner's serving connection and answers the
   * id — or the pending check's id when one is waiting on a live connection; null when no connection
   * could take it, or there is no image to name. The pending entry is written before the frame
   * leaves, so an answer cannot outrun it.
   */
  private String sendUnlessPending(UUID runnerId, Instant now) {
    DeskRunnerRegistry.Session session = registry.serving(runnerId);
    if (session == null) {
      return null;
    }
    String image = checkImage();
    if (image == null) {
      LOG.warnf(
          "Runner %s cannot be sent a health check: this deployment knows no public domain",
          session.runnerName());
      return null;
    }
    Pending fresh = new Pending(UUID.randomUUID().toString(), now, session);
    Pending[] standing = {null};
    pending.compute(
        runnerId,
        (id, waiting) -> {
          if (waiting != null && waiting.session().isOpen()) {
            standing[0] = waiting;
            return waiting;
          }
          return fresh;
        });
    if (standing[0] != null) {
      return standing[0].requestId();
    }
    if (!registry.send(session, new HealthCheck(fresh.requestId(), image))) {
      pending.remove(runnerId, fresh);
      return null;
    }
    newestSent.put(runnerId, now);
    LOG.infof("Asked runner %s for health check %s", session.runnerName(), fresh.requestId());
    return fresh.requestId();
  }

  private static String truncate(String s) {
    return s.length() <= 1000 ? s : s.substring(0, 1000);
  }
}
