package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;

/**
 * <b>The derived block's rows: what an agent session says about itself, and the read the sweep
 * makes</b> (qits-895). The entities module's half of {@code POST /work/{id}/agent-waiting}; the
 * service layer decides which statuses derive a block (a phase is its concept, not this module's)
 * and passes that rule in as a predicate judged on the locked row.
 *
 * <h2>What a frame may write, and what it may not</h2>
 *
 * <p>A frame writes {@link WorkEntity#agentActivityAt}, {@link WorkEntity#agentWaitingSince},
 * {@link WorkEntity#agentWaitingCause} and {@link WorkEntity#agentWaitingOn} and nothing else. It never touches {@link WorkEntity#blocked},
 * {@link WorkEntity#blockedBy} or {@link WorkEntity#blockedReason} — session activity is not a
 * statement anybody made — it writes no audit row and no comment, and it does <b>not</b> move {@code
 * updated_at}: the write is one native {@code UPDATE} of those columns rather than a dirty
 * managed row, because a session reports at every turn and a row whose "last changed" moved every
 * time an agent stopped typing would say nothing about the work.
 *
 * <h2>Why a frame can be ignored</h2>
 *
 * <p>Frames race: the session reports from another process, over a hop, and a transition can land
 * between the moment one was stamped and the moment it arrives. Every transition stamps {@code
 * agentActivityAt} (now), so a frame stamped earlier than it — less a skew tolerance for the two
 * clocks — is from before the move and is dropped rather than re-deriving a block at the new status.
 * A frame stamped in the future is read as now: a wrong clock must not hold every later frame off.
 */
@ApplicationScoped
public class AgentWaitingService {

  @Inject WorkEntityRepository entities;

  @Inject WritePatience writes;

  @Inject ReadPatience reads;

  /**
   * What one frame did.
   *
   * @param entity the row after the frame — detached, carrying the three columns as written
   * @param applied whether the frame was written at all; false for an ignored one (a status that
   *     derives nothing, or a frame older than the row's activity)
   * @param wasEffective whether the derived block stood before the frame
   * @param effective whether it stands after it
   * @param wasWaitingOn the declared wait the session waited on before the frame, or null
   *     (qits-1153)
   */
  public record Frame(
      WorkEntity entity,
      boolean applied,
      boolean wasEffective,
      boolean effective,
      String wasWaitingOn) {

    /**
     * Whether what the entity reads as moved — the derived block's effectiveness, or the declared
     * wait it is waiting on — which is what a caller announces on.
     */
    public boolean changed() {
      return wasEffective != effective
          || !Objects.equals(wasWaitingOn, EntityBlockState.waitingOn(entity));
    }
  }

