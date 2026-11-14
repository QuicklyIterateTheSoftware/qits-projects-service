package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.NamedAgentStateAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * <b>The {@link NamedAgentStateAnnouncer} adapter</b> (qits-2157): each observed change of a named
 * agent's state becomes one {@link NamedAgentStateChanged} on the bus. The dedup is the caller's
 * ({@code deskhost/NamedAgentStates}); this only maps and publishes.
 *
 * <p>{@code @DefaultBean}, as {@link PitchEventAnnouncer} is, so the suite's recording double wins
 * the injection simply by existing. {@code QitsEventBus.publish} never throws (an undelivered event
 * goes to its outbox), and the caller catches anything that does.
 */
@ApplicationScoped
@DefaultBean
public class NamedAgentStateEventAnnouncer implements NamedAgentStateAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onNamedAgentStateChanged(NamedAgentStateChange change) {
    publish(event(change));
  }

  protected void publish(QitsEvent event) {
    bus.publish(event);
  }

  /** The wire event of {@code change}. */
  static NamedAgentStateChanged event(NamedAgentStateChange change) {
    Instant at = change.changedAt() == null ? Instant.now() : change.changedAt();
    return new NamedAgentStateChanged(
        null,
        change.projectId(),
        change.agentName(),
        change.state(),
        change.commandId(),
        change.sessionId(),
        at);
  }
}
