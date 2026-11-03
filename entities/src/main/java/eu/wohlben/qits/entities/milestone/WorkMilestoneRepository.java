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

  /** The entities that wait on {@code milestoneId}. */
  public List<WorkMilestone> ofMilestone(String milestoneId) {
    return find("milestoneId", Sort.by("createdAt").and("entityId"), milestoneId).list();
  }
}
