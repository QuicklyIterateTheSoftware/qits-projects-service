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

  /**
   * qits-workspaces' {@code DeskWaitClosed} as it arrives: that service's event type is not on this
   * classpath, so the payload is written as the wire carries it.
   */
  static EventFrame waitClosed(
      String projectId, String waitId, String label, String state, String turn) {
    com.fasterxml.jackson.databind.node.ObjectNode payload =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    payload.put("waitId", waitId);
    payload.put("projectId", projectId);
    payload.put("label", label);
    payload.put("state", state);
    payload.put("eventName", "ReleaseRequestStateChanged");
    payload.put("matchedEventId", "MATCHED".equals(state) ? UUID.randomUUID().toString() : null);
    payload.put("turn", turn);
    payload.put("closedAt", "2026-10-26T09:00:00Z");
    return new EventFrame(
        UUID.randomUUID().toString(),
        "DeskWaitClosed",
        Instant.now(),
        payload.toString(),
        null,
        null,
        null);
  }
}
