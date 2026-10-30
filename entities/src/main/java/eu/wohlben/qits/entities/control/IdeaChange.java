package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Idea;
import java.time.Instant;

/**
 * <b>One thing that happened to an idea</b> (qits-1387), as {@link IdeaAnnouncer} hands it on: what
 * kind of change, and the idea as the change left it.
 *
 * @param kind what happened
 * @param ideaId the idea's id
 * @param projectId its project
 * @param title its title as the change left it
 * @param status its derived status as the change left it
 * @param statusBefore the status before, when the change moved it; null when it did not
 * @param archived whether it is archived now
 * @param archiveReason why it was archived, while it is
 * @param changedBy who made the change — for a status a work write moved, who made that write
 * @param occurredAt when the change committed
 */
public record IdeaChange(
    Kind kind,
    String ideaId,
    String projectId,
    String title,
    String status,
    String statusBefore,
    boolean archived,
    String archiveReason,
    String changedBy,
    Instant occurredAt) {

  /** What happened to the idea. Each is its own event on the bus. */
  public enum Kind {
    /** A new idea, PLANNED. */
    CREATED,
    /** An edit of its fields, or its derived status moved because of a work write. */
    UPDATED,
    /** Archived with a reason; the status is left as it was. */
    ARCHIVED,
    /** Taken out of the archive. */
    UNARCHIVED
  }

  /** {@code idea} as {@code kind} left it. */
  public static IdeaChange of(Kind kind, Idea idea, String statusBefore, String changedBy) {
    return new IdeaChange(
        kind,
        idea.id,
        idea.projectId,
        idea.title,
        idea.status == null ? null : idea.status.name(),
        statusBefore,
        idea.archived,
        idea.archiveReason,
        changedBy,
        Instant.now());
  }
}
