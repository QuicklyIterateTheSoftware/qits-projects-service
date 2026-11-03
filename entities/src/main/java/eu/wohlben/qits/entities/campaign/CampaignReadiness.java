package eu.wohlben.qits.entities.campaign;

import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Which campaign memberships may run</b> (epic qits-615): an unclaimed CAMPAIGN membership whose
 * every milestone ({@code campaign_membership_milestone}) is met. A membership that waits on no
 * milestone has no condition and may run as soon as its campaign runs — the campaign-era rule "no
 * groups is satisfied", kept. <b>No criterion is read here</b>: a milestone's {@code satisfied_at}
 * is the whole answer, kept current by {@code MilestoneDates}.
 *
 * <p>The one "satisfied memberships" step every source answers through: the bus listener's retry
 * arm, the executor's {@code MilestoneMet} consumer and its sweep, and the campaign doors'
 * hand-over. Whether to act — the campaign started, running, unblocked, the member scheduled — is
 * the executor's own re-check, never this answer's claim.
 */
@ApplicationScoped
public class CampaignReadiness {

  private static final String SATISFIED =
      """
      select m.id
        from entity_membership m
       where m.id in (:ids)
         and m.kind = 'CAMPAIGN'
         and m.claimed_at is null
         and not exists (select 1
                           from campaign_membership_milestone w
                           join milestone ms on ms.id = w.milestone_id
                          where w.membership_id = m.id
                            and ms.satisfied_at is null)
      """;

  private static final String MEMBERSHIPS_OF =
      """
      select m.id
        from entity_membership m
        join entity campaign on campaign.id = m.parent_id
        join campaign_start s on s.campaign_id = m.parent_id
       where m.child_id = :entityId
         and m.kind = 'CAMPAIGN'
         and m.claimed_at is null
         and campaign.status not in ('DONE', 'DROPPED')
       order by m.parent_id, m.id
      """;

  private static final String WAITING_ON =
      """
      select w.membership_id
        from campaign_membership_milestone w
        join entity_membership m on m.id = w.membership_id
       where w.milestone_id = :milestoneId
         and m.kind = 'CAMPAIGN'
         and m.claimed_at is null
       order by m.parent_id, m.position, m.id
      """;

  @Inject EntityMembershipRepository memberships;

  /**
   * Of {@code membershipIds}, the CAMPAIGN memberships that are unclaimed and whose milestones are
   * all met, in the order given. Flushes first, so a write through the session is seen.
   */
  @Transactional(TxType.MANDATORY)
  public List<String> satisfiedUnclaimed(Collection<String> membershipIds) {
    if (membershipIds == null || membershipIds.isEmpty()) {
      return List.of();
    }
    em().flush();
    Set<String> ordered = new LinkedHashSet<>(membershipIds);
    @SuppressWarnings("unchecked")
    List<String> found =
        em().createNativeQuery(SATISFIED).setParameter("ids", List.copyOf(ordered)).getResultList();
    Set<String> satisfied = Set.copyOf(found);
    List<String> answer = new ArrayList<>();
    for (String id : ordered) {
      if (satisfied.contains(id)) {
        answer.add(id);
      }
    }
    return List.copyOf(answer);
  }

  /**
   * <b>The unclaimed, satisfied CAMPAIGN memberships whose MEMBER is {@code entityId}</b>, of
   * campaigns started and neither DONE nor DROPPED — the retry arm off the member's own transition:
   * a member refused earlier (blocked, wrong status) may be dispatchable now while none of its
   * milestones will move again.
   */
  @Transactional(TxType.MANDATORY)
  public List<String> satisfiedUnclaimedMembershipsOf(String entityId) {
    if (entityId == null) {
      return List.of();
    }
    @SuppressWarnings("unchecked")
    List<String> ids =
        em().createNativeQuery(MEMBERSHIPS_OF).setParameter("entityId", entityId).getResultList();
    return satisfiedUnclaimed(ids);
  }

  /**
   * The unclaimed CAMPAIGN memberships that wait on {@code milestoneId} and are now satisfied —
   * what a {@code MilestoneMet} hands to the executor. A claimed membership is frozen and never
   * answered.
   */
  @Transactional(TxType.MANDATORY)
  public List<String> satisfiedUnclaimedWaitingOn(String milestoneId) {
    if (milestoneId == null) {
      return List.of();
    }
    @SuppressWarnings("unchecked")
    List<String> ids =
        em().createNativeQuery(WAITING_ON).setParameter("milestoneId", milestoneId).getResultList();
    return satisfiedUnclaimed(ids);
  }

  private EntityManager em() {
    return memberships.getEntityManager();
  }
}
