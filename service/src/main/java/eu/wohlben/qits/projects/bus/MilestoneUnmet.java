package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>A milestone stopped holding</b> (epic qits-615): an edit unlatched what made it hold, and no
 * group holds any more. Its {@code satisfied_at} was cleared — dates mean "met since". Published by
 * {@link MilestoneAnnouncer} after the edit committed, durably, as {@link MilestoneMet} is. The
 * campaign executor ignores it on purpose: a claimed membership is frozen, and an unclaimed one
 * simply is not dispatchable while it waits. The wire name is the simple class name.
 *
 * @param at when it stopped holding — the record's {@link #occurredAt}
 */
public record MilestoneUnmet(
    UUID eventId, String milestoneId, String projectId, String displayName, Instant at)
    implements QitsEvent {

  public MilestoneUnmet {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public MilestoneUnmet(String milestoneId, String projectId, String displayName, Instant at) {
    this(null, milestoneId, projectId, displayName, at);
  }

  @Override
  public Instant occurredAt() {
    return at;
  }
}
