package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.AgentWaitingService;
import eu.wohlben.qits.entities.control.EntityBlockState;
import eu.wohlben.qits.entities.entity.WorkEntity;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The derived block: an agent session waiting for a person, read as BLOCKED once it has stood
 * for the debounce</b> (qits-895). The rules behind {@code POST /work/{id}/agent-waiting} and the
 * sweep that announces a wait the moment it becomes a block.
 *
 * <h2>Why it is derived and not set</h2>
 *
 * <p>An agent that ended its turn with nothing in flight is waiting for a person whether or not it
 * said so with {@code block_entity}, and the board should show that. But nobody <em>stated</em> a
 * blocker, so this never writes the explicit flag: it keeps the session's wait in columns of its own
 * ({@code agent_waiting_since}, {@code agent_waiting_cause}) and every answer ORs the two at read
 * time ({@link EntityBlockState}). No gate reads it — a derived block never refuses a dispatch or
 * withholds a phase turn — and it writes no comment and no audit row.
 *
 * <h2>Where a wait counts</h2>
 *
 * <p>Only where a block could stand: {@link EntityBlocks#blockable} — a ticket or an epic at a
 * status whose phase runs (REPORTED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING), a
 * campaign on its walk short of VERIFIED, never a feature or a task. A frame about any other row is
 * answered 204 and changes nothing, as is a frame older than the row's last activity less {@code
 * qits.projects.agent-waiting.skew} — frames race, and every transition stamps that activity so one
 * sent before the move cannot re-derive a block at the new status.
 *
 * <h2>When it is said</h2>
 *
 * <p>A wait is not a block until it has stood for {@code qits.projects.agent-waiting.debounce}, so
 * the frame that starts one announces nothing. {@link #sweep} finds every wait that crossed the
 * debounce since its previous pass and announces each — the SSE hint of its archetype, and {@link
 * AgentEntitySignals#changed} so the session's name follows the effective block. A frame that ends
 * an effective wait announces at once. Announcing is idempotent (a hint is "read again", a signal
 * carries the whole state), so the sweep's window may overlap without harm and is kept simple.
 */
@ApplicationScoped
public class AgentWaiting {

  private static final Logger LOG = Logger.getLogger(AgentWaiting.class);

  @Inject AgentWaitingService rows;

  @Inject ProjectChangePublisher publisher;

  @Inject AgentEntitySignals agents;

  /** How much older than the row's last activity a frame may be and still apply. */
  @ConfigProperty(name = "qits.projects.agent-waiting.skew", defaultValue = "5s")
  Duration skew;

  /** The sweep's interval, which bounds the first pass's look back. */
  @ConfigProperty(name = "qits.projects.agent-waiting.sweep-interval", defaultValue = "10s")
  Duration sweepInterval;

  /** Where the previous pass stood; null until the first pass after boot. */
  private volatile Instant lastSweep;

  /**
   * One session frame about {@code entity}. Never refuses a known entity: an ignored frame is as
   * much an answer as an applied one, and the session has nothing to do about either.
   *
   * @param at when the session stamped the frame, or null for now
   */
  public void report(WorkEntity entity, boolean waiting, String cause, String sessionId, Instant at) {
    Instant now = Instant.now();
    AgentWaitingService.Frame frame =
        rows.record(entity.id, waiting, cause, at, now, skew, EntityBlocks::blockable);
    LOG.debugf(
        "Agent session %s on %s %s: waiting=%s, %s",
        sessionId, entity.archetype, entity.id, waiting, frame.applied() ? "applied" : "ignored");
    if (frame.changed()) {
      // An ended wait that was a block, or a late frame whose wait is already past the debounce.
      announce(frame.entity());
    }
  }

  @Scheduled(
      every = "{qits.projects.agent-waiting.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduledSweep() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return; // the suite drives sweep(now) directly
    }
    try {
      sweep(Instant.now());
    } catch (RuntimeException e) {
      LOG.error("The agent-waiting sweep failed — retried on the next interval.", e);
    }
  }

  /**
   * One pass at {@code now}: announces every wait that became a block — crossed the debounce —
   * since the previous pass, or, on the first pass after boot, within the last debounce plus one
   * interval (so a restart neither loses a crossing nor replays the whole table). Answers how many
   * it announced.
   */
  @ActivateRequestContext
  public int sweep(Instant now) {
    Duration debounce = EntityBlockState.debounce();
    Instant upTo = now.minus(debounce);
    Instant previous = lastSweep;
    Instant after =
        previous == null
            ? upTo.minus(debounce).minus(sweepInterval)
            : previous.minus(debounce);
    if (!after.isBefore(upTo)) {
      return 0;
    }
    List<WorkEntity> crossed = rows.waitingSince(after, upTo);
    for (WorkEntity row : crossed) {
      announce(row);
    }
    lastSweep = now;
    return crossed.size();
  }

  /** Forgets where the previous pass stood, so the next is a first pass — for the suite. */
  public void forgetLastSweep() {
    lastSweep = null;
  }

  private void announce(WorkEntity row) {
    publisher.fire(row.projectId, ProjectChangeHint.Topic.of(row.archetype));
    agents.changed(row);
  }
}
