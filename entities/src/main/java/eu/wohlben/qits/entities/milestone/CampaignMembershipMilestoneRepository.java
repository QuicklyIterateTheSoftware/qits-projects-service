package eu.wohlben.qits.entities.milestone;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Which campaign memberships wait on which milestones; the caller owns the transaction. */
@ApplicationScoped
public class CampaignMembershipMilestoneRepository
    implements PanacheRepositoryBase<CampaignMembershipMilestone, CampaignMembershipMilestone.Key> {

  private static final Sort IN_ORDER = Sort.by("createdAt").and("milestoneId");

  /** What {@code membershipId} waits on, in the order it was set. */
  public List<CampaignMembershipMilestone> ofMembership(String membershipId) {
    return find("membershipId", IN_ORDER, membershipId).list();
  }

  /** The waits of every membership in {@code membershipIds}, in one query. */
  public List<CampaignMembershipMilestone> ofMemberships(Collection<String> membershipIds) {
    if (membershipIds == null || membershipIds.isEmpty()) {
      return List.of();
    }
    return find("membershipId in ?1", IN_ORDER, membershipIds).list();
  }

  /** The waits on every milestone in {@code milestoneIds}, in one query. */
  public List<CampaignMembershipMilestone> ofMilestones(Collection<String> milestoneIds) {
    if (milestoneIds == null || milestoneIds.isEmpty()) {
      return List.of();
    }
    return find("milestoneId in ?1", Sort.by("createdAt").and("membershipId"), milestoneIds).list();
  }

  /** Whether any membership still waits on {@code milestoneId}. */
  public boolean anyOf(String milestoneId) {
    return count("milestoneId", milestoneId) > 0;
  }

  /** The memberships that wait on {@code milestoneId}. */
  public List<CampaignMembershipMilestone> ofMilestone(String milestoneId) {
    return find("milestoneId", Sort.by("createdAt").and("membershipId"), milestoneId).list();
  }

  /** The milestone {@code membershipId}'s condition doors own, if it has one. */
  public Optional<CampaignMembershipMilestone> generatedOf(String membershipId) {
    return find("membershipId = ?1 and generatedCondition = true", membershipId)
        .firstResultOptional();
  }
}
