package eu.wohlben.qits.projects.campaignhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.campaign.CampaignCriterion;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.api.DispatchMode;
import eu.wohlben.qits.projects.api.DispatchRefused;
import eu.wohlben.qits.projects.api.EntityBlocks;
import eu.wohlben.qits.projects.api.EntityDispatch;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.bus.CampaignCriteriaListener;
import eu.wohlben.qits.projects.bus.EntityTransitioned;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.arc.ClientProxy;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The campaign executor (qits-417) against the real database: the claim, the dispatch, the record,
 * the refusals, the start press and the retries — every case the task names, the headline first.
 *
 * <p>No {@code @TestProfile} (the test-profile budget rule). {@link EntityDispatch} is replaced per
 * test through {@link QuarkusMock} where a test needs to count or to step in between two steps of
 * the sequence; everywhere else the real dispatch runs against the suite's {@link
 * RecordingWorkspaceAgentDispatch}, so a dispatch is one recorded port call.
 */
@QuarkusTest
class CampaignExecutorTest {

  private String projectId;

  @Inject CampaignExecutor executor;
  @Inject CampaignService campaigns;
  @Inject WorkEntityService workEntities;
  @Inject EntityDispatch entityDispatch;
  @Inject RecordingWorkspaceAgentDispatch port;
  @Inject CampaignCriteriaListener listener;
  @Inject EntityBlocks blocks;

  @Inject
  @PersistenceUnit("epics")
  EntityManager em;

  /**
   * A project with a wrapper, per test: {@code PlatformStateReset} empties the projects tables
   * before every method, and the dispatch reads the project and its wrapper.
   */
  @BeforeEach
  void setUp() {
    port.reset();
    projectId =
        asAdmin("setup")
            .body(
                new ProjectController.CreateProjectRequest(
                    "Campaign Executor", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
  }

  @AfterEach
  void quiet() throws Exception {
    awaitQuiet();
  }

  // --- 1. the headline: two concurrent attempts, one dispatch ------------------------------------

  @Test
  void twoConcurrentAttemptsOnOneSatisfiedMembershipDispatchExactlyOnce() throws Exception {
    Fixture f = campaignOf(scheduled("Only once"));
    campaigns.start(f.campaign.id, "dana");

    CountingDispatch counting = countingDispatch();
    // Both attempts pass steps 1 and 2 before either claims, so the claim is what decides.
    CyclicBarrier bothPrechecked = new CyclicBarrier(2);
    counting.onFirstPrecheckPerThread = member -> await(bothPrechecked);
    counting.delayDispatchMs = 300;

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      List<Future<CampaignExecutor.Attempt>> attempts = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        attempts.add(
            threads.submit(
                () -> {
                  go.await();
                  return executor.tryDispatch(f.membershipIds.get(0), null);
                }));
      }
      go.countDown();
      List<CampaignExecutor.Attempt> outcomes = new ArrayList<>();
      for (Future<CampaignExecutor.Attempt> attempt : attempts) {
        outcomes.add(attempt.get(30, TimeUnit.SECONDS));
      }
      Collections.sort(outcomes);
      assertEquals(
          List.of(CampaignExecutor.Attempt.LOST, CampaignExecutor.Attempt.DISPATCHED), outcomes);
    } finally {
      threads.shutdownNow();
    }

    assertEquals(1, counting.dispatches.get(), "exactly one EntityDispatch.dispatch");
    assertEquals(1, port.calls().size(), "one workspace, one agent");
    EntityMembership edge = membership(f, 0);
    assertNotNull(edge.claimedAt);
    assertNotNull(edge.dispatchedAt);
    assertEquals("41", edge.dispatchWorkspaceId);
    assertEquals(port.lastCall().branch(), edge.dispatchBranch);
    assertEquals("SCHEDULED", edge.dispatchAgentLaunch);
    assertNull(edge.dispatchError);
  }

  // --- 2. a pause or a condition edit racing the claim ------------------------------------------

