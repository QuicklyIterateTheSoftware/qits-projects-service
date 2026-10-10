package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * <b>An entity's EFFECTIVE block, and why — the one place the two sources are OR'd</b> (qits-895).
 *
 * <p>A row is blocked from two independent sources. The <b>explicit</b> block is {@link
 * WorkEntity#blocked}: somebody said the phase cannot finish, with a reason and a name ({@link
 * WorkEntity#blockedReason}, {@link WorkEntity#blockedBy}). The <b>derived</b> block is the agent
 * session working the entity having ended its turn with nothing in flight: {@link
 * WorkEntity#agentWaitingSince}, effective once it has stood for {@link #debounce()} — so a turn a
 * person answers at once never flickers into a block. Every answer's {@code blocked} is this
 * record's {@link #blocked}, computed at read time from the row and the clock, so nothing stores
 * "effective" and nothing has to keep it in step.
 *
 * <p><b>No gate reads this.</b> The dispatch refusal, the phase advance and the campaign executor
 * read {@link WorkEntity#blocked} alone: a derived block is something to show a person, never a
 * reason to stop work. That split is the whole point of keeping two sources rather than one flag.
 *
 * <p>The debounce is configuration ({@value #DEBOUNCE_KEY}, default 60s) read off the ambient
 * MicroProfile config rather than injected, because the answer shapes that carry it ({@link
 * TransitionedEntity#of}, the service's DTOs) are built by static factories called from everywhere;
 * an injected value would have to be threaded through every one of them.
 *
 * @param blocked the effective flag: explicit OR derived-effective
 * @param source {@link #EXPLICIT}, {@link #AGENT_WAITING} or {@link #BOTH}; {@code null} when not
 *     blocked
 * @param reason the explicit block's stated reason while it stands (which may be null on a row
 *     blocked before epics V28), else {@link #AGENT_WAITING_REASON} for a derived block alone; {@code
 *     null} when not blocked
 * @param blockedBy who set the explicit block, or {@code null} — always null for a derived block
 *     alone, which nobody set
 */
public record EntityBlockState(boolean blocked, String source, String reason, String blockedBy) {

  public static final String EXPLICIT = "EXPLICIT";

  public static final String AGENT_WAITING = "AGENT_WAITING";

  public static final String BOTH = "BOTH";

  /** The reason a derived block answers with, a fixed sentence: no person wrote one. */
  public static final String AGENT_WAITING_REASON =
      "The agent ended its turn with nothing in flight and is waiting for a person.";

  /** How long a session must stand waiting before the derived block is effective. */
  public static final String DEBOUNCE_KEY = "qits.projects.agent-waiting.debounce";

  /** {@link #DEBOUNCE_KEY}'s value when nothing sets it. */
  public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(60);

  private static final EntityBlockState UNBLOCKED = new EntityBlockState(false, null, null, null);

  /** The row's block as it stands now, under the configured debounce. */
  public static EntityBlockState of(WorkEntity row) {
    return of(row, Instant.now(), debounce());
  }

  /** The row's block as it stands at {@code now}, under {@code debounce}. */
  public static EntityBlockState of(WorkEntity row, Instant now, Duration debounce) {
    boolean explicit = row.blocked;
    boolean derived = agentWaitingEffective(row, now, debounce);
    if (explicit && derived) {
      return new EntityBlockState(true, BOTH, row.blockedReason, row.blockedBy);
    }
    if (explicit) {
      return new EntityBlockState(true, EXPLICIT, row.blockedReason, row.blockedBy);
    }
    if (derived) {
      return new EntityBlockState(true, AGENT_WAITING, AGENT_WAITING_REASON, null);
    }
    return UNBLOCKED;
  }

  /**
   * Whether the derived block stands at {@code now}: a session waiting, for at least {@code
   * debounce}. A wait stamped in the future (a skewed clock) is not effective until it is reached.
   */
  public static boolean agentWaitingEffective(WorkEntity row, Instant now, Duration debounce) {
    Instant since = row.agentWaitingSince;
    return since != null && !now.isBefore(since.plus(debounce));
  }

  /**
   * The configured debounce ({@value #DEBOUNCE_KEY}), or {@link #DEFAULT_DEBOUNCE}. A negative value
   * is read as zero.
   */
  public static Duration debounce() {
    Duration configured;
    try {
      configured =
          ConfigProvider.getConfig()
              .getOptionalValue(DEBOUNCE_KEY, Duration.class)
              .orElse(DEFAULT_DEBOUNCE);
    } catch (IllegalStateException noConfig) {
      configured = DEFAULT_DEBOUNCE;
    }
    return configured.isNegative() ? Duration.ZERO : configured;
  }
}
