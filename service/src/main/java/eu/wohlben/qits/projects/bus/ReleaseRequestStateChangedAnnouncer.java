package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.ReleaseRequestStateAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a release request's state change into {@link ReleaseRequestStateChanged} and hands it to
 * the bus (qits-1153) — {@link ReleaseRequestChangedAnnouncer}'s shape.
 *
 * <p>Called by {@code domain}'s {@code ReleaseRequestStates} after the transaction that wrote the
 * state committed, on the thread that wrote it, so the bus takes the cause from that thread's
 * causation scope. {@code @DefaultBean}: the suite's recording fake wins the port, and no test
 * reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class ReleaseRequestStateChangedAnnouncer implements ReleaseRequestStateAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onStateChanged(StateChange change) {
    bus.publish(event(change));
  }

  /** The event for one change — package-visible so the contract test reads the real mapping. */
  static ReleaseRequestStateChanged event(StateChange change) {
    return new ReleaseRequestStateChanged(
        change.releaseRequestId(),
        change.projectId(),
        change.repoId(),
        change.repoName(),
        change.state(),
        change.previousState(),
        change.changedAt(),
        change.mergedSha(),
        change.version(),
        change.detail(),
        change.branches());
  }
}
