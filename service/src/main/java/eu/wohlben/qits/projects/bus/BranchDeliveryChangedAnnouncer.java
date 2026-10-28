package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.BranchDeliveryAnnouncer;
import eu.wohlben.qits.projects.control.BranchDeliveryStep;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a branch's delivery step into {@link BranchDeliveryChanged} and hands it to the bus
 * (qits-1719) — {@link ReleaseRequestStateChangedAnnouncer}'s shape.
 *
 * <p>Called by {@code domain}'s {@code BranchDeliveries} after the transaction that wrote the step
 * committed, on the thread that wrote it, so the bus takes the cause from that thread's causation
 * scope. {@code @DefaultBean}: the suite's recording fake wins the port, and no test reaches the
 * bus.
 */
@ApplicationScoped
@DefaultBean
public class BranchDeliveryChangedAnnouncer implements BranchDeliveryAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onStep(BranchDeliveryStep step) {
    bus.publish(event(step));
  }

  /** The event for one step — package-visible so the contract test reads the real mapping. */
  static BranchDeliveryChanged event(BranchDeliveryStep step) {
    return new BranchDeliveryChanged(
        null,
        step.projectId(),
        step.repoId(),
        step.repository(),
        step.branch(),
        step.requestId(),
        step.qualifiedId(),
        step.foldSha(),
        step.step(),
        step.previousRequestId(),
        step.previousQualifiedId(),
        step.failure(),
        step.runId(),
        step.version(),
        step.everywhere(),
        step.changedAt());
  }
}
