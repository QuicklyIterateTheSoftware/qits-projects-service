package eu.wohlben.qits.entities.milestone;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/** The OR'd groups of milestones, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class MilestoneGroupRepository implements PanacheRepositoryBase<MilestoneGroup, String> {

  private static final Sort IN_ORDER = Sort.by("position").and("id");

  /** One milestone's groups, in position order. */
  public List<MilestoneGroup> groupsOf(String milestoneId) {
    return find("milestoneId", IN_ORDER, milestoneId).list();
  }

  /** The groups of every milestone in {@code milestoneIds}, in one query. */
  public List<MilestoneGroup> groupsOfAll(Collection<String> milestoneIds) {
    if (milestoneIds == null || milestoneIds.isEmpty()) {
      return List.of();
    }
    return find("milestoneId in ?1", IN_ORDER, milestoneIds).list();
  }
}
