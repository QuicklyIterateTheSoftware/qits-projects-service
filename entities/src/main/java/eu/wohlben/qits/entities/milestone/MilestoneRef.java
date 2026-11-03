package eu.wohlben.qits.entities.milestone;

import java.time.Instant;
import java.util.List;

/**
 * <b>A milestone as a work read names it</b> (epic qits-615): its id, its name and since when it is
 * met — null while it is not. What {@code GET /work/{qualifiedId}}, {@code GET
 * /projects/{project}/work} and a campaign member answer in {@code milestones}; the milestone itself
 * is {@code GET /milestones/{id}}.
 */
public record MilestoneRef(String id, String displayName, Instant satisfiedAt) {

  public static MilestoneRef of(Milestone milestone) {
    return new MilestoneRef(milestone.id, milestone.displayName, milestone.satisfiedAt);
  }

  /** {@code milestones} as refs, in the order given. */
  public static List<MilestoneRef> of(List<Milestone> milestones) {
    return milestones == null ? List.of() : milestones.stream().map(MilestoneRef::of).toList();
  }

  /** How many of {@code refs} are not met. */
  public static int unmet(List<MilestoneRef> refs) {
    return refs == null ? 0 : (int) refs.stream().filter(ref -> ref.satisfiedAt() == null).count();
  }
}
