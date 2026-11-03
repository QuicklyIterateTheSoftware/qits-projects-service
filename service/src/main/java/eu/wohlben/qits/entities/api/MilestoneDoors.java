package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.api.MilestoneDtos.MilestoneDto;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.milestone.MilestoneRef;
import eu.wohlben.qits.entities.milestone.MilestoneService;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The milestone doors' rules</b> (epic qits-615), behind {@link ProjectMilestonesController},
 * {@link MilestoneController}, {@link WorkMilestonesController} and the membership door on {@link
 * WorkMembersController}. The campaign doors' rules ({@link CampaignDoors}), restated for a
 * milestone:
 *
 * <ul>
 *   <li><b>Every write but approve binds a {@code qits:agent} caller</b> (and the delivery desk) to
 *       the project the write touches — the milestone's, the work entity's, the campaign's — read
 *       first, so an id naming nothing is its 404 before any 403 ({@link EntitiesAgentAccess}).
 *   <li><b>Approve is a person</b> ({@link PersonCheck#requireAdminOrDesk}: a verified person, or
 *       the milestone's own project's delivery desk), and the actor recorded is the proof's.
 *   <li><b>Every write fires the {@code epics} hint</b> after the service returned. MilestoneMet and
 *       MilestoneUnmet are the service's own, published after commit.
 *   <li><b>An ENTITY_STATUS predicate's {@code entityId} takes a qualified id</b>, resolved here; one
 *       naming nothing is handed on for the service's own 400.
 * </ul>
 */
@ApplicationScoped
public class MilestoneDoors {

  @Inject MilestoneService milestones;

  @Inject MilestoneViews views;

  @Inject EntityIdResolver ids;

  @Inject CampaignService campaigns;

  @Inject CampaignViews campaignViews;

  @Inject WorkEntityService workEntities;

  @Inject ProjectChangePublisher publisher;

  @Inject PersonCheck persons;

  /** One criterion as the wire states it. */
  public record CriterionSpec(String id, String kind, Map<String, Object> predicate) {}

  /** One OR'd group as the wire states it. */
  public record GroupSpec(List<CriterionSpec> criteria) {}

  // --- milestones -----------------------------------------------------------------------------

  /** A project's milestones, oldest first; {@code met} narrows to the met or the unmet ones. */
  public List<MilestoneDto> list(String project, Boolean met) {
    String projectId = ids.resolveProject(project).id;
    return views.milestones(
        milestones.listByProject(projectId).stream()
            .filter(view -> met == null || view.milestone().met() == met)
            .toList());
  }

  /** One milestone, or a 404. */
  public MilestoneDto get(String milestoneId) {
    return views.milestone(milestones.get(milestoneId));
  }

  /** A new milestone of {@code project}, latched from the current state at once. */
  public MilestoneDto create(
      SecurityIdentity identity, String project, String displayName, List<GroupSpec> groups) {
    String projectId = ids.resolveProject(project).id;
    EntitiesAgentAccess.requireProject(identity, projectId);
    MilestoneService.Edited created =
        milestones.create(
            projectId, displayName, specs(groups), EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.milestone(created.view());
  }

  /** A milestone renamed. */
  public MilestoneDto rename(SecurityIdentity identity, String milestoneId, String displayName) {
    String projectId = bind(identity, milestoneId);
    MilestoneService.View renamed = milestones.rename(milestoneId, displayName);
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.milestone(renamed);
  }

  /** A milestone's groups replaced whole. */
  public MilestoneDto replaceGroups(
      SecurityIdentity identity, String milestoneId, List<GroupSpec> groups) {
    String projectId = bind(identity, milestoneId);
    MilestoneService.Edited edited =
        milestones.replaceGroups(milestoneId, specs(groups), EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.milestone(edited.view());
  }

  /** A person's yes on an APPROVAL criterion; the milestone is read first, so none is a 404. */
  public MilestoneDto approve(String milestoneId, String criterionId, String note) {
    String projectId = milestones.get(milestoneId).milestone().projectId;
    String approver = persons.requireAdminOrDesk(projectId);
    MilestoneService.Edited approved = milestones.approve(milestoneId, criterionId, note, approver);
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return views.milestone(approved.view());
  }

  // --- a work entity's milestones ---------------------------------------------------------------

  /** What {@code entity} waits on, in the order attached. */
  public List<MilestoneRef> ofEntity(WorkEntity entity) {
    return MilestoneRef.of(milestones.ofEntity(entity.id));
  }

  /** {@code entity} waits on the milestone too; answers what it waits on now. */
  public List<MilestoneRef> attach(SecurityIdentity identity, WorkEntity entity, String milestoneId) {
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
    milestones.attach(entity.id, milestoneId);
    publisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
    return ofEntity(entity);
  }

  /**
   * {@code entity} no longer waits on the milestone; answers what it waits on now. A milestone it
   * does not wait on is a 404, so a typo is not a silent success.
   */
  public List<MilestoneRef> detach(SecurityIdentity identity, WorkEntity entity, String milestoneId) {
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
    if (milestones.ofEntity(entity.id).stream().noneMatch(m -> m.id.equals(milestoneId))) {
      throw new NotFoundException(
          "Entity " + entity.id + " does not wait on milestone " + milestoneId);
    }
    milestones.detach(entity.id, milestoneId);
    publisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
    return ofEntity(entity);
  }

  // --- a campaign membership's milestones -------------------------------------------------------

  /** What a membership of {@code campaignId} waits on, set whole; answers the member. */
  public CampaignMemberDto setMemberMilestones(
      SecurityIdentity identity, String campaignId, String membershipId, List<String> milestoneIds) {
    String projectId = workEntities.get(Archetype.CAMPAIGN, campaignId).projectId;
    EntitiesAgentAccess.requireProject(identity, projectId);
    milestones.setMembershipMilestones(campaignId, membershipId, milestoneIds);
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    CampaignService.Campaign campaign = campaigns.get(campaignId);
    return campaign.members().stream()
        .filter(member -> member.membership().id.equals(membershipId))
        .findFirst()
        .map(member -> campaignViews.member(member, campaign.campaign().status))
        .orElseThrow(
            () -> new NotFoundException("No member " + membershipId + " in campaign " + campaignId));
  }

  // --- plumbing -------------------------------------------------------------------------------

  /** The milestone's project — its 404 first — with a bound agent held to it. */
  private String bind(SecurityIdentity identity, String milestoneId) {
    String projectId = milestones.get(milestoneId).milestone().projectId;
    EntitiesAgentAccess.requireProject(identity, projectId);
    return projectId;
  }

  private List<MilestoneService.GroupSpec> specs(List<GroupSpec> groups) {
    if (groups == null) {
      return List.of();
    }
    return groups.stream()
        .map(
            group ->
                group == null || group.criteria() == null
                    ? new MilestoneService.GroupSpec(List.of())
                    : new MilestoneService.GroupSpec(
                        group.criteria().stream()
                            .map(
                                criterion ->
                                    criterion == null
                                        ? null
                                        : new MilestoneService.CriterionSpec(
                                            criterion.id(),
                                            criterion.kind(),
                                            predicate(criterion.predicate())))
                            .toList()))
        .toList();
  }

  private Map<String, Object> predicate(Map<String, Object> predicate) {
    if (predicate == null || !(predicate.get("entityId") instanceof String named)) {
      return predicate;
    }
    Map<String, Object> resolved = new LinkedHashMap<>(predicate);
    try {
      resolved.put("entityId", ids.resolve(named).id);
    } catch (NotFoundException unknown) {
      // Left as named: the service says what is wrong with it.
    }
    return resolved;
  }
}
