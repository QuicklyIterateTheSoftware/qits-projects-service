package eu.wohlben.qits.projects.campaignhost;

import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.DispatchMode;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * <b>A campaign's start press</b> (qits-417): what {@code POST /entities/{id}/dispatch} does when
 * the entity is a campaign. {@code EntityDispatchController} branches here; {@code EntityDispatch}
 * itself refuses a campaign, so no in-process path can cut a workspace for one.
 *
 * <ol>
 *   <li>{@code PHASE} is a 409 — a campaign presses dispatch only; a status other than REFINED or
 *       READY_FOR_DEV (qits-887: the two a campaign runs at, {@link
 *       EntityStateMachine#campaignRunsAt}) is a 409 (decided again under the campaign row's lock, in
 *       {@link CampaignService#start}); and so
 *       is a <b>blocked</b> campaign (qits-592) — somebody wrote down why it must wait, and a start
 *       press would be a sweep {@link CampaignExecutor} refuses member by member anyway. The block
 *       is decided here only, unlocked: a block landing after this check starts a campaign whose
 *       executor still claims nothing until it is cleared, which is the promise that matters.
 *   <li>Upsert {@code campaign_start} ({@link CampaignService#start}): {@code first_started_at} on the
 *       first press only; {@code started_at}, {@code started_by} and {@code active = true} on every
 *       one. That commits.
 *   <li>Then {@link CampaignService#latchFromCurrentState} — the in-flight hole — and then {@link
 *       CampaignExecutor#sweep(String)}, which tries every unclaimed member whose condition holds.
 * </ol>
 *
 * <p>A press while the campaign is running is allowed and is how a person re-checks every waiting
 * member at once. Resuming a paused campaign is a press too: moving it back to REFINED is not.
 * <b>Nor is moving it to READY_FOR_DEV a start</b> (qits-887, decision 19): that status means
 * "ready for development", and a campaign is started by this press alone, at either status.
 */
@ApplicationScoped
public class CampaignStarter {

  @Inject CampaignService campaigns;

  @Inject CampaignExecutor executor;

  @Inject ProjectChangePublisher publisher;

  /**
   * The press. Answers the campaign's progress as the sweep left it — the rows {@link
   * CampaignService#progress} reads, which the door renders as the progress read's own wrapper.
   *
   * @param actor who pressed; recorded as {@code started_by} and named in every member's dispatch
   */
  public CampaignService.ProgressRead start(WorkEntity campaign, DispatchMode mode, String actor) {
    if (campaign.archetype != Archetype.CAMPAIGN) {
      throw new IllegalArgumentException(campaign.id + " is a " + campaign.archetype);
    }
    if (mode != DispatchMode.FLOW) {
      throw new DomainException(
          409,
          "PHASE runs one phase of a ticket or an epic, and a campaign presses dispatch only — send"
              + " {\"mode\":\"FLOW\"} to start campaign "
              + campaign.id
              + ".");
    }
    if (!EntityStateMachine.campaignRunsAt(campaign.status)) {
      throw new DomainException(
          409, CampaignService.START_REFUSED.formatted(campaign.id, campaign.status));
    }
    if (campaign.blocked) {
      throw new DomainException(
          409,
          "Campaign "
              + campaign.id
              + " is blocked, so it is not started — something is in the way and the campaign's"
              + " thread says what. Clear the block once that is resolved, then dispatch.");
    }
    campaigns.start(campaign.id, actor == null || actor.isBlank() ? "unknown" : actor);
    publisher.fire(campaign.projectId, ProjectChangeHint.Topic.EPICS);
    campaigns.latchFromCurrentState(campaign.id);
    executor.sweep(campaign.id);
    return campaigns.progress(campaign.id);
  }
}
