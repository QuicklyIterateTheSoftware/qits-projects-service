package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.campaign.CampaignStartRecordRepository;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * <b>A campaign's status, derived from its members, and stored</b> (qits-650) — the one exception
 * to "a status is asked for". Every campaign word is a statement about its members, so it is
 * written by this class and by nothing a person presses; the rule is {@link #derive}, the one
 * place it is stated:
 *
 * <ul>
 *   <li>each member's status is read on the {@linkplain EntityStateMachine#campaignWalk campaign
 *       walk} {@code REPORTED < REFINED < READY_FOR_DEV < IMPLEMENTED < VERIFIED < DONE}, with
 *       IMPLEMENTING counting as READY_FOR_DEV and VERIFYING as IMPLEMENTED ({@link
 *       EntityStateMachine#onCampaignWalk}) — the last word achieved;
 *   <li>DROPPED members are not counted; the lowest counted word is the campaign's;
 *   <li>no members at all → REPORTED; every member DROPPED → DROPPED.
 * </ul>
 *
 * <p><b>DONE and DROPPED are never recomputed.</b> DONE is final; DROPPED — set by a person or
 * derived — stays until a person reopens it, and the reopen (DROPPED → REPORTED, one of the two
 * manual campaign moves) recomputes at once, because the campaign is then at REPORTED again.
 *
 * <h2>Called in the writer's transaction</h2>
 *
 * <p>Every write that can change a campaign's floor calls {@link #recompute} or {@link
 * #recomputeCampaignsOf} inside its own hold: {@code WorkEntityService}'s status move (and every
 * cascade it carries), its delete of a member ({@code leaveCampaigns}), {@code
 * EntityTransitionService}'s whole-state write (a member's status or its archetype), and {@code
 * CampaignService}'s add and remove. The answer ({@link Recomputed}) is announced by the writer
 * after its hold, never inside it — the hold's body re-runs on a retry.
 *
 * <p>A derived change is a move like any other on the wire, but not in its side effects: it writes
 * {@code statusBefore}, is audited as {@link #CHANGED_BY}, and is announced as an {@code
 * EntityTransitioned} — and it <b>leaves the campaign's blocks alone</b> (a block is a person's
 * statement about the campaign, not about a phase the word starts) and <b>runs no pause hook</b>,
 * except that a derived move into DONE or DROPPED pauses the start: a finished or dropped campaign
 * runs nothing, and a reopen must not quietly resume a run nobody pressed.
 *
 * <h2>Locks</h2>
 *
 * <p>The campaign row is taken {@code PESSIMISTIC_WRITE} before its members are read, every
 * campaign of one write in id order, so two writes recomputing the same campaigns queue in one
 * order. {@code CampaignService} takes the campaign row first and then its membership rows; this
 * class takes the campaign row and <b>no membership row and no member row</b> — it reads both as
 * values. So a member's move, which holds the member's row and then asks for the campaign row,
 * waits for an add or a remove to finish, and the add or remove never waits on the member's row:
 * there is no cycle. Under read committed every statement after the lock sees what committed
 * before it, so whichever of the two takes the campaign row second derives from the other's rows.
 * The pause it makes on a derived DONE takes the start row after the campaign row, the order {@code
 * CampaignService.start} and the manual pause hook take them in.
 */
@ApplicationScoped
public class CampaignStatusRecompute {

  /** Who a derived change is recorded as: the system, never the person whose write caused it. */
  public static final String CHANGED_BY = "system";

  @Inject WorkEntityRepository entities;

  @Inject CampaignStartRecordRepository campaignStarts;

  @Inject AuditService auditService;

  @Inject WritePatience writes;

  /** A derived campaign may carry an idea (qits-1792), so its move re-derives that idea too. */
  @Inject IdeaStatusRecompute ideaStatus;

  @Inject IdeaAnnouncements ideaAnnouncements;

  /** The announcement seam, optional like every port here. */
  @Inject Instance<TransitionAnnouncer> announcer;

  // --- the rule ---------------------------------------------------------------------------------