  @Test
  void aPauseRacingTheClaimDispatchesNothing() throws Exception {
    Fixture f = campaignOf(scheduled("Paused under me"));
    campaigns.start(f.campaign.id, "dana");
    CountingDispatch counting = countingDispatch();

    // The pause's row lock is held, uncommitted, while the attempt reaches its claim.
    holdingWhile(
        () ->
            em.createNativeQuery(
                    "update campaign_start set active = false where campaign_id = :id")
                .setParameter("id", f.campaign.id)
                .executeUpdate(),
        () ->
            assertEquals(
                CampaignExecutor.Attempt.NOT_READY,
                executor.tryDispatch(f.membershipIds.get(0), null)));

    assertEquals(0, counting.dispatches.get());
    assertNull(membership(f, 0).claimedAt);
  }

  @Test
  void aConditionEditRacingTheClaimDispatchesNothing() throws Exception {
    Fixture f = campaignOf(scheduled("Gated under me"));
    campaigns.start(f.campaign.id, "dana");
    CountingDispatch counting = countingDispatch();
    String membershipId = f.membershipIds.get(0);

    // setCondition's shape: the membership row locked, then an unmet criterion written under it.
    holdingWhile(
        () -> {
          em.createNativeQuery("select id from entity_membership where id = :id for update")
              .setParameter("id", membershipId)
              .getResultList();
          String groupId = UUID.randomUUID().toString();
          em.createNativeQuery(
                  "insert into campaign_criterion_group (id, membership_id, position, created_at)"
                      + " values (:id, :m, 0, now())")
              .setParameter("id", groupId)
              .setParameter("m", membershipId)
              .executeUpdate();
          em.createNativeQuery(
                  "insert into campaign_criterion (id, group_id, position, kind, predicate,"
                      + " created_at) values (:id, :g, 0, 'APPROVAL', '{}', now())")
              .setParameter("id", UUID.randomUUID().toString())
              .setParameter("g", groupId)
              .executeUpdate();
          return 1;
        },
        () ->
            assertEquals(
                CampaignExecutor.Attempt.NOT_READY, executor.tryDispatch(membershipId, null)));

    assertEquals(0, counting.dispatches.get());
    assertNull(membership(f, 0).claimedAt);
  }

  // --- 3. and 4. a member joined in flight ------------------------------------------------------

