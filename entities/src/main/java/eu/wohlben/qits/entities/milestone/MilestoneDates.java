package eu.wohlben.qits.entities.milestone;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * <b>The one place a group's and a milestone's {@code satisfied_at} are derived</b> (epic qits-615),
 * from the criteria's latches, after every latch and every edit:
 *
 * <ul>
 *   <li>A <b>group</b> holds when every criterion in it has latched, since its <b>last</b> latch —
 *       the max of its criteria's {@code satisfied_at}. An empty group holds vacuously since it was
 *       written ({@code Conditions}' rule), which is why the write doors refuse one.
 *   <li>A <b>milestone</b> holds when any group holds, since the <b>earliest</b> date among its
 *       holding groups. A milestone with no groups holds since it was written, by the same rule.
 * </ul>
 *
 * <p>Dates mean "met since": a recompute that finds nothing holding clears the date. The answer says
 * which milestones came to hold and which stopped holding — what {@code MilestoneMet} and {@code
 * MilestoneUnmet} are published from. The caller holds the milestone rows' locks (every writer takes
 * a milestone row before its criteria), and flushed nothing it needs to: this flushes first.
 */
@ApplicationScoped
public class MilestoneDates {

  @Inject MilestoneRepository milestones;

  @Inject MilestoneGroupRepository groups;

  @Inject MilestoneCriterionRepository criteria;

  /** Re-derives {@code milestoneIds}' dates, in id order; see the class javadoc. */
  @Transactional(TxType.MANDATORY)
  public MilestonesChanged recompute(Collection<String> milestoneIds) {
    if (milestoneIds == null || milestoneIds.isEmpty()) {
      return MilestonesChanged.none();
    }
    milestones.getEntityManager().flush();
    List<MilestonesChanged.Met> met = new ArrayList<>();
    List<MilestonesChanged.Unmet> unmet = new ArrayList<>();
    Instant now = Instant.now();
    for (String id : new TreeSet<>(milestoneIds)) {
      Milestone milestone = milestones.findById(id);
      if (milestone == null) {
        continue;
      }
      List<MilestoneGroup> found = groups.groupsOf(id);
      Map<String, List<MilestoneCriterion>> byGroup = new LinkedHashMap<>();
      for (MilestoneGroup group : found) {
        byGroup.put(group.id, new ArrayList<>());
      }
      for (MilestoneCriterion criterion : criteria.criteriaOfAll(byGroup.keySet())) {
        byGroup.get(criterion.groupId).add(criterion);
      }
      MilestoneGroup earliest = null;
      for (MilestoneGroup group : found) {
        Instant holds = holdsSince(group, byGroup.get(group.id));
        if (!Objects.equals(group.satisfiedAt, holds)) {
          group.satisfiedAt = holds;
        }
        if (holds != null && (earliest == null || holds.isBefore(earliest.satisfiedAt))) {
          earliest = group;
        }
      }
      Instant before = milestone.satisfiedAt;
      Instant after =
          found.isEmpty() ? milestone.createdAt : earliest == null ? null : earliest.satisfiedAt;
      if (!Objects.equals(before, after)) {
        milestone.satisfiedAt = after;
      }
      if (before == null && after != null) {
        met.add(
            new MilestonesChanged.Met(
                milestone.id,
                milestone.projectId,
                milestone.displayName,
                earliest == null ? null : earliest.id,
                after));
      } else if (before != null && after == null) {
        unmet.add(
            new MilestonesChanged.Unmet(
                milestone.id, milestone.projectId, milestone.displayName, now));
      }
    }
    milestones.getEntityManager().flush();
    return new MilestonesChanged(met, unmet);
  }

  /** A group's date, from its criteria: see the class javadoc. */
  static Instant holdsSince(MilestoneGroup group, List<MilestoneCriterion> criteria) {
    if (criteria == null || criteria.isEmpty()) {
      return group.createdAt;
    }
    Instant last = null;
    for (MilestoneCriterion criterion : criteria) {
      if (criterion.satisfiedAt == null) {
        return null;
      }
      if (last == null || criterion.satisfiedAt.isAfter(last)) {
        last = criterion.satisfiedAt;
      }
    }
    return last;
  }
}
