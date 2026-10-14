package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.gitmirror.GitProcessSpans;
import eu.wohlben.qits.projects.gitmirror.GitTracing;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code release-gates.resolve} (qits-1202): one span per {@link ReleaseGates#resolve}, saying
 * whether the answer came from the freshness cache, with a {@code release-gates.read} span nested
 * under it on a miss and whatever the git host does nested under that in turn (resolve &gt; read
 * &gt; http). {@code release-gates.for-display} gets the same treatment for {@link
 * ReleaseGates#forDisplay}. Built with {@code new} and no database: the repository row is absent (so
 * the branch is {@code main}) and the git host is a proxy that answers an empty tree — a known,
 * empty gate set, which is what gets cached.
 */
class ReleaseGatesTracingTest {

  private final StackTracing tracing = new StackTracing();

  private ReleaseGates gates() {
    ReleaseGates gates = new ReleaseGates();
    gates.freshnessMs = 60_000;
    gates.repositories =
        new RepositoryRepository() {
          @Override
          public Optional<Repository> findByIdOptional(String id) {
            return Optional.empty();
          }
        };
    gates.gitHosts = new FixedInstance<>(gitHostThatTraces());
    gates.tracing = new FixedInstance<>(tracing);
    return gates;
  }

  /** Every call opens a span of its own, standing in for the HTTP client span a real one makes. */
  private ReleaseGitHost gitHostThatTraces() {
    return (ReleaseGitHost)
        Proxy.newProxyInstance(
            ReleaseGitHost.class.getClassLoader(),
            new Class<?>[] {ReleaseGitHost.class},
            (proxy, method, args) -> {
              try (GitTracing.Span ignored = tracing.start("http " + method.getName(), Map.of())) {
                if (method.getName().equals("tree")) {
                  return ReleaseGitHost.Answer.of(List.of("README.md"));
                }
                throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  @Test
  void aMissReadsTheGitHostInsideTheSpanAndAHitReadsNothing() {
    ReleaseGates gates = gates();

    assertTrue(gates.resolve("repo-1").nothingToWaitOn());

    StackTracing.Recorded miss = tracing.only(ReleaseGates.RESOLVE_SPAN);
    assertEquals("repo-1", miss.attributes.get(GitProcessSpans.REPO_ID));
    assertEquals("miss", miss.attributes.get(ReleaseGates.CACHE_ATTRIBUTE));
    StackTracing.Recorded read = tracing.only(ReleaseGates.READ_SPAN);
    assertEquals("repo-1", read.attributes.get(GitProcessSpans.REPO_ID));
    assertSame(miss, read.parent, "the read nests under resolve (resolve > read > http)");
    assertSame(read, tracing.only("http tree").parent, "the git host's call nests under the read");
    assertTrue(miss.closed);
    assertTrue(read.closed);

    tracing.spans.clear();
    gates.resolve("repo-1");

    StackTracing.Recorded hit = tracing.only(ReleaseGates.RESOLVE_SPAN);
    assertEquals("hit", hit.attributes.get(ReleaseGates.CACHE_ATTRIBUTE));
    assertEquals(1, tracing.spans.size(), "a hit asks nobody: " + tracing.spans);
  }

  @Test
  void aGatesBuiltWithoutCdiTracesNothingAndStillAnswers() {
    ReleaseGates gates = gates();
    gates.tracing = null;

    assertTrue(gates.resolve("repo-1").nothingToWaitOn());
    assertEquals(1, tracing.spans.size(), "only the stand-in host's own span: " + tracing.spans);
  }

  /**
   * {@code release-gates.for-display} (qits-1202): one span per {@link ReleaseGates#forDisplay},
   * counting how many of the repositories it was asked about were a fresh cache hit, how many were
   * served stale and how many had to be read synchronously — with a {@code release-gates.read} span
   * per synchronous read, carrying the repository's id. A mix of exactly one fresh hit and one
   * uncached repository takes the {@code toRead.size() == 1} shortcut, which reads inline rather
   * than through {@code readers} — so the test needs no {@code @PostConstruct startReaders()}.
   */
  @Test
  void forDisplayTracesAMixOfAFreshHitAndAnUncachedRepository() {
    ReleaseGates gates = gates();

    // Seed repo-1 as a fresh cache hit, then forget that span before the span under test.
    assertTrue(gates.resolve("repo-1").nothingToWaitOn());
    tracing.spans.clear();

    Map<String, ReleaseGates.GateSet> answered =
        gates.forDisplay(Map.of("repo-1", "main", "repo-2", "main"));

    assertTrue(answered.get("repo-1").nothingToWaitOn());
    assertTrue(answered.get("repo-2").nothingToWaitOn());

    StackTracing.Recorded forDisplay = tracing.only(ReleaseGates.FOR_DISPLAY_SPAN);
    assertEquals(2L, forDisplay.attributes.get(ReleaseGates.REPOSITORIES_ATTRIBUTE));
    assertEquals(1L, forDisplay.attributes.get(ReleaseGates.HITS_ATTRIBUTE));
    assertEquals(0L, forDisplay.attributes.get(ReleaseGates.STALE_ATTRIBUTE));
    assertEquals(1L, forDisplay.attributes.get(ReleaseGates.MISSES_ATTRIBUTE));
    assertTrue(forDisplay.closed);

    StackTracing.Recorded read = tracing.only(ReleaseGates.READ_SPAN);
    assertEquals("repo-2", read.attributes.get(GitProcessSpans.REPO_ID));
    assertSame(forDisplay, read.parent, "the inline read nests under the display span");
  }
}
