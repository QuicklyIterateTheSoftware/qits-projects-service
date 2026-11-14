package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite's {@link NamedAgentStateEventAnnouncer} (qits-2157): records each {@link
 * NamedAgentStateChanged} instead of publishing it, so a test can see what a named agent's state
 * change announced. An ordinary bean over the {@code @DefaultBean}, so it wins the injection point;
 * the wire mapping ({@link NamedAgentStateEventAnnouncer#event}) still runs.
 */
@ApplicationScoped
public class RecordingNamedAgentStateEventAnnouncer extends NamedAgentStateEventAnnouncer {

  private final List<QitsEvent> published = new ArrayList<>();

  @Override
  protected synchronized void publish(QitsEvent event) {
    published.add(event);
  }

  /** Every {@link NamedAgentStateChanged} recorded about {@code projectId}, in order. */
  public synchronized List<NamedAgentStateChanged> of(String projectId) {
    return published.stream()
        .filter(NamedAgentStateChanged.class::isInstance)
        .map(NamedAgentStateChanged.class::cast)
        .filter(event -> event.projectId().equals(projectId))
        .toList();
  }

  public synchronized void reset() {
    published.clear();
  }
}
