package eu.wohlben.qits.projects.campaignhost;

import eu.wohlben.qits.entities.campaign.CampaignCriterion;
import eu.wohlben.qits.entities.campaign.CampaignCriterionGroup;
import eu.wohlben.qits.entities.campaign.CampaignCriterionGroupRepository;
import eu.wohlben.qits.entities.campaign.CampaignCriterionRepository;
import eu.wohlben.qits.entities.campaign.CampaignEvaluator;
import eu.wohlben.qits.entities.campaign.CampaignMembersSatisfied;
import eu.wohlben.qits.entities.campaign.Conditions;
import eu.wohlben.qits.entities.campaign.EntityQualifier;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.MembershipKind;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.projects.api.DispatchMode;
import eu.wohlben.qits.projects.api.DispatchRefused;
import eu.wohlben.qits.projects.api.EntityDispatch;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

/**
 * <b>The half of a campaign that acts</b> (epic f6c67e74, qits-417): a membership whose condition
 * holds is checked for dispatchability, claimed with one conditional UPDATE committed on its own, and
 * dispatched exactly once through {@link EntityDispatch} — FLOW, at its implement phase, never
 * anything else. The outcome is recorded on the membership. The sequence and its transaction
 * boundaries are the dossier page "Evaluator and executor: the sequence", followed step for step in
 * {@link #tryDispatch}.
 *
 * <h2>At most once, stated</h2>
 *
 * <p>A double dispatch puts two agents on one ticket in two workspaces and both commit, so the claim
 * <b>commits before the call out</b>. The worst case is then a claimed membership whose dispatch did
 * not happen, which is recorded ({@code dispatch_error}), kept claimed and left to a person — never a
 * second agent. A lost claim is a no-op; an unknown outcome is never retried.
 *
 * <h2>Who calls {@link #tryDispatch}, possibly all at once</h2>
 *
 * <ul>
 *   <li>{@link CampaignMemberSatisfied} from the bus listener, and {@link CampaignMembersSatisfied}
 *       from an authoring write (an approval; an add or a condition edit while running) — both
 *       observed {@code AFTER_SUCCESS}, so only a committed latch is acted on, and handed off the
 *       committing thread to {@link #async};
 *   <li>the sweep, {@link #sweep()}, every {@code qits.projects.campaign-sweep-interval} — the belt
 *       under the observers: a restart between latch and dispatch loses nothing;
 *   <li>a start press, through {@link #sweep(String)}.
 * </ul>
 *
 * <p><b>Only step 3 decides.</b> Under the two row locks one caller sees {@code claimed_at is null}
 * and updates one row, and every other caller sees it claimed.
 *
 * <h2>Only a scheduled member is claimed (qits-887)</h2>
 *
 * <p>A member is claimed at READY_FOR_DEV — a person's scheduling — and at no other status that
 * starts implement. A REFINED member is {@link Attempt#NOT_READY} with {@link #WAITING_FOR_SCHEDULE}
 * written once on its membership, which the progress shows; an unclaimed IMPLEMENTING member is
 * {@link Attempt#NOT_READY} with nothing written, because somebody started it by hand and a claim
 * would put a second agent on it. VERIFIED and DONE stay {@link Attempt#ARRIVED}. Both steps 1 and 3
 * decide it. <b>Scheduling re-checks the member at once</b>: its move into READY_FOR_DEV is an
 * {@code EntityTransitioned}, and {@code bus/CampaignCriteriaListener}'s retry arm hands every
 * satisfied, unclaimed membership of a moved entity back here — in every campaign that holds it — so
 * the dispatch does not wait for the periodic sweep.
 *
 * <h2>A blocked campaign claims nothing new (qits-592)</h2>
 *
 * <p>Steps 1 and 3 both read the campaign row's {@code blocked} beside its status, and a blocked
 * campaign is {@link Attempt#NOT_READY} — no claim, no refusal written, since a block is a wait and
 * not something wrong with the member. The sweep skips it too. Members already claimed are not
 * touched: their agents run on. The flag is read and never locked: it guards no at-most-once
 * property, so a block racing a claim either wins or lands one claim late, and taking the campaign
 * row here would break the lock order below. Claiming resumes after an unblock ({@code
 * EntityBlocks} runs {@link #sweep(String)} straight after, and the periodic sweep is the belt) or
 * after the next transition clears the flag — a move off REFINED and READY_FOR_DEV pauses the
 * start, so that one resumes at the next start press.
 *
 * <h2>A campaign runs at REFINED and at READY_FOR_DEV (qits-887)</h2>
 *
 * <p>Steps 1 and 3 and the sweep claim while the campaign is started and at either status ({@link
 * EntityStateMachine#campaignRunsAt}). The move between the two starts nothing and pauses nothing:
 * READY_FOR_DEV means "ready for development", and only a start press starts a campaign.
 *
 * <h2>Locks, and why they cannot deadlock with {@code CampaignService}</h2>
 *
 * <p>Step 3 takes {@code campaign_start FOR SHARE}, then the membership {@code FOR UPDATE}, and
 * nothing after them; it takes <b>no</b> lock on the campaign's entity row. Every other writer takes
 * the campaign's entity row first:
 *
 * <ul>
 *   <li>{@code CampaignService}'s membership writes: campaign row, then membership rows. They never
 *       lock {@code campaign_start} (they read it, unlocked, under MVCC).
 *   <li>The start press ({@code CampaignService.start}) and the pause hook ({@code
 *       WorkEntityService.transition}): campaign row, then {@code campaign_start}. They lock no
 *       membership.
 * </ul>
 *
 * <p>So a transaction holding a membership lock ({@code CampaignService}) never waits on {@code
 * campaign_start}, and a transaction holding {@code campaign_start} exclusively (press, pause) never
 * waits on a membership: the executor can wait on either, and neither can wait on the executor while
 * holding what it wants. Two executors share {@code campaign_start} and queue on the one membership.
 * No cycle exists. The precheck inside step 3 reads another database with the transaction suspended
 * and takes no lock.
 */
