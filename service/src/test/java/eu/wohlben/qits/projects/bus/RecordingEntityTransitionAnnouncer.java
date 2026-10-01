package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.TransitionAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link TransitionAnnouncer}: records the {@link EntityTransitioned} each announced
 * batch would publish, instead of publishing it.
 *
 * <p>An ordinary bean over the {@code @DefaultBean} {@link EntityTransitionAnnouncer}, so it wins the
 * port's injection point and no test reaches the bus. It <b>extends</b> the adapter and replaces
 * {@link EntityTransitionAnnouncer#publish} alone, so the adapter's agent signals (qits-617) still
 * run in the suite as they run in production — a double that re-implemented the port would hide
 * exactly that hop — the posture of {@code
 * RecordingReleaseAnnouncer}. What it records is the adapter's own mapping ({@link
 * EntityTransitionAnnouncer#event}), so an assertion here is about the wire record and not about a
 * second transcription of it. The entities module's {@code RecordingTransitionAnnouncer} is not on
 * this module's classpath (no test-jar), which is why this one exists.
 */
@ApplicationScoped
public class RecordingEntityTransitionAnnouncer extends EntityTransitionAnnouncer {

  private final List<EntityTransitioned> published = new ArrayList<>();

  @Override
  protected synchronized void publish(EntityTransitioned event) {
    published.add(event);
  }

  /** Every event that would have been published since the last {@link #reset()}, in order. */
  public synchronized List<EntityTransitioned> published() {
    return List.copyOf(published);
  }

  public synchronized void reset() {
    published.clear();
  }
}
