package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseRequestWithdrawalComments;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The suite's {@link ReleaseRequestWithdrawalComments} (qits-1671): records what a withdrawal by
 * branch deletion told the work items, instead of writing on their threads. A plain
 * {@code @ApplicationScoped} bean over the {@code @DefaultBean} {@code
 * entitieshost/WorkItemWithdrawalComments}, so it wins the injection simply by existing — {@code
 * RecordingReleasedBranchWorkspaces}' shape. The port returns nothing, so nothing is scripted but
 * {@link #failWith}, which breaks the never-throw contract on purpose so the caller's belt is an
 * assertion rather than a comment.
 */
@ApplicationScoped
public class RecordingReleaseRequestWithdrawalComments implements ReleaseRequestWithdrawalComments {

  private final List<Withdrawn> calls = new ArrayList<>();
  private final AtomicBoolean fail = new AtomicBoolean(false);

  @Override
  public synchronized void withdrawn(Withdrawn withdrawn) {
    calls.add(withdrawn);
    if (fail.get()) {
      throw new IllegalStateException("the work item's thread exploded");
    }
  }

  public synchronized List<Withdrawn> calls() {
    return List.copyOf(calls);
  }

  /** The calls about one request. */
  public synchronized List<Withdrawn> callsFor(String releaseRequestId) {
    return calls.stream().filter(call -> releaseRequestId.equals(call.releaseRequestId())).toList();
  }

  public void failWith(boolean value) {
    fail.set(value);
  }

  public synchronized void reset() {
    calls.clear();
    fail.set(false);
  }
}
