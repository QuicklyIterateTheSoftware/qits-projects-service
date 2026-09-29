package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.CiRunLineage;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The suite's {@link CiRunLineage}: an ordinary bean, so it wins the injection over the {@code
 * @DefaultBean} HTTP adapter ({@link FakeActiveBuilds}' arrangement). Knows no run by default —
 * "not a retry" — which is what every test that never stages a gap needs. A test stages the runs
 * qits-ci auto-retried with {@link #retried}, or makes every lookup throw with {@link #failing}.
 */
@ApplicationScoped
public class FakeCiRunLineage implements CiRunLineage {

  private final Map<String, String> parents = new ConcurrentHashMap<>();
  private final List<String> asked = new CopyOnWriteArrayList<>();
  private final AtomicBoolean failing = new AtomicBoolean();

  /** qits-ci says {@code runId} re-fired {@code parent}. */
  public void retried(String runId, String parent) {
    parents.put(runId, parent);
  }

  /** Every lookup throws, as a port that broke its no-throw contract would. */
  public void failing(boolean value) {
    failing.set(value);
  }

  public List<String> asked() {
    return List.copyOf(asked);
  }

  public void reset() {
    parents.clear();
    asked.clear();
    failing.set(false);
  }

  @Override
  public Optional<String> retryOfRunId(String runId) {
    asked.add(runId);
    if (failing.get()) {
      throw new IllegalStateException("qits-ci could not be reached");
    }
    return Optional.ofNullable(parents.get(runId));
  }
}
