package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An epic's or a ticket's assignee changed (qits-2518): a person's create, PATCH or PUT-shaped
 * transition naming a different one, clearing it, or a dispatch putting its agent on the row.
 *
 * <p>Published once per row and transaction, after the commit, and only when the value differs from
 * the one the row held before — a restatement publishes nothing. Ids are strings and the archetype
 * is its declared word, for {@link EntityTransitioned}'s reason. {@code assignedAt} is the record's
 * {@link #occurredAt}, named for the fact so {@code CanonicalJson}'s mix-in does not drop it.
 * Registered in {@link EventWireReflection}.
 *
 * @param entityId the item
 * @param projectId the item's project
 * @param archetype the item's archetype, as the declared word ({@code EPIC} or {@code TICKET})
 * @param number the item's per-project number
 * @param qualifiedId {@code <project-slug>-<number>}, absent when the slug could not be read
 * @param title the item's title
 * @param status the item's status after the change
 * @param assignee the assignee now, absent when cleared
 * @param previousAssignee the assignee before, absent when there was none
 * @param claimed whether the assignee is a person's claim now (qits-2259)
 * @param changedBy the principal that made the change
 * @param changedByName who that is, for a person to read: the token's name where the change came
 *     through a door by that principal, else {@code changedBy}
 * @param assignedAt when the change committed
 */
public record EntityAssigned(
    UUID eventId,
    String entityId,
    String projectId,
    String archetype,
    long number,
    String qualifiedId,
    String title,
    String status,
    String assignee,
    String previousAssignee,
    boolean claimed,
    String changedBy,
    String changedByName,
    Instant assignedAt)
    implements QitsEvent {

  public EntityAssigned {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return assignedAt;
  }
}
