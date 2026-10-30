package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>{@code IdeaArchived}</b> (qits-1387): An idea was archived with a reason; its status is unchanged.
 *
 * <p>One of four idea events, all the same shape, published by {@link IdeaEventAnnouncer} after
 * the write committed. The class lives here, not in a vocabulary jar, for {@code RepositoryRenamed}'s
 * reason; a consumer decodes it into a record of its own. No field spells an envelope word ({@code
 * name}, {@code eventId}'s own accessor aside, {@code occurredAt}, {@code signature}):
 * {@code changedAt} is the envelope's {@code occurredAt}.
 *
 * @param ideaId the idea
 * @param projectId its project
 * @param title its title as the change left it
 * @param status its derived status as the change left it: PLANNED, QUEUED or DONE
 * @param statusBefore the status before, when this change moved it; absent otherwise
 * @param archived whether it is archived now
 * @param archiveReason why, while it is
 * @param changedBy who made the change
 */
public record IdeaArchived(
    UUID eventId,
    String ideaId,
    String projectId,
    String title,
    String status,
    String statusBefore,
    boolean archived,
    String archiveReason,
    String changedBy,
    Instant changedAt)
    implements QitsEvent {

  public IdeaArchived {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
