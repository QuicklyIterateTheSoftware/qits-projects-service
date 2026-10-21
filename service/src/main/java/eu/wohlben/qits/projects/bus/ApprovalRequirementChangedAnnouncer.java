package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.ApprovalRequirementAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * {@link ApprovalRequirementAnnouncer} onto the bus as {@link ApprovalRequirementChanged}. {@code
 * @DefaultBean}, so the suite's recording fake wins the injection and no test reaches the bus.
 * {@link QitsEventBus#publish} never throws for an undelivered event — it goes to the durable outbox.
 */
@ApplicationScoped
@DefaultBean
public class ApprovalRequirementChangedAnnouncer implements ApprovalRequirementAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onRequirementChanged(RequirementChange change) {
    bus.publish(event(change));
  }

  static ApprovalRequirementChanged event(RequirementChange change) {
    return new ApprovalRequirementChanged(
        null,
        change.releaseRequestId(),
        change.releaseRequestQualifiedId(),
        change.projectId(),
        change.repoId(),
        change.repoName(),
        change.mergedSha(),
        change.requirementId(),
        change.reason(),
        change.admits(),
        change.excludesAuthors(),
        change.authors(),
        change.state(),
        change.changedAt());
  }
}
