package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.dto.CommentDto;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes {@link EntityCommented} for a comment the comment door wrote (qits-1660). The door calls
 * it after the write commits, and a failure here never fails the comment.
 *
 * <p>{@code @DefaultBean}, like every announcer here: the suite's {@code
 * RecordingEntityCommentAnnouncer} overrides {@link #publish} alone, so no test reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class EntityCommentAnnouncer {

  /**
   * {@code @name}, not inside a word or an e-mail address. A name may hold {@code -} or {@code .}
   * between its characters, so {@code @desk-admin} is not {@code @desk}, and {@code @desk.} is.
   */
  private static final Pattern MENTION =
      Pattern.compile("(?<![\\w@.])@([A-Za-z0-9_]+(?:[-.][A-Za-z0-9_]+)*)");

  @Inject QitsEventBus bus;

  public void onCommented(WorkEntity entity, CommentDto comment, String changedBy) {
    publish(event(entity, comment, changedBy));
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(EntityCommented event) {
    bus.publish(event);
  }

  static EntityCommented event(WorkEntity entity, CommentDto comment, String changedBy) {
    return new EntityCommented(
        null,
        comment.id(),
        entity.id,
        entity.projectId,
        entity.archetype == null ? null : entity.archetype.name(),
        entity.number,
        entity.title,
        comment.author(),
        comment.authorAgentOf(),
        changedBy,
        mentions(comment.body()),
        comment.createdAt() == null ? Instant.now() : comment.createdAt());
  }

  /** Every {@code @name} in {@code body}, lower case, in order, each once. */
  static List<String> mentions(String body) {
    if (body == null) {
      return List.of();
    }
    Set<String> found = new LinkedHashSet<>();
    Matcher matcher = MENTION.matcher(body);
    while (matcher.find()) {
      found.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }
    return List.copyOf(found);
  }
}
