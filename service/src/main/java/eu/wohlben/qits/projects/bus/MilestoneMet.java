package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>A milestone came to hold</b> (epic qits-615): every criterion of one of its OR'd groups has
 * latched. Published by {@link MilestoneAnnouncer} after the write or the latch that met it has
 * committed — from the criteria consumer ({@link CampaignCriteriaListener}), the approve door and
 * the edit path alike — durably, through the outbox, exactly as {@link EntityTransitioned} is.
 *
 * <p>The campaign executor consumes it ({@code campaignhost/CampaignMilestoneListener}) and the
 * landing app refreshes on it by {@code projectId}. The wire name is the simple class name.
 *
 * @param milestoneId the milestone
 * @param projectId its project
 * @param displayName its name as it stands now
 * @param groupId the holding group whose date the milestone took (the earliest)
 * @param satisfiedAt met since — the record's {@link #occurredAt}; named for the fact rather than
 *     {@code occurredAt}, for {@link EntityTransitioned}'s reason (the canonical mix-in drops a
 *     component sharing a {@link QitsEvent} method's name)
 */
public record MilestoneMet(
    UUID eventId,
    String milestoneId,
    String projectId,
    String displayName,
    String groupId,
    Instant satisfiedAt)
    implements QitsEvent {

  public MilestoneMet {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public MilestoneMet(
      String milestoneId, String projectId, String displayName, String groupId, Instant satisfiedAt) {
    this(null, milestoneId, projectId, displayName, groupId, satisfiedAt);
  }

  @Override
  public Instant occurredAt() {
    return satisfiedAt;
  }
}
