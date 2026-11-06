package eu.wohlben.qits.projects.bus;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link WorkNeedAnnouncer} (qits-1903): records what would have crossed the wire
 * instead of reaching the bus, and wins the injection point over the {@code @DefaultBean} adapter
 * simply by existing. It overrides the {@code publish} half alone, so the CDI observer runs exactly
 * as it does deployed.
 */
@ApplicationScoped
public class RecordingWorkNeedAnnouncer extends WorkNeedAnnouncer {

  private final List<WorkNeedMet> published = new ArrayList<>();

  @Override
  protected synchronized void publish(WorkNeedMet event) {
    published.add(event);
  }

  /** Every event that would have been published since the last {@link #reset()}, in order. */
  public synchronized List<WorkNeedMet> met() {
    return List.copyOf(published);
  }

  /** The events naming {@code entityId} as the item that waits. */
  public synchronized List<WorkNeedMet> metFor(String entityId) {
    return published.stream().filter(event -> entityId.equals(event.entityId())).toList();
  }

  public synchronized void reset() {
    published.clear();
  }
}
