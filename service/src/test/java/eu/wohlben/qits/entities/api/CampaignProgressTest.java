package eu.wohlben.qits.entities.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignCriterionProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignEvaluatorDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberState;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.campaign.CampaignCriterion;
import eu.wohlben.qits.entities.campaign.CampaignCriterionGroup;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.campaign.CampaignStartRecord;
import eu.wohlben.qits.entities.campaign.CriterionPredicate;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.MembershipKind;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link CampaignProgress} over built rows (qits-418) — no database and no Quarkus: the derivation
 * is pure, so every state, every unsatisfiable reason and every kind of evidence is a row built
 * here and a word read back.
 */
class CampaignProgressTest {

  private static final Instant T = Instant.parse("2026-09-27T12:00:00Z");

  private static final CampaignEvaluatorDto DARK = new CampaignEvaluatorDto(false, null, false);

  /** Qualifies every row of project {@code p-qits} as {@code qits-<n>}. */
  private static final Function<WorkEntity, String> QITS =
      row -> "p-qits".equals(row.projectId) ? "qits-" + row.number : null;

  private int numbers = 400;

  // --- the eight states ----------------------------------------------------------------------------

  @Test
  void everyStateIsDerivedInOrderAndAVerifiedMemberIsDoneClaimedOrNot() {
    WorkEntity dropped = entity("Dropped", "DROPPED");
    WorkEntity verified = entity("Verified by hand", "VERIFIED");
    WorkEntity done = entity("Done", "DONE");
    WorkEntity failed = entity("Failed", "REFINED");
    WorkEntity joined = entity("Joined", "IMPLEMENTED");
    WorkEntity running = entity("Running", "REFINED");
    WorkEntity refused = entity("Refused", "REFINED");
    WorkEntity ready = entity("Ready", "REFINED");
    WorkEntity waiting = entity("Waiting", "REFINED");

    EntityMembership droppedEdge = edge(dropped, 0);
    droppedEdge.claimedAt = T; // DROPPED wins over any run record
    droppedEdge.dispatchError = "boom";
    EntityMembership verifiedEdge = edge(verified, 1); // unclaimed: finished elsewhere
    EntityMembership doneEdge = edge(done, 2);
    doneEdge.claimedAt = T;
    doneEdge.dispatchError = "failed here, finished later"; // DONE still wins
    EntityMembership failedEdge = edge(failed, 3);
    failedEdge.claimedAt = T;
    failedEdge.joinedRunning = true; // the error wins over joined_running
    failedEdge.dispatchError = "the workspaces service answered 502";
    EntityMembership joinedEdge = edge(joined, 4);
    joinedEdge.claimedAt = T;
    joinedEdge.joinedRunning = true;
    EntityMembership runningEdge = edge(running, 5);
    runningEdge.claimedAt = T;
    runningEdge.dispatchedAt = T;
    runningEdge.dispatchWorkspaceId = "41";
    runningEdge.dispatchBranch = "ticket/running";
    runningEdge.dispatchAgentLaunch = "LAUNCHED";
    EntityMembership refusedEdge = edge(refused, 6);
    refusedEdge.dispatchRefusal = "ticket qits-406 is blocked";
    refusedEdge.dispatchRefusedAt = T;
    EntityMembership readyEdge = edge(ready, 7);
    EntityMembership waitingEdge = edge(waiting, 8);

    CampaignProgressDto progress =
        derive(
            List.of(
                member(droppedEdge, dropped),
                member(verifiedEdge, verified),
                member(doneEdge, done),
                member(failedEdge, failed),
                member(joinedEdge, joined),
                member(runningEdge, running),
                member(refusedEdge, refused),
                member(readyEdge, ready),
                member(waitingEdge, waiting, group(approval()))),
            Map.of());

    assertEquals(
        List.of(
            CampaignMemberState.DROPPED,
            CampaignMemberState.DONE,
            CampaignMemberState.DONE,
            CampaignMemberState.DISPATCH_FAILED,
            CampaignMemberState.JOINED_RUNNING,
            CampaignMemberState.RUNNING,
            CampaignMemberState.REFUSED,
            CampaignMemberState.READY,
            CampaignMemberState.WAITING),
        progress.members().stream().map(CampaignMemberProgressDto::state).toList());

    CampaignMemberProgressDto run = progress.members().get(5);
    assertEquals(T, run.dispatchedAt());
    assertEquals("41", run.dispatch().workspaceId());
    assertEquals("ticket/running", run.dispatch().branch());
    assertEquals("LAUNCHED", run.dispatch().agentLaunch());
    CampaignMemberProgressDto refusal = progress.members().get(6);
    assertEquals("ticket qits-406 is blocked", refusal.dispatchRefusal());
    assertEquals(T, refusal.dispatchRefusedAt());
    assertEquals(
        "the workspaces service answered 502", progress.members().get(3).dispatchError());
    assertTrue(progress.members().get(4).joinedRunning());
  }

