package eu.wohlben.qits.entities.milestone;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/** Which work entities wait on which milestones; the caller owns the transaction. */
@ApplicationScoped
public class WorkMilestoneRepository
    implements PanacheRepositoryBase<WorkMilestone, WorkMilestone.Key> {

  private static final Sort IN_ORDER = Sort.by("createdAt").and("milestoneId");

  /** What {@code entityId} waits on, in the order it was attached. */
  public List<WorkMilestone> ofEntity(String entityId) {
    return find("entityId", IN_ORDER, entityId).list();
  }

  /** The attachments of every entity in {@code entityIds}, in one query. */
  public List<WorkMilestone> ofEntities(Collection<String> entityIds) {
    if (entityIds == null || entityIds.isEmpty()) {
      return List.of();
    }
    return find("entityId in ?1", IN_ORDER, entityIds).list();
  }

  /** The attachments of every milestone in {@code milestoneIds}, in one query. */
  public List<WorkMilestone> ofMilestones(Collection<String> milestoneIds) {
    if (milestoneIds == null || milestoneIds.isEmpty()) {
      return List.of();
    }
    return find("milestoneId in ?1", Sort.by("createdAt").and("entityId"), milestoneIds).list();
  }

  /** Every attachment to a milestone of {@code projectId}, in one query. */
  public List<WorkMilestone> ofProject(String projectId) {
    return find(
            "milestoneId in (select m.id from Milestone m where m.projectId = ?1)",
            IN_ORDER,
            projectId)
        .list();
  }

  /** Whether anything still waits on {@code milestoneId} through an attachment. */
  public boolean anyOf(String milestoneId) {
    return count("milestoneId", milestoneId) > 0;
  }

  /** The entities that wait on {@code milestoneId}. */
  public List<WorkMilestone> ofMilestone(String milestoneId) {
    return find("milestoneId", Sort.by("createdAt").and("entityId"), milestoneId).list();
  }
}
