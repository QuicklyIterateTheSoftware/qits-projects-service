package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.milestone.MilestonesChanged;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * <b>Publishes {@link MilestoneMet} and {@link MilestoneUnmet}</b> (epic qits-615) — the one place
 * either crosses the wire, from every source:
 *
 * <ul>
 *   <li>an authoring write in the {@code entities} module (a milestone created, edited or approved,
 *       a campaign member's condition set or approved, a start's current-state latch) fires {@link
 *       MilestonesChanged} on the CDI bus inside its transaction, and {@link #onChanged} — {@code
 *       during = AFTER_SUCCESS} — publishes it once that write has committed, never for an attempt
 *       that rolled back and was retried;
 *   <li>the criteria consumer ({@link CampaignCriteriaListener}) calls {@link #announce} itself with
 *       what {@code MilestoneEvaluator.observe} answered, after its own {@code epics} transaction
 *       committed.
 * </ul>
 *
 * <p><b>Durable, never fire-and-forget</b>: {@code QitsEventBus.publish} attempts the PUT inline and
 * leaves the event to its outbox (its own transaction) when qits-events is not there, exactly as
 * {@link EntityTransitionAnnouncer} publishes {@link EntityTransitioned}. It never throws into the
 * write that caused it.
 *
 * <p>{@code @DefaultBean}, like every announcer here: the suite's {@code RecordingMilestoneAnnouncer}
 * overrides {@link #publish} alone, so no test reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class MilestoneAnnouncer {

  private static final Logger LOG = Logger.getLogger(MilestoneAnnouncer.class);

  @Inject QitsEventBus bus;

  /** An authoring write's change, once it has committed. */
  void onChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) MilestonesChanged changes) {
    announce(changes);
  }

  /** Publishes every met and un-met milestone of {@code changes}, in order; never throws. */
  public void announce(MilestonesChanged changes) {
    if (changes == null) {
      return;
    }
    for (MilestonesChanged.Met met : changes.met()) {
      try {
        publish(
            new MilestoneMet(
                met.milestoneId(),
                met.projectId(),
                met.displayName(),
                met.groupId(),
                met.satisfiedAt()));
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not announce that milestone %s is met", met.milestoneId());
      }
    }
    for (MilestonesChanged.Unmet unmet : changes.unmet()) {
      try {
        publish(
            new MilestoneUnmet(
                unmet.milestoneId(), unmet.projectId(), unmet.displayName(), unmet.at()));
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not announce that milestone %s is no longer met", unmet.milestoneId());
      }
    }
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(MilestoneMet event) {
    bus.publish(event);
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(MilestoneUnmet event) {
    bus.publish(event);
  }
}
