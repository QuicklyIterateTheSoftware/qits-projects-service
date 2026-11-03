package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.campaign.CampaignReadiness;
import eu.wohlben.qits.entities.campaign.Observation;
import eu.wohlben.qits.entities.milestone.MilestoneEvaluator;
import eu.wohlben.qits.entities.milestone.MilestonesChanged;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.campaignhost.CampaignCriteriaConsumerHealth;
import eu.wohlben.qits.projects.campaignhost.CampaignMemberSatisfied;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener's own half, without a container: the wire names as literals, the decode of the three
 * signatures, the retry arm, the dedupe, the announcement of what was met, and the poison rule. What
 * {@link MilestoneEvaluator} itself does with an observation is {@code MilestoneEvaluatorTest}'s,
 * against the real database.
 */
class CampaignCriteriaListenerTest {

  private CampaignCriteriaListener listener;
  private RecordingEvaluator evaluator;
  private RecordingReadiness readiness;
  private RecordingMilestoneAnnouncer announcer;
  private CapturingEvent fired;
  private CampaignCriteriaConsumerHealth health;

  @BeforeEach
  void setUp() {
    listener = new CampaignCriteriaListener();
    evaluator = new RecordingEvaluator();
    readiness = new RecordingReadiness();
    announcer = new RecordingMilestoneAnnouncer();
    fired = new CapturingEvent();
    health = new CampaignCriteriaConsumerHealth();
    listener.evaluator = evaluator;
    listener.readiness = readiness;
    listener.announcer = announcer;
    listener.memberSatisfied = fired;
    listener.health = health;
    // The epics transaction is the seam CampaignCriteriaClaimSeamTest proves against the real
    // claim; with no container there is no transaction to open, so the work runs as it stands.
    listener.epicsTransaction = Runnable::run;
  }

  @Test
  void theConsumerIdAndSignaturesArePinned() {
    assertEquals("projects-campaign-criteria", listener.consumerId());
    assertEquals(
        Set.of("EntityTransitioned", "DeploymentActive", "ReleaseRequestStateChanged"),
        listener.signatures());
  }

  @Test
  void oneObservationPerBatchEntity() {
    EntityTransitioned event =
        new EntityTransitioned(
            List.of(
                entity("qits-1", "REFINED", "REPORTED"),
                entity("qits-2", "IMPLEMENTED", "REFINED")),
            Instant.parse("2026-09-27T10:00:00Z"));

    listener.onFrame(frameOf(event));

    assertEquals(2, evaluator.observed.size(), "one call to observe() per entity in the batch");
    assertEquals("qits-1", entityIdOf(evaluator.observed.get(0)));
    assertEquals("qits-2", entityIdOf(evaluator.observed.get(1)));
  }

  @Test
  void aPreStatusBeforePayloadDecodesWithNull() {
    // An entity from before the field existed: the batch names no statusBefore key at all.
    EventFrame frame =
        new EventFrame(
            UUID.randomUUID().toString(),
            "EntityTransitioned",
            Instant.parse("2026-09-27T10:00:00Z"),
            "{\"entities\":[{\"entityId\":\"qits-1\",\"projectId\":\"qits\","
                + "\"archetype\":\"TICKET\",\"title\":\"t\",\"changedBy\":\"tester\","
                + "\"number\":1}],\"transitionedAt\":\"2026-09-27T10:00:00Z\"}",
            null,
            null,
            null);

    listener.onFrame(frame);

    assertEquals(1, evaluator.observed.size());
    Observation.EntityReached reached = (Observation.EntityReached) evaluator.observed.get(0).observation();
    assertNull(reached.statusBefore());
  }

  @Test
  void aDeploymentActiveWithABlankVersionDecodes() {
    EventFrame frame =
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.parse("2026-09-27T10:00:00Z"),
            "{\"deploymentId\":\"dep-1\",\"applicationName\":\"qits-ci\","
                + "\"environmentId\":\"env-1\",\"environmentName\":\"dev\",\"version\":\"\"}",
            null,
            null,
            null);

    listener.onFrame(frame);