@ApplicationScoped
public class CampaignExecutor {

  private static final Logger LOG = Logger.getLogger(CampaignExecutor.class);

  /**
   * The one phase a campaign starts a member at: READY_FOR_DEV, so FLOW carries it to VERIFIED.
   */
  static final String PHASE = "implement";

  /**
   * What a REFINED member's membership says while it waits (qits-887): a person schedules work, the
   * campaign never does, so the member is not claimed until somebody has. Written once, like every
   * refusal, and cleared by the claim that follows the scheduling.
   */
  static final String WAITING_FOR_SCHEDULE =
      "Waiting for a person to schedule it (READY_FOR_DEV); the campaign dispatches it once they"
          + " have.";

  /** What one {@link #tryDispatch} came to — for the sweep's count, the log and the tests. */
  public enum Attempt {
    /**
     * Nothing to do: not a campaign member, claimed, not started, not REFINED or READY_FOR_DEV,
     * blocked, not satisfied — or the member is not scheduled (REFINED, its waiting written once)
     * or was started by hand (an unclaimed IMPLEMENTING member, qits-887).
     */
    NOT_READY,
    /** The member is VERIFIED or DONE already: skipped silently; progress shows it done. */
    ARRIVED,
    /** Refused before anything happened; the member stays unclaimed and says why. */
    REFUSED,
    /** Another caller claimed it first. */
    LOST,
    /** Claimed and dispatched, the outcome recorded. */
    DISPATCHED,
    /** Claimed, then refused by the dispatch itself before anything happened: claim released. */
    RELEASED,
    /** Claimed, and the dispatch failed with its outcome unknown: claim kept, never retried. */
    FAILED
  }

  @Inject EntityDispatch entityDispatch;

  @Inject EntityMembershipRepository memberships;

  @Inject CampaignCriterionGroupRepository groups;

  @Inject CampaignCriterionRepository criteria;

  @Inject CampaignEvaluator evaluator;

  @Inject ProjectChangePublisher publisher;

  @Inject Instance<EntityQualifier> qualifier;

  @Inject
  @PersistenceUnit("epics")
  EntityManager em;

