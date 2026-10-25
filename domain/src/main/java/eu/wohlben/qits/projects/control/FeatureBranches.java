package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.BranchWorkItems;
import eu.wohlben.qits.projects.control.approval.BranchWorkItems.WorkItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>Whether a branch counts toward the soft feature freeze</b> — the classification of epic
 * qits-1580: an active release request is frozen once it holds as many counted sources as its
 * threshold ({@link FeatureFreezeThreshold}), and every further ask through the create door queues
 * behind it.
 *
 * <p><b>Every branch that resolves to a work item counts</b> ({@link BranchWorkItems}): an {@code
 * EPIC}, a {@code TASK}, or a {@code TICKET} of any type — {@code IMPROVEMENT}, {@code BUG} and
 * platform {@code MAINTENANCE} tickets alike, and one that never said. A fix is work somebody has to
 * gate, so it widens the fold as much as a feature does.
 *
 * <p><b>A branch naming no work item never counts</b>: {@code main}, {@code maintenance/*}
 * dependency and gitlink bumps, {@code maintenance/automations/...}, {@code release/*}, anything
 * unparseable — and a branch whose work item cannot be read, since the freeze is soft and "could not
 * tell" must not fail the ask. Where no {@link BranchWorkItems} adapter is deployed every branch
 * names nothing, and nothing counts.
 *
 * <p>The method and the source row's {@code feature} column keep the name they had before the owner
 * ruled that every work item counts; what they mean now is "counts toward the freeze".
 */
@ApplicationScoped
public class FeatureBranches {

  private static final Logger LOG = Logger.getLogger(FeatureBranches.class);

  @Inject Instance<BranchWorkItems> workItems;

  /** Whether {@code branch} counts toward the freeze; see the class javadoc. */
  public boolean isFeature(String branch) {
    if (!workItems.isResolvable()) {
      return false;
    }
    return isFeature(workItems.get(), branch);
  }

  /** The classification over a port in hand — the bean's answer, and the unit test's seam. */
  static boolean isFeature(BranchWorkItems port, String branch) {
    Optional<WorkItem> item;
    try {
      item = port.workItemOf(branch);
    } catch (RuntimeException e) {
      LOG.infof(
          e, "Could not read the work item of branch %s; it does not count toward the freeze", branch);
      return false;
    }
    return item.isPresent();
  }
}