    assertEquals(1, evaluator.observed.size());
    Observation.DeploymentWentActive active =
        (Observation.DeploymentWentActive) evaluator.observed.get(0).observation();
    assertEquals("qits-ci", active.applicationName());
    assertEquals("dev", active.environmentName());
    assertEquals("", active.version());
  }

  @Test
  void aFinalizedReleaseRequestStateChangedDecodes() {
    listener.onFrame(frameOf(stateChanged("FINALIZED", "2026.927.101010")));

    assertEquals(1, evaluator.observed.size());
    Observation.Released released = (Observation.Released) evaluator.observed.get(0).observation();
    assertEquals("qits-qits", released.repositoryName());
    assertEquals("2026.927.101010", released.version());
  }

  @Test
  void aReleasedReleaseRequestStateChangedDoesNotLatch() {
    // RELEASED is the tag cut; the request can still go OBSOLETE and never ship.
    listener.onFrame(frameOf(stateChanged("RELEASED", "2026.927.101010")));

    assertTrue(evaluator.observed.isEmpty());
    assertTrue(fired.fired.isEmpty());
  }

  @Test
  void anObsoleteReleaseRequestStateChangedDoesNotLatch() {
    listener.onFrame(frameOf(stateChanged("OBSOLETE", "2026.927.101010")));

    assertTrue(evaluator.observed.isEmpty());
    assertTrue(fired.fired.isEmpty());
  }

  @Test
  void aFinalizedReleaseRequestStateChangedWithNoVersionDoesNotLatch() {
    listener.onFrame(frameOf(stateChanged("FINALIZED", null)));

    assertTrue(evaluator.observed.isEmpty());
    assertTrue(fired.fired.isEmpty());
  }

  @Test
  void aPoisonPayloadSettlesWithoutThrowing() {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "EntityTransitioned",
            Instant.parse("2026-09-27T10:00:00Z"),
            "not json at all",
            null,
            null,
            null));
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.parse("2026-09-27T10:00:00Z"),
            "not json at all",
            null,
            null,
            null));
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "ReleaseRequestStateChanged",
            Instant.parse("2026-09-27T10:00:00Z"),
            "not json at all",
            null,
            null,
            null));

    assertTrue(evaluator.observed.isEmpty(), "nothing to observe, and nothing thrown");
    assertTrue(fired.fired.isEmpty());
  }

  @Test
  void aFrameWhoseOwnIdIsNotAUuidIsSkipped() {
    EntityTransitioned event =
        new EntityTransitioned(
            List.of(entity("qits-1", "REFINED", "REPORTED")), Instant.parse("2026-09-27T10:00:00Z"));
    EventEnvelope envelope = EventEnvelope.of(event);
    listener.onFrame(
        new EventFrame(
            "not-a-uuid",
            envelope.name(),
            envelope.occurredAt(),
            envelope.payload(),
            null,
            envelope.parentId(),
            null));

    assertTrue(evaluator.observed.isEmpty(), "no event id to record evidence under");
    assertTrue(fired.fired.isEmpty());
  }

  @Test
  void memberEntityRetriesFireOncePerMembership() {
    readiness.retryAnswers.put("qits-1", List.of("m1", "m2"));
    readiness.retryAnswers.put("qits-2", List.of("m2"));

    EntityTransitioned event =
        new EntityTransitioned(
            List.of(
                entity("qits-1", "REFINED", "REPORTED"),
                entity("qits-2", "IMPLEMENTED", "REFINED")),
            Instant.parse("2026-09-27T10:00:00Z"));

    listener.onFrame(frameOf(event));

    assertEquals(List.of("qits-1", "qits-2"), readiness.retryCalls);
    assertEquals(
        List.of("m1", "m2"),
        fired.fired.stream().map(CampaignMemberSatisfied::membershipId).toList(),
        "m2 named for both entities fires exactly once");
  }

  @Test
  void everyFiredEventCarriesTheFramesOwnIdAsEvidence() {
    UUID id = UUID.randomUUID();
    readiness.retryAnswers.put("qits-1", List.of("m1"));
    EntityTransitioned event =
        new EntityTransitioned(
            List.of(entity("qits-1", "REFINED", "REPORTED")), Instant.parse("2026-09-27T10:00:00Z"));
    EventEnvelope envelope = EventEnvelope.of(event);
    listener.onFrame(
        new EventFrame(
            id.toString(), envelope.name(), envelope.occurredAt(), envelope.payload(), null, null, null));

    assertEquals(1, fired.fired.size());
    assertEquals(id, fired.fired.get(0).evidenceEventId());
  }

  @Test
  void everyMilestoneTheLatchesMetIsAnnouncedAndNoMembershipIsHandedOnForIt() {
    Instant at = Instant.parse("2026-09-27T10:00:00Z");
    evaluator.observeAnswers.add(
        new MilestonesChanged(
            List.of(new MilestonesChanged.Met("ms-1", "qits", "bootstrap test node", "g-1", at)),
            List.of()));
    evaluator.observeAnswers.add(
        new MilestonesChanged(
            List.of(new MilestonesChanged.Met("ms-2", "qits", "second", "g-2", at)), List.of()));

    listener.onFrame(
        frameOf(
            new EntityTransitioned(
                List.of(entity("qits-1", "VERIFIED", "IMPLEMENTED"), entity("qits-2", "DONE", "VERIFIED")),
                at)));

    assertEquals(
        List.of("ms-1", "ms-2"),
        announcer.met().stream().map(MilestoneMet::milestoneId).toList(),
        "one MilestoneMet per milestone, in order");
    MilestoneMet first = announcer.met().get(0);
    assertEquals("qits", first.projectId());
    assertEquals("bootstrap test node", first.displayName());
    assertEquals("g-1", first.groupId());
    assertEquals(at, first.satisfiedAt());
    assertTrue(fired.fired.isEmpty(), "the executor hears a milestone on the bus, never from here");

    evaluator.observeAnswers.add(
        new MilestonesChanged(
            List.of(new MilestonesChanged.Met("ms-3", "qits", "deployed", "g-3", at)), List.of()));
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            at,
            "{\"applicationName\":\"qits-ci\",\"environmentName\":\"dev\",\"version\":\"1\"}",
            null,
            null,
            null));
    assertEquals("ms-3", announcer.met().get(2).milestoneId());
  }

  @Test
  void aFailingEvaluatorIsRecordedAndRethrownSoTheClaimRollsBack() {
    evaluator.failWith = new IllegalStateException("the epics database is gone");
    EventFrame frame =
        frameOf(
            new EntityTransitioned(
                List.of(entity("qits-1", "REFINED", "REPORTED")),
                Instant.parse("2026-09-27T10:00:00Z")));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> listener.onFrame(frame));

    assertSame(evaluator.failWith, thrown, "rethrown as it came, for the library to roll back");
    assertTrue(health.failing());
    assertTrue(health.lastError().contains(frame.id()), health.lastError());
    assertTrue(health.lastError().contains("the epics database is gone"), health.lastError());
    assertTrue(fired.fired.isEmpty(), "nothing is handed on from a frame that failed");
    assertTrue(announcer.published().isEmpty(), "nothing is announced from a frame that failed");

    evaluator.failWith = null;
    listener.onFrame(frame);
    assertFalse(health.failing(), "the next frame that gets through clears it");
    assertTrue(health.lastError().contains(frame.id()), "the last error stays, as history");
  }

  @Test
  void aPoisonFrameCountsAsHandled() {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "ReleaseRequestStateChanged",
            Instant.parse("2026-09-27T10:00:00Z"),
            "not json at all",
            null,
            null,
            null));
    assertFalse(health.failing());
    assertNotNull(health.lastHandledAt(), "settled, so handled");
  }

  // -------------------------------------------------------------------------------------------

  private static ReleaseRequestStateChanged stateChanged(String state, String version) {
    return new ReleaseRequestStateChanged(
        "req-1",
        "qits-qits-rr-1",
        "qits",
        "repo-1",
        "qits-qits",
        "release/qits-qits-rr-1",
        state,
        "READY",
        Instant.parse("2026-09-27T10:00:00Z"),
        "abc123",
        version,
        null,
        null);
  }

  private static EntityTransitioned.Entity entity(String entityId, String status, String statusBefore) {
    return new EntityTransitioned.Entity(
        entityId, "qits", "TICKET", null, null, entityId, "title", status, statusBefore, "tester", 1L);
  }

  private static String entityIdOf(Observation.Observed observed) {
    return ((Observation.EntityReached) observed.observation()).entityId();
  }

  /** The event as it really arrives: canonicalized into an envelope, then read back as a frame. */
  private static EventFrame frameOf(QitsEvent event) {
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

  private static final class RecordingEvaluator extends MilestoneEvaluator {
    final List<Observation.Observed> observed = new ArrayList<>();
    final Deque<MilestonesChanged> observeAnswers = new ArrayDeque<>();
    RuntimeException failWith;

    @Override
    public MilestonesChanged observe(Observation.Observed observed) {
      if (failWith != null) {
        throw failWith;
      }
      this.observed.add(observed);
      return observeAnswers.isEmpty() ? MilestonesChanged.none() : observeAnswers.poll();
    }
  }

  private static final class RecordingReadiness extends CampaignReadiness {
    final List<String> retryCalls = new ArrayList<>();
    final Map<String, List<String>> retryAnswers = new HashMap<>();

    @Override
    public List<String> satisfiedUnclaimedMembershipsOf(String entityId) {
      retryCalls.add(entityId);
      return retryAnswers.getOrDefault(entityId, List.of());
    }
  }

  private static final class CapturingEvent implements Event<CampaignMemberSatisfied> {
    final List<CampaignMemberSatisfied> fired = new ArrayList<>();

    @Override
    public void fire(CampaignMemberSatisfied event) {
      fired.add(event);
    }

    @Override
    public <U extends CampaignMemberSatisfied> CompletionStage<U> fireAsync(U event) {
      throw new UnsupportedOperationException("the listener must fire synchronously");
    }

    @Override
    public <U extends CampaignMemberSatisfied> CompletionStage<U> fireAsync(
        U event, NotificationOptions options) {
      throw new UnsupportedOperationException("the listener must fire synchronously");
    }

    @Override
    public Event<CampaignMemberSatisfied> select(Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <U extends CampaignMemberSatisfied> Event<U> select(
        Class<U> subtype, Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <U extends CampaignMemberSatisfied> Event<U> select(
        jakarta.enterprise.util.TypeLiteral<U> subtype, Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }
  }
}
