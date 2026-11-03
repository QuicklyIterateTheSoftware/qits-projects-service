package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.MilestoneDtos.MilestoneCriterionDto;
import eu.wohlben.qits.entities.api.MilestoneDtos.MilestoneDto;
import eu.wohlben.qits.entities.api.MilestoneDtos.MilestoneGroupDto;
import eu.wohlben.qits.entities.api.MilestoneDtos.MilestoneMembershipDto;
import eu.wohlben.qits.entities.api.MilestoneDtos.MilestoneWorkDto;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.milestone.CampaignMembershipMilestone;
import eu.wohlben.qits.entities.milestone.CampaignMembershipMilestoneRepository;
import eu.wohlben.qits.entities.milestone.Milestone;
import eu.wohlben.qits.entities.milestone.MilestoneCriterion;
import eu.wohlben.qits.entities.milestone.MilestoneService;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.ProjectService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>{@code MilestoneService}'s views as the doors' JSON</b> ({@link MilestoneDtos}). What waits on
 * a milestone is named by qualified id and title, so one read of the rows behind a batch of views
 * — the waiting entities, the memberships, their campaigns and members, and the waits' generated
 * flags — in a transaction of its own, a fixed handful of queries however many milestones, and one
 * slug lookup per project.
 */
@ApplicationScoped
public class MilestoneViews {

  @Inject ProjectService projectService;

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject CampaignMembershipMilestoneRepository waits;

  /** One milestone. */
  public MilestoneDto milestone(MilestoneService.View view) {
    return milestones(List.of(view)).get(0);
  }

  /** Milestones, in the order given. */
  public List<MilestoneDto> milestones(List<MilestoneService.View> views) {
    if (views.isEmpty()) {
      return List.of();
    }
    Rows rows = QuarkusTransaction.requiringNew().call(() -> rows(views));
    Set<String> projectIds = new HashSet<>();
    views.forEach(view -> projectIds.add(view.milestone().projectId));
    rows.entities().values().forEach(row -> projectIds.add(row.projectId));
    Map<String, String> slugs = projectService.slugsByIds(projectIds);
    List<MilestoneDto> answer = new ArrayList<>();
    for (MilestoneService.View view : views) {
      answer.add(dto(view, rows, slugs));
    }
    return answer;
  }

  private record Rows(
      Map<String, WorkEntity> entities,
      Map<String, EntityMembership> memberships,
      Set<String> generated) {}

  private Rows rows(List<MilestoneService.View> views) {
    Set<String> membershipIds = new LinkedHashSet<>();
    Set<String> milestoneIds = new LinkedHashSet<>();
    Set<String> entityIds = new LinkedHashSet<>();
    for (MilestoneService.View view : views) {
      milestoneIds.add(view.milestone().id);
      membershipIds.addAll(view.membershipIds());
      entityIds.addAll(view.entityIds());
    }
    Map<String, EntityMembership> edges = new LinkedHashMap<>();
    if (!membershipIds.isEmpty()) {
      for (EntityMembership edge :
          memberships.find("id in ?1", List.copyOf(membershipIds)).list()) {
        edges.put(edge.id, edge);
        entityIds.add(edge.parentId);
        entityIds.add(edge.childId);
      }
    }
    Map<String, WorkEntity> rows = new LinkedHashMap<>();
    for (WorkEntity row : entities.listByIds(entityIds)) {
      rows.put(row.id, row);
    }
    Set<String> generated = new HashSet<>();
    for (CampaignMembershipMilestone wait : waits.ofMilestones(milestoneIds)) {
      if (wait.generatedCondition) {
        generated.add(wait.membershipId + "/" + wait.milestoneId);
      }
    }
    return new Rows(rows, edges, generated);
  }

  private static MilestoneDto dto(
      MilestoneService.View view, Rows rows, Map<String, String> slugs) {
    Milestone milestone = view.milestone();
    List<MilestoneGroupDto> groups =
        view.groups().stream()
            .map(
                group ->
                    new MilestoneGroupDto(
                        group.group().id,
                        group.group().position,
                        group.group().createdAt,
                        group.group().satisfiedAt,
                        group.criteria().stream().map(MilestoneViews::criterion).toList()))
            .toList();
    List<MilestoneWorkDto> waiting = new ArrayList<>();
    for (String entityId : view.entityIds()) {
      WorkEntity row = rows.entities().get(entityId);
      if (row != null) {
        waiting.add(
            new MilestoneWorkDto(
                row.id,
                qualified(slugs, row),
                row.archetype.name(),
                row.title,
                row.status));
      }
    }
    List<MilestoneMembershipDto> gated = new ArrayList<>();
    for (String membershipId : view.membershipIds()) {
      EntityMembership edge = rows.memberships().get(membershipId);
      if (edge == null) {
        continue;
      }
      WorkEntity campaign = rows.entities().get(edge.parentId);
      WorkEntity member = rows.entities().get(edge.childId);
      gated.add(
          new MilestoneMembershipDto(
              edge.id,
              edge.parentId,
              campaign == null ? null : qualified(slugs, campaign),
              campaign == null ? null : campaign.title,
              edge.childId,
              member == null ? null : qualified(slugs, member),
              edge.claimedAt,
              rows.generated().contains(edge.id + "/" + milestone.id)));
    }
    return new MilestoneDto(
        milestone.id,
        milestone.projectId,
        milestone.displayName,
        milestone.createdAt,
        milestone.satisfiedAt,
        milestone.met(),
        groups,
        waiting,
        gated);
  }

  private static MilestoneCriterionDto criterion(MilestoneCriterion criterion) {
    return new MilestoneCriterionDto(
        criterion.id,
        criterion.kind.name(),
        criterion.decoded().asMap(),
        criterion.seeded,
        criterion.createdAt,
        criterion.satisfiedAt,
        CampaignViews.evidence(criterion),
        CampaignViews.approval(criterion));
  }

  private static String qualified(Map<String, String> slugs, WorkEntity row) {
    String slug = slugs.get(row.projectId);
    return slug == null ? null : QualifiedEntityIds.render(slug, row.number);
  }
}
