package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.campaign.CampaignEvaluator;
import eu.wohlben.qits.entities.campaign.Observation;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
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
 * signatures, the retry arm, the dedupe, and the poison rule. What {@link CampaignEvaluator} itself
 * does with an observation is {@code CampaignEvaluatorTest}'s, against the real database.
 */
class CampaignCriteriaListenerTest {

  private CampaignCriteriaListener listener;
  private RecordingEvaluator evaluator;
  private CapturingEvent fired;

  @BeforeEach
  void setUp() {
    listener = new CampaignCriteriaListener();
    evaluator = new RecordingEvaluator();
    fired = new CapturingEvent();
    listener.evaluator = evaluator;
    listener.memberSatisfied = fired;
  }

  @Test
  void theConsumerIdAndSignaturesArePinned() {
    assertEquals("projects-campaign-criteria", listener.consumerId());
    assertEquals(
        Set.of("EntityTransitioned", "DeploymentActive", "SCMRelease"), listener.signatures());
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
  void anScmReleaseDecodes() {
    SCMRelease release =
        new SCMRelease(
            "qits",
            "repo-1",
            "qits-qits",
            "release/req-1",
            "2026.927.101010",
            "abc123",
            "req-1",
            Instant.parse("2026-09-27T10:00:00Z"),
            "MEDIUM");

    listener.onFrame(frameOf(release));

    assertEquals(1, evaluator.observed.size());
    Observation.Released released = (Observation.Released) evaluator.observed.get(0).observation();
    assertEquals("qits-qits", released.repositoryName());
    assertEquals("2026.927.101010", released.version());
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
            "SCMRelease",
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
  void memberEntityRetriesAreIncludedAndIdsAreDeduped() {
    evaluator.observeAnswers.add(List.of("m1"));
    evaluator.observeAnswers.add(List.of());
    evaluator.retryAnswers.put("qits-1", List.of("m1", "m2"));
    evaluator.retryAnswers.put("qits-2", List.of("m2"));

    EntityTransitioned event =
        new EntityTransitioned(
            List.of(
                entity("qits-1", "REFINED", "REPORTED"),
                entity("qits-2", "IMPLEMENTED", "REFINED")),
            Instant.parse("2026-09-27T10:00:00Z"));

    listener.onFrame(frameOf(event));

    assertEquals(List.of("qits-1", "qits-2"), evaluator.retryCalls);
    assertEquals(
        Set.of("m1", "m2"),
        fired.fired.stream().map(CampaignMemberSatisfied::membershipId).collect(java.util.stream.Collectors.toSet()));
    assertEquals(2, fired.fired.size(), "m1 named by both arms fires exactly once");
  }

  @Test
  void everyFiredEventCarriesTheFramesOwnIdAsEvidence() {
    UUID id = UUID.randomUUID();
    evaluator.observeAnswers.add(List.of("m1"));
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

  // -------------------------------------------------------------------------------------------

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

  private static final class RecordingEvaluator extends CampaignEvaluator {
    final List<Observation.Observed> observed = new ArrayList<>();
    final Deque<List<String>> observeAnswers = new ArrayDeque<>();
    final List<String> retryCalls = new ArrayList<>();
    final Map<String, List<String>> retryAnswers = new HashMap<>();

    @Override
    public List<String> observe(Observation.Observed observed) {
      this.observed.add(observed);
      return observeAnswers.isEmpty() ? List.of() : observeAnswers.poll();
    }

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