  @Test
  void aRefusalOnAMemberWhoseConditionNoLongerHoldsIsWaitingNotRefused() {
    WorkEntity target = entity("Target", "REFINED");
    WorkEntity gated = entity("Gated", "REFINED");
    EntityMembership edge = edge(gated, 1);
    edge.dispatchRefusal = "left over";
    CampaignProgressDto progress =
        derive(
            List.of(
                member(edge(target, 0), target),
                member(edge, gated, group(status(target, "VERIFIED")))),
            Map.of(target.id, target));
    assertEquals(CampaignMemberState.WAITING, progress.members().get(1).state());
  }

  // --- the unsatisfiable reasons -------------------------------------------------------------------

  @Test
  void eachUnsatisfiableReasonIsReportedForEntityStatusOnly() {
    WorkEntity gone = entity("Gone", "REFINED"); // never handed over as a target: deleted
    WorkEntity droppedTarget = entity("Dropped target", "DROPPED");
    WorkEntity outsider = entity("Left the campaign", "REFINED");
    WorkEntity ahead = entity("Already verified", "VERIFIED");
    WorkEntity pending = entity("Still going", "IMPLEMENTED");
    WorkEntity waiter = entity("Waiter", "REFINED");

    EntityMembership waiterEdge = edge(waiter, 3);
    CampaignProgressDto progress =
        derive(
            List.of(
                member(edge(droppedTarget, 0), droppedTarget),
                member(edge(ahead, 1), ahead),
                member(edge(pending, 2), pending),
                member(
                    waiterEdge,
                    waiter,
                    group(
                        status(gone, "VERIFIED"),
                        status(droppedTarget, "VERIFIED"),
                        status(outsider, "VERIFIED"),
                        status(ahead, "VERIFIED"),
                        status(pending, "VERIFIED"),
                        deployment("qits-projects", "dev", "2026.930.1"),
                        release("qits-qits", null, null),
                        approval()))),
            targets(droppedTarget, outsider, ahead, pending));

    List<CampaignCriterionProgressDto> criteria = criteriaOf(progress, 3);
    assertReason(criteria.get(0), "target deleted");
    assertReason(criteria.get(1), "target dropped");
    assertReason(criteria.get(2), "target no longer a member");
    assertReason(
        criteria.get(3),
        "qits-" + ahead.number + " is already VERIFIED; press start to latch it from the current state");
    assertReason(criteria.get(4), null);
    for (CampaignCriterionProgressDto event : criteria.subList(5, 8)) {
      assertReason(event, null); // an event kind is never judged unsatisfiable
    }

    assertEquals(gone.id + " reaches VERIFIED", criteria.get(0).wouldBeSatisfiedBy());
    assertEquals(
        "qits-" + pending.number + " (Still going) reaches VERIFIED",
        criteria.get(4).wouldBeSatisfiedBy());
    assertEquals(
        "DeploymentActive of qits-projects at or above 2026.930.1 in dev",
        criteria.get(5).wouldBeSatisfiedBy());
    assertEquals("SCMRelease of qits-qits", criteria.get(6).wouldBeSatisfiedBy());
    assertEquals("a person approves", criteria.get(7).wouldBeSatisfiedBy());

    assertEquals(
        List.of(gone.id, droppedTarget.id, outsider.id, ahead.id, pending.id),
        progress.members().get(3).waitsFor());
  }

