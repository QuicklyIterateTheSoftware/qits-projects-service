package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An epic's or a ticket's explicit block flag changed (qits-2674): the block door ({@code POST
 * /work/{qualifiedId}/blocked}) set or cleared it, or a transition cleared a block that stood.
 *
 * <p>Published once per row and transaction, after the commit, and only when the flag differs from
 * the one the row held before — an idempotent re-block, and a transition of a row that was not
 * blocked, publish nothing. The derived agent-waiting block is a reading, not a write, and is never
 * published. Ids are strings and the archetype is its declared word, for {@link
 * EntityTransitioned}'s reason. {@code changedAt} is the record's {@link #occurredAt}, named for the
 * fact so {@code CanonicalJson}'s mix-in does not drop it. Registered in {@link
 * EventWireReflection}.
 *
 * @param entityId the item
 * @param projectId the item's project
 * @param archetype the item's archetype, as the declared word ({@code EPIC} or {@code TICKET})
 * @param number the item's per-project number
 * @param qualifiedId {@code <project-slug>-<number>}, absent when the slug could not be read
 * @param status the item's status after the change
 * @param blocked the flag now
 * @param reason the stated blocker of a block; the stated reason of an unblock, absent when none was
 *     given; {@code cleared by transition to <STATUS>} when a transition cleared it
 * @param changedBy the principal that made the change
 * @param changedAt when the change committed
 */
public record EntityBlockChanged(
    UUID eventId,
    String entityId,
    String projectId,
    String archetype,
    long number,
    String qualifiedId,
    String status,
    boolean blocked,
    String reason,
    String changedBy,
    Instant changedAt)
    implements QitsEvent {

  public EntityBlockChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
