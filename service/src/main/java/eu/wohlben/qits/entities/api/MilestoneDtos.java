package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CriterionApprovalDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CriterionEvidenceDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>The milestone doors' JSON</b> (epic qits-615). Records only; {@link MilestoneViews} renders
 * them from {@code MilestoneService}'s views. A criterion's evidence and approval are the campaign
 * condition's own shapes ({@link CriterionEvidenceDto}, {@link CriterionApprovalDto}), since a
 * milestone's criteria are the columns a campaign condition had.
 */
public final class MilestoneDtos {

  private MilestoneDtos() {}

  /**
   * A milestone whole: its groups (OR'd) of criteria (AND'd), each with its own dates, and what
   * waits on it — the work entities attached to it and the campaign memberships it gates. {@code
   * satisfiedAt} is "met since", the earliest date of its holding groups, null while none holds;
   * {@code met} says the same as a boolean.
   */
  @Schema(name = "MilestoneDto", description = "A milestone, its condition and what waits on it.")
  public record MilestoneDto(
      String id,
      String projectId,
      String displayName,
      Instant createdAt,
      Instant satisfiedAt,
      boolean met,
      List<MilestoneGroupDto> groups,
      List<MilestoneWorkDto> entities,
      List<MilestoneMembershipDto> memberships) {}

  /** One OR'd group: it holds — since {@code satisfiedAt} — once every criterion in it latched. */
  @Schema(name = "MilestoneGroupDto", description = "One OR'd group of a milestone's condition.")
  public record MilestoneGroupDto(
      String id,
      int position,
      Instant createdAt,
      Instant satisfiedAt,
      List<MilestoneCriterionDto> criteria) {}

  /**
   * One AND'd criterion: its kind, its predicate, and its latch. {@code createdAt} is its
   * forward-only floor — no event from before it latches it — moved to the edit when its predicate
   * changes.
   */
  @Schema(name = "MilestoneCriterionDto", description = "One AND'd criterion of a milestone.")
  public record MilestoneCriterionDto(
      String id,
      String kind,
      Map<String, Object> predicate,
      boolean seeded,
      Instant createdAt,
      Instant satisfiedAt,
      CriterionEvidenceDto evidence,
      CriterionApprovalDto approval) {}

  /** A work entity waiting on the milestone. {@code qualifiedId} null when the slug is unreadable. */
  @Schema(name = "MilestoneWorkDto", description = "A work entity waiting on a milestone.")
  public record MilestoneWorkDto(
      String id, String qualifiedId, String archetype, String title, String status) {}

  /**
   * A campaign membership the milestone gates: the campaign, its member, whether it was claimed (a
   * claimed membership is frozen, whatever the milestone does later), and whether the milestone is
   * the membership's generated condition — the one {@code PUT …/members/{id}/condition} edits.
   */
  @Schema(name = "MilestoneMembershipDto", description = "A campaign membership waiting on a milestone.")
  public record MilestoneMembershipDto(
      String membershipId,
      String campaignId,
      String campaignQualifiedId,
      String campaignTitle,
      String entityId,
      String entityQualifiedId,
      Instant claimedAt,
      boolean generatedCondition) {}
}