  @Test
  void theAlreadyReasonIsForAnUnclaimedMemberAndALatchedCriterionIsAlwaysSatisfiable() {
    WorkEntity ahead = entity("Ahead", "DONE");
    WorkEntity claimed = entity("Claimed", "IMPLEMENTED");
    WorkEntity latched = entity("Latched", "REFINED");
    EntityMembership claimedEdge = edge(claimed, 1);
    claimedEdge.claimedAt = T;
    claimedEdge.joinedRunning = true;
    CampaignCriterion stillOpen = status(ahead, "VERIFIED");
    CampaignCriterion satisfied = status(ahead, "VERIFIED");
    satisfied.satisfiedAt = T;
    satisfied.evidenceEventId = UUID.fromString("00000000-0000-0000-0000-000000000418");
    satisfied.evidenceSignature = "EntityTransitioned";
    satisfied.evidenceSummary = "qits-" + ahead.number + " moved VERIFIED -> DONE";
    CampaignProgressDto progress =
        derive(
            List.of(
                member(edge(ahead, 0), ahead),
                member(claimedEdge, claimed, group(stillOpen)),
                member(edge(latched, 2), latched, group(satisfied))),
            Map.of(ahead.id, ahead));

    assertReason(criteriaOf(progress, 1).get(0), null);
    CampaignCriterionProgressDto evidenced = criteriaOf(progress, 2).get(0);
    assertTrue(evidenced.satisfied());
    assertReason(evidenced, null);
    assertTrue(progress.members().get(2).groups().get(0).satisfied());
    assertEquals(CampaignMemberState.READY, progress.members().get(2).state());
  }

  // --- evidence ------------------------------------------------------------------------------------

  @Test
  void aLatchCarriesItsEvidenceFromAnEventAPersonOrTheStateAtStart() {
    WorkEntity target = entity("Target", "VERIFIED");
    WorkEntity member = entity("Member", "REFINED");

    CampaignCriterion byEvent = deployment("qits-projects", null, "2026.930.1");
    byEvent.satisfiedAt = T;
    byEvent.evidenceEventId = UUID.fromString("00000000-0000-0000-0000-00000000e7e7");
    byEvent.evidenceSignature = "DeploymentActive";
    byEvent.evidenceSummary = "qits-projects 2026.930.2 went active in dev";

    CampaignCriterion byPerson = approval();
    byPerson.satisfiedAt = T.plusSeconds(1);
    byPerson.approvedBy = "xion";
    byPerson.approvalNote = "go";

    CampaignCriterion atStart = status(target, "VERIFIED");
    atStart.satisfiedAt = T.plusSeconds(2);
    atStart.evidenceSignature = CampaignCriterion.STATE_AT_START;
    atStart.evidenceSummary =
        "qits-" + target.number + " was already VERIFIED when the campaign started";

    CampaignCriterion open = release("qits-qits", "p-qits", "2026.930.1");

    CampaignProgressDto progress =
        derive(
            List.of(
                member(edge(target, 0), target),
                member(edge(member, 1), member, group(byEvent, byPerson, atStart), group(open))),
            Map.of(target.id, target));

    List<CampaignCriterionProgressDto> first = criteriaOf(progress, 1);
    CampaignCriterionProgressDto event = first.get(0);
    assertTrue(event.satisfied());
    assertEquals(T, event.satisfiedAt());
    assertEquals("00000000-0000-0000-0000-00000000e7e7", event.evidence().eventId());
    assertEquals("DeploymentActive", event.evidence().signature());
    assertEquals("qits-projects 2026.930.2 went active in dev", event.evidence().summary());
    assertNull(event.approval());

    CampaignCriterionProgressDto person = first.get(1);
    assertEquals("xion", person.approval().approvedBy());
    assertEquals("go", person.approval().note());
    assertNull(person.evidence());
    assertEquals(T.plusSeconds(1), person.satisfiedAt());

    CampaignCriterionProgressDto state = first.get(2);
    assertNull(state.evidence().eventId());
    assertEquals(CampaignCriterion.STATE_AT_START, state.evidence().signature());
    assertEquals(
        "qits-" + target.number + " was already VERIFIED when the campaign started",
        state.evidence().summary());

    CampaignCriterionProgressDto outstanding = criteriaOf(progress, 1, 1).get(0);
    assertFalse(outstanding.satisfied());
    assertNull(outstanding.evidence());
    assertNull(outstanding.approval());
    assertNull(outstanding.satisfiedAt());
    assertEquals(
        "SCMRelease of qits-qits at or above 2026.930.1 in project p-qits",
        outstanding.wouldBeSatisfiedBy());

    assertTrue(progress.members().get(1).groups().get(0).satisfied());
    assertFalse(progress.members().get(1).groups().get(1).satisfied());
    assertEquals(CampaignMemberState.READY, progress.members().get(1).state(), "any group holds");
  }

