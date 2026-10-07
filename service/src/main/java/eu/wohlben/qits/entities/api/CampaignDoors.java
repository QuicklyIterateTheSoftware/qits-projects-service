package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.CampaignInFlight;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/**
 * <b>One campaign's doors</b> (qits-970, epic qits-965): the rules behind {@code
 * /work/{qualifiedId}/members…} and {@code …/progress} ({@link WorkMembersController}, {@link
 * WorkProgressController}), moved here from the {@code /campaigns/{id}/…} controller that served them
 * first and was deleted in qits-976. Every method takes the campaign's <b>id</b> — the surface
 * resolves its own path first — and answers DTOs; the controller wraps them in its own answer
 * records. {@code CampaignMcpTools}' {@code set_campaign_member_condition} reads its argument
 * through {@link ConditionGroup} and {@link #toGroupSpecs}.
 *
 * <ul>
 *   <li><b>Every write but approve binds a {@code qits:agent} caller</b> to the campaign's project:
 *       the campaign is resolved first (an unknown id is its 404), its project checked before the
 *       write ({@link EntitiesAgentAccess}).
 *   <li><b>Approve is a person alone</b>: {@link PersonCheck}, and the actor is the name from that
 *       proof, never a body field nor a forwarded header.
 *   <li><b>Every write fires the {@code epics} hint</b>, after the service has returned.
 * </ul>
 */
@ApplicationScoped
public class CampaignDoors {

  @Inject CampaignService campaigns;

  @Inject CampaignViews views;

  @Inject WorkEntityService workEntities;

  @Inject CampaignInFlight inFlight;

  @Inject ProjectChangePublisher publisher;

  @Inject PersonCheck persons;

  /** The campaign with its start and its members in campaign order. */
  public CampaignDto campaign(String campaignId) {
    return views.campaign(campaigns.get(campaignId));
  }

  /** How the campaign is doing (qits-418): derived on every read; nothing stored. */
  public CampaignProgressDto progress(String campaignId) {
    return views.progress(campaigns.progress(campaignId));
  }

  /**
   * A new membership. {@code inFlight} null means the service decides: true when the member is
   * IMPLEMENTING or later, or an ACTIVE workspace stands on its branch.
   */
  public CampaignMemberDto addMember(
      SecurityIdentity identity,
      String campaignId,
      String entityId,
      Integer position,
      Boolean explicitInFlight) {
    String projectId = bind(identity, campaignId);
    boolean running = inFlight.resolve(explicitInFlight, campaigns.entity(entityId));
    CampaignService.Member added =
        campaigns.addMember(
            campaignId, entityId, position, running, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.member(added);
  }

  /** A membership moved to {@code position}; answers the campaign as the move left it. */
  public CampaignDto moveMember(
      SecurityIdentity identity, String campaignId, String membershipId, Integer position) {
    String projectId = bind(identity, campaignId);
    CampaignService.Campaign moved =
        campaigns.moveMember(
            campaignId, membershipId, position, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.campaign(moved);
  }

  /** A membership removed: 409 once claimed, and while another member's criterion targets it. */
  public void removeMember(SecurityIdentity identity, String campaignId, String membershipId) {
    String projectId = bind(identity, campaignId);
    campaigns.removeMember(campaignId, membershipId, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
  }

  /** A membership's whole condition, PUT semantics; an empty list waits on nothing. */
  public CampaignMemberDto setCondition(
      SecurityIdentity identity,
      String campaignId,
      String membershipId,
      List<CampaignService.GroupSpec> groups) {
    String projectId = bind(identity, campaignId);
    CampaignService.Member member =
        campaigns.setCondition(
            campaignId, membershipId, groups, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.member(member);
  }

  /**
   * A person's yes on an APPROVAL criterion — a person this service verified itself ({@link
   * PersonCheck}, 403 otherwise), whose name is what is recorded.
   */
  public CampaignMemberDto approve(
      String campaignId, String membershipId, String criterionId, String note) {
    String approver = persons.requireAdmin();
    String projectId = workEntities.get(Archetype.CAMPAIGN, campaignId).projectId;
    CampaignService.Approved approved =
        campaigns.approve(campaignId, membershipId, criterionId, note, approver);
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.member(approved.member());
  }

  /** One criterion of a condition: {@code id} restates an existing one and keeps its latch. */
  public record ConditionCriterion(String id, String kind, Map<String, Object> predicate) {}

  /** One OR'd group of a condition: the MCP tool argument's shape. */
  public record ConditionGroup(List<ConditionCriterion> criteria) {}

  /**
   * The tool argument's shape of a condition, translated into {@link CampaignService}'s own spec —
   * {@code CampaignMcpTools}' {@code set_campaign_member_condition}, which takes the {@link
   * ConditionGroup}/{@link ConditionCriterion} records as tool arguments. Ids are handed on as
   * written: the tool reads UUIDs, as it always has.
   */
  public static List<CampaignService.GroupSpec> toGroupSpecs(List<ConditionGroup> groups) {
    return groups == null
        ? List.of()
        : groups.stream()
            .map(
                group ->
                    group == null || group.criteria() == null
                        ? new CampaignService.GroupSpec(List.of())
                        : new CampaignService.GroupSpec(
                            group.criteria().stream()
                                .map(
                                    criterion ->
                                        criterion == null
                                            ? null
                                            : new CampaignService.CriterionSpec(
                                                criterion.id(),
                                                criterion.kind(),
                                                criterion.predicate()))
                                .toList()))
            .toList();
  }

  /** The campaign's project — its 404 first — with a bound agent held to it. */
  private String bind(SecurityIdentity identity, String campaignId) {
    String projectId = workEntities.get(Archetype.CAMPAIGN, campaignId).projectId;
    EntitiesAgentAccess.requireProject(identity, projectId);
    return projectId;
  }
}
