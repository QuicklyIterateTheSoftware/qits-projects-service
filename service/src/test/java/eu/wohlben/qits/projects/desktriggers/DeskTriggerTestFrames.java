package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.ApprovalRequirementChanged;
import eu.wohlben.qits.projects.bus.EntityTransitioned;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The events the desk trigger suites feed the listener, built as they really arrive:
 * canonicalized into an envelope, then read back as a frame ({@code
 * ReleaseRequestHeadListenerTest.frameOf}'s technique).
 */
final class DeskTriggerTestFrames {

  private DeskTriggerTestFrames() {}

  static EventFrame frameOf(QitsEvent event) {
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

  static EntityTransitioned.Entity entity(
      String projectId,
      String archetype,
      long number,
      String title,
      String statusBefore,
      String status,
      String changedBy) {
    return new EntityTransitioned.Entity(
        UUID.randomUUID().toString(),
        projectId,
        archetype,
        null,
        null,
        "slug-" + number,
        title,
        status,
        statusBefore,
        changedBy,
        number);
  }

  static EventFrame transitioned(EntityTransitioned.Entity... entities) {
    return frameOf(new EntityTransitioned(List.of(entities), Instant.now()));
  }

  static EventFrame requirement(
      String projectId,
      String requestId,
      String qualifiedId,
      String requirementId,
      String reason,
      List<String> admits,
      String state) {
    return frameOf(
        new ApprovalRequirementChanged(
            null,
            requestId,
            qualifiedId,
            projectId,
            "repo-" + UUID.randomUUID(),
            "qits-qits",
            "412911eb" + "0".repeat(32),
            requirementId,
            reason,
            admits,
            false,
            List.of(),
            state,
            Instant.now()));
  }
}
