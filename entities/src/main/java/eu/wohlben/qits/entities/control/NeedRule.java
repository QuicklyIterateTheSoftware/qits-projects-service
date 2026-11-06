package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>When a need is met</b> (qits-1903): the one comparison every reader of a need makes — the
 * work reads' {@code met}, the dispatch gate, the phase advance's hold and {@code WorkNeedMet}'s
 * flip. Moved here from {@code MilestoneService.reached}, which delegates to it until qits-1908
 * deletes the milestones.
 */
public final class NeedRule {

  /** The forward walk a needed status is read along: REPORTED … DONE. */
  private static final List<EntityStatus> FORWARD = EntityStateMachine.walk();

  private NeedRule() {}

  /**
   * Whether {@code current} is at or past {@code wanted} along the forward walk. DROPPED is never
   * past anything; a DROPPED target is at a wanted DROPPED and nowhere else. A word off the walk (an
   * issue's) or a null on either side reaches nothing.
   */
  public static boolean reached(String current, String wanted) {
    if (current == null || wanted == null) {
      return false;
    }
    if (EntityStatus.DROPPED.name().equals(current) || EntityStatus.DROPPED.name().equals(wanted)) {
      return current.equals(wanted);
    }
    int at = indexOf(current);
    int want = indexOf(wanted);
    return at >= 0 && want >= 0 && at >= want;
  }

  /** Whether {@code word} is a status a need may name: a word of the walk, or DROPPED. */
  public static boolean isNeedable(String word) {
    return word != null && (indexOf(word) >= 0 || EntityStatus.DROPPED.name().equals(word));
  }

  /** The words a need may name, the walk's in order and then DROPPED — for a refusal to list. */
  public static List<String> needable() {
    List<String> words = new ArrayList<>();
    FORWARD.forEach(status -> words.add(status.name()));
    words.add(EntityStatus.DROPPED.name());
    return List.copyOf(words);
  }

  private static int indexOf(String word) {
    for (int i = 0; i < FORWARD.size(); i++) {
      if (FORWARD.get(i).name().equals(word)) {
        return i;
      }
    }
    return -1;
  }
}
