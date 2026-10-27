package eu.wohlben.qits.projects.desktriggers;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>One thing a project's delivery desk should hear about</b> (epic qits-1604), as a {@link
 * DeskTriggerSource} read it off a bus event — before the listener has decided whether to admit it.
 *
 * @param projectId the project whose desk is to be told
 * @param itemRef what the desk is shown: the qualified id ({@code qits-1580}, {@code
 *     qits-qits-rr-201}), the handle every {@code qits} command takes
 * @param itemId the item's own id, for a reader of the row; not shown to the desk
 * @param cause who or what caused it, as a person reads it — a principal ({@code changedBy}) when
 *     there is one, else a phrase that can never equal one ({@code fold <sha8>}, {@code comment
 *     <id8>})
 * @param label the text in brackets after {@code itemRef}
 * @param eventId the bus event that carried it
 * @param occurredAt when that event says it happened
 * @param commentId the comment it came from, for a reader to open; null when it did not come from
 *     one (qits-1660)
 * @param principal who caused it, for the listener: it drops a trigger whose principal is one of
 *     the desk's own subjects, so the desk is not told what it did itself. Null when no principal
 *     caused it
 */
public record DeskTrigger(
    String projectId,
    String itemRef,
    String itemId,
    String cause,
    String label,
    UUID eventId,
    Instant occurredAt,
    String commentId,
    String principal) {

  /** A trigger with no comment, whose {@code cause} is also its principal. */
  public DeskTrigger(
      String projectId,
      String itemRef,
      String itemId,
      String cause,
      String label,
      UUID eventId,
      Instant occurredAt) {
    this(projectId, itemRef, itemId, cause, label, eventId, occurredAt, null, cause);
  }
}
