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

  // --- the progress read (qits-418) ----------------------------------------------------------------

  /**
   * A campaign's progress: the campaign, whether anything is listening for the events its criteria
   * wait on, and every member's derived state — {@code GET /campaigns/{id}/progress} and the start
   * press answer it. Derived by {@link CampaignProgress}; nothing of it is stored.
   */
  public record CampaignProgressDto(
      CampaignProgressCampaignDto campaign,
      CampaignEvaluatorDto evaluator,
      List<CampaignMemberProgressDto> members) {}

  /** The campaign a progress read is of. */
  public record CampaignProgressCampaignDto(
      String id, String qualifiedId, String title, String status, CampaignStartDto start) {}

  /**
   * The criteria evaluator's health: {@code connected} is the event stream subscriber's live
   * connection, {@code lastSweepCompletedAt} and {@code stalled} the catch-up sweep's census — what
   * tells a correct wait from nothing listening.
   */
  public record CampaignEvaluatorDto(
      boolean connected, Instant lastSweepCompletedAt, boolean stalled) {}

  /** The eight words a member's state is, derived in this order, the first match winning. */
  public enum CampaignMemberState {
    DROPPED,
    DONE,
    DISPATCH_FAILED,
    JOINED_RUNNING,
    RUNNING,
    REFUSED,
    READY,
    WAITING
  }

  /** One member's progress: its state, what it waits for, its condition judged, its run record. */
  public record CampaignMemberProgressDto(
      String membershipId,
      int position,
      CampaignMemberEntityDto entity,
      CampaignMemberState state,
      List<String> waitsFor,
      boolean joinedRunning,
      List<CampaignGroupProgressDto> groups,
      Instant dispatchedAt,
      CampaignDispatchDto dispatch,
      String dispatchRefusal,
      Instant dispatchRefusedAt,
      String dispatchError) {}

  /** One OR'd group, and whether every one of its criteria is latched. */
  public record CampaignGroupProgressDto(
      String id, boolean satisfied, List<CampaignCriterionProgressDto> criteria) {}

  /**
   * One criterion judged: latched or not, the sentence that would latch it, whether it still can
   * (ENTITY_STATUS alone is ever judged unsatisfiable, and {@code reason} says why), and its
   * evidence once latched.
   */
  public record CampaignCriterionProgressDto(
      String id,
      String kind,
      boolean seeded,
      boolean satisfied,
      String wouldBeSatisfiedBy,
      boolean satisfiable,
      String reason,
      CriterionEvidenceDto evidence,
      CriterionApprovalDto approval,
      Instant satisfiedAt) {}
}
