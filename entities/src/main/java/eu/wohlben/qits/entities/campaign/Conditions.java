package eu.wohlben.qits.entities.campaign;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * <b>The rule that decides whether a campaign member's condition holds</b> — a monotone DNF, and
 * nothing else: a member waits on OR'd groups, each of AND'd criteria.
 *
 * <ul>
 *   <li><b>No groups is satisfied</b>: a member with no condition may run as soon as the campaign
 *       starts.
 *   <li>Otherwise the condition holds when <b>any</b> group has <b>every</b> criterion latched.
 * </ul>
 *
 * <p>Pure: no database, no clock. A criterion is latched when its {@code satisfied_at} is set, and
 * latches only ever go from unset to set, so a condition that holds keeps holding. An empty group
 * would hold vacuously, which is why the write door refuses one rather than this rule deciding it.
 */
public final class Conditions {

  private Conditions() {}

  /** Whether the DNF over {@code groups} holds — see the class javadoc. */
  public static boolean satisfied(Collection<? extends Collection<CampaignCriterion>> groups) {
    return holds(groups, criterion -> criterion.satisfiedAt != null);
  }

  /**
   * The same rule over any representation of a criterion, {@code latched} saying whether one is —
   * for a reader that has the latches as columns rather than as entities ({@link
   * CampaignEvaluator#satisfiedUnclaimed}).
   */
  public static <T> boolean holds(
      Collection<? extends Collection<T>> groups, Predicate<? super T> latched) {
    if (groups == null || groups.isEmpty()) {
      return true;
    }
    for (Collection<T> group : groups) {
      if (group.stream().allMatch(latched)) {
        return true;
      }
    }
    return false;
  }
}