  /**
   * Off the committing thread, and carrying nothing of it: no request context (an HTTP request's
   * would be destroyed under the task), no transaction, no causation scope — the evidence id is
   * passed explicitly instead.
   */
  ManagedExecutor async;

  /** Tasks handed to {@link #async} and not yet finished — read by the suite to wait for quiet. */
  private final AtomicInteger pending = new AtomicInteger();

  @PostConstruct
  void start() {
    async =
        ManagedExecutor.builder()
            .propagated(ThreadContext.NONE)
            .cleared(ThreadContext.ALL_REMAINING)
            .build();
  }

  @PreDestroy
  void stop() {
    async.shutdown();
  }

  // --- the observers ---------------------------------------------------------------------------

  /** The bus listener's fact, once its claim and latches have committed. */
  void onSatisfied(
      @Observes(during = TransactionPhase.AFTER_SUCCESS) CampaignMemberSatisfied satisfied) {
    submit(satisfied.membershipId(), satisfied.evidenceEventId());
  }

  /** An authoring write's hand-over, once it has committed. No event is its evidence. */
  void onMembersSatisfied(
      @Observes(during = TransactionPhase.AFTER_SUCCESS) CampaignMembersSatisfied satisfied) {
    for (String membershipId : satisfied.membershipIds()) {
      submit(membershipId, null);
    }
  }

  /** Never throws into the committing thread's completion. */
  private void submit(String membershipId, UUID evidenceEventId) {
    pending.incrementAndGet();
    try {
      async.runAsync(
          () -> {
            try {
              tryDispatchQuietly(membershipId, evidenceEventId);
            } finally {
              pending.decrementAndGet();
            }
          });
    } catch (RejectedExecutionException | IllegalStateException e) {
      pending.decrementAndGet();
      LOG.warnf(
          "Could not hand campaign member %s to the executor (%s); the sweep will pick it up.",
          membershipId, e.getMessage());
    }
  }

  /** {@link ActivateRequestContext}: Panache on a thread that is nobody's request. */
  @ActivateRequestContext
  void tryDispatchQuietly(String membershipId, UUID evidenceEventId) {
    try {
      tryDispatch(membershipId, evidenceEventId);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Campaign member %s could not be tried; the sweep retries it.", membershipId);
    }
  }

  /** How many handed-off attempts are still running. */
  int pendingAttempts() {
    return pending.get();
  }

  // --- tryDispatch -----------------------------------------------------------------------------

  /**
   * What step 1 read, for the steps that follow; {@code verdict} set means stop there, and {@code
   * refusalWritten} that the stop wrote the member's waiting onto its membership.
   */
  private record Look(
      Attempt verdict,
      String campaignId,
      String projectId,
      WorkEntity campaign,
      WorkEntity member,
      String storedRefusal,
      boolean refusalWritten) {

    static Look stop(Attempt verdict) {
      return new Look(verdict, null, null, null, null, null, false);
    }

    /** An unscheduled member: not ready, its waiting written when it was not already. */
    static Look waiting(String projectId, boolean written) {
      return new Look(Attempt.NOT_READY, null, projectId, null, null, null, written);
    }
  }

  /** What step 3 decided. */
  private record Claim(Attempt verdict, String startedBy, String refusal, boolean refusalWritten) {}

