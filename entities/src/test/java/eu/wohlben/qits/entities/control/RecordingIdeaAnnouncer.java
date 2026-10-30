package eu.wohlben.qits.entities.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/** Records every idea change announced (qits-1387), as {@link RecordingTransitionAnnouncer} does. */
@ApplicationScoped
public class RecordingIdeaAnnouncer implements IdeaAnnouncer {

  private final List<IdeaChange> changes = new ArrayList<>();

  @Override
  public synchronized void onIdeaChanged(IdeaChange change) {
    changes.add(change);
  }

  /** Every change announced since the last {@link #clear()}, in order. */
  public synchronized List<IdeaChange> changes() {
    return List.copyOf(changes);
  }

  public synchronized void clear() {
    changes.clear();
  }
}
