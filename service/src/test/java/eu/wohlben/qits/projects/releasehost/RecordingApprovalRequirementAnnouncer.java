package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ApprovalRequirementAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link ApprovalRequirementAnnouncer}: records every change instead of publishing it,
 * and wins the injection over the {@code @DefaultBean} adapter so no test reaches the bus.
 */
@ApplicationScoped
public class RecordingApprovalRequirementAnnouncer implements ApprovalRequirementAnnouncer {

  private final List<RequirementChange> recorded = Collections.synchronizedList(new ArrayList<>());

  public List<RequirementChange> changesOf(String releaseRequestId) {
    synchronized (recorded) {
      return recorded.stream()
          .filter(change -> change.releaseRequestId().equals(releaseRequestId))
          .toList();
    }
  }

  /** {@code <requirementId>:<state>@<first 7 of the fold>}, in the order they were announced. */
  public List<String> statesOf(String releaseRequestId) {
    return changesOf(releaseRequestId).stream()
        .map(
            change ->
                change.requirementId()
                    + ":"
                    + change.state()
                    + "@"
                    + change.mergedSha().substring(0, Math.min(7, change.mergedSha().length())))
        .toList();
  }

  public void reset() {
    recorded.clear();
  }

  @Override
  public void onRequirementChanged(RequirementChange change) {
    recorded.add(change);
  }
}
