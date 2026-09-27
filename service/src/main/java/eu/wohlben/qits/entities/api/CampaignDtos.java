package eu.wohlben.qits.entities.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <b>The campaign doors' JSON</b> (qits-413), field for field the dossier page "Doors and DTOs".
 * Records only; {@link CampaignViews} renders them from {@code CampaignService}'s views. Each name is
 * unique in {@code docs/openapi.yml}, so adding them renumbers no other schema.
 */
public final class CampaignDtos {

  private CampaignDtos() {}

  /** A campaign in a project's listing. */
  public record CampaignSummaryDto(
      String id,
      long number,
      String qualifiedId,
      String projectId,
      String title,
      String status,
      boolean started,
      boolean active,
      int members) {}

  /** A campaign with its start and its members, ordered by position. */
  public record CampaignDto(
      String id,
      long number,
      String qualifiedId,
      String projectId,
      String slug,
      String title,
      String description,
      String status,
      CampaignStartDto start,
      List<CampaignMemberDto> members) {}

  /** A campaign's start; null on a campaign never started. */
  public record CampaignStartDto(
      Instant firstStartedAt, Instant startedAt, String startedBy, boolean active) {}

  /** One membership: the entity it gathers, its run record and its condition. */
  public record CampaignMemberDto(
      String membershipId,
      int position,
      CampaignMemberEntityDto entity,
      Instant claimedAt,
      boolean joinedRunning,
      Instant dispatchedAt,
      CampaignDispatchDto dispatch,
      String dispatchRefusal,
      Instant dispatchRefusedAt,
      String dispatchError,
      List<CriterionGroupDto> groups) {}

  /** The gathered entity, as a member row draws it. */
  public record CampaignMemberEntityDto(
      String id,
      String archetype,
      String qualifiedId,
      String title,
      String status,
      boolean blocked) {}

  /** Where the campaign's dispatch of a member landed; every field null until it has. */
  public record CampaignDispatchDto(String workspaceId, String branch, String agentLaunch) {}

  /** One OR'd group of a condition. */
  public record CriterionGroupDto(String id, List<CriterionDto> criteria) {}

  /** One AND'd criterion: its kind, its predicate as JSON, and its latch. */
  public record CriterionDto(
      String id,
      String kind,
      Map<String, Object> predicate,
      boolean seeded,
      Instant satisfiedAt,
      CriterionEvidenceDto evidence,
      CriterionApprovalDto approval) {}

  /** What latched a criterion: an event, or {@code STATE_AT_START} with no event id. */
  public record CriterionEvidenceDto(String eventId, String signature, String summary) {}

  /** Who approved an APPROVAL criterion, and what they said. */
  public record CriterionApprovalDto(String approvedBy, String note) {}
}
