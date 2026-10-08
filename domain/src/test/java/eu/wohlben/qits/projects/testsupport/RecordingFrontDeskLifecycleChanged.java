package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.FrontDeskLifecycleChanged;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.List;

/**
 * A TEST-SCOPE {@link FrontDeskLifecycleChanged} that records the calls instead of acting on them.
 * {@code @Alternative @Priority}, so it wins over the shipped {@code @DefaultBean} {@code
 * NoopFrontDeskLifecycleChanged} and, in the service's suite, over the front desk's real
 * implementation too — a suite about the desk injects that one by its own type.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class RecordingFrontDeskLifecycleChanged implements FrontDeskLifecycleChanged {

  /** One call, as the reconcile made it. */
  public record Call(String projectId, FrontDeskLifecycle lifecycle) {}

  private final List<Call> calls = new ArrayList<>();

  @Override
  public synchronized void onChanged(String projectId, FrontDeskLifecycle lifecycle) {
    calls.add(new Call(projectId, lifecycle));
  }

  /** Every call made about {@code projectId}, in order. */
  public synchronized List<Call> callsOf(String projectId) {
    return calls.stream().filter(c -> c.projectId().equals(projectId)).toList();
  }

  /** Forgets everything recorded so far; the bean is shared across a {@code @QuarkusTest} class. */
  public synchronized void clear() {
    calls.clear();
  }
}
