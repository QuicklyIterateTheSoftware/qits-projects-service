package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Somebody wrote a comment on a work item's thread through the comment door (qits-1660).
 *
 * <p>It carries who it names ({@code mentions}), not the body: a reader that needs the text reads
 * the thread. Ids are strings and the archetype is its declared word, for {@link
 * EntityTransitioned}'s reason. {@code commentedAt} is the record's {@link #occurredAt}, named for the
 * fact so {@code CanonicalJson}'s mix-in does not drop it. Registered in {@link
 * EventWireReflection}.
 *
 * @param commentId the comment
 * @param entityId the item whose thread it is on
 * @param rootEntityId the item's root work item (qits-2518): the epic of a feature or a task, the
 *     item itself for an epic, a ticket or an issue — the one an agent is keyed by
 * @param projectId the item's project
 * @param archetype the item's archetype, as the declared word
 * @param number the item's per-project number, the {@code <n>} of {@code <project-slug>-<n>}
 * @param title the item's title
 * @param author who the comment is by: the principal, or the agent's id for an agent's comment
 * @param authorName who the comment is by, for a person to read (qits-2518): the token's {@code
 *     name}, else its {@code preferred_username}, else the principal
 * @param authorAgentOf for an agent's comment, the qualified id of the agent's work item; else absent
 * @param changedBy the principal that wrote it — the audit name, and what the desk triggers compare
 *     with the desk's own subjects
 * @param mentions every {@code @name} in the body, lower case, in order, each once
 * @param commentedAt when the comment was written
 */
public record EntityCommented(
    UUID eventId,
    String commentId,
    String entityId,
    String rootEntityId,
    String projectId,
    String archetype,
    long number,
    String title,
    String author,
    String authorName,
    String authorAgentOf,
    String changedBy,
    List<String> mentions,
    Instant commentedAt)
    implements QitsEvent {

  public EntityCommented {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    mentions = mentions == null ? List.of() : List.copyOf(mentions);
  }

  @Override
  public Instant occurredAt() {
    return commentedAt;
  }
}