  /**
   * <b>One attempt at a membership</b>, the dossier's steps 1–5 — see the class javadoc. Safe to
   * call concurrently for one membership from any number of threads; never runs inside a caller's
   * transaction (each step owns its own).
   *
   * @param evidenceEventId the event that latched the criterion which tipped the condition over, the
   *     cause every row the dispatch writes is stamped with; null detaches the dispatch from any cause,
   *     which is intended for an approval, a state-at-start latch and a condition with no criteria
   */
  @ActivateRequestContext
  public Attempt tryDispatch(String membershipId, UUID evidenceEventId) {
    // 1. CHEAP READ — no lock, its own transaction.
    Look look = QuarkusTransaction.requiringNew().call(() -> look(membershipId));
    if (look.refusalWritten()) {
      publisher.fire(look.projectId(), ProjectChangeHint.Topic.EPICS);
    }
    if (look.verdict() != null) {
      return look.verdict();
    }

    // 2. PRECHECK — every refusal the dispatch would make, no side effect, outside any transaction.
    try {
      entityDispatch.precheck(look.member(), PHASE);
    } catch (DispatchRefused refused) {
      if (!refused.getMessage().equals(look.storedRefusal())) {
        boolean written =
            QuarkusTransaction.requiringNew()
                .call(() -> writeRefusal(membershipId, refused.getMessage()));
        if (written) {
          publisher.fire(look.projectId(), ProjectChangeHint.Topic.EPICS);
        }
      }
      return Attempt.REFUSED;
    }

    // 3. CLAIM — under the two row locks, committed before step 4.
    Claim claim =
        QuarkusTransaction.requiringNew().call(() -> claim(membershipId, look.campaignId()));
    if (claim.refusalWritten()) {
      publisher.fire(look.projectId(), ProjectChangeHint.Topic.EPICS);
    }
    if (claim.verdict() != null) {
      return claim.verdict();
    }

    // 4. DISPATCH — by id, so the dispatch re-reads the row; never the step-1 entity.
    String changedBy =
        "campaign "
            + EntityQualifier.render(qualifier, look.campaign())
            + " (started by "
            + claim.startedBy()
            + ")";
    AtomicReference<EntityDispatch.Outcome> holder = new AtomicReference<>();
    try {
      CausationScope.with(
          evidenceEventId,
          () ->
              holder.set(
                  entityDispatch.dispatch(
                      look.member().id, DispatchMode.FLOW, changedBy, PHASE)));
    } catch (DispatchRefused refused) {
      // 5b. PRE-PORT REFUSAL — raced since step 3; nothing happened. Release and say why.
      record(
          "release the claim",
          membershipId,
          () -> releaseWithRefusal(membershipId, refused.getMessage()));
      publisher.fire(look.projectId(), ProjectChangeHint.Topic.EPICS);
      LOG.infof(
          "Campaign member %s was refused at dispatch, so its claim is released: %s",
          membershipId, refused.getMessage());
      return Attempt.RELEASED;
    } catch (RuntimeException unknown) {
      // 5c. UNKNOWN — the call out, or anything after it. The claim is kept; never retried.
      String error = unknown.getClass().getName() + ": " + unknown.getMessage();
      record("record the dispatch error", membershipId, () -> writeError(membershipId, error));
      publisher.fire(look.projectId(), ProjectChangeHint.Topic.EPICS);
      LOG.warnf(
          unknown,
          "Dispatching campaign member %s failed with its outcome unknown; it stays claimed and is"
              + " not retried — a person dispatches it by hand once they have looked.",
          membershipId);
      return Attempt.FAILED;
    }

    // 5a. OK — the outcome, on the membership.
    EntityDispatch.Outcome outcome = holder.get();
    record("record the dispatch", membershipId, () -> writeDispatched(membershipId, outcome));
    publisher.fire(look.projectId(), ProjectChangeHint.Topic.EPICS);
    LOG.infof(
        "Campaign member %s dispatched onto %s (workspace %s, %s)",
        membershipId,
        outcome.branch(),
        outcome.made().workspaceRowId(),
        outcome.made().agentLaunch());
    return Attempt.DISPATCHED;
  }

  /** Step 1. */
  private Look look(String membershipId) {
    EntityMembership edge = membershipId == null ? null : memberships.findById(membershipId);
    if (edge == null || edge.kind != MembershipKind.CAMPAIGN || edge.claimedAt != null) {
      return Look.stop(Attempt.NOT_READY);
    }
    WorkEntity campaign = em.find(WorkEntity.class, edge.parentId);
    if (campaign == null || !EntityStateMachine.campaignRunsAt(campaign.status) || campaign.blocked) {
      return Look.stop(Attempt.NOT_READY);
    }
    if (!startActive(edge.parentId, false).active()) {
      return Look.stop(Attempt.NOT_READY);
    }
    if (!satisfied(edge.id)) {
      return Look.stop(Attempt.NOT_READY);
    }
    WorkEntity member = em.find(WorkEntity.class, edge.childId);
    if (member == null) {
      return Look.stop(Attempt.NOT_READY);
    }
    if (arrived(member)) {
      return Look.stop(Attempt.ARRIVED);
    }
    if (startedByHand(member)) {
      return Look.stop(Attempt.NOT_READY);
    }
    if (unscheduled(member)) {
      return Look.waiting(campaign.projectId, waitForSchedule(edge));
    }
    return new Look(
        null, campaign.id, campaign.projectId, campaign, member, edge.dispatchRefusal, false);
  }