  // --- the dependency is the criteria, not the order ----------------------------------------------

  /**
   * A was first and B was seeded on it; then B was moved to the front. The criteria still say B
   * waits on A, so the read — listed in position order — shows B first and WAITING on A, and A,
   * now second, READY: the order is presentation and the criteria are the dependency.
   */
  @Test
  void aMoveReordersTheRowsAndLeavesTheCriteriaSayingWhoWaitsOnWhom() {
    WorkEntity a = entity("A", "REFINED");
    WorkEntity b = entity("B", "REFINED");
    WorkEntity c = entity("C", "REFINED");
    CampaignCriterion seed = status(a, "VERIFIED");
    seed.seeded = true;
    CampaignCriterion cSeed = status(b, "VERIFIED");
    cSeed.seeded = true;

    CampaignProgressDto progress =
        derive(
            List.of(
                member(edge(b, 0), b, group(seed)),
                member(edge(a, 1), a),
                member(edge(c, 2), c, group(cSeed))),
            targets(a, b));

    assertEquals(
        List.of(b.id, a.id, c.id),
        progress.members().stream().map(m -> m.entity().id()).toList());
    assertEquals(
        List.of(0, 1, 2), progress.members().stream().map(CampaignMemberProgressDto::position).toList());
    assertEquals(CampaignMemberState.WAITING, progress.members().get(0).state());
    assertEquals(List.of(a.id), progress.members().get(0).waitsFor());
    assertEquals(CampaignMemberState.READY, progress.members().get(1).state());
    assertEquals(List.of(), progress.members().get(1).waitsFor());
    assertEquals(List.of(b.id), progress.members().get(2).waitsFor());
    CampaignCriterionProgressDto seeded = criteriaOf(progress, 0).get(0);
    assertTrue(seeded.seeded());
    assertTrue(seeded.satisfiable());
    assertEquals("qits-" + a.number + " (A) reaches VERIFIED", seeded.wouldBeSatisfiedBy());
  }

  // --- the campaign and the evaluator --------------------------------------------------------------

  @Test
  void theCampaignAndTheEvaluatorAreCarriedAsGiven() {
    WorkEntity campaign = entity("Rename qits-x", "REFINED");
    campaign.archetype = Archetype.CAMPAIGN;
    CampaignStartRecord start = new CampaignStartRecord();
    start.campaignId = campaign.id;
    start.firstStartedAt = T;
    start.startedAt = T.plusSeconds(60);
    start.startedBy = "xion";
    start.active = true;
    CampaignEvaluatorDto live = new CampaignEvaluatorDto(true, T, false);

    CampaignProgressDto progress =
        CampaignProgress.derive(
            new CampaignService.ProgressRead(
                new CampaignService.Campaign(campaign, start, List.of()), Map.of()),
            QITS,
            live);

    assertEquals(campaign.id, progress.campaign().id());
    assertEquals("qits-" + campaign.number, progress.campaign().qualifiedId());
    assertEquals("Rename qits-x", progress.campaign().title());
    assertEquals("REFINED", progress.campaign().status());
    assertEquals("xion", progress.campaign().start().startedBy());
    assertEquals(T, progress.campaign().start().firstStartedAt());
    assertTrue(progress.campaign().start().active());
    assertEquals(live, progress.evaluator());
    assertEquals(List.of(), progress.members());
  }

