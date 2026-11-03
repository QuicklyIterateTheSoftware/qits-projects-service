package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link MilestoneAnnouncer}: records what would have crossed the wire instead of
 * reaching the bus, and wins the injection point over the {@code @DefaultBean} adapter simply by
 * existing. It overrides the two {@code publish} halves alone, so the CDI observer and the
 * listener's own call run exactly as they do deployed.
 */
@ApplicationScoped
public class RecordingMilestoneAnnouncer extends MilestoneAnnouncer {

  private final List<QitsEvent> published = new ArrayList<>();

  @Override
  protected synchronized void publish(MilestoneMet event) {
    published.add(event);
  }

  @Override
  protected synchronized void publish(MilestoneUnmet event) {
    published.add(event);
  }

  /** Every event that would have been published since the last {@link #reset()}, in order. */
  public synchronized List<QitsEvent> published() {
    return List.copyOf(published);
  }

  /** The {@link MilestoneMet}s among {@link #published()}. */
  public synchronized List<MilestoneMet> met() {
    return published.stream()
        .filter(MilestoneMet.class::isInstance)
        .map(MilestoneMet.class::cast)
        .toList();
  }

  /** The {@link MilestoneUnmet}s among {@link #published()}. */
  public synchronized List<MilestoneUnmet> unmet() {
    return published.stream()
        .filter(MilestoneUnmet.class::isInstance)
        .map(MilestoneUnmet.class::cast)
        .toList();
  }

  public synchronized void reset() {
    published.clear();
  }
}
