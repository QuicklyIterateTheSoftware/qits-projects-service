package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link PitchEventAnnouncer} (qits-2155): records each {@link PitchChanged} instead of
 * publishing it, so a test can see what a pitch write announced. An ordinary bean over the {@code
 * @DefaultBean}, so it wins the injection point; the wire mapping ({@link
 * PitchEventAnnouncer#event}) still runs.
 */
@ApplicationScoped
public class RecordingPitchEventAnnouncer extends PitchEventAnnouncer {

  private final List<QitsEvent> published = new ArrayList<>();

  @Override
  protected synchronized void publish(QitsEvent event) {
    published.add(event);
  }

  /** Every {@link PitchChanged} recorded about {@code pitchId}, in order. */
  public synchronized List<PitchChanged> of(String pitchId) {
    return published.stream()
        .filter(PitchChanged.class::isInstance)
        .map(PitchChanged.class::cast)
        .filter(event -> event.pitchId().equals(pitchId))
        .toList();
  }

  public synchronized void reset() {
    published.clear();
  }
}