  /** Step 3's body, inside its own transaction. */
  private Claim claim(String membershipId, String campaignId) {
    Start start = startActive(campaignId, true); // FOR SHARE: waits out a pause in flight
    @SuppressWarnings("unchecked")
    List<Object> locked =
        em.createNativeQuery(
                "select id from entity_membership where id = :id and kind = 'CAMPAIGN' for update")
            .setParameter("id", membershipId)
            .getResultList(); // FOR UPDATE: waits out setCondition / approve / remove / a rival claim
    if (locked.isEmpty()) {
      return new Claim(Attempt.NOT_READY, null, null, false);
    }
    EntityMembership edge = em.find(EntityMembership.class, membershipId);
    WorkEntity campaign = em.find(WorkEntity.class, campaignId);
    if (edge.claimedAt != null) {
      LOG.debugf("Campaign member %s is already claimed; this attempt stands down.", membershipId);
      return new Claim(Attempt.LOST, null, null, false);
    }
    if (campaign == null
        || !EntityStateMachine.campaignRunsAt(campaign.status)
        || campaign.blocked
        || !start.active()
        || !satisfied(edge.id)) {
      return new Claim(Attempt.NOT_READY, null, null, false);
    }
    WorkEntity member = em.find(WorkEntity.class, edge.childId);
    if (member == null) {
      return new Claim(Attempt.NOT_READY, null, null, false);
    }
    if (arrived(member)) {
      return new Claim(Attempt.ARRIVED, null, null, false);
    }
    if (startedByHand(member)) {
      return new Claim(Attempt.NOT_READY, null, null, false);
    }
    if (unscheduled(member)) {
      return new Claim(Attempt.NOT_READY, null, WAITING_FOR_SCHEDULE, waitForSchedule(edge));
    }
    String refusal =
        QuarkusTransaction.suspendingExisting()
            .call(
                () -> {
                  try {
                    entityDispatch.precheck(member, PHASE);
                    return null;
                  } catch (DispatchRefused refused) {
                    return refused.getMessage();
                  }
                });
    if (refusal != null) {
      boolean written = !refusal.equals(edge.dispatchRefusal) && writeRefusal(membershipId, refusal);
      return new Claim(Attempt.REFUSED, null, refusal, written);
    }
    int claimed =
        em.createNativeQuery(
                """
                update entity_membership
                   set claimed_at = :now, dispatch_refusal = null, dispatch_refused_at = null,
                       updated_at = :now
                 where id = :id and kind = 'CAMPAIGN' and claimed_at is null
                """)
            .setParameter("now", Instant.now())
            .setParameter("id", membershipId)
            .executeUpdate();
    if (claimed != 1) {
      LOG.debugf("Campaign member %s was claimed by another attempt first.", membershipId);
      return new Claim(Attempt.LOST, null, null, false);
    }
    return new Claim(null, start.startedBy(), null, false);
  }

  /** The start's two facts; locked {@code FOR SHARE} when {@code share}. */
  private record Start(boolean active, String startedBy) {}

