package eu.wohlben.qits.entities.campaign;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/** The OR'd groups of campaign members' conditions, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class CampaignCriterionGroupRepository
    implements PanacheRepositoryBase<CampaignCriterionGroup, String> {

  private static final Sort IN_ORDER = Sort.by("position").and("id");

  /** One membership's groups, in position order. */
  public List<CampaignCriterionGroup> groupsOf(String membershipId) {
    return find("membershipId", IN_ORDER, membershipId).list();
  }

  /**
   * The groups of every membership in {@code membershipIds}, in one query — a campaign read is one
   * statement per table, never one per member. {@code in ()} is a syntax error in postgres, hence the
   * guard.
   */
  public List<CampaignCriterionGroup> groupsOfAll(Collection<String> membershipIds) {
    if (membershipIds == null || membershipIds.isEmpty()) {
      return List.of();
    }
    return find("membershipId in ?1", IN_ORDER, membershipIds).list();
  }
}
