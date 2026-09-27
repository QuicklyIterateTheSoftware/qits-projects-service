package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.TransitionAnnouncer;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link TransitionAnnouncer}: records the {@link EntityTransitioned} each announced
 * batch would publish, instead of publishing it.
 *
 * <p>An ordinary bean over the {@code @DefaultBean} {@link EntityTransitionAnnouncer}, so it wins the
 * port's injection point and no test reaches the bus — the posture of {@code
 * RecordingReleaseAnnouncer}. What it records is the adapter's own mapping ({@link
 * EntityTransitionAnnouncer#event}), so an assertion here is about the wire record and not about a
 * second transcription of it. The entities module's {@code RecordingTransitionAnnouncer} is not on
 * this module's classpath (no test-jar), which is why this one exists.
 */
@ApplicationScoped
public class RecordingEntityTransitionAnnouncer implements TransitionAnnouncer {

  private final List<EntityTransitioned> published = new ArrayList<>();

  @Override
  public synchronized void onEntitiesTransitioned(
      List<TransitionedEntity> entities, Instant transitionedAt) {
    published.add(EntityTransitionAnnouncer.event(entities, transitionedAt));
  }

  /** Every event that would have been published since the last {@link #reset()}, in order. */
  public synchronized List<EntityTransitioned> published() {
    return List.copyOf(published);
  }

  public synchronized void reset() {
    published.clear();
  }
}
