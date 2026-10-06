package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntitiesTestSupport;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.EntitiesException;
import eu.wohlben.qits.entities.persistence.AuditRepository;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@link CampaignService} against the real database: the seed, positions, the in-flight join, the
 * refusals, the condition PUT, the approval latch, the latch from the current state and the pause
 * hook. A {@code @QuarkusTest} on {@link EntitiesTestSupport} with <b>no {@code @TestProfile}</b> —
 * the test-profile budget rule.
 */
@QuarkusTest
class CampaignServiceTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-campaign-service";
  private static final String OTHER_PROJECT = "proj-campaign-elsewhere";
  private static final String WHO = "tester";

  @Inject CampaignService campaigns;
  @Inject WorkEntityService workEntities;
  @Inject CampaignStartRecordRepository starts;
  @Inject AuditRepository audit;
  @Inject EntityMembershipRepository memberships;

  // --- the seed ------------------------------------------------------------------------------------

  @Test
  void aMemberAddedBehindAnotherWaitsForItToBeVerified() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    WorkEntity b = ticket("B");

    CampaignService.Member first = campaigns.addMember(campaign.id, a.id, null, false, WHO);
    CampaignService.Member second = campaigns.addMember(campaign.id, b.id, null, false, WHO);

    assertEquals(0, first.membership().position);
    assertTrue(first.groups().isEmpty(), "the first member waits on nothing");
    assertEquals(1, second.membership().position);
    assertSeededOn(second, a.id);
    assertFalse(second.satisfied());
  }

  @Test
  void aPredecessorAlreadyVerifiedOrDoneSeedsNothing() {
    WorkEntity campaign = campaign();
    WorkEntity verified = walk(ticket("Verified"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED");
    WorkEntity afterVerified = ticket("After verified");
    WorkEntity done = walk(ticket("Done"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");
    WorkEntity afterDone = ticket("After done");

    campaigns.addMember(campaign.id, verified.id, null, false, WHO);
    assertTrue(campaigns.addMember(campaign.id, afterVerified.id, null, false, WHO).groups().isEmpty());
    campaigns.addMember(campaign.id, done.id, 0, false, WHO);
    assertTrue(campaigns.addMember(campaign.id, afterDone.id, 1, false, WHO).groups().isEmpty());
  }

  @Test
  void anInsertionBetweenTwoSeedsTheNewcomerAndLeavesTheNeighbourWaitingWhereItWas() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    WorkEntity b = ticket("B");
    WorkEntity c = ticket("C");
    campaigns.addMember(campaign.id, a.id, null, false, WHO);
    campaigns.addMember(campaign.id, c.id, null, false, WHO);

    CampaignService.Member inserted = campaigns.addMember(campaign.id, b.id, 1, false, WHO);

    CampaignService.Campaign whole = campaigns.get(campaign.id);
    assertEquals(List.of(a.id, b.id, c.id), memberIds(whole));
    assertEquals(List.of(0, 1, 2), positions(whole));
    assertSeededOn(inserted, a.id);
    assertSeededOn(whole.members().get(2), a.id);
  }

  @Test
  void aReorderTouchesNoCriterion() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    WorkEntity b = ticket("B");
    WorkEntity c = ticket("C");
    campaigns.addMember(campaign.id, a.id, null, false, WHO);
    campaigns.addMember(campaign.id, b.id, null, false, WHO);
    CampaignService.Member third = campaigns.addMember(campaign.id, c.id, null, false, WHO);
    List<String> before = criterionIdsAndPredicates(campaigns.get(campaign.id));

    CampaignService.Campaign moved =
        campaigns.moveMember(campaign.id, third.membership().id, 0, WHO);

    assertEquals(List.of(c.id, a.id, b.id), memberIds(moved));
    assertEquals(List.of(0, 1, 2), positions(moved));
    assertEquals(before, criterionIdsAndPredicates(moved), "a move never rewrites a condition");
    // C still waits on B — the seed is not re-derived for its new place.
    assertSeededOn(moved.members().get(0), b.id);
    assertTrue(moved.members().get(1).groups().isEmpty());
  }

  // --- joining -------------------------------------------------------------------------------------

  @Test
  void aMemberInFlightJoinsClaimedAndRunning() {
    WorkEntity campaign = campaign();
    WorkEntity running = ticket("Running");
    Instant before = Instant.now();

    CampaignService.Member member = campaigns.addMember(campaign.id, running.id, null, true, WHO);

    assertNotNull(member.membership().claimedAt);
    assertFalse(member.membership().claimedAt.isBefore(before.minusSeconds(1)));
    assertTrue(member.membership().joinedRunning);
    assertNull(member.membership().dispatchedAt);

    CampaignService.Member waiting = campaigns.addMember(campaign.id, ticket("W").id, null, false, WHO);
    assertNull(waiting.membership().claimedAt);
    assertFalse(waiting.membership().joinedRunning);
  }

  @Test
  void onlyPhasedWorkOfThisProjectJoinsOnce() {
    WorkEntity campaign = campaign();
    WorkEntity epic =
        workEntities.create(Archetype.EPIC, PROJECT, EntityWrite.epic("Plan", null), WHO).entity();
    WorkEntity feature =
        workEntities
            .create(Archetype.FEATURE, epic.id, EntityWrite.feature("Part", null, null), WHO)
            .entity();
    WorkEntity task =
        workEntities
            .create(Archetype.TASK, feature.id, EntityWrite.task("repo", "Step", null, null), WHO)
            .entity();
    WorkEntity other = campaign();
    WorkEntity foreign =
        workEntities
            .create(
                Archetype.TICKET,
                OTHER_PROJECT,
                EntityWrite.ticket("Elsewhere", "it occurs", null, "BUG", null),
                WHO)
            .entity();

    conflict(() -> campaigns.addMember(campaign.id, feature.id, null, false, WHO), "runs no phase");
    conflict(() -> campaigns.addMember(campaign.id, task.id, null, false, WHO), "runs no phase");
    conflict(() -> campaigns.addMember(campaign.id, other.id, null, false, WHO), "cannot gather");
    conflict(() -> campaigns.addMember(campaign.id, foreign.id, null, false, WHO), OTHER_PROJECT);

    campaigns.addMember(campaign.id, epic.id, null, false, WHO);
    conflict(() -> campaigns.addMember(campaign.id, epic.id, null, false, WHO), "already a member");
    assertEquals(1, campaigns.get(campaign.id).members().size());
  }

  @Test
  void membershipIsFrozenOnceTheCampaignIsImplemented() {
    WorkEntity campaign = campaign();
    CampaignService.Member member = campaigns.addMember(campaign.id, ticket("A").id, null, false, WHO);
    walk(campaign, "REFINED");
    // Running or not, REFINED is still editable.
    campaigns.addMember(campaign.id, ticket("B").id, null, false, WHO);
    walk(campaign, "READY_FOR_DEV", "IMPLEMENTED");

    conflict(() -> campaigns.addMember(campaign.id, ticket("C").id, null, false, WHO), "IMPLEMENTED");
    conflict(() -> campaigns.moveMember(campaign.id, member.membership().id, 1, WHO), "IMPLEMENTED");
    conflict(() -> campaigns.removeMember(campaign.id, member.membership().id, WHO), "IMPLEMENTED");
    conflict(
        () -> campaigns.setCondition(campaign.id, member.membership().id, List.of(), WHO),
        "IMPLEMENTED");
  }

  // --- removal -------------------------------------------------------------------------------------

  @Test
  void aMemberSomebodyWaitsOnIsNotRemoved() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    CampaignService.Member first = campaigns.addMember(campaign.id, a.id, null, false, WHO);
    CampaignService.Member second = campaigns.addMember(campaign.id, ticket("B").id, null, false, WHO);

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> campaigns.removeMember(campaign.id, first.membership().id, WHO));
    assertTrue(refused.getMessage().contains(second.membership().id), refused.getMessage());
    assertTrue(refused.getMessage().contains("Edit their conditions first"), refused.getMessage());

    campaigns.setCondition(campaign.id, second.membership().id, List.of(), WHO);
    campaigns.removeMember(campaign.id, first.membership().id, WHO);

    CampaignService.Campaign whole = campaigns.get(campaign.id);
    assertEquals(List.of(second.membership().id), whole.members().stream().map(m -> m.membership().id).toList());
    assertEquals(List.of(0), positions(whole), "the gap closes");
  }

  @Test
  void aClaimedMemberIsNotRemovedAndItsConditionIsNotEdited() {
    WorkEntity campaign = campaign();
    CampaignService.Member running = campaigns.addMember(campaign.id, ticket("R").id, null, true, WHO);

    conflict(() -> campaigns.removeMember(campaign.id, running.membership().id, WHO), "run record");
    conflict(
        () -> campaigns.setCondition(campaign.id, running.membership().id, List.of(), WHO),
        "no longer edited");
  }

  // --- the condition -------------------------------------------------------------------------------

  @Test
  void aPutKeepsTheLatchOfWhatItRestatesAndDeletesWhatItOmits() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    campaigns.addMember(campaign.id, a.id, null, false, WHO);
    CampaignService.Member b = campaigns.addMember(campaign.id, ticket("B").id, null, false, WHO);
    String seededId = b.groups().get(0).criteria().get(0).id;

    CampaignService.Member gated =
        campaigns.setCondition(
            campaign.id,
            b.membership().id,
            List.of(group(approval(null)), group(restated(seededId, "ENTITY_STATUS", a.id))),
            WHO);
    String approvalId = gated.groups().get(0).criteria().get(0).id;
    CampaignService.Approved approved =
        campaigns.approve(campaign.id, b.membership().id, approvalId, "ship it", "xion");
    assertEquals(List.of(b.membership().id), approved.satisfied(), "the approval made it hold");

    CampaignService.Member restated =
        campaigns.setCondition(
            campaign.id,
            b.membership().id,
            List.of(
                group(
                    restated(approvalId, "APPROVAL", null),
                    new CampaignService.CriterionSpec(
                        null, "SCM_RELEASE", Map.of("repositoryName", "qits-qits")))),
            WHO);

    assertEquals(1, restated.groups().size());
    List<CampaignCriterion> kept = restated.groups().get(0).criteria();
    assertEquals(2, kept.size());
    assertEquals(approvalId, kept.get(0).id);
    assertNotNull(kept.get(0).satisfiedAt, "a restated criterion keeps its latch");
    assertEquals("xion", kept.get(0).approvedBy);
    assertEquals("ship it", kept.get(0).approvalNote);
    assertNull(kept.get(1).satisfiedAt);
    assertFalse(
        allCriterionIds(campaigns.get(campaign.id)).contains(seededId), "the omitted seed is gone");
  }

  @Test
  void aConditionIsRefusedWithEveryViolationListed() {
    WorkEntity campaign = campaign();
    CampaignService.Member member = campaigns.addMember(campaign.id, ticket("A").id, null, false, WHO);

    BadRequestException shape =
        assertThrows(
            BadRequestException.class,
            () ->
                campaigns.setCondition(
                    campaign.id,
                    member.membership().id,
                    List.of(
                        new CampaignService.GroupSpec(List.of()),
                        group(
                            new CampaignService.CriterionSpec(
                                null, "ENTITY_STATUS", Map.of("status", "GONE")),
                            new CampaignService.CriterionSpec(null, "WEATHER", Map.of()))),
                    WHO));
    assertTrue(shape.getMessage().contains("group 1 has no criteria"), shape.getMessage());
    assertTrue(shape.getMessage().contains("group 2, criterion 1: entityId is required"));
    assertTrue(shape.getMessage().contains("group 2, criterion 1: unknown status GONE"));
    assertTrue(shape.getMessage().contains("group 2, criterion 2: unknown kind WEATHER"));

    WorkEntity outsider = ticket("Not a member");
    BadRequestException target =
        assertThrows(
            BadRequestException.class,
            () ->
                campaigns.setCondition(
                    campaign.id,
                    member.membership().id,
                    List.of(group(restated(null, "ENTITY_STATUS", outsider.id))),
                    WHO));
    assertTrue(target.getMessage().contains("is not a member of this campaign"), target.getMessage());
  }

  @Test
  void anApprovalIsLatchedOnceAndOnlyOnAnApproval() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    campaigns.addMember(campaign.id, a.id, null, false, WHO);
    CampaignService.Member b = campaigns.addMember(campaign.id, ticket("B").id, null, false, WHO);
    CampaignService.Member gated =
        campaigns.setCondition(
            campaign.id,
            b.membership().id,
            List.of(group(approval(null), restated(null, "ENTITY_STATUS", a.id))),
            WHO);
    String approvalId = gated.groups().get(0).criteria().get(0).id;
    String statusId = gated.groups().get(0).criteria().get(1).id;

    conflict(
        () -> campaigns.approve(campaign.id, b.membership().id, statusId, null, "xion"),
        "not APPROVAL");
    CampaignService.Approved approved =
        campaigns.approve(campaign.id, b.membership().id, approvalId, null, "xion");
    assertTrue(approved.satisfied().isEmpty(), "the other half of the group is still open");
    conflict(
        () -> campaigns.approve(campaign.id, b.membership().id, approvalId, null, "someone"),
        "already approved by xion");
  }

  // --- the latch from the current state ------------------------------------------------------------

  @Test
  void theCurrentStateLatchesAtOrPastTheWantedStatusAndNeverFromDropped() {
    WorkEntity campaign = campaign();
    WorkEntity verified = walk(ticket("Verified"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED");
    WorkEntity done = walk(ticket("Done"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");
    WorkEntity dropped = walk(ticket("Dropped"), "DROPPED");
    WorkEntity refined = walk(ticket("Refined"), "REFINED");
    WorkEntity waiter = ticket("Waiter");
    for (WorkEntity member : List.of(verified, done, dropped, refined)) {
      campaigns.addMember(campaign.id, member.id, 0, false, WHO);
    }
    CampaignService.Member waiting = campaigns.addMember(campaign.id, waiter.id, null, false, WHO);
    campaigns.setCondition(
        campaign.id,
        waiting.membership().id,
        List.of(
            group(restated(null, "ENTITY_STATUS", verified.id), restated(null, "ENTITY_STATUS", done.id)),
            group(restated(null, "ENTITY_STATUS", dropped.id)),
            group(restated(null, "ENTITY_STATUS", refined.id))),
        WHO);

    List<String> satisfied = campaigns.latchFromCurrentState(campaign.id);

    assertEquals(List.of(waiting.membership().id), satisfied, "the first group now holds");
    Map<String, CampaignCriterion> byTarget = new LinkedHashMap<>();
    for (CampaignService.Group group : member(campaign, waiting.membership().id).groups()) {
      for (CampaignCriterion criterion : group.criteria()) {
        byTarget.put(((CriterionPredicate.EntityStatusIs) criterion.decoded()).entityId(), criterion);
      }
    }
    CampaignCriterion atVerified = byTarget.get(verified.id);
    assertNotNull(atVerified.satisfiedAt);
    assertEquals(CampaignCriterion.STATE_AT_START, atVerified.evidenceSignature);
    assertNull(atVerified.evidenceEventId);
    assertEquals(
        "#" + verified.number + " was already VERIFIED when the campaign started",
        atVerified.evidenceSummary);
    assertNotNull(byTarget.get(done.id).satisfiedAt, "DONE is past VERIFIED");
    assertEquals(
        "#" + done.number + " was already DONE when the campaign started",
        byTarget.get(done.id).evidenceSummary);
    assertNull(byTarget.get(dropped.id).satisfiedAt, "DROPPED is never past anything");
    assertNull(byTarget.get(refined.id).satisfiedAt, "REFINED is below VERIFIED");

    assertTrue(campaigns.latchFromCurrentState(campaign.id).isEmpty(), "a latch fires once");
  }

  @Test
  void aClaimedMembershipIsNotLatched() {
    WorkEntity campaign = campaign();
    WorkEntity a = ticket("A");
    campaigns.addMember(campaign.id, a.id, null, false, WHO);
    CampaignService.Member b = campaigns.addMember(campaign.id, ticket("B").id, null, false, WHO);
    walk(a, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              memberships.findById(b.membership().id).claimedAt = Instant.now();
            });

    assertTrue(campaigns.latchFromCurrentState(campaign.id).isEmpty());
    assertNull(member(campaign, b.membership().id).groups().get(0).criteria().get(0).satisfiedAt);
  }

  @Test
  void anActiveStartLatchesANewMembersSeedAtOnce() {
    WorkEntity campaign = campaign();
    WorkEntity a = walk(ticket("A"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    campaigns.addMember(campaign.id, a.id, null, false, WHO);
    walk(campaign, "REFINED");
    start(campaign.id);
    walk(a, "VERIFIED");
    // B goes in front, so it gets no seed; its condition is stated by hand, on a member that is
    // already VERIFIED — and with the start active, the same write latches it.
    CampaignService.Member b = campaigns.addMember(campaign.id, ticket("B").id, 0, false, WHO);
    assertTrue(b.groups().isEmpty());
    CampaignService.Member onA =
        campaigns.setCondition(
            campaign.id, b.membership().id, List.of(group(restated(null, "ENTITY_STATUS", a.id))), WHO);

    assertNotNull(onA.groups().get(0).criteria().get(0).satisfiedAt, "latched in the same write");
    assertEquals(
        CampaignCriterion.STATE_AT_START, onA.groups().get(0).criteria().get(0).evidenceSignature);
  }

  // --- the pause hook ------------------------------------------------------------------------------

  @Test
  void leavingRefinedPausesTheStartInTheSameMove() {
    WorkEntity campaign = walk(campaign(), "REFINED");
    start(campaign.id);
    assertTrue(active(campaign.id));

    walk(campaign, "REPORTED");
    assertFalse(active(campaign.id), "paused with the move");

    walk(campaign, "REFINED");
    assertFalse(active(campaign.id), "moving back is not a resume; a new press is");

    WorkEntity neverStarted = walk(campaign(), "REFINED", "REPORTED");
    assertTrue(inTx(() -> starts.startOf(neverStarted.id).isEmpty()), "no row, nothing to do");
  }

  // --- the audit -----------------------------------------------------------------------------------

  @Test
  void everyMembershipChangeIsAnUpdateOfTheCampaign() {
    WorkEntity campaign = campaign();
    CampaignService.Member member = campaigns.addMember(campaign.id, ticket("A").id, null, false, WHO);
    campaigns.setCondition(campaign.id, member.membership().id, List.of(group(approval(null))), WHO);
    campaigns.removeMember(campaign.id, member.membership().id, WHO);

    List<String> changes =
        inTx(
            () ->
                audit.listForEntity(AuditEntityType.CAMPAIGN, campaign.id).stream()
                    .map(entry -> entry.snapshot)
                    .filter(snapshot -> snapshot.contains("\"change\""))
                    .toList());
    assertEquals(3, changes.size(), changes.toString());
    assertTrue(changes.stream().anyMatch(s -> s.contains("MEMBER_ADDED")));
    assertTrue(changes.stream().anyMatch(s -> s.contains("CONDITION_SET")));
    assertTrue(changes.stream().anyMatch(s -> s.contains("MEMBER_REMOVED")));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private WorkEntity campaign() {
    return workEntities.createCampaign(PROJECT, "The order", null, WHO);
  }

  private WorkEntity ticket(String title) {
    return workEntities
        .create(
            Archetype.TICKET, PROJECT, EntityWrite.ticket(title, "it occurs", null, "BUG", null), WHO)
        .entity();
  }

  /** Moves {@code row} through each status in turn and answers it as it ends up. */
  private WorkEntity walk(WorkEntity row, String... statuses) {
    WorkEntity moved = row;
    for (String status : statuses) {
      moved = workEntities.transition(row.archetype, row.id, status, WHO).entity();
    }
    return moved;
  }

  private void start(String campaignId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CampaignStartRecord start = new CampaignStartRecord();
              start.campaignId = campaignId;
              start.firstStartedAt = Instant.now();
              start.startedAt = start.firstStartedAt;
              start.startedBy = WHO;
              start.active = true;
              starts.persist(start);
            });
  }

  private boolean active(String campaignId) {
    return inTx(() -> starts.isActive(campaignId));
  }

  private static <T> T inTx(java.util.concurrent.Callable<T> read) {
    return QuarkusTransaction.requiringNew().call(read);
  }

  private CampaignService.Member member(WorkEntity campaign, String membershipId) {
    return campaigns.get(campaign.id).members().stream()
        .filter(m -> m.membership().id.equals(membershipId))
        .findFirst()
        .orElseThrow();
  }

  private static CampaignService.GroupSpec group(CampaignService.CriterionSpec... criteria) {
    return new CampaignService.GroupSpec(List.of(criteria));
  }

  private static CampaignService.CriterionSpec approval(String id) {
    return new CampaignService.CriterionSpec(id, "APPROVAL", Map.of());
  }

  /** A criterion restating {@code id} (or a new one when null); ENTITY_STATUS waits on VERIFIED. */
  private static CampaignService.CriterionSpec restated(String id, String kind, String target) {
    Map<String, Object> predicate =
        target == null ? Map.of() : Map.of("entityId", target, "status", "VERIFIED");
    return new CampaignService.CriterionSpec(id, kind, predicate);
  }

  private static void assertSeededOn(CampaignService.Member member, String predecessorId) {
    assertEquals(1, member.groups().size(), "one group");
    List<CampaignCriterion> criteria = member.groups().get(0).criteria();
    assertEquals(1, criteria.size(), "one criterion");
    CampaignCriterion seeded = criteria.get(0);
    assertTrue(seeded.seeded);
    assertEquals(CriterionKind.ENTITY_STATUS, seeded.kind);
    assertEquals(
        new CriterionPredicate.EntityStatusIs(predecessorId, "VERIFIED"), seeded.decoded());
    assertNull(seeded.satisfiedAt);
  }

  private static void conflict(Executable call, String fragment) {
    EntitiesException refused = assertThrows(ConflictException.class, call);
    assertEquals(409, refused.statusCode());
    assertTrue(refused.getMessage().contains(fragment), refused.getMessage());
  }

  private static List<String> memberIds(CampaignService.Campaign campaign) {
    return campaign.members().stream().map(member -> member.membership().childId).toList();
  }

  private static List<Integer> positions(CampaignService.Campaign campaign) {
    return campaign.members().stream().map(member -> member.membership().position).toList();
  }

  private static List<String> criterionIdsAndPredicates(CampaignService.Campaign campaign) {
    return campaign.members().stream()
        .flatMap(member -> member.groups().stream())
        .flatMap(group -> group.criteria().stream())
        .map(criterion -> criterion.id + " " + criterion.predicate)
        .sorted()
        .toList();
  }

  private static List<String> allCriterionIds(CampaignService.Campaign campaign) {
    return campaign.members().stream()
        .flatMap(member -> member.groups().stream())
        .flatMap(group -> group.criteria().stream())
        .map(criterion -> criterion.id)
        .toList();
  }
}
