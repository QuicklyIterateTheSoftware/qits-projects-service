package eu.wohlben.qits.projects.bus;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link EntityBlockAnnouncer} (qits-2674): records each {@link EntityBlockChanged}
 * instead of publishing it. An ordinary bean over the {@code @DefaultBean}, so it wins the injection
 * point; the mapping still runs.
 */
@ApplicationScoped
public class RecordingEntityBlockAnnouncer extends EntityBlockAnnouncer {

  private final List<EntityBlockChanged> published = new ArrayList<>();

  @Override
  protected synchronized void publish(EntityBlockChanged event) {
    published.add(event);
  }

  /** Every {@link EntityBlockChanged} recorded about {@code projectId}, in order. */
  public synchronized List<EntityBlockChanged> of(String projectId) {
    return published.stream().filter(event -> projectId.equals(event.projectId())).toList();
  }

  public synchronized void reset() {
    published.clear();
  }
}