  /**
   * Applies one session frame to the row {@code id} names, or a 404.
   *
   * @param waiting whether the session is now waiting for a person
   * @param cause what it says it is waiting for, or null
   * @param waitingOn the label of the declared wait it is waiting on, or null (qits-1153); kept
   *     whether or not {@code waiting} is set — a wait can be open, or declared, while the agent is
   *     busy (qits-1460) — and a frame without it clears it
   * @param at when the session stamped the frame; null or in the future reads as {@code now}
   * @param now this process's clock, passed in so a test can stand anywhere on it
   * @param skew how much older than the row's activity a frame may be and still apply
   * @param derives whether the row's archetype and status derive a block at all — judged on the
   *     locked row, so a transition racing the frame is seen
   */
  public Frame record(
      String id,
      boolean waiting,
      String cause,
      String waitingOn,
      Instant at,
      Instant now,
      Duration skew,
      Predicate<WorkEntity> derives) {
    Duration debounce = EntityBlockState.debounce();
    Instant stamped = at == null || at.isAfter(now) ? now : at;
    return writes.hold(
        "agent session activity",
        () -> {
          EntityManager em = entities.getEntityManager();
          WorkEntity row =
              id == null ? null : em.find(WorkEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
          if (row == null) {
            throw new NotFoundException("Entity not found: " + id);
          }
          // Detached before anything is set on it, so the commit's dirty check writes nothing — the
          // native UPDATE below is the whole write, and updated_at stays where the work left it.
          em.detach(row);
          boolean was = EntityBlockState.agentWaitingEffective(row, now, debounce);
          String wasOn = EntityBlockState.waitingOn(row);
          if (!derives.test(row)
              || (row.agentActivityAt != null && stamped.isBefore(row.agentActivityAt.minus(skew)))) {
            return new Frame(row, false, was, was, wasOn);
          }
          Instant activity =
              row.agentActivityAt == null || stamped.isAfter(row.agentActivityAt)
                  ? stamped
                  : row.agentActivityAt;
          Instant since =
              !waiting ? null : row.agentWaitingSince != null ? row.agentWaitingSince : stamped;
          String said = waiting && cause != null && !cause.isBlank() ? cause.trim() : null;
          // The label is stored whether or not the session is waiting for a person (qits-1460): a
          // wait can be declared, or still open, while the agent is busy, and the item should keep
          // showing it rather than losing it until the agent goes idle.
          String on = declaredWait(cause, waitingOn);
          // Typed binds: a null bound untyped reaches postgres as bytea, which no timestamptz takes.
          em.createNativeQuery(
                  "update entity set agent_activity_at = ?1, agent_waiting_since = ?2,"
                      + " agent_waiting_cause = ?3, agent_waiting_on = ?5 where id = ?4")
              .unwrap(NativeQuery.class)
              .setParameter(1, activity, StandardBasicTypes.INSTANT)
              .setParameter(2, since, StandardBasicTypes.INSTANT)
              .setParameter(3, said, StandardBasicTypes.STRING)
              .setParameter(4, row.id, StandardBasicTypes.STRING)
              .setParameter(5, on, StandardBasicTypes.STRING)
              .executeUpdate();
          row.agentActivityAt = activity;
          row.agentWaitingSince = since;
          row.agentWaitingCause = said;
          row.agentWaitingOn = on;
          return new Frame(
              row, true, was, EntityBlockState.agentWaitingEffective(row, now, debounce), wasOn);
        });
  }

  /**
   * The cause qits-workspaces sends while the agent waits on declared waits (qits-1153), with their
   * labels joined by {@code "; "} in {@code waitingOn}. The other wait causes ({@code WaitMatched},
   * {@code WaitTimedOut}, {@code WaitCancelled}) are ordinary reports.
   */
  public static final String DECLARED_WAIT_CAUSE = "Wait";

  /** Said when a {@value #DECLARED_WAIT_CAUSE} frame names no label. */
  public static final String UNNAMED_DECLARED_WAIT = "a declared wait";

  /**
   * The declared wait a waiting frame names, or null for a wait for a person: its {@code waitingOn},
   * else {@link #UNNAMED_DECLARED_WAIT} for a {@value #DECLARED_WAIT_CAUSE} frame without one — that
   * cause never derives a block.
   */
  static String declaredWait(String cause, String waitingOn) {
    if (waitingOn != null && !waitingOn.isBlank()) {
      return waitingOn.trim();
    }
    return cause != null && DECLARED_WAIT_CAUSE.equals(cause.trim()) ? UNNAMED_DECLARED_WAIT : null;
  }

  /**
   * Every row whose agent wait began in {@code (after, upTo]} — the sweep's question: with {@code
   * upTo = now - debounce}, the rows whose derived block became effective since the last pass. A
   * wait on a declared wait derives no block, so it is not among them (qits-1153). Read in a
   * transaction of its own, so what it answers is what is committed.
   */
  public List<WorkEntity> waitingSince(Instant after, Instant upTo) {
    return reads.hold(
        "the entities an agent is waiting on",
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () ->
                        entities
                            .find(
                                "agentWaitingSince > ?1 and agentWaitingSince <= ?2"
                                    + " and agentWaitingOn is null",
                                after,
                                upTo)
                            .list()));
  }
}
