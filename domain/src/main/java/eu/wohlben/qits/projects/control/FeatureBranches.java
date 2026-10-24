package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.BranchWorkItems;
import eu.wohlben.qits.projects.control.approval.BranchWorkItems.WorkItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>Whether a branch carries a feature</b> — the classification of epic qits-1580, the soft
 * feature freeze: an active release request takes as many feature sources as its project's {@code
 * release_requests.feature_freeze} allows, and fixes always.
 *
 * <p>The branch's work item ({@link BranchWorkItems}) decides:
 *
 * <ul>
 *   <li>a {@code TICKET} that is a {@code BUG} or {@code MAINTENANCE} is a fix, not a feature;
 *   <li>a {@code TICKET} that is an {@code IMPROVEMENT}, or never said, is a feature;
 *   <li>an {@code EPIC} or a {@code TASK} is a feature.
 * </ul>
 *
 * <p>A branch naming no work item — {@code main}, {@code maintenance/*}, {@code release/*}, anything
 * unparseable — is not a feature, and neither is one whose work item cannot be read: the freeze is
 * soft, so "could not tell" lets the branch join rather than failing or queueing the ask. Where no
 * {@link BranchWorkItems} adapter is deployed every branch names nothing, and nothing is a feature.
 */
@ApplicationScoped
public class FeatureBranches {

  private static final Logger LOG = Logger.getLogger(FeatureBranches.class);

  @Inject Instance<BranchWorkItems> workItems;

  /** Whether {@code branch} names a work item that is a feature; see the class javadoc. */
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
      LOG.infof(e, "Could not read the work item of branch %s; it counts as no feature", branch);
      return false;
    }
    if (item.isEmpty()) {
      return false;
    }
    String archetype = item.get().archetype();
    if ("TICKET".equals(archetype)) {
      String ticketType = item.get().ticketType();
      return !"BUG".equals(ticketType) && !"MAINTENANCE".equals(ticketType);
    }
    return "EPIC".equals(archetype) || "TASK".equals(archetype);
  }
}
