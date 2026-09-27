package eu.wohlben.qits.projects.campaignhost;

import java.util.UUID;

/**
 * <b>A campaign member's condition now holds, and nobody has claimed it yet.</b> Fired by {@code
 * bus/CampaignCriteriaListener} once per membership whose DNF {@link
 * eu.wohlben.qits.entities.campaign.CampaignEvaluator#observe} or {@link
 * eu.wohlben.qits.entities.campaign.CampaignEvaluator#satisfiedUnclaimedMembershipsOf} answered
 * satisfied — inside the listener's own transaction, on the CDI event bus, never on qits-events.
 *
 * <p><b>Nothing observes this yet.</b> The executor that dispatches a satisfied member — the next
 * task in this epic — is an {@code @Observes(during = TransactionPhase.AFTER_SUCCESS)} listener, so
 * it runs only once the latch (and the membership rows it read) have actually committed, and never
 * when the listener's claim rolls back. Firing it here, one task ahead of anything reading it, is
 * deliberate: the fact and its dispatch are two different concerns, and the fact is what this task
 * is scoped to.
 *
 * <p>{@code membershipId} is the {@code entity_membership} row whose condition holds;
 * {@code evidenceEventId} is the frame that latched the criterion which tipped it over — the same id
 * {@link eu.wohlben.qits.entities.campaign.Observation.Observed#eventId()} carried in, and nothing
 * more than a trace edge for whoever eventually acts on this.
 */
public record CampaignMemberSatisfied(String membershipId, UUID evidenceEventId) {}
