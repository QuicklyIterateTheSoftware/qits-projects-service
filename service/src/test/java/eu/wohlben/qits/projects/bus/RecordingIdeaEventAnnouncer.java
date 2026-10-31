package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link IdeaEventAnnouncer} (qits-1791): records each idea event instead of publishing
 * it, so a test can see what a write announced. An ordinary bean over the {@code @DefaultBean}, so it
 * wins the injection point; the wire mapping ({@link IdeaEventAnnouncer#event}) still runs.
 */
@ApplicationScoped
public class RecordingIdeaEventAnnouncer extends IdeaEventAnnouncer {

  private final List<QitsEvent> published = new ArrayList<>();

  @Override
  protected synchronized void publish(QitsEvent event) {
    published.add(event);
  }

  public synchronized List<QitsEvent> published() {
    return List.copyOf(published);
  }

  public synchronized void reset() {
    published.clear();
  }
}