  /** The status a campaign has when its members stand at {@code memberStatuses}. */
  public static EntityStatus derive(Collection<String> memberStatuses) {
    List<EntityStatus> walk = EntityStateMachine.campaignWalk();
    int members = 0;
    EntityStatus lowest = null;
    for (String word : memberStatuses) {
      Optional<EntityStatus> status = parse(word);
      if (status.isEmpty()) {
        continue;
      }
      members++;
      Optional<EntityStatus> mapped = EntityStateMachine.onCampaignWalk(status.get());
      if (mapped.isPresent()
          && (lowest == null || walk.indexOf(mapped.get()) < walk.indexOf(lowest))) {
        lowest = mapped.get();
      }
    }
    if (members == 0) {
      return EntityStatus.REPORTED;
    }
    return lowest == null ? EntityStatus.DROPPED : lowest;
  }

  /** Whether a campaign at {@code campaignStatus} is still derived: it is neither DONE nor DROPPED. */
  public static boolean isDerivedAt(String campaignStatus) {
    return campaignStatus != null
        && !EntityStatus.DONE.name().equals(campaignStatus)
        && !EntityStatus.DROPPED.name().equals(campaignStatus);
  }

  /**
   * <b>Whether a member at {@code memberStatus} sets the floor of a campaign at {@code
   * campaignStatus}</b> — the {@code heldBy} rule: the member is not DROPPED, the campaign is still
   * derived, and the member's word on the campaign walk is the campaign's.
   */
  public static boolean holds(String campaignStatus, String memberStatus) {
    if (!isDerivedAt(campaignStatus)) {
      return false;
    }
    return parse(memberStatus)
        .flatMap(EntityStateMachine::onCampaignWalk)
        .map(mapped -> mapped.name().equals(campaignStatus))
        .orElse(false);
  }

  // --- in a writer's transaction ----------------------------------------------------------------

  /** What a recompute changed: the campaigns it moved, and the ideas those moves re-derived. */
  public record Recomputed(List<TransitionedEntity> moved, List<IdeaChange> ideas) {

    public static Recomputed none() {
      return new Recomputed(List.of(), List.of());
    }

    public boolean isEmpty() {
      return moved.isEmpty() && ideas.isEmpty();
    }
  }

  /**
   * <b>Inside a write's transaction</b>: every campaign {@code memberIds} have joined, recomputed —
   * see {@link #recompute}. A member that joined none changes nothing and costs one query.
   */
  public Recomputed recomputeCampaignsOf(Collection<String> memberIds) {
    List<String> ids = new ArrayList<>();
    for (String id : memberIds) {
      if (id != null) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return Recomputed.none();
    }
    em().flush();
    return recompute(entities.campaignIdsGathering(ids));
  }

  /**
   * <b>Takes the named campaigns' rows</b> {@code PESSIMISTIC_WRITE}, in id order, before a writer
   * touches their membership rows — what a delete of a member does: it removes the member's
   * campaign edges and closes their gaps, which locks membership rows, and a campaign row asked for
   * only afterwards would be the opposite of {@code CampaignService}'s order (campaign row, then
   * memberships) and could deadlock against an add. {@link #recompute} re-takes the same locks,
   * which costs nothing.
   */
  public void lock(Collection<String> campaignIds) {
    TreeSet<String> ordered = new TreeSet<>();
    for (String id : campaignIds) {
      if (id != null) {
        ordered.add(id);
      }
    }
    for (String id : ordered) {
      WorkEntity campaign = em().find(WorkEntity.class, id);
      if (campaign != null) {
        em().refresh(campaign, LockModeType.PESSIMISTIC_WRITE);
      }
    }
  }

