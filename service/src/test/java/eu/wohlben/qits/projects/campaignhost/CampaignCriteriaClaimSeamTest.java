package eu.wohlben.qits.projects.campaignhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignEvaluatorDto;
import eu.wohlben.qits.entities.campaign.CampaignCriterion;
import eu.wohlben.qits.entities.campaign.CampaignEvaluator;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.campaign.Observation;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.DurableFunnel;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.persistence.ConsumedEvents;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.bus.CampaignCriteriaListener;
import eu.wohlben.qits.projects.bus.EntityTransitioned;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.arc.ClientProxy;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The criteria consumer through the eventstream library's REAL claim</b> (qits-416/qits-418) —
 * the test whose absence let a consumer that failed on every frame ship green.
 *
 * <p>The library runs {@code onFrame} inside {@code DurableFunnel}'s claim transaction, after the
 * {@code consumed_event} insert has enlisted the {@code eventstream} datasource. {@code
 * CampaignExecutorTest.deliver} stood in for that with a bare {@code requiringNew()} that enlisted
 * nothing, so the listener's {@code epics} work was the transaction's FIRST resource there and its
 * SECOND in production, where Narayana refuses it ({@code ARJUNA016045 … Enlisted connection used
 * without active transaction}). Here the frame goes through {@link DurableFunnel#offer} itself — the
 * one entry point both of the library's channels use — so the claim is the library's own statement
 * on the library's own datasource, and nothing about the transaction around the listener is a
 * stand-in.
 *
 * <p>The bus is dark in {@code %test}, and the funnel short-circuits to {@code SKIPPED} while it is
 * ({@code qits.eventstream.enabled=false}); that flag is the only thing switched on, per test, and
 * back off after. Nothing dials and nothing sweeps — only the one offer this test makes is real. No
 * {@code @TestProfile} (the test-profile budget rule).
 */
@QuarkusTest
class CampaignCriteriaClaimSeamTest {

  @Inject DurableFunnel funnel;
  @Inject ConsumedEvents consumed;
  @Inject CampaignCriteriaListener listener;
  @Inject CampaignCriteriaConsumerHealth consumerHealth;
  @Inject CampaignEvaluatorHealth health;
  @Inject CampaignExecutor executor;
  @Inject CampaignService campaigns;
  @Inject WorkEntityService workEntities;
  @Inject RecordingWorkspaceAgentDispatch port;

  @Inject
  @PersistenceUnit("eventstream")
  EntityManager eventstream;

  private String projectId;
  private Field enabled;
  private DurableFunnel realFunnel;

  @BeforeEach
  void setUp() throws Exception {
    port.reset();
    consumerHealth.reset();
    projectId =
        given()
            .contentType(ContentType.JSON)
            .header("X-Qits-User", "setup")
            .header("X-Qits-Roles", "qits:admin")
            .body(
                new ProjectController.CreateProjectRequest(
                    "Claim Seam", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    realFunnel = ClientProxy.unwrap(funnel);
    enabled = DurableFunnel.class.getDeclaredField("enabled");
    enabled.setAccessible(true);
    enabled.setBoolean(realFunnel, true);
  }

  @AfterEach
  void tearDown() throws Exception {
    enabled.setBoolean(realFunnel, false);
    awaitQuiet();
    // The bean outlives this class; the goldens and the progress tests read it as never-failed.
    consumerHealth.reset();
  }

  @Test
  void aFrameOfferedThroughTheRealClaimLatchesTheCriterionAndCommitsTheClaim() {
    WorkEntity first = scheduled("First");
    WorkEntity second = scheduled("Second");
    WorkEntity campaign = campaignOf(first, second);
    campaigns.start(campaign.id, "dana");
    walk(first, "IMPLEMENTED", "VERIFIED");
    EventFrame verified = transitioned(first, "IMPLEMENTED", "VERIFIED");

    DurableFunnel.Result result = funnel.offer(listener, verified);

    assertEquals(
        DurableFunnel.Result.HANDLED,
        result,
        "the claim committed with the listener's work inside it — FAILED here is the 2026-09-27"
            + " wedge (see the log for ARJUNA016045)");
    assertTrue(
        consumed.isHandled(CampaignCriteriaConsumerHealth.CONSUMER_ID, verified.id()),
        "the claim row is there, so the event is settled");
    CampaignCriterion latched = criterionOf(campaign, second);
    assertNotNull(latched.satisfiedAt, "the second member's criterion latched on the frame");
    assertEquals(UUID.fromString(verified.id()), latched.evidenceEventId);

    awaitQuiet();
    assertEquals(1, dispatchesOf(second), "the latch was handed on and dispatched once");

    // The redelivery a rolled-back claim would cause, and the idempotence the split rests on: the
    // same frame again — with its claim forgotten, so the listener really runs a second time —
    // writes no second latch and dispatches nothing more.
    Instant latchedAt = latched.satisfiedAt;
    forgetClaim(verified.id());
    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(listener, verified));
    awaitQuiet();
    assertEquals(latchedAt, criterionOf(campaign, second).satisfiedAt);
    assertEquals(1, dispatchesOf(second), "a redelivered frame dispatches nothing twice");

    CampaignEvaluatorDto evaluator = health.now();
    assertFalse(evaluator.consumerFailing());
    assertFalse(evaluator.stalled());
    assertNull(evaluator.lastError());
  }

  @Test
  void aFailingFrameIsReportedStalledUntilALaterFrameGetsThrough() {
    WorkEntity only = scheduled("Only");
    WorkEntity campaign = campaignOf(only);
    FlakyEvaluator flaky = new FlakyEvaluator();
    QuarkusMock.installMockForType(flaky, CampaignEvaluator.class);

    flaky.failing = true;
    EventFrame broken = transitioned(only, "REFINED", "IMPLEMENTED");
    assertEquals(DurableFunnel.Result.FAILED, funnel.offer(listener, broken));
    assertFalse(
        consumed.isHandled(CampaignCriteriaConsumerHealth.CONSUMER_ID, broken.id()),
        "the claim rolled back: the event is still owed");

    CampaignEvaluatorDto failed = health.now();
    assertTrue(failed.consumerFailing());
    assertTrue(failed.stalled(), "a failing consumer is a stalled evaluator, for the SPA's warning");
    assertNotNull(failed.lastErrorAt());
    assertTrue(failed.lastError().contains(broken.id()), failed.lastError());
    assertTrue(failed.lastError().contains("the epics database said no"), failed.lastError());
    given()
        .header("X-Qits-User", "dana")
        .header("X-Qits-Roles", "qits:admin")
        .get("/projects/api/campaigns/" + campaign.id + "/progress")
        .then()
        .statusCode(200)
        .body("progress.evaluator.stalled", equalTo(true))
        .body("progress.evaluator.consumerFailing", equalTo(true))
        .body("progress.evaluator.lastError", containsString(broken.id()));

    flaky.failing = false;
    EventFrame fine = transitioned(only, "REFINED", "IMPLEMENTED");
    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(listener, fine));

    CampaignEvaluatorDto recovered = health.now();
    assertFalse(recovered.consumerFailing(), "the frame that got through clears it");
    assertFalse(recovered.stalled());
    assertTrue(recovered.lastError().contains(broken.id()), "the last error stays, as history");
  }

  // --- fixtures ---------------------------------------------------------------------------------

  /** An evaluator that throws while told to — the shape of any failure inside the epics work. */
  static class FlakyEvaluator extends CampaignEvaluator {
    volatile boolean failing;

    @Override
    public List<String> observe(Observation.Observed observed) {
      if (failing) {
        throw new IllegalStateException("the epics database said no");
      }
      return List.of();
    }

    @Override
    public List<String> satisfiedUnclaimedMembershipsOf(String entityId) {
      return List.of();
    }
  }

  /** A REFINED campaign over {@code members}, in order, each waiting on the previous one. */
  private WorkEntity campaignOf(WorkEntity... members) {
    WorkEntity campaign = workEntities.createCampaign(projectId, "The order", null, "setup");
    for (WorkEntity member : members) {
      campaigns.addMember(campaign.id, member.id, null, false, "setup");
    }
    return walk(campaign, "REFINED");
  }

  private CampaignCriterion criterionOf(WorkEntity campaign, WorkEntity member) {
    return campaigns.get(campaign.id).members().stream()
        .filter(m -> m.membership().childId.equals(member.id))
        .findFirst()
        .orElseThrow()
        .groups()
        .get(0)
        .criteria()
        .get(0);
  }

  /** A ticket a person scheduled: READY_FOR_DEV, where its implement phase runs (qits-887). */
  private WorkEntity scheduled(String title) {
    WorkEntity ticket =
        workEntities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket(title, "it occurs", null, "BUG", null).withAcceptanceCriteria(TestCriteria.CRITERIA),
                "setup")
            .entity();
    return walk(ticket, "REFINED", "READY_FOR_DEV");
  }

  private WorkEntity walk(WorkEntity row, String... statuses) {
    WorkEntity moved = row;
    for (String status : statuses) {
      moved = workEntities.transition(row.archetype, row.id, status, Mover.person("setup")).entity();
    }
    return moved;
  }

  /** The listener's own frame for {@code entity}'s move, as the bus would deliver it. */
  private static EventFrame transitioned(WorkEntity entity, String before, String after) {
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

  /** Counted by subject: the minute sweep may try the others while this test runs. */
  private long dispatchesOf(WorkEntity member) {
    return port.calls().stream().filter(c -> member.id.equals(c.subject().ticketId())).count();
  }

  private void forgetClaim(String eventId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                eventstream
                    .createNativeQuery(
                        "delete from consumed_event where listener_id = ?1 and event_id = ?2")
                    .setParameter(1, CampaignCriteriaConsumerHealth.CONSUMER_ID)
                    .setParameter(2, eventId)
                    .executeUpdate());
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
}
