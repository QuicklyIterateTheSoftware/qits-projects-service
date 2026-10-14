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
   * than through {@code readers} — so the test needs no {@code @PostConstruct startReaders()}. That
   * synchronous read is the suite's mode ({@code displayReadsSynchronously}); the deployed mode reads
   * nothing here, which the next test pins.
   */
  @Test
  void forDisplayTracesAMixOfAFreshHitAndAnUncachedRepository() {
    ReleaseGates gates = gates();

    // Seed repo-1 as a fresh cache hit, then forget that span before the span under test.
    assertTrue(gates.resolve("repo-1").nothingToWaitOn());
    tracing.spans.clear();

    gates.displayReadsSynchronously = true;
    Map<String, ReleaseGates.GateSet> answered =
        gates.forDisplay(Map.of("repo-1", "main", "repo-2", "main"), Map.of());

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
  /**
   * The deployed mode (qits-1201): an uncached repository is answered from its stored set, or as not
   * read yet where there is none, and the git host is asked nothing on the caller's thread — the
   * refresh that is started needs the reader pool, which a {@code new}-built instance has not got.
   */
  @Test
  void forDisplayInTheDeployedModeReadsNothingOnTheCallersThread() {
    ReleaseGates gates = gates();

    Map<String, ReleaseGates.GateSet> answered =
        gates.forDisplay(
            Map.of("repo-1", "main", "repo-2", "main"),
            Map.of(
                "repo-1",
                new ReleaseGates.Stored(
                    ReleaseGates.GateSet.of(java.util.EnumSet.of(ReleaseGates.Kind.CI)),
                    java.time.Instant.now())));

    assertTrue(answered.get("repo-1").requires(ReleaseGates.Kind.CI));
    assertEquals(ReleaseGates.NOT_READ_YET, answered.get("repo-2").detail());
    assertTrue(
        tracing.spans.stream().noneMatch(span -> span.name.equals(ReleaseGates.READ_SPAN)),
        "no read: " + tracing.spans);
    StackTracing.Recorded forDisplay = tracing.only(ReleaseGates.FOR_DISPLAY_SPAN);
    assertEquals(1L, forDisplay.attributes.get(ReleaseGates.HITS_ATTRIBUTE));
    assertEquals(1L, forDisplay.attributes.get(ReleaseGates.STALE_ATTRIBUTE));
    assertEquals(0L, forDisplay.attributes.get(ReleaseGates.MISSES_ATTRIBUTE));
  }

  /** The stored form round-trips, and a set nothing can parse reads as nothing stored. */
  @Test
  void aGateSetRoundTripsThroughItsStoredForm() {
    ReleaseGates.GateSet set =
        ReleaseGates.GateSet.of(
            java.util.EnumSet.of(ReleaseGates.Kind.CI, ReleaseGates.Kind.APPROVAL));
    assertEquals(set, ReleaseGates.fromJson(ReleaseGates.toJson(set)));
    ReleaseGates.GateSet none = ReleaseGates.GateSet.of(java.util.EnumSet.noneOf(ReleaseGates.Kind.class));
    assertEquals(none, ReleaseGates.fromJson(ReleaseGates.toJson(none)));
    assertEquals(null, ReleaseGates.fromJson("{\"known\":true,\"kinds\":[\"NOPE\"]}"));
    assertEquals(null, ReleaseGates.fromJson("not json"));
  }
}
