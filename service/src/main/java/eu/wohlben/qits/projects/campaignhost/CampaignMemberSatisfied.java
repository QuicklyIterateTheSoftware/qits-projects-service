package eu.wohlben.qits.projects.campaignhost;

import java.util.UUID;

/**
 * <b>A campaign member's milestones are all met, and nobody has claimed it yet.</b> Fired once per
 * membership by {@code bus/CampaignMilestoneListener} (a {@code MilestoneMet} it was waiting on) and
 * by {@code bus/CampaignCriteriaListener}'s retry arm ({@link
 * eu.wohlben.qits.entities.campaign.CampaignReadiness#satisfiedUnclaimedMembershipsOf}, the member
 * itself moved) — inside the listener's own transaction, on the CDI event bus, never on qits-events.
 *
 * <p><b>{@link CampaignExecutor} observes it</b> (qits-417) with {@code @Observes(during =
 * TransactionPhase.AFTER_SUCCESS)}, so it runs only once the latch (and the membership rows it read)
 * have actually committed, and never when the listener's claim rolls back — and hands the membership
 * to {@link CampaignExecutor#tryDispatch} off the committing thread.
 *
 * <p>{@code membershipId} is the {@code entity_membership} row whose condition holds;
 * {@code evidenceEventId} is the frame that handed it over — the {@code MilestoneMet}, or the
 * member's own {@code EntityTransitioned} — and the cause the dispatch it leads to is stamped with.
 */
public record CampaignMemberSatisfied(String membershipId, UUID evidenceEventId) {}