  @Test
  void aMemberJoinedInFlightIsNeverDispatchedAndItsSuccessorStartsWhenItIsVerified() {
    WorkEntity running = walk(ticket("Already running"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    Fixture f = campaignOf(List.of(running, scheduled("Waits on it")), List.of(true, false));
    assertTrue(membership(f, 0).joinedRunning);

    press(f.campaign.id);
    assertEquals(0, port.calls().size(), "the running member is not dispatched, the next waits");

    walk(running, "VERIFIED");
    deliver(transitioned(running, "IMPLEMENTED", "VERIFIED"));
    awaitQuiet();

    assertEquals(1, port.calls().size());
    assertEquals(f.members.get(1).id, port.lastCall().subject().ticketId());
    assertNotNull(membership(f, 1).dispatchedAt);
    assertNull(membership(f, 0).dispatchedAt, "joined running: claimed, never dispatched");
  }

  @Test
  void aMemberJoinedInFlightThatWasVerifiedBeforeThePressLetsItsSuccessorStartAtThePress() {
    WorkEntity running = walk(ticket("Finished early"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    Fixture f = campaignOf(List.of(running, scheduled("Next")), List.of(true, false));
    walk(running, "VERIFIED");

    press(f.campaign.id);

    assertEquals(1, port.calls().size(), "the press latched the state at start and dispatched");
    assertEquals(f.members.get(1).id, port.lastCall().subject().ticketId());
    CampaignCriterion seed =
        member(f, 1).groups().get(0).criteria().get(0);
    assertEquals(CampaignCriterion.STATE_AT_START, seed.evidenceSignature);
  }

  // --- 5. refused visibly, once, then dispatched -----------------------------------------------

  @Test
  void aSatisfiedMemberAtReportedIsRefusedOnceStaysUnclaimedAndIsDispatchedOnceScheduled() {
    WorkEntity reported = ticket("Not refined yet");
    Fixture f = campaignOf(reported);

    press(f.campaign.id);
    EntityMembership refused = membership(f, 0);
    assertNull(refused.claimedAt);
    assertNotNull(refused.dispatchRefusal);
    assertTrue(refused.dispatchRefusal.contains("REPORTED"), refused.dispatchRefusal);
    Instant refusedAt = refused.dispatchRefusedAt;

    assertEquals(0, executor.sweep(f.campaign.id));
    assertEquals(refusedAt, membership(f, 0).dispatchRefusedAt, "written once, not every sweep");

    walk(reported, "REFINED");
    // qits-887: REFINED starts no phase, so the member waits for a person to schedule it.
    assertEquals(0, executor.sweep(f.campaign.id));
    walk(reported, "READY_FOR_DEV");
    assertEquals(1, executor.sweep(f.campaign.id));
    EntityMembership dispatched = membership(f, 0);
    assertNotNull(dispatched.dispatchedAt);
    assertNull(dispatched.dispatchRefusal, "the claim clears the refusal");
    assertEquals(1, port.calls().size());
    // The executor's dispatch is the press's, so the member starts IMPLEMENTING too (qits-749).
    assertEquals(
        "IMPLEMENTING", workEntities.get(Archetype.TICKET, reported.id).status);
  }

  // --- 6. the member moves between the precheck and the claim ------------------------------------

  @Test
  void aMemberThatMovesBetweenThePrecheckAndTheClaimIsNotDispatchedAndIsRefused() {
    WorkEntity member = scheduled("Moves under me");
    Fixture f = campaignOf(member);
    campaigns.start(f.campaign.id, "dana");
    CountingDispatch counting = countingDispatch();
    counting.onFirstPrecheckPerThread =
        seen -> workEntities.transition(Archetype.TICKET, member.id, "IMPLEMENTED", "someone");

    assertEquals(
        CampaignExecutor.Attempt.REFUSED, executor.tryDispatch(f.membershipIds.get(0), null));

    assertEquals(0, counting.dispatches.get());
    EntityMembership edge = membership(f, 0);
    assertNull(edge.claimedAt);
    assertTrue(edge.dispatchRefusal.contains("IMPLEMENTED"), edge.dispatchRefusal);
  }

  // --- 7. redelivery -------------------------------------------------------------------------

  @Test
  void aRedeliveryOfTheSatisfyingEventAfterTheDispatchChangesNothing() {
    WorkEntity first = scheduled("First");
    Fixture f = campaignOf(List.of(first, scheduled("Second")), List.of(false, false));
    press(f.campaign.id);
    assertEquals(1, port.calls().size(), "the first starts at the press");

    walk(first, "IMPLEMENTED", "VERIFIED");
    EventFrame verified = transitioned(first, "IMPLEMENTED", "VERIFIED");
    deliver(verified);
    awaitQuiet();
    assertEquals(2, port.calls().size());
    EntityMembership once = membership(f, 1);
    Instant latchedAt = member(f, 1).groups().get(0).criteria().get(0).satisfiedAt;

    deliver(verified);
    awaitQuiet();
    executor.sweep(f.campaign.id);

    assertEquals(2, port.calls().size());
    assertEquals(once.dispatchedAt, membership(f, 1).dispatchedAt);
    assertEquals(latchedAt, member(f, 1).groups().get(0).criteria().get(0).satisfiedAt);
  }

  // --- 8. paused until pressed; an approval does not wait for the sweep -------------------------

  @Test
  void aCampaignMovedToReportedDispatchesNothingUntilPressedAgain() {
    Fixture f = campaignOf(scheduled("Gated"));
    String criterionId = gate(f, 0);
    press(f.campaign.id);
    assertEquals(0, port.calls().size(), "waits for the approval");

    workEntities.transition(Archetype.CAMPAIGN, f.campaign.id, "REPORTED", "dana");
    campaigns.approve(f.campaign.id, f.membershipIds.get(0), criterionId, null, "dana");
    awaitQuiet();
    workEntities.transition(Archetype.CAMPAIGN, f.campaign.id, "REFINED", "dana");
    assertEquals(0, executor.sweep(f.campaign.id));
    assertEquals(0, executor.sweep());
    assertEquals(0, port.calls().size(), "moving back to REFINED is not a resume");

    press(f.campaign.id);
    assertEquals(1, port.calls().size());
  }

  @Test
  void anApprovalDispatchesWithoutWaitingForTheSweep() {
    Fixture f = campaignOf(scheduled("Approve me"));
    String criterionId = gate(f, 0);
    press(f.campaign.id);
    assertEquals(0, port.calls().size());

    asAdmin("dana")
        .body(Map.of("note", "go"))
        .post(
            "/projects/api/campaigns/"
                + f.campaign.id
                + "/members/"
                + f.membershipIds.get(0)
                + "/criteria/"
                + criterionId
                + "/approve")
        .then()
        .statusCode(200);
    awaitQuiet();

    assertEquals(1, port.calls().size());
    assertNotNull(membership(f, 0).dispatchedAt);
  }

  // --- 8b. a blocked campaign claims nothing new (qits-592) --------------------------------------

  /**
   * A started campaign that is blocked claims none of its satisfied members — not by an attempt,
   * not by its own sweep, not by the global one — and writes no refusal on them, since a block is a
   * wait and not something wrong with the member. The unblock is itself the trigger: {@code
   * EntityBlocks} runs the campaign's sweep straight after, so the member is dispatched without
   * waiting for the periodic one.
   */
  @Test
  void aBlockedCampaignClaimsNoMemberAndItsUnblockDispatches() {
    Fixture f = campaignOf(scheduled("Held back"));
    campaigns.start(f.campaign.id, "dana");
    workEntities.setBlocked(Archetype.CAMPAIGN, f.campaign.id, true, "dana");

    assertEquals(
        CampaignExecutor.Attempt.NOT_READY, executor.tryDispatch(f.membershipIds.get(0), null));
    assertEquals(0, executor.sweep(f.campaign.id));
    assertEquals(0, executor.sweep());
    assertEquals(0, port.calls().size());
    EntityMembership waiting = membership(f, 0);
    assertNull(waiting.claimedAt);
    assertNull(waiting.dispatchRefusal, "a block is a wait, not a refusal of the member");

    blocks.apply(
        workEntities.get(Archetype.CAMPAIGN, f.campaign.id), false, "the pilot finished", "dana");

    assertEquals(1, port.calls().size(), "the unblock runs the campaign's sweep");
    assertNotNull(membership(f, 0).dispatchedAt);
  }

  /**
   * The claim (step 3) reads the flag again: a block landing after the cheap read and the precheck
   * still stops the claim, so nothing is claimed and nothing is dispatched.
   */
  @Test
  void aBlockLandingBeforeTheClaimClaimsNothing() {
    Fixture f = campaignOf(scheduled("Blocked under me"));
    campaigns.start(f.campaign.id, "dana");
    CountingDispatch counting = countingDispatch();
    counting.onFirstPrecheckPerThread =
        seen -> workEntities.setBlocked(Archetype.CAMPAIGN, f.campaign.id, true, "someone");

    assertEquals(
        CampaignExecutor.Attempt.NOT_READY, executor.tryDispatch(f.membershipIds.get(0), null));

    assertEquals(0, counting.dispatches.get());
    assertNull(membership(f, 0).claimedAt);
  }

  // --- 9. the port failing, and a refusal after the claim --------------------------------------

  @Test
  void anExceptionFromThePortKeepsTheClaimAndTheSweepDoesNotRetryIt() {
    Fixture f = campaignOf(scheduled("The far side is down"));
    port.willFailWith(new DomainException(502, "qits-workspaces answered 502"));

    press(f.campaign.id);
    assertEquals(1, port.calls().size());
    EntityMembership failed = membership(f, 0);
    assertNotNull(failed.claimedAt, "kept");
    assertNull(failed.dispatchedAt);
    assertTrue(failed.dispatchError.contains("qits-workspaces answered 502"), failed.dispatchError);
    assertTrue(failed.dispatchError.contains(DomainException.class.getName()));

    port.reset();
    assertEquals(0, executor.sweep(f.campaign.id));
    assertEquals(0, executor.sweep());
    press(f.campaign.id);
    assertEquals(0, port.calls().size(), "an unknown outcome is never retried");
  }

  @Test
  void aRefusalFromTheDispatchItselfReleasesTheClaim() {
    Fixture f = campaignOf(scheduled("Raced after the claim"));
    campaigns.start(f.campaign.id, "dana");
    CountingDispatch counting = countingDispatch();
    counting.refuseDispatch = new DispatchRefused(409, "moved on since the claim");

    assertEquals(
        CampaignExecutor.Attempt.RELEASED, executor.tryDispatch(f.membershipIds.get(0), null));

    EntityMembership released = membership(f, 0);
    assertNull(released.claimedAt, "released: nothing happened");
    assertEquals("moved on since the claim", released.dispatchRefusal);
    assertEquals(0, port.calls().size());

    counting.refuseDispatch = null;
    assertEquals(
        CampaignExecutor.Attempt.DISPATCHED, executor.tryDispatch(f.membershipIds.get(0), null));
  }

  // --- 10. the door ------------------------------------------------------------------------------

  @Test
  void thePressRefusesPhaseAndAnUnrefinedCampaignAndAnAgentAndTheReadSaysStartThenRecheck() {
    Fixture f = campaignOf(scheduled("Door"), false);
    String path = "/projects/api/entities/" + f.campaign.id + "/dispatch";

    asAdmin("dana")
        .get(path)
        .then()
        .statusCode(200)
        .body("state.archetype", equalTo("CAMPAIGN"))
        .body("state.dispatchable", equalTo(false))
        .body("state.nextPhase", equalTo("start"));
    asAdmin("dana")
        .body(Map.of("mode", "FLOW"))
        .post(path)
        .then()
        .statusCode(409)
        .body("message", containsString("Start a campaign from REFINED"));

    walk(f.campaign, "REFINED");
    asAdmin("dana")
        .body(Map.of("mode", "PHASE"))
        .post(path)
        .then()
        .statusCode(409)
        .body("message", containsString("a campaign presses dispatch only"));
    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "an-agent")
        .header("X-Qits-Roles", "qits:agent")
        .body(Map.of("mode", "FLOW"))
        .post(path)
        .then()
        .statusCode(403);
    assertEquals(0, port.calls().size());
    asAdmin("dana")
        .get(path)
        .then()
        .statusCode(200)
        .body("state.dispatchable", equalTo(true))
        .body("state.nextPhase", equalTo("start"));

    asAdmin("dana")
        .body(Map.of("mode", "FLOW"))
        .post(path)
        .then()
        .statusCode(200)
        .body("progress.campaign.id", equalTo(f.campaign.id))
        .body("progress.campaign.start.startedBy", equalTo("dana"))
        .body("progress.campaign.start.active", equalTo(true))
        .body("progress.evaluator.connected", equalTo(false))
        .body("progress.members[0].state", equalTo("RUNNING"))
        .body("progress.members[0].dispatch.workspaceId", equalTo("41"));
    asAdmin("dana")
        .get(path)
        .then()
        .statusCode(200)
        .body("state.nextPhase", equalTo("recheck"))
        .body("state.mode", equalTo("FLOW"));
    assertEquals(1, port.calls().size());
  }

  @Test
  void theInProcessDispatchRefusesACampaign() {
    Fixture f = campaignOf(scheduled("Belt"));
    DispatchRefused refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            DispatchRefused.class,
            () -> entityDispatch.dispatch(f.campaign.id, DispatchMode.FLOW, "someone"));
    assertTrue(refused.getMessage().contains("is not dispatched onto a workspace"));
    assertEquals(0, port.calls().size());
  }

  // --- fixtures ---------------------------------------------------------------------------------

  /** A campaign, REFINED unless told otherwise, and its members in order. */
  private record Fixture(WorkEntity campaign, List<WorkEntity> members, List<String> membershipIds) {}

  private Fixture campaignOf(WorkEntity member) {
    return campaignOf(member, true);
  }

  private Fixture campaignOf(WorkEntity member, boolean refined) {
    return campaignOf(List.of(member), List.of(false), refined);
  }

  private Fixture campaignOf(List<WorkEntity> members, List<Boolean> inFlight) {
    return campaignOf(members, inFlight, true);
  }

  private Fixture campaignOf(List<WorkEntity> members, List<Boolean> inFlight, boolean refined) {
    WorkEntity campaign = workEntities.createCampaign(projectId, "The order", null, "setup");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < members.size(); i++) {
      ids.add(
          campaigns
              .addMember(campaign.id, members.get(i).id, null, inFlight.get(i), "setup")
              .membership()
              .id);
    }
    if (refined) {
      campaign = walk(campaign, "REFINED");
    }
    return new Fixture(campaign, members, ids);
  }

  /** Replaces the member's condition with one APPROVAL criterion; answers its id. */
  private String gate(Fixture f, int index) {
    return campaigns
        .setCondition(
            f.campaign.id,
            f.membershipIds.get(index),
            List.of(
                new CampaignService.GroupSpec(
                    List.of(new CampaignService.CriterionSpec(null, "APPROVAL", null)))),
            "setup")
        .groups()
        .get(0)
        .criteria()
        .get(0)
        .id;
  }

  private WorkEntity ticket(String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            projectId,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null).withAcceptanceCriteria(TestCriteria.CRITERIA),
            "setup")
        .entity();
  }

  /** A ticket a person scheduled: READY_FOR_DEV, where its implement phase runs (qits-887). */
  private WorkEntity scheduled(String title) {
    return walk(ticket(title), "REFINED", "READY_FOR_DEV");
  }

  private WorkEntity walk(WorkEntity row, String... statuses) {
    WorkEntity moved = row;
    for (String status : statuses) {
      moved = workEntities.transition(row.archetype, row.id, status, "setup").entity();
    }
    return moved;
  }

  private CampaignService.Member member(Fixture f, int index) {
    return campaigns.get(f.campaign.id).members().stream()
        .filter(m -> m.membership().id.equals(f.membershipIds.get(index)))
        .findFirst()
        .orElseThrow();
  }

  private EntityMembership membership(Fixture f, int index) {
    return member(f, index).membership();
  }

  private void press(String campaignId) {
    asAdmin("dana")
        .body(Map.of("mode", "FLOW"))
        .post("/projects/api/entities/" + campaignId + "/dispatch")
        .then()
        .statusCode(200);
  }

  /** The listener's own frame for {@code entity}'s move, as the bus would deliver it. */
  private EventFrame transitioned(WorkEntity entity, String before, String after) {
    EntityTransitioned event =
        new EntityTransitioned(
            List.of(
                new EntityTransitioned.Entity(
                    entity.id,
                    entity.projectId,
                    entity.archetype.name(),
                    null,
                    null,
                    entity.slug,
                    entity.title,
                    after,
                    before,
                    "someone",
                    entity.number)),
            Instant.now());
    EventEnvelope envelope = EventEnvelope.of(event);
    return new EventFrame(
        UUID.randomUUID().toString(),
        envelope.name(),
        envelope.occurredAt(),
        envelope.payload(),
        null,
        envelope.parentId(),
        null);
  }

  /**
   * {@code onFrame} inside a transaction of its own. NOT a faithful stand-in for the library's
   * claim: that one has enlisted the {@code eventstream} datasource before {@code onFrame} runs, and
   * this one has enlisted nothing — which is exactly how the two-datasource wedge of 2026-09-27
   * shipped green. {@link CampaignCriteriaClaimSeamTest} drives the real claim; this stays for the
   * executor's own cases, which are about what happens after a latch.
   */
  private void deliver(EventFrame frame) {
    QuarkusTransaction.requiringNew().run(() -> listener.onFrame(frame));
  }

  /**
   * Runs {@code lock} in a transaction held open on another thread while {@code attempt} runs here,
   * then commits it after a pause long enough for the attempt to be waiting on its lock.
   */
  private void holdingWhile(java.util.concurrent.Callable<Integer> lock, Runnable attempt)
      throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    ExecutorService holder = Executors.newSingleThreadExecutor();
    try {
      Future<?> held =
          holder.submit(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(
                          () -> {
                            try {
                              lock.call();
                              locked.countDown();
                              Thread.sleep(700);
                            } catch (Exception e) {
                              throw new IllegalStateException(e);
                            }
                          }));
      assertTrue(locked.await(10, TimeUnit.SECONDS), "the rival transaction took its lock");
      attempt.run();
      held.get(10, TimeUnit.SECONDS);
    } finally {
      holder.shutdownNow();
    }
  }

  private CountingDispatch countingDispatch() {
    CountingDispatch counting = new CountingDispatch(ClientProxy.unwrap(entityDispatch));
    QuarkusMock.installMockForType(counting, EntityDispatch.class);
    return counting;
  }

  /**
   * The real dispatch, counted, with a seam between the executor's steps: a hook on each thread's
   * first precheck (step 2), a delay inside the dispatch, or a refusal in its place.
   */
  static class CountingDispatch extends EntityDispatch {
    private final EntityDispatch real;
    final AtomicInteger dispatches = new AtomicInteger();
    volatile Consumer<WorkEntity> onFirstPrecheckPerThread;
    volatile long delayDispatchMs;
    volatile DispatchRefused refuseDispatch;
    private final ThreadLocal<Boolean> prechecked = ThreadLocal.withInitial(() -> false);

    CountingDispatch(EntityDispatch real) {
      this.real = real;
    }

    @Override
    public String precheck(WorkEntity entity, String requiredPhase) {
      if (!prechecked.get()) {
        prechecked.set(true);
        Consumer<WorkEntity> hook = onFirstPrecheckPerThread;
        if (hook != null) {
          hook.accept(entity);
        }
      }
      return real.precheck(entity, requiredPhase);
    }

    @Override
    public String precheck(WorkEntity entity) {
      return real.precheck(entity);
    }

    @Override
    public Outcome dispatch(
        String id, DispatchMode mode, String changedBy, String requiredPhase) {
      dispatches.incrementAndGet();
      if (refuseDispatch != null) {
        throw refuseDispatch;
      }
      if (delayDispatchMs > 0) {
        try {
          Thread.sleep(delayDispatchMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return real.dispatch(id, mode, changedBy, requiredPhase);
    }

    @Override
    public Outcome dispatch(String id, DispatchMode mode, String changedBy) {
      dispatches.incrementAndGet();
      return real.dispatch(id, mode, changedBy);
    }

    @Override
    public Outcome dispatch(WorkEntity entity, DispatchMode mode, String changedBy) {
      dispatches.incrementAndGet();
      return real.dispatch(entity, mode, changedBy);
    }

    @Override
    public WorkEntity get(String id) {
      return real.get(id);
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("the other attempt never reached its precheck", e);
    }
  }

  private void awaitQuiet() {
    long deadline = System.currentTimeMillis() + 15_000;
    while (executor.pendingAttempts() > 0 && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    assertEquals(0, executor.pendingAttempts(), "the executor's hand-offs finished");
  }

  /** A person: the forwarded headers and the session cookie this service verifies (qits-891). */
  private static RequestSpecification asAdmin(String user) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(user));
  }
}
