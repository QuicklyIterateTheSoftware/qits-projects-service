package eu.wohlben.qits.projects.bus;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link EntityAssignAnnouncer} (qits-2518): records each {@link EntityAssigned}
 * instead of publishing it. An ordinary bean over the {@code @DefaultBean}, so it wins the injection
 * point; the mapping and the display-name rule still run.
 */
@ApplicationScoped
public class RecordingEntityAssignAnnouncer extends EntityAssignAnnouncer {

  private final List<EntityAssigned> published = new ArrayList<>();

  @Override
  protected synchronized void publish(EntityAssigned event) {
    published.add(event);
  }

  /** Every {@link EntityAssigned} recorded about {@code projectId}, in order. */
  public synchronized List<EntityAssigned> of(String projectId) {
    return published.stream().filter(event -> projectId.equals(event.projectId())).toList();
  }

  public synchronized void reset() {
    published.clear();
  }
}
