package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignCriterionProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDispatchDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignEvaluatorDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignGroupProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberState;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressCampaignDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignStartDto;
import eu.wohlben.qits.entities.campaign.CampaignCriterion;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.campaign.CampaignStartRecord;
import eu.wohlben.qits.entities.campaign.Conditions;
import eu.wohlben.qits.entities.campaign.CriterionKind;
import eu.wohlben.qits.entities.campaign.CriterionPredicate;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.control.EntityBlockState;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>A campaign's progress, derived</b> (qits-418): every word of {@link CampaignProgressDto} read
 * off the memberships, their criteria and the rows those criteria target. There is no tracker and
 * nothing here is stored, so the answer cannot drift from the rows it describes.
 *
 * <p>Pure: no database, no clock, no bean. The caller hands it the rows ({@link
 * CampaignService#progress}), a way to qualify a row ({@code qits-412}, or null when the project's
 * slug cannot be read) and the evaluator's health; {@code CampaignProgressTest} drives it over built
 * rows.
 *
 * <h2>The member's state — the first rule that applies</h2>
 *
 * <ol>
 *   <li>{@code DROPPED}: the member is DROPPED.
 *   <li>{@code DONE}: the member is VERIFIED or DONE, <b>whether or not this campaign claimed it</b>
 *       — work finished by hand or by another campaign is done here too.
 *   <li>{@code DISPATCH_FAILED}: claimed, and {@code dispatch_error} is set.
 *   <li>{@code JOINED_RUNNING}: claimed, and it joined running.
 *   <li>{@code RUNNING}: claimed, otherwise.
 *   <li>{@code REFUSED}: unclaimed, its condition holds, and {@code dispatch_refusal} is set.
 *   <li>{@code READY}: unclaimed and its condition holds — transient: the executor is about to act,
 *       or the campaign is paused.
 *   <li>{@code WAITING}: anything else.
 * </ol>
 *
 * <h2>When an ENTITY_STATUS criterion is unsatisfiable</h2>
 *
 * <p>Judged only while the criterion is unlatched — a latch never comes undone, so a latched one is
 * satisfiable by definition — in this order: its target is gone ({@code "target deleted"}), DROPPED
 * ({@code "target dropped"}, unless DROPPED is what it waits for), or no longer a member of this
 * campaign ({@code "target no longer a member"}); or, for an <em>unclaimed</em> member, the target is
 * already at or past the wanted status ({@code "<qid> is already <status>; press start to latch it
 * from the current state"}) — the state-at-start latch walks unclaimed members only, so the advice
 * would be false on a claimed one. An event kind is never judged unsatisfiable: the platform cannot
 * know a deploy will not come.
 */
public final class CampaignProgress {

  private CampaignProgress() {}

  /**
   * The whole progress read.
   *
   * @param qualified a row's {@code qits-412}, or null when it cannot be rendered
   */
  public static CampaignProgressDto derive(
      CampaignService.ProgressRead read,
      Function<WorkEntity, String> qualified,
      CampaignEvaluatorDto evaluator) {
    CampaignService.Campaign campaign = read.campaign();
    WorkEntity row = campaign.campaign();
    CampaignStartRecord start = campaign.start();
    Set<String> memberIds = new LinkedHashSet<>();
    for (CampaignService.Member member : campaign.members()) {
      memberIds.add(member.membership().childId);
    }
    Context context = new Context(read.targets(), memberIds, qualified);
    EntityBlockState block = EntityBlockState.of(row);
    return new CampaignProgressDto(
        new CampaignProgressCampaignDto(
            row.id,
            qualified.apply(row),
            row.title,
            row.status,
            block.blocked(),
            start == null
                ? null
                : new CampaignStartDto(
                    start.firstStartedAt, start.startedAt, start.startedBy, start.active),
            block.source(),
            block.reason(),
            block.blockedBy()),
        evaluator,
        campaign.members().stream().map(member -> member(member, context)).toList());
  }

  /** The member's state — see the class javadoc for the order. */
  public static CampaignMemberState stateOf(CampaignService.Member member) {
    EntityMembership edge = member.membership();
    String status = member.entity() == null ? null : member.entity().status;
    if (EntityStatus.DROPPED.name().equals(status)) {
      return CampaignMemberState.DROPPED;
    }
    if (EntityStatus.VERIFIED.name().equals(status) || EntityStatus.DONE.name().equals(status)) {
      return CampaignMemberState.DONE;
    }
    if (edge.claimedAt != null) {
      if (edge.dispatchError != null) {
        return CampaignMemberState.DISPATCH_FAILED;
      }
      return edge.joinedRunning ? CampaignMemberState.JOINED_RUNNING : CampaignMemberState.RUNNING;
    }
    if (member.satisfied()) {
      return edge.dispatchRefusal != null
          ? CampaignMemberState.REFUSED
          : CampaignMemberState.READY;
    }
    return CampaignMemberState.WAITING;
  }

  /** The entity ids a member's criteria target, each once, in criteria order. */
  public static List<String> waitsFor(CampaignService.Member member) {
    Set<String> ids = new LinkedHashSet<>();
    for (CampaignService.Group group : member.groups()) {
      for (CampaignCriterion criterion : group.criteria()) {
        if (criterion.decoded() instanceof CriterionPredicate.EntityStatusIs target) {
          ids.add(target.entityId());
        }
      }
    }
    return List.copyOf(ids);
  }

  /**
   * The sentence that would latch {@code predicate}: {@code "qits-280 (Campaigns in the SPA) reaches
   * VERIFIED"}, {@code "DeploymentActive of qits-projects at or above 2026.930.1 in dev"}, {@code
   * "SCMRelease of qits-qits at or above 2026.930.1"}, {@code "a person approves"}.
   */
  public static String wouldBeSatisfiedBy(
      CriterionPredicate predicate,
      Map<String, WorkEntity> targets,
      Function<WorkEntity, String> qualified) {
    return switch (predicate) {
      case CriterionPredicate.EntityStatusIs p -> {
        WorkEntity target = targets.get(p.entityId());
        yield (target == null
                ? p.entityId()
                : qid(target, qualified) + " (" + target.title + ")")
            + " reaches "
            + p.status();
      }
      case CriterionPredicate.DeploymentActive p ->
          "DeploymentActive of "
              + p.applicationName()
              + floor(p.minimumVersion())
              + (p.environmentName() == null ? "" : " in " + p.environmentName());
      case CriterionPredicate.ScmRelease p ->
          "SCMRelease of "
              + p.repositoryName()
              + floor(p.minimumVersion())
              + (p.projectId() == null ? "" : " in project " + p.projectId());
      case CriterionPredicate.Approval p -> "a person approves";
    };
  }

  // --- plumbing -------------------------------------------------------------------------------------

  /** What every criterion of one campaign is judged against. */
  private record Context(
      Map<String, WorkEntity> targets,
      Set<String> memberIds,
      Function<WorkEntity, String> qualified) {}

  private static CampaignMemberProgressDto member(CampaignService.Member member, Context context) {
    EntityMembership edge = member.membership();
    WorkEntity entity = member.entity();
    boolean unclaimed = edge.claimedAt == null;
    List<CampaignGroupProgressDto> groups = new ArrayList<>();
    for (CampaignService.Group group : member.groups()) {
      groups.add(
          new CampaignGroupProgressDto(
              group.group().id,
              Conditions.satisfied(List.of(group.criteria())),
              group.criteria().stream()
                  .map(criterion -> criterion(criterion, unclaimed, context))
                  .toList()));
    }
    return new CampaignMemberProgressDto(
        edge.id,
        edge.position,
        entity == null
            ? null
            : CampaignViews.memberEntity(entity, context.qualified().apply(entity)),
        stateOf(member),
        waitsFor(member),
        edge.joinedRunning,
        List.copyOf(groups),
        edge.dispatchedAt,
        new CampaignDispatchDto(
            edge.dispatchWorkspaceId, edge.dispatchBranch, edge.dispatchAgentLaunch),
        edge.dispatchRefusal,
        edge.dispatchRefusedAt,
        edge.dispatchError);
  }

  private static CampaignCriterionProgressDto criterion(
      CampaignCriterion criterion, boolean unclaimed, Context context) {
    CriterionPredicate predicate = criterion.decoded();
    boolean satisfied = criterion.satisfiedAt != null;
    String reason =
        satisfied || criterion.kind != CriterionKind.ENTITY_STATUS
            ? null
            : unsatisfiable((CriterionPredicate.EntityStatusIs) predicate, unclaimed, context);
    return new CampaignCriterionProgressDto(
        criterion.id,
        criterion.kind.name(),
        criterion.seeded,
        satisfied,
        wouldBeSatisfiedBy(predicate, context.targets(), context.qualified()),
        reason == null,
        reason,
        CampaignViews.evidence(criterion),
        CampaignViews.approval(criterion),
        criterion.satisfiedAt);
  }

  /** Why an unlatched ENTITY_STATUS criterion can no longer latch by itself, or null if it can. */
  private static String unsatisfiable(
      CriterionPredicate.EntityStatusIs wanted, boolean unclaimed, Context context) {
    WorkEntity target = context.targets().get(wanted.entityId());
    if (target == null) {
      return "target deleted";
    }
    if (EntityStatus.DROPPED.name().equals(target.status)
        && !EntityStatus.DROPPED.name().equals(wanted.status())) {
      return "target dropped";
    }
    if (!context.memberIds().contains(target.id)) {
      return "target no longer a member";
    }
    if (unclaimed && CampaignService.reached(target.status, wanted.status())) {
      return qid(target, context.qualified())
          + " is already "
          + target.status
          + "; press start to latch it from the current state";
    }
    return null;
  }

  /** {@code qits-412}, or {@code #412} when the slug cannot be read — never null. */
  private static String qid(WorkEntity row, Function<WorkEntity, String> qualified) {
    String rendered = qualified.apply(row);
    return rendered != null ? rendered : "#" + row.number;
  }

  private static String floor(String minimumVersion) {
    return minimumVersion == null ? "" : " at or above " + minimumVersion;
  }
}
