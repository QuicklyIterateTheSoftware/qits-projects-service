package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.PitchAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * <b>The {@link PitchAnnouncer} adapter</b> (qits-2155): each committed pitch change becomes one
 * {@link PitchChanged} on the bus. Told after the write committed ({@code control/Pitches}), never
 * inside it.
 *
 * <p>{@code @DefaultBean}, as {@link IdeaEventAnnouncer} is, so the suite's recording double wins
 * the injection simply by existing. {@code QitsEventBus.publish} never throws (an undelivered event
 * goes to its outbox), and the caller catches anything that does.
 */
@ApplicationScoped
@DefaultBean
public class PitchEventAnnouncer implements PitchAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onPitchChanged(PitchChange change) {
    publish(event(change));
  }

  protected void publish(QitsEvent event) {
    bus.publish(event);
  }

  /** The wire event of {@code change}. */
  static PitchChanged event(PitchChange change) {
    Instant at = change.changedAt() == null ? Instant.now() : change.changedAt();
    return new PitchChanged(
        null,
        change.projectId(),
        change.pitchId(),
        change.agentName(),
        change.repositoryId(),
        change.route(),
        change.screenshot(),
        change.change().name(),
        change.version(),
        at);
  }
}
