package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.api.CampaignViews;
import eu.wohlben.qits.entities.api.EntitiesPrincipal;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.campaignhost.CampaignStarter;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * <b>The dispatch press and its read</b> (qits-970, epic qits-965), behind {@code
 * /work/{qualifiedId}/dispatch} ({@link WorkDispatchController}), which resolves its path to an
 * entity id and hands it here — the campaign branch and the one dispatch path, moved out of the
 * {@code /entities/{id}/dispatch} controller deleted in qits-976. Nothing here names a controller
 * class; the door wraps {@link Pressed} in its own answer record.
 */
@ApplicationScoped
public class DispatchDoors {

  @Inject EntityDispatch dispatch;

  /** A campaign's press is its start. */
  @Inject CampaignStarter starter;

  @Inject CampaignViews views;

  /**
   * What a press did: exactly one of the two is set — the dispatch made, or, on a campaign, its
   * progress as the start press left it.
   */
  public record Pressed(EntityDispatchDto dispatch, CampaignProgressDto progress) {}

  /**
   * The press. On a campaign it is the campaign's start ({@link CampaignStarter}); on anything else
   * the one dispatch path. The mode is read first, so a missing or unknown one is the 400 it always
   * was, then the entity's 404.
   */
  public Pressed press(SecurityIdentity identity, String entityId, String mode) {
    DispatchMode parsed = DispatchMode.parse(mode);
    String changedBy = EntitiesPrincipal.changedBy(identity);
    WorkEntity entity = dispatch.get(entityId); // 404
    if (entity.archetype == Archetype.CAMPAIGN) {
      CampaignService.ProgressRead started = starter.start(entity, parsed, changedBy);
      return new Pressed(null, views.progress(started));
    }
    return new Pressed(dispatch.dispatch(entityId, parsed, changedBy).toDto(), null);
  }

  /** The read: what a press would start now. */
  public EntityDispatchStateDto state(String entityId) {
    return dispatch.state(entityId);
  }
}
