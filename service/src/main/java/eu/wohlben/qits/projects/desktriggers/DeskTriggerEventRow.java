package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * One admitted line of a window, as a {@link DeskTriggerSource} renders it — a read of {@link
 * DeskTriggerEvent} that keeps the sources off the entity.
 */
public record DeskTriggerEventRow(
    String source,
    String itemRef,
    String itemId,
    String label,
    String cause,
    UUID eventId,
    Instant occurredAt,
    Instant admittedAt) {

  public static DeskTriggerEventRow of(DeskTriggerEvent event) {
    return new DeskTriggerEventRow(
        event.source,
        event.itemRef,
        event.itemId,
        event.label,
        event.cause,
        event.eventId,
        event.occurredAt,
        event.admittedAt);
  }

  /** {@code - <itemRef> (<label>)}, the bracket left out when there is no label. */
  public String line() {
    return "- " + itemRef + (label == null || label.isBlank() ? "" : " (" + label + ")");
  }
}