  /**
   * <b>Inside a write's transaction</b>: each named campaign (nulls and repeats ignored) locked in
   * id order, re-derived from its members as they now stand, and written where the stored status
   * differs — see the class javadoc for what a derived change does and does not do. An id naming
   * no campaign is skipped; a campaign at DONE or DROPPED is left as it is.
   */
  public Recomputed recompute(Collection<String> campaignIds) {
    TreeSet<String> ordered = new TreeSet<>();
    for (String id : campaignIds) {
      if (id != null) {
        ordered.add(id);
      }
    }
    if (ordered.isEmpty()) {
      return Recomputed.none();
    }
    // The writer's own changes first, so the lock's refresh below loses none of them and the member
    // read sees them.
    em().flush();
    List<TransitionedEntity> moved = new ArrayList<>();
    List<String> inspiring = new ArrayList<>();
    for (String id : ordered) {
      WorkEntity campaign = em().find(WorkEntity.class, id);
      if (campaign == null || campaign.archetype != Archetype.CAMPAIGN) {
        continue;
      }
      em().refresh(campaign, LockModeType.PESSIMISTIC_WRITE);
      if (!isDerivedAt(campaign.status)) {
        continue;
      }
      EntityStatus derived = derive(entities.campaignMemberStatuses(id));
      if (derived.name().equals(campaign.status)) {
        continue;
      }
      String before = campaign.status;
      campaign.status = derived.name();
      if (derived == EntityStatus.DONE || derived == EntityStatus.DROPPED) {
        // A finished or dropped campaign runs nothing, and its reopen must not resume a run nobody
        // pressed: the start pauses here, in this transaction, as the manual drop's does.
        campaignStarts.pause(id);
      }
      if (derived == EntityStatus.DROPPED) {
        // A pre-approval does not outlive the work that was decided against (qits-1075's rule on
        // every door that lands DROPPED). Why it closed (qits-248): every member was decided
        // against, so the campaign is OBSOLETE — no successor is named, none was stated.
        campaign.preApprovedBy = null;
        campaign.outcome = WorkOutcome.OBSOLETE;
        campaign.supersededByEntityId = null;
      }
      em().flush();
      auditService.record(
          AuditEntityType.of(campaign.archetype),
          campaign.id,
          campaign.id,
          AuditOperation.UPDATE,
          CHANGED_BY,
          campaign);
      moved.add(TransitionedEntity.of(campaign, null, before, CHANGED_BY));
      inspiring.add(campaign.inspiredByIdeaId);
    }
    if (moved.isEmpty()) {
      return Recomputed.none();
    }
    return new Recomputed(List.copyOf(moved), ideaStatus.recompute(inspiring, CHANGED_BY));
  }

  // --- after a writer's hold --------------------------------------------------------------------

  /**
   * Announces what a recompute changed — after the writer's hold, never inside it. A writer that
   * announces a batch of its own joins {@link Recomputed#moved} to it instead, and hands only the
   * idea changes here ({@link #announceIdeas}).
   */
  public void announce(Recomputed recomputed) {
    if (recomputed == null || recomputed.isEmpty()) {
      return;
    }
    if (!recomputed.moved().isEmpty() && !announcer.isUnsatisfied()) {
      announcer.get().onEntitiesTransitioned(recomputed.moved(), Instant.now());
    }
    announceIdeas(recomputed);
  }

  /** The idea half of {@link #announce}. */
  public void announceIdeas(Recomputed recomputed) {
    if (recomputed != null) {
      ideaAnnouncements.announce(recomputed.ideas());
    }
  }

  // --- the boot reconcile -----------------------------------------------------------------------

  /** One campaign whose stored status the reconcile found wrong, and the status it was given. */
  public record Drift(String campaignId, String projectId, String stored, String derived) {}

  /** What {@link #reconcile} fixed, and what it is to announce. */
  public record Reconciled(List<Drift> drifts, Recomputed changes) {}

  /**
   * <b>The boot reconcile</b>: every campaign that is neither DONE nor DROPPED re-derived in one
   * write, each whose stored status differs fixed as a derived change. Answers the drifts (for the
   * caller's WARNs) and the changes (for its announcement, {@link #announce}).
   */
  public Reconciled reconcile() {
    List<Drift> drifts = new ArrayList<>();
    Recomputed[] changes = {Recomputed.none()};
    writes.run(
        "campaign status reconcile",
        () -> {
          drifts.clear();
          changes[0] = recompute(entities.campaignIdsStillDerived());
          for (TransitionedEntity moved : changes[0].moved()) {
            drifts.add(
                new Drift(moved.id(), moved.projectId(), moved.statusBefore(), moved.status()));
          }
        });
    return new Reconciled(List.copyOf(drifts), changes[0]);
  }

  private static Optional<EntityStatus> parse(String word) {
    if (word == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(EntityStatus.valueOf(word));
    } catch (IllegalArgumentException unknown) {
      return Optional.empty();
    }
  }

  private EntityManager em() {
    return entities.getEntityManager();
  }
}