  @Test
  void anUnqualifiableRowFallsBackToItsNumberInASentence() {
    WorkEntity target = entity("Elsewhere", "REFINED");
    target.projectId = "p-unknown";
    WorkEntity member = entity("Member", "REFINED");
    member.projectId = "p-unknown";
    CampaignProgressDto progress =
        derive(
            List.of(
                member(edge(target, 0), target),
                member(edge(member, 1), member, group(status(target, "IMPLEMENTED")))),
            Map.of(target.id, target));
    assertNull(progress.members().get(0).entity().qualifiedId());
    assertEquals(
        "#" + target.number + " (Elsewhere) reaches IMPLEMENTED",
        criteriaOf(progress, 1).get(0).wouldBeSatisfiedBy());
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private CampaignProgressDto derive(
      List<CampaignService.Member> members, Map<String, WorkEntity> targets) {
    WorkEntity campaign = entity("Campaign", "REFINED");
    campaign.archetype = Archetype.CAMPAIGN;
    return CampaignProgress.derive(
        new CampaignService.ProgressRead(
            new CampaignService.Campaign(campaign, null, members), targets),
        QITS,
        DARK);
  }

  private static List<CampaignCriterionProgressDto> criteriaOf(
      CampaignProgressDto progress, int member) {
    return criteriaOf(progress, member, 0);
  }

  private static List<CampaignCriterionProgressDto> criteriaOf(
      CampaignProgressDto progress, int member, int group) {
    return progress.members().get(member).groups().get(group).criteria();
  }

  private static void assertReason(CampaignCriterionProgressDto criterion, String reason) {
    assertEquals(reason, criterion.reason(), criterion.wouldBeSatisfiedBy());
    assertEquals(reason == null, criterion.satisfiable(), criterion.wouldBeSatisfiedBy());
  }

  private WorkEntity entity(String title, String status) {
    WorkEntity row = new WorkEntity();
    row.id = UUID.randomUUID().toString();
    row.projectId = "p-qits";
    row.archetype = Archetype.TICKET;
    row.number = numbers++;
    row.title = title;
    row.status = status;
    return row;
  }

  private static Map<String, WorkEntity> targets(WorkEntity... rows) {
    Map<String, WorkEntity> map = new LinkedHashMap<>();
    for (WorkEntity row : rows) {
      map.put(row.id, row);
    }
    return map;
  }

  private static EntityMembership edge(WorkEntity entity, int position) {
    EntityMembership edge = new EntityMembership();
    edge.id = UUID.randomUUID().toString();
    edge.kind = MembershipKind.CAMPAIGN;
    edge.parentId = "campaign";
    edge.childId = entity.id;
    edge.position = position;
    return edge;
  }

  @SafeVarargs
  private static CampaignService.Member member(
      EntityMembership edge, WorkEntity entity, List<CampaignCriterion>... groups) {
    List<CampaignService.Group> built = new ArrayList<>();
    int position = 0;
    for (List<CampaignCriterion> criteria : groups) {
      CampaignCriterionGroup group = new CampaignCriterionGroup();
      group.id = UUID.randomUUID().toString();
      group.membershipId = edge.id;
      group.position = position++;
      for (CampaignCriterion criterion : criteria) {
        criterion.groupId = group.id;
      }
      built.add(new CampaignService.Group(group, criteria));
    }
    return new CampaignService.Member(edge, entity, built);
  }

  private static List<CampaignCriterion> group(CampaignCriterion... criteria) {
    return Arrays.asList(criteria);
  }

  private static CampaignCriterion criterion(CriterionPredicate predicate) {
    CampaignCriterion criterion = new CampaignCriterion();
    criterion.id = UUID.randomUUID().toString();
    criterion.kind = predicate.kind();
    criterion.predicate = predicate.canonicalJson();
    return criterion;
  }

  private static CampaignCriterion status(WorkEntity target, String status) {
    return criterion(new CriterionPredicate.EntityStatusIs(target.id, status));
  }

  private static CampaignCriterion deployment(String application, String env, String floor) {
    return criterion(new CriterionPredicate.DeploymentActive(application, env, floor));
  }

  private static CampaignCriterion release(String repository, String projectId, String floor) {
    return criterion(new CriterionPredicate.ScmRelease(repository, projectId, floor));
  }

  private static CampaignCriterion approval() {
    return criterion(new CriterionPredicate.Approval());
  }
}
