package eu.wohlben.qits.projects.campaignhost;

import java.util.UUID;

/**
 * <b>A campaign member's condition now holds, and nobody has claimed it yet.</b> Fired by {@code
 * bus/CampaignCriteriaListener} once per membership whose DNF {@link
 * eu.wohlben.qits.entities.campaign.CampaignEvaluator#observe} or {@link
 * eu.wohlben.qits.entities.campaign.CampaignEvaluator#satisfiedUnclaimedMembershipsOf} answered
 * satisfied — inside the listener's own transaction, on the CDI event bus, never on qits-events.
 *
 * <p><b>{@link CampaignExecutor} observes it</b> (qits-417) with {@code @Observes(during =
 * TransactionPhase.AFTER_SUCCESS)}, so it runs only once the latch (and the membership rows it read)
 * have actually committed, and never when the listener's claim rolls back — and hands the membership
 * to {@link CampaignExecutor#tryDispatch} off the committing thread.
 *
 * <p>{@code membershipId} is the {@code entity_membership} row whose condition holds;
 * {@code evidenceEventId} is the frame that latched the criterion which tipped it over — the same id
 * {@link eu.wohlben.qits.entities.campaign.Observation.Observed#eventId()} carried in, and nothing
 * the cause the dispatch it leads to is stamped with.
 */
public record CampaignMemberSatisfied(String membershipId, UUID evidenceEventId) {}
