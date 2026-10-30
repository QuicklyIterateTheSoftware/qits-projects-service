package eu.wohlben.qits.entities.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * <b>The one place an idea change is handed to {@link IdeaAnnouncer}</b> (qits-1387), after the
 * write that made it has committed. Every writer collects its {@link IdeaChange}s inside its hold —
 * cleared at the start of each attempt, so a retried body never announces twice — and calls {@link
 * #announce} once the hold has returned. A failing announcer is a WARN and never fails a write that
 * has already happened.
 */
@ApplicationScoped
public class IdeaAnnouncements {

  private static final Logger LOG = Logger.getLogger(IdeaAnnouncements.class);

  @Inject Instance<IdeaAnnouncer> announcer;

  /** Hands each change on, in order. Never throws. */
  public void announce(List<IdeaChange> changes) {
    if (changes == null || changes.isEmpty() || announcer.isUnsatisfied()) {
      return;
    }
    for (IdeaChange change : List.copyOf(changes)) {
      try {
        announcer.get().onIdeaChanged(change);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not announce %s of idea %s", change.kind(), change.ideaId());
      }
    }
  }
}