  private Start startActive(String campaignId, boolean share) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em.createNativeQuery(
                "select active, started_by from campaign_start where campaign_id = :id"
                    + (share ? " for share" : ""))
            .setParameter("id", campaignId)
            .getResultList();
    if (rows.isEmpty()) {
      return new Start(false, null);
    }
    return new Start(Boolean.TRUE.equals(rows.get(0)[0]), (String) rows.get(0)[1]);
  }

  /** The membership's DNF over its criteria as committed now. */
  private boolean satisfied(String membershipId) {
    List<CampaignCriterionGroup> found = groups.groupsOf(membershipId);
    Map<String, List<CampaignCriterion>> byGroup = new LinkedHashMap<>();
    for (CampaignCriterionGroup group : found) {
      byGroup.put(group.id, new ArrayList<>());
    }
    for (CampaignCriterion criterion : criteria.criteriaOfAll(byGroup.keySet())) {
      byGroup.get(criterion.groupId).add(criterion);
    }
    return Conditions.satisfied(byGroup.values());
  }

  private static boolean arrived(WorkEntity member) {
    return Set.of(EntityStatus.VERIFIED.name(), EntityStatus.DONE.name()).contains(member.status);
  }

  /**
   * <b>Not scheduled yet</b> (qits-887): a REFINED member waits for a person's READY_FOR_DEV and is
   * never claimed before it — the campaign orders work, it does not approve it. Not a refusal of
   * the member, so it is decided here rather than by the dispatch's precheck, and its own sentence
   * ({@link #WAITING_FOR_SCHEDULE}) is what the progress shows.
   */
  private static boolean unscheduled(WorkEntity member) {
    return EntityStatus.REFINED.name().equals(member.status);
  }

  /**
   * <b>Started by hand</b> (qits-887): an IMPLEMENTING member nobody here claimed has an agent on it
   * already — somebody pressed it themselves. The precheck would let a second agent start on it,
   * so it is never claimed; nothing is wrong with it, so no refusal is written.
   */
  private static boolean startedByHand(WorkEntity member) {
    return EntityStatus.IMPLEMENTING.name().equals(member.status);
  }

  /** The waiting sentence on {@code edge}, written once; answers whether this call wrote it. */
  private boolean waitForSchedule(EntityMembership edge) {
    return !WAITING_FOR_SCHEDULE.equals(edge.dispatchRefusal)
        && writeRefusal(edge.id, WAITING_FOR_SCHEDULE);
  }

  // --- the records -----------------------------------------------------------------------------

  /** A refusal, written only when its text changed and only on an unclaimed membership. */
  private boolean writeRefusal(String membershipId, String refusal) {
    return em.createNativeQuery(
                """
                update entity_membership
                   set dispatch_refusal = :refusal, dispatch_refused_at = :now, updated_at = :now
                 where id = :id and kind = 'CAMPAIGN' and claimed_at is null
                   and dispatch_refusal is distinct from :refusal
                """)
            .setParameter("refusal", refusal)
            .setParameter("now", Instant.now())
            .setParameter("id", membershipId)
            .executeUpdate()
        == 1;
  }

  private void releaseWithRefusal(String membershipId, String refusal) {
    Instant now = Instant.now();
    em.createNativeQuery(
            """
            update entity_membership
               set claimed_at = null, dispatch_refusal = :refusal, dispatch_refused_at = :now,
                   updated_at = :now
             where id = :id and dispatched_at is null
            """)
        .setParameter("refusal", refusal)
        .setParameter("now", now)
        .setParameter("id", membershipId)
        .executeUpdate();
  }

  private void writeError(String membershipId, String error) {
    em.createNativeQuery(
            """
            update entity_membership set dispatch_error = :error, updated_at = :now where id = :id
            """)
        .setParameter("error", error)
        .setParameter("now", Instant.now())
        .setParameter("id", membershipId)
        .executeUpdate();
  }

  private void writeDispatched(String membershipId, EntityDispatch.Outcome outcome) {
    Instant now = Instant.now();
    em.createNativeQuery(
            """
            update entity_membership
               set dispatched_at = :now, dispatch_workspace_id = :workspace,
                   dispatch_branch = :branch, dispatch_agent_launch = :launch,
                   dispatch_error = null, updated_at = :now
             where id = :id
            """)
        .setParameter("now", now)
        .setParameter("workspace", String.valueOf(outcome.made().workspaceRowId()))
        .setParameter("branch", outcome.branch())
        .setParameter("launch", outcome.made().agentLaunch())
        .setParameter("id", membershipId)
        .executeUpdate();
  }

  /**
   * A record after the call out, in a transaction of its own. A failure here is logged and not
   * thrown: the claim is already committed, so the one thing that cannot happen is a second
   * dispatch, and the record is what a person reads to see what did.
   */
  private void record(String what, String membershipId, Runnable write) {
    try {
      QuarkusTransaction.requiringNew().run(write);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Could not %s on campaign member %s", what, membershipId);
    }
  }

  // --- the sweep -------------------------------------------------------------------------------

  /**
   * The belt under the observers. Gated to {@link LaunchMode#NORMAL}, as {@code
   * RefinementStaleImageSweep} is: a suite or a dev session must not dispatch agents in the
   * background; the suite drives {@link #sweep(String)} directly.
   */
  @Scheduled(
      every = "{qits.projects.campaign-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduledSweep() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    sweepQuietly();
  }

  /** One pass, every failure logged and none rethrown. */
  @ActivateRequestContext
  void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException e) {
      LOG.error("The campaign sweep failed — retried on the next interval.", e);
    }
  }

  /**
   * Every active, unblocked campaign at a status it runs at (REFINED or READY_FOR_DEV, qits-887);
   * answers how many members it dispatched.
   */
  @ActivateRequestContext
  public int sweep() {
    @SuppressWarnings("unchecked")
    List<String> campaignIds =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    em.createNativeQuery(
                            """
                            select s.campaign_id
                              from campaign_start s
                              join entity e on e.id = s.campaign_id
                             where s.active and e.status in (:running) and not e.blocked
                             order by s.campaign_id
                            """)
                        .setParameter(
                            "running",
                            EntityStateMachine.campaignRunStatuses().stream()
                                .map(Enum::name)
                                .toList())
                        .getResultList());
    int dispatched = 0;
    for (String campaignId : campaignIds) {
      dispatched += sweep(campaignId);
    }
    return dispatched;
  }

  /** One unclaimed, satisfied membership, and the evidence of its newest latch (null if none). */
  private record Candidate(String membershipId, UUID lastEvidenceEventId) {}

  /**
   * One campaign: {@link #tryDispatch} for every unclaimed membership whose condition holds, with
   * {@link #lastEvidenceEventIdOf} as its cause. Never throws; answers how many it dispatched.
   */
  @ActivateRequestContext
  public int sweep(String campaignId) {
    List<Candidate> candidates;
    try {
      candidates = QuarkusTransaction.requiringNew().call(() -> candidatesOf(campaignId));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read campaign %s's waiting members; retried on the next sweep.", campaignId);
      return 0;
    }
    int dispatched = 0;
    for (Candidate candidate : candidates) {
      try {
        if (tryDispatch(candidate.membershipId(), candidate.lastEvidenceEventId())
            == Attempt.DISPATCHED) {
          dispatched++;
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Campaign member %s could not be tried; retried on the next sweep.",
            candidate.membershipId());
      }
    }
    return dispatched;
  }

  private List<Candidate> candidatesOf(String campaignId) {
    List<String> unclaimed =
        memberships.campaignMembers(campaignId).stream()
            .filter(edge -> edge.claimedAt == null)
            .map(edge -> edge.id)
            .toList();
    List<Candidate> candidates = new ArrayList<>();
    for (String membershipId : evaluator.satisfiedUnclaimed(unclaimed)) {
      candidates.add(new Candidate(membershipId, lastEvidenceEventIdOf(membershipId)));
    }
    return candidates;
  }

  /** The evidence event of the most recently satisfied criterion of the membership, or null. */
  private UUID lastEvidenceEventIdOf(String membershipId) {
    @SuppressWarnings("unchecked")
    List<Object> rows =
        em.createNativeQuery(
                """
                select c.evidence_event_id
                  from campaign_criterion c
                  join campaign_criterion_group g on g.id = c.group_id
                 where g.membership_id = :id and c.satisfied_at is not null
                 order by c.satisfied_at desc, c.id
                 limit 1
                """)
            .setParameter("id", membershipId)
            .getResultList();
    if (rows.isEmpty() || rows.get(0) == null) {
      return null;
    }
    Object id = rows.get(0);
    return id instanceof UUID uuid ? uuid : UUID.fromString(id.toString());
  }
}
