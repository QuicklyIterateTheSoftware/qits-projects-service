package eu.wohlben.qits.entities.control;

import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.wohlben.qits.entities.entity.Archetype;
import java.util.List;

/**
 * <b>A need as a work read names it</b> (qits-1903): the needed item — its id, its qualified id,
 * its title and archetype — the status it must reach, the status it holds now, and whether that is
 * {@link NeedRule#reached far enough}. What {@code GET /work/{qualifiedId}}, its children and
 * {@code GET /projects/{project}/work} answer in {@code needs}.
 *
 * @param id the needed item
 * @param qualifiedId its {@code <slug>-<n>}, filled in {@code service} ({@link #withQualifiedId});
 *     null until then, for {@link TransitionedEntity#qualifiedId}'s reason
 * @param title its title as it stands
 * @param archetype what it is
 * @param status the status it must reach
 * @param currentStatus the status it holds now
 * @param met {@code NeedRule.reached(currentStatus, status)}
 * @param number its per-project number, which {@link #qualifiedId} is rendered from — never on the
 *     wire
 */
public record NeedRef(
    String id,
    String qualifiedId,
    String title,
    Archetype archetype,
    String status,
    String currentStatus,
    boolean met,
    @JsonIgnore long number) {

  /** The need of a stored row on the needed item as it stands now. */
  public static NeedRef of(String neededId, String status, String title, Archetype archetype,
      String currentStatus, long number) {
    return new NeedRef(
        neededId,
        null,
        title,
        archetype,
        status,
        currentStatus,
        NeedRule.reached(currentStatus, status),
        number);
  }

  /** The same need, told what its item is called in a commit subject. */
  public NeedRef withQualifiedId(String rendered) {
    return new NeedRef(id, rendered, title, archetype, status, currentStatus, met, number);
  }

  /** How many of {@code needs} are not met. */
  public static int unmet(List<NeedRef> needs) {
    return needs == null ? 0 : (int) needs.stream().filter(need -> !need.met()).count();
  }
}
