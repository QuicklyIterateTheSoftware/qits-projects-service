package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDispatchDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberEntityDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignStartDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignSummaryDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CriterionApprovalDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CriterionDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CriterionEvidenceDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CriterionGroupDto;
import eu.wohlben.qits.entities.campaign.CampaignCriterion;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.campaign.CampaignStartRecord;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.campaignhost.CampaignEvaluatorHealth;
import eu.wohlben.qits.projects.control.ProjectService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;

/**
 * <b>{@code CampaignService}'s views as the doors' JSON</b> ({@link CampaignDtos}), each qualified id
 * rendered from ONE slug lookup: a campaign and every member it gathers are in one project (the add
 * refuses anything else), so a whole campaign costs one {@link ProjectService#slugsByIds} call.
 *
 * <p>Public and a bean of its own, rather than private methods on the controllers, because the
 * {@code repository} MCP server's campaign tools answer the same shapes.
 */
@ApplicationScoped
public class CampaignViews {

  @Inject ProjectService projectService;

  @Inject CampaignEvaluatorHealth health;

  /** A project's listing. */
  public List<CampaignSummaryDto> summaries(List<CampaignService.Summary> summaries) {
    if (summaries.isEmpty()) {
      return List.of();
    }
    String slug = slugOf(summaries.get(0).campaign().projectId);
    return summaries.stream()
        .map(
            summary -> {
              WorkEntity row = summary.campaign();
              CampaignStartRecord start = summary.start();
              return new CampaignSummaryDto(
                  row.id,
                  row.number,
                  qualified(slug, row),
                  row.projectId,
                  row.title,
                  row.status,
                  row.blocked,
                  start != null,
                  start != null && start.active,
                  summary.members());
            })
        .toList();
  }

  /** A whole campaign. */
  public CampaignDto campaign(CampaignService.Campaign campaign) {
    WorkEntity row = campaign.campaign();
    String slug = slugOf(row.projectId);
    CampaignStartRecord start = campaign.start();
    return new CampaignDto(
        row.id,
        row.number,
        qualified(slug, row),
        row.projectId,
        row.slug,
        row.title,
        row.description,
        row.status,
        row.blocked,
        start == null
            ? null
            : new CampaignStartDto(
                start.firstStartedAt, start.startedAt, start.startedBy, start.active),
        campaign.members().stream().map(member -> member(slug, member)).toList());
  }

  /** One member. */
  public CampaignMemberDto member(CampaignService.Member member) {
    return member(slugOf(member.entity() == null ? null : member.entity().projectId), member);
  }

  private CampaignMemberDto member(String slug, CampaignService.Member member) {
    EntityMembership edge = member.membership();
    WorkEntity entity = member.entity();
    return new CampaignMemberDto(
        edge.id,
        edge.position,
        entity == null
            ? null
            : new CampaignMemberEntityDto(
                entity.id,
                entity.archetype.name(),
                qualified(slug, entity),
                entity.title,
                entity.status,
                entity.blocked),
        edge.claimedAt,
        edge.joinedRunning,
        edge.dispatchedAt,
        new CampaignDispatchDto(
            edge.dispatchWorkspaceId, edge.dispatchBranch, edge.dispatchAgentLaunch),
        edge.dispatchRefusal,
        edge.dispatchRefusedAt,
        edge.dispatchError,
        member.groups().stream()
            .map(
                group ->
                    new CriterionGroupDto(
                        group.group().id,
                        group.criteria().stream().map(CampaignViews::criterion).toList()))
            .toList());
  }

  private static CriterionDto criterion(CampaignCriterion criterion) {
    return new CriterionDto(
        criterion.id,
        criterion.kind.name(),
        criterion.decoded().asMap(),
        criterion.seeded,
        criterion.satisfiedAt,
        evidence(criterion),
        approval(criterion));
  }

  /** What latched {@code criterion} — an event or {@code STATE_AT_START} — or null. */
  static CriterionEvidenceDto evidence(CampaignCriterion criterion) {
    return criterion.satisfiedAt != null && criterion.evidenceSignature != null
        ? new CriterionEvidenceDto(
            criterion.evidenceEventId == null ? null : criterion.evidenceEventId.toString(),
            criterion.evidenceSignature,
            criterion.evidenceSummary)
        : null;
  }

  /** Who approved {@code criterion}, and what they said — or null. */
  static CriterionApprovalDto approval(CampaignCriterion criterion) {
    return criterion.satisfiedAt != null && criterion.approvedBy != null
        ? new CriterionApprovalDto(criterion.approvedBy, criterion.approvalNote)
        : null;
  }

  /**
   * A campaign's progress (qits-418) — {@link CampaignProgress#derive} over the rows {@link
   * CampaignService#progress} read, qualified from the one slug, with the evaluator's health as it
   * stands now.
   */
  public CampaignProgressDto progress(CampaignService.ProgressRead read) {
    String slug = slugOf(read.campaign().campaign().projectId);
    return CampaignProgress.derive(read, row -> qualified(slug, row), health.now());
  }

  /** The project's slug, or null when it cannot be read — a decoration never fails the read. */
  private String slugOf(String projectId) {
    return projectId == null ? null : projectService.slugsByIds(Set.of(projectId)).get(projectId);
  }

  private static String qualified(String slug, WorkEntity row) {
    return slug == null ? null : QualifiedEntityIds.render(slug, row.number);
  }
}
