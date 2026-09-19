package eu.wohlben.qits.epics.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A TEST-SCOPE implementation of the {@link TransitionAnnouncer} port that records the
 * announcements instead of publishing them.
 *
 * <p>It is what lets a suite assert the one thing about a transition that is not a row: that the
 * platform was told <b>once for the batch</b>, with every entity's post-state in it, and that it was
 * <b>not told at all</b> when the post-state was refused. The bus is dark under {@code %test} and
 * this module has no adapter for the port at all, so without this fake "an event was announced" and
 * "nothing happened" would look identical.
 *
 * <p>An ordinary bean and not an {@code @Alternative}, exactly like {@code
 * RecordingRepositoryAnnouncer} one module over: the shipped implementation is {@code @DefaultBean}
 * in {@code service/…/bus/}, so this one wins the port's injection point simply by existing. Nothing
 * in {@code src/main} references it.
 */
@ApplicationScoped
public class RecordingTransitionAnnouncer implements TransitionAnnouncer {

  /** One announcement, as the port stated it. */
  public record Batch(List<TransitionedEntity> entities, Instant transitionedAt) {}

  private final List<Batch> batches = new ArrayList<>();

  @Override
  public synchronized void onEntitiesTransitioned(
      List<TransitionedEntity> entities, Instant transitionedAt) {
    batches.add(new Batch(List.copyOf(entities), transitionedAt));
  }

  /** Every announcement made since the last {@link #clear()}, in order. */
  public synchronized List<Batch> batches() {
    return List.copyOf(batches);
  }

  public synchronized void clear() {
    batches.clear();
  }
}
