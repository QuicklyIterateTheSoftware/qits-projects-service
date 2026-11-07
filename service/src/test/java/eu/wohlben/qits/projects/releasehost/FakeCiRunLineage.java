package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.CiRunLineage;
import eu.wohlben.qits.projects.control.UncoveredOperations;
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
 * Since qits-1920 it also answers which outputs a red run found stale: none by default, a test
 * stages them with {@link #stale}; {@link #failing} makes that read throw too. Since qits-1940 the
 * same read answers the operations no golden master covers, staged with {@link #uncovered}.
 */
@ApplicationScoped
public class FakeCiRunLineage implements CiRunLineage {

  private final Map<String, String> parents = new ConcurrentHashMap<>();
  private final Map<String, List<String>> stale = new ConcurrentHashMap<>();
  private final Map<String, UncoveredOperations> uncovered = new ConcurrentHashMap<>();
  private final List<String> reportsAsked = new CopyOnWriteArrayList<>();
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

  /** qits-ci's reports for {@code runId} name {@code kinds} stale (qits-1920). */
  public void stale(String runId, String... kinds) {
    stale.put(runId, List.of(kinds));
  }

  /** qits-ci's reports for {@code runId} count {@code count} uncovered operations (qits-1940). */
  public void uncovered(String runId, int count, String... names) {
    uncovered.put(runId, new UncoveredOperations(count, List.of(names)));
  }

  /** The runs whose reports were read, in order. */
  public List<String> reportsAsked() {
    return List.copyOf(reportsAsked);
  }

  public void reset() {
    parents.clear();
    asked.clear();
    stale.clear();
    uncovered.clear();
    reportsAsked.clear();
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

  @Override
  public RunFindings findings(String runId) {
    reportsAsked.add(runId);
    if (failing.get()) {
      throw new IllegalStateException("qits-ci could not be reached");
    }
    return new RunFindings(stale.getOrDefault(runId, List.of()), uncovered.get(runId));
  }
}
