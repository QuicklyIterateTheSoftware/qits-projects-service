package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.campaign.CampaignStartRecordRepository;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A campaign's derived status (qits-650): the lowest status of its members that are not DROPPED, on
 * the campaign walk, with IMPLEMENTING read as READY_FOR_DEV and VERIFYING as IMPLEMENTED; REPORTED
 * when it has none, DROPPED when all are. Re-derived in the transaction of every writer — a member's
 * move on either door, an add, a remove, a member's delete — and at boot; never out of DONE or
 * DROPPED, and at once on the reopen.
 */
@QuarkusTest
class CampaignStatusRecomputeTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-campaign-status";
  private static final String WHO = "ada";
  private static final Mover PERSON = Mover.person(WHO);

  @Inject WorkEntityService work;
  @Inject CampaignService campaigns;
  @Inject CampaignStatusRecompute recompute;
  @Inject EntityTransitionService transitions;
  @Inject CampaignStartRecordRepository starts;
  @Inject RecordingTransitionAnnouncer announced;

  @BeforeEach
  void quiet() {
    announced.clear();
  }

  // --- the rule -----------------------------------------------------------------------------------

  @Test
  void theRuleItself() {
    assertEquals(EntityStatus.REPORTED, CampaignStatusRecompute.derive(List.of()));
    assertEquals(EntityStatus.DROPPED, CampaignStatusRecompute.derive(List.of("DROPPED", "DROPPED")));
    assertEquals(EntityStatus.REFINED, CampaignStatusRecompute.derive(List.of("VERIFIED", "REFINED")));
    assertEquals(
        EntityStatus.REFINED,
        CampaignStatusRecompute.derive(List.of("VERIFIED", "REFINED", "DROPPED", "VERIFIED")));
    assertEquals(EntityStatus.READY_FOR_DEV, CampaignStatusRecompute.derive(List.of("IMPLEMENTING")));
    assertEquals(EntityStatus.IMPLEMENTED, CampaignStatusRecompute.derive(List.of("VERIFYING", "DONE")));
    assertEquals(EntityStatus.DONE, CampaignStatusRecompute.derive(List.of("DONE", "DROPPED")));
    assertEquals(EntityStatus.VERIFIED, CampaignStatusRecompute.derive(List.of("DONE", "VERIFIED")));
  }

  @Test
  void whoHoldsTheFloor() {
    assertTrue(CampaignStatusRecompute.holds("REFINED", "REFINED"));
    assertTrue(CampaignStatusRecompute.holds("READY_FOR_DEV", "IMPLEMENTING"));
    assertTrue(CampaignStatusRecompute.holds("IMPLEMENTED", "VERIFYING"));
    assertFalse(CampaignStatusRecompute.holds("REFINED", "VERIFIED"));
    assertFalse(CampaignStatusRecompute.holds("DONE", "DONE"), "a DONE campaign has no holder");
    assertFalse(CampaignStatusRecompute.holds("DROPPED", "DROPPED"));
    assertFalse(CampaignStatusRecompute.holds("REPORTED", "DROPPED"));
  }

  // --- the writers ---------------------------------------------------------------------------------

  /** A member's move on the status door moves the campaign with it, in the same write. */
  @Test
  void aMembersMoveMovesTheCampaign() {
    WorkEntity a = ticket("A");
    WorkEntity b = ticket("B");
    WorkEntity campaign = campaignOf(a, b);
    assertEquals("REPORTED", status(campaign));

    walk(a, "REFINED", "READY_FOR_DEV", "IMPLEMENTING");
    assertEquals("REPORTED", status(campaign), "B still holds the floor");

    walk(b, "REFINED");
    assertEquals("REFINED", status(campaign));
    walk(b, "READY_FOR_DEV");
    assertEquals("READY_FOR_DEV", status(campaign), "IMPLEMENTING counts as READY_FOR_DEV");
    walk(b, "IMPLEMENTED", "VERIFYING");
    walk(a, "IMPLEMENTED");
    assertEquals("IMPLEMENTED", status(campaign), "VERIFYING counts as IMPLEMENTED");
    walk(a, "VERIFIED");
    walk(b, "VERIFIED");
    assertEquals("VERIFIED", status(campaign));
    walk(a, "DONE");
    walk(b, "DONE");
    assertEquals("DONE", status(campaign));
  }

  /**
   * A derived change writes {@code statusBefore}, is audited as the system, is announced beside the
   * member's move — and keeps the campaign's block.
   */
  @Test
  void aDerivedChangeIsAnnouncedAuditedAsTheSystemAndKeepsTheBlock() {
    WorkEntity a = ticket("A");
    WorkEntity campaign = campaignOf(a);
    work.setBlocked(Archetype.CAMPAIGN, campaign.id, true, "waiting on the vendor", WHO);
    announced.clear();

    walk(a, "REFINED");

    List<TransitionedEntity> batch = announced.batches().get(0).entities();
    TransitionedEntity derived =
        batch.stream().filter(entry -> entry.id().equals(campaign.id)).findFirst().orElseThrow();
    assertEquals("REPORTED", derived.statusBefore());
    assertEquals("REFINED", derived.status());
    assertEquals(CampaignStatusRecompute.CHANGED_BY, derived.changedBy());
    assertEquals(a.id, batch.get(0).id(), "the member's own move leads the one batch");
    inFreshTx(
        () -> {
          WorkEntity stored = workEntityRepository.findById(campaign.id);
          assertTrue(stored.blocked, "a derived change clears no block");
          assertEquals("waiting on the vendor", stored.blockedReason);
          List<AuditEntry> rows = auditRepository.list("entityId", campaign.id);
          assertTrue(
              rows.stream().anyMatch(row -> CampaignStatusRecompute.CHANGED_BY.equals(row.changedBy)),
              "audited as the system");
        });
  }

  /** Adding a REPORTED member to an IMPLEMENTED campaign drops it back; removing it lifts it again. */
  @Test
  void anAddAndARemoveRecomputeIt() {
    WorkEntity done = ticket("Ahead");
    walk(done, "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    WorkEntity campaign = campaignOf(done);
    assertEquals("IMPLEMENTED", status(campaign), "the add derived it");

    WorkEntity behind = ticket("Behind");
    CampaignService.Member added = campaigns.addMember(campaign.id, behind.id, null, false, WHO);
    assertEquals("REPORTED", status(campaign));

    campaigns.removeMember(campaign.id, added.membership().id, WHO);
    assertEquals("IMPLEMENTED", status(campaign));
  }

  /** Deleting a member entity re-derives every campaign it was in. */
  @Test
  void aMembersDeleteRecomputesIt() {
    WorkEntity ahead = ticket("Ahead");
    walk(ahead, "REFINED");
    WorkEntity behind = ticket("Behind");
    WorkEntity campaign = campaignOf(ahead, behind);
    assertEquals("REPORTED", status(campaign));

    work.delete(Archetype.TICKET, behind.id, WHO);

    assertEquals("REFINED", status(campaign));
  }

  /** The bulk door's status statement on a member re-derives the campaign too. */
  @Test
  void theBulkDoorsMemberMoveRecomputesIt() {
    WorkEntity ahead = ticket("Ahead");
    walk(ahead, "REFINED");
    WorkEntity behind = ticket("Behind");
    WorkEntity campaign = campaignOf(ahead, behind);
    announced.clear();

    transitions.transition(
        Map.of(behind.id, ticketEntry("Behind", EntityStatus.DROPPED, WorkOutcome.WONT_DO)), WHO);

    assertEquals("REFINED", status(campaign), "a DROPPED member is not counted");
    assertTrue(
        announced.batches().get(0).entities().stream()
            .anyMatch(entry -> entry.id().equals(campaign.id) && "REFINED".equals(entry.status())),
        "the derived move joins the bulk door's batch");
  }

  /** Turning a member into what a campaign cannot gather is a 409 naming the campaign. */
  @Test
  void theBulkDoorRefusesToTurnAMemberIntoWhatACampaignCannotHold() {
    WorkEntity epic = work.create(
            Archetype.EPIC, PROJECT, EntityWrite.epic("Plan", null).withAcceptanceCriteria(CRITERIA), WHO)
        .entity();
    WorkEntity member = ticket("Gathered");
    WorkEntity campaign = campaignOf(member);

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () ->
                transitions.transition(
                    Map.of(
                        member.id,
                        new EntityTransition(
                            Archetype.FEATURE,
                            new EntityTransition.Membership(epic.id, null),
                            "Gathered",
                            null,
                            "REPORTED",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)),
                    WHO));

    assertTrue(refusal.getMessage().contains(member.id + " is a member of campaign #" + campaign.number), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("would become a FEATURE"), refusal.getMessage());
    inFreshTx(() -> assertEquals(Archetype.TICKET, workEntityRepository.findById(member.id).archetype));
  }

  // --- what is never recomputed --------------------------------------------------------------------

  /**
   * Every member DROPPED drops the campaign; it stays DROPPED when a member comes back, and the
   * reopen recomputes at once.
   */
  @Test
  void droppedIsStickyAndTheReopenRecomputes() {
    WorkEntity a = ticket("A");
    WorkEntity campaign = campaignOf(a);
    walk(a, "DROPPED");
    assertEquals("DROPPED", status(campaign), "every member DROPPED");

    walk(a, "REPORTED", "REFINED");
    assertEquals("DROPPED", status(campaign), "a DROPPED campaign is never recomputed");

    work.transition(Archetype.CAMPAIGN, campaign.id, "REPORTED", PERSON);
    assertEquals("REFINED", status(campaign), "the reopen recomputes at once");
  }

  /** A manual drop of a campaign with open members stays; DONE is final. */
  @Test
  void aManualDropAndDoneAreLeftAlone() {
    WorkEntity a = ticket("A");
    WorkEntity campaign = campaignOf(a);
    work.transition(Archetype.CAMPAIGN, campaign.id, "DROPPED", PERSON, TestDrops.WONT_DO);
    walk(a, "REFINED");
    assertEquals("DROPPED", status(campaign));

    WorkEntity b = ticket("B");
    walk(b, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");
    WorkEntity finished = campaignOf(b);
    assertEquals("DONE", status(finished));
    assertEquals(0, recompute.reconcile().drifts().size());
    assertEquals("DONE", status(finished));
  }

  // --- the door ------------------------------------------------------------------------------------

  /** Every campaign move but the drop and the reopen is a 409 saying the status is derived. */
  @Test
  void theStatusDoorRefusesEveryOtherCampaignMove() {
    WorkEntity campaign = campaignOf(ticket("A"));
    for (String target : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE")) {
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> work.transition(Archetype.CAMPAIGN, campaign.id, target, PERSON),
              target);
      assertTrue(refusal.getMessage().contains("derived from its members"), refusal.getMessage());
    }
    assertEquals("REPORTED", status(campaign));
  }

  // --- running -------------------------------------------------------------------------------------

  /** A derived move between open statuses leaves the start alone; a derived move into DONE pauses it. */
  @Test
  void aDerivedDoneStopsTheRunAndNothingElseDoes() {
    WorkEntity a = ticket("A");
    WorkEntity campaign = campaignOf(a);
    campaigns.start(campaign.id, WHO);
    assertTrue(isActive(campaign), "a REPORTED campaign starts");

    walk(a, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED");
    assertEquals("VERIFIED", status(campaign));
    assertTrue(isActive(campaign), "derived moves between open statuses leave the start alone");

    walk(a, "DONE");
    assertEquals("DONE", status(campaign));
    assertFalse(isActive(campaign), "a derived DONE pauses the start");
  }

  /** A manual drop pauses the start; the reopen does not resume it. */
  @Test
  void aManualDropPausesTheStart() {
    WorkEntity campaign = campaignOf(ticket("A"));
    campaigns.start(campaign.id, WHO);

    work.transition(Archetype.CAMPAIGN, campaign.id, "DROPPED", PERSON, TestDrops.WONT_DO);
    assertFalse(isActive(campaign));
    work.transition(Archetype.CAMPAIGN, campaign.id, "REPORTED", PERSON);
    assertFalse(isActive(campaign), "a reopen is not a start press");
  }

  // --- the boot reconcile ----------------------------------------------------------------------------

  @Test
  void theReconcileRepairsDriftAndLeavesDoneAndDroppedAlone() {
    WorkEntity a = ticket("A");
    walk(a, "REFINED");
    WorkEntity drifted = campaignOf(a);
    WorkEntity dropped = campaignOf(ticket("B"));
    work.transition(Archetype.CAMPAIGN, dropped.id, "DROPPED", PERSON, TestDrops.WONT_DO);
    setStatus(drifted, "IMPLEMENTED");
    announced.clear();

    CampaignStatusRecompute.Reconciled reconciled = recompute.reconcile();
    recompute.announce(reconciled.changes());

    assertEquals(
        List.of(new CampaignStatusRecompute.Drift(drifted.id, PROJECT, "IMPLEMENTED", "REFINED")),
        reconciled.drifts());
    assertEquals("REFINED", status(drifted));
    assertEquals("DROPPED", status(dropped));
    assertEquals(1, announced.batches().size());
  }

  // --- the locks -----------------------------------------------------------------------------------

  /**
   * A member's move racing an add to its campaign: the move holds the member's row and then asks for
   * the campaign row; the add holds the campaign row and waits on no member row. They queue and never
   * deadlock, and whichever runs second derives from the other's committed rows.
   */
  @Test
  void aMembersMoveRacingAnAddNeverDeadlocksAndEndsDerivedRight() throws Exception {
    WorkEntity mover = ticket("Moves");
    WorkEntity campaign = campaignOf(mover);
    List<WorkEntity> joiners = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      WorkEntity joiner = ticket("Joins " + i);
      walk(joiner, "REFINED");
      joiners.add(joiner);
    }
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < joiners.size(); round++) {
        CyclicBarrier together = new CyclicBarrier(2);
        String target = round % 2 == 0 ? "REFINED" : "REPORTED";
        WorkEntity joiner = joiners.get(round);
        Future<?> move =
            pool.submit(
                () -> {
                  await(together);
                  work.transition(Archetype.TICKET, mover.id, target, PERSON);
                  return null;
                });
        Future<?> add =
            pool.submit(
                () -> {
                  await(together);
                  campaigns.addMember(campaign.id, joiner.id, null, false, WHO);
                  return null;
                });
        for (Future<?> racer : List.of(move, add)) {
          try {
            racer.get(60, TimeUnit.SECONDS);
          } catch (java.util.concurrent.ExecutionException e) {
            failures.add(e.getCause());
          }
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(List.of(), failures);
    List<String> statuses =
        QuarkusTransaction.requiringNew()
            .call(() -> workEntityRepository.campaignMemberStatuses(campaign.id));
    assertEquals(joiners.size() + 1, statuses.size());
    assertEquals(CampaignStatusRecompute.derive(statuses).name(), status(campaign));
    assertEquals("REPORTED", status(mover), "six moves, ending back where it started");
    assertEquals("REPORTED", status(campaign));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private WorkEntity campaignOf(WorkEntity... members) {
    WorkEntity campaign = work.createCampaign(PROJECT, "The order", null, WHO);
    for (WorkEntity member : members) {
      campaigns.addMember(campaign.id, member.id, null, false, WHO);
    }
    return campaign;
  }

  private WorkEntity ticket(String title) {
    return work.create(
            Archetype.TICKET,
            PROJECT,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null).withAcceptanceCriteria(CRITERIA),
            WHO)
        .entity();
  }

  private static EntityTransition ticketEntry(
      String title, EntityStatus status, WorkOutcome outcome) {
    return new EntityTransition(
        Archetype.TICKET,
        null,
        title,
        null,
        status.name(),
        TicketType.BUG,
        "it occurs",
        null,
        null,
        null,
        null,
        null,
        CRITERIA,
        outcome);
  }

  private void walk(WorkEntity row, String... statuses) {
    for (String status : statuses) {
      work.transition(row.archetype, row.id, status, PERSON, TestDrops.of(status));
    }
  }

  private String status(WorkEntity row) {
    return QuarkusTransaction.requiringNew().call(() -> workEntityRepository.findById(row.id).status);
  }

  private boolean isActive(WorkEntity campaign) {
    return QuarkusTransaction.requiringNew().call(() -> starts.isActive(campaign.id));
  }

  private void setStatus(WorkEntity row, String status) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workEntityRepository
                    .getEntityManager()
                    .createNativeQuery("update entity set status = :status where id = :id")
                    .setParameter("status", status)
                    .setParameter("id", row.id)
                    .executeUpdate());
  }
}
