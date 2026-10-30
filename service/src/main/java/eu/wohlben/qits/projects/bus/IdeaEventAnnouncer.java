package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.IdeaAnnouncer;
import eu.wohlben.qits.entities.control.IdeaChange;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * <b>The {@link IdeaAnnouncer} adapter</b> (qits-1387): each {@link IdeaChange} becomes one of
 * {@link IdeaCreated}, {@link IdeaUpdated}, {@link IdeaArchived} or {@link IdeaUnarchived} on the
 * bus. Told after the write committed ({@code control/IdeaAnnouncements}), never inside it.
 *
 * <p>{@code @DefaultBean}, as {@link EntityTransitionAnnouncer} is, so a recording test double wins
 * the injection simply by existing. {@code QitsEventBus.publish} never throws (an undelivered event
 * goes to its outbox), and the caller catches anything that does.
 */
@ApplicationScoped
@DefaultBean
public class IdeaEventAnnouncer implements IdeaAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onIdeaChanged(IdeaChange change) {
    publish(event(change));
  }

  protected void publish(QitsEvent event) {
    bus.publish(event);
  }

  /** The wire event of {@code change}: one record per kind, the same fields each. */
  static QitsEvent event(IdeaChange change) {
    Instant at = change.occurredAt() == null ? Instant.now() : change.occurredAt();
    return switch (change.kind()) {
      case CREATED ->
          new IdeaCreated(
              null,
              change.ideaId(),
              change.projectId(),
              change.title(),
              change.status(),
              change.statusBefore(),
              change.archived(),
              change.archiveReason(),
              change.changedBy(),
              at);
      case UPDATED ->
          new IdeaUpdated(
              null,
              change.ideaId(),
              change.projectId(),
              change.title(),
              change.status(),
              change.statusBefore(),
              change.archived(),
              change.archiveReason(),
              change.changedBy(),
              at);
      case ARCHIVED ->
          new IdeaArchived(
              null,
              change.ideaId(),
              change.projectId(),
              change.title(),
              change.status(),
              change.statusBefore(),
              change.archived(),
              change.archiveReason(),
              change.changedBy(),
              at);
      case UNARCHIVED ->
          new IdeaUnarchived(
              null,
              change.ideaId(),
              change.projectId(),
              change.title(),
              change.status(),
              change.statusBefore(),
              change.archived(),
              change.archiveReason(),
              change.changedBy(),
              at);
    };
  }
}
