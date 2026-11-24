package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkTag;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>Work an agent may take</b> (qits-2595): what the work list keeps under {@code
 * unassigned=true}. It mirrors what the dispatch refuses ({@code EntityDispatchService}: blocked,
 * reserved for a person, a condition, an unmet need, already claimed), so an agent that picks from
 * this list picks something it can carry. Every clause must hold:
 *
 * <ul>
 *   <li>an {@link Archetype#EPIC} or a {@link Archetype#TICKET} — a feature or a task is part of
 *       its epic's work, never taken on its own;
 *   <li>no assignee (null or blank);
 *   <li>a status in {@link #STATUSES}: REFINED is left out because only a person moves it on to
 *       READY_FOR_DEV, so an agent holding one could not progress it; VERIFIED, DONE and DROPPED
 *       are finished;
 *   <li>not blocked;
 *   <li>no tag {@link WorkTag#RESERVED_FOR_HUMAN} or {@link WorkTag#CONDITION};
 *   <li>no unmet need ({@code unmetNeeds} null or 0) — so it reads a summary whose needs are
 *       attached.
 * </ul>
 */
public final class Takeable {

  /** The statuses a takeable item may be in. */
  public static final Set<String> STATUSES =
      Set.of(
          EntityStatus.REPORTED.name(),
          EntityStatus.READY_FOR_DEV.name(),
          EntityStatus.IMPLEMENTING.name(),
          EntityStatus.IMPLEMENTED.name(),
          EntityStatus.VERIFYING.name());

  /** The rule — see the class javadoc. */
  public static final Predicate<EntitySummary> RULE = Takeable::test;

  private Takeable() {}

  /** Whether {@code entry} may be taken — see the class javadoc. */
  public static boolean test(EntitySummary entry) {
    return (entry.archetype() == Archetype.EPIC || entry.archetype() == Archetype.TICKET)
        && (entry.assignee() == null || entry.assignee().isBlank())
        && STATUSES.contains(entry.status())
        && !Boolean.TRUE.equals(entry.blocked())
        && (entry.tags() == null
            || (!entry.tags().contains(WorkTag.RESERVED_FOR_HUMAN)
                && !entry.tags().contains(WorkTag.CONDITION)))
        && (entry.unmetNeeds() == null || entry.unmetNeeds() == 0);
  }
}
