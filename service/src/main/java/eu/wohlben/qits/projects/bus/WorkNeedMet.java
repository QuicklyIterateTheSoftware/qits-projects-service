package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>A work item's need came to be met</b> (qits-1903): a transition moved the needed item to (or
 * past) the status an item waits for. Published by {@link WorkNeedAnnouncer} after the transition
 * has committed, durably, through the outbox — one event per
 * need that flips from unmet to met. Nothing is published when a need goes unmet again, nor when a
 * needs write names an item that is already met: a write is not something reaching its status.
 *
 * <p>The delivery desk waits on it ({@code qits wait --event WorkNeedMet --when 'qualifiedId=qits-1898
 * allMet=true'}). It is news, not the re-wake: a pre-approved FLOW run its needs held at REFINED is
 * resumed in process, from the same {@code NeedsMet} this is published from ({@code
 * api/NeedsMetResume}, qits-2026), whether or not anybody waits on the wire. The wire name is the
 * simple class name.
 *
 * @param projectId the project of both items
 * @param entityId the item that waits
 * @param qualifiedId its qualified id, or null when its project cannot be read
 * @param neededEntityId the item that moved
 * @param neededQualifiedId its qualified id, or null as above
 * @param status the status the need names
 * @param neededStatus the status the needed item just reached
 * @param allMet whether the waiting item has no unmet need left
 * @param metAt when the move committed — the record's {@link #occurredAt}; named for the fact,
 *     for {@link EntityTransitioned}'s reason (the canonical mix-in drops a component sharing a
 *     {@link QitsEvent} method's name)
 */
public record WorkNeedMet(
    UUID eventId,
    String projectId,
    String entityId,
    String qualifiedId,
    String neededEntityId,
    String neededQualifiedId,
    String status,
    String neededStatus,
    boolean allMet,
    Instant metAt)
    implements QitsEvent {

  public WorkNeedMet {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public WorkNeedMet(
      String projectId,
      String entityId,
      String qualifiedId,
      String neededEntityId,
      String neededQualifiedId,
      String status,
      String neededStatus,
      boolean allMet,
      Instant metAt) {
    this(
        null,
        projectId,
        entityId,
        qualifiedId,
        neededEntityId,
        neededQualifiedId,
        status,
        neededStatus,
        allMet,
        metAt);
  }

  @Override
  public Instant occurredAt() {
    return metAt;
  }
}
