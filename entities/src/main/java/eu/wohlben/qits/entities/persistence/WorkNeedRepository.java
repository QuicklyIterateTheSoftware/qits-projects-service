package eu.wohlben.qits.entities.persistence;

import eu.wohlben.qits.entities.entity.WorkNeed;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/** Which work items need which (qits-1903); the caller owns the transaction. */
@ApplicationScoped
public class WorkNeedRepository implements PanacheRepositoryBase<WorkNeed, WorkNeed.Key> {

  private static final Sort IN_ORDER = Sort.by("createdAt").and("neededEntityId");

  /** What {@code entityId} needs, in the order it was written. */
  public List<WorkNeed> ofEntity(String entityId) {
    return find("entityId", IN_ORDER, entityId).list();
  }

  /** The needs of every item in {@code entityIds}, in one query. */
  public List<WorkNeed> ofEntities(Collection<String> entityIds) {
    if (entityIds == null || entityIds.isEmpty()) {
      return List.of();
    }
    return find("entityId in ?1", IN_ORDER, entityIds).list();
  }

  /** Every need whose waiting item is of {@code projectId}, in one query. */
  public List<WorkNeed> ofProject(String projectId) {
    return find(
            "entityId in (select e.id from WorkEntity e where e.projectId = ?1)",
            IN_ORDER,
            projectId)
        .list();
  }

  /** Every need naming one of {@code neededIds} as what it waits for — who waits on a moved item. */
  public List<WorkNeed> onNeeded(Collection<String> neededIds) {
    if (neededIds == null || neededIds.isEmpty()) {
      return List.of();
    }
    return find("neededEntityId in ?1", Sort.by("createdAt").and("entityId"), neededIds).list();
  }

  /** Replaces what {@code entityId} needs by nothing; the caller persists the new set after. */
  public long clearOf(String entityId) {
    return delete("entityId", entityId);
  }
}
