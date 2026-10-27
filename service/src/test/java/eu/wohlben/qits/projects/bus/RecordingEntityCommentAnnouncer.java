package eu.wohlben.qits.projects.bus;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link EntityCommentAnnouncer}: records each {@link EntityCommented} instead of
 * publishing it. An ordinary bean over the {@code @DefaultBean}, so it wins the injection point.
 */
@ApplicationScoped
public class RecordingEntityCommentAnnouncer extends EntityCommentAnnouncer {

  private final List<EntityCommented> published = new ArrayList<>();

  @Override
  protected synchronized void publish(EntityCommented event) {
    published.add(event);
  }

  public synchronized List<EntityCommented> published() {
    return List.copyOf(published);
  }

  public synchronized void reset() {
    published.clear();
  }
}
