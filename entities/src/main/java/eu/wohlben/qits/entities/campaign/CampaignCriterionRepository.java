package eu.wohlben.qits.entities.campaign;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/** The AND'd criteria inside campaign criterion groups, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class CampaignCriterionRepository
    implements PanacheRepositoryBase<CampaignCriterion, String> {

  private static final Sort IN_ORDER = Sort.by("position").and("id");

  /** The criteria of every group in {@code groupIds}, in one query, each group's in position order. */
  public List<CampaignCriterion> criteriaOfAll(Collection<String> groupIds) {
    if (groupIds == null || groupIds.isEmpty()) {
      return List.of();
    }
    return find("groupId in ?1", IN_ORDER, groupIds).list();
  }
}
