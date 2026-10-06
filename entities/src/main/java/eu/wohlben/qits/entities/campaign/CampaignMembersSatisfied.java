package eu.wohlben.qits.entities.campaign;

import java.util.List;

/**
 * <b>An authoring write left these memberships satisfied and unclaimed</b> (qits-417): an approval,
 * or an add or a condition edit on a campaign whose start is active. Fired by {@link
 * CampaignService} on the CDI event bus inside the write's own transaction, so an observer declared
 * {@code during = AFTER_SUCCESS} — the service layer's campaign executor — runs only once the write
 * has committed, and never for an attempt that rolled back and was retried.
 *
 * <p>This module's own record rather than the service layer's {@code CampaignMemberSatisfied}
 * (which the bus listener fires), because this module depends on nothing above it. It carries no
 * evidence event id: nothing an approval or a state-at-start latch records is an event. Whether to
 * act — the campaign REFINED or READY_FOR_DEV, its start active, the member still unclaimed — is
 * the executor's own re-check, never this record's claim.
 */
public record CampaignMembersSatisfied(String campaignId, List<String> membershipIds) {}
