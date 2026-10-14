package eu.wohlben.qits.projects.control;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

/**
 * <b>The content half of the approval question, as a display reads it</b> (qits-1203): whether a
 * fold changes {@code .config/qits/} and whether it adds anything to {@code main} — {@link
 * ApprovalPolicy#readContent} — remembered per {@code (repoId, mergedSha)} so that the release-request
 * list does not pay for it on every view.
 *
 * <p>Measured before this existed: the list's database half took about 35 ms and the rest of a 1.0
 * to 2.5 s page was this read, asked for one open request after another — per repository a mirror
 * refresh ({@code git fetch}, 60–658 ms, because the mirror refetches once it is more than five
 * seconds old) and about ten git subprocesses. A list called every forty seconds paid all of it every
 * time.
 *
 * <p><b>Why it may be remembered at all.</b> The answer is a function of the fold, which a sha
 * names immutably, and of {@code main}, which the two reads diff against (the merge base, and
 * whether the fold adds anything to it). A new fold is a new sha and so a miss by construction; a
 * moved {@code main} is the one thing that can change an answer for the same sha, and this service
 * hears about every one of those — {@link ReleaseRequests#onMainMoved}, which the head listener calls
 * for a push to {@code main} and {@code ReleaseFinalization.merge} calls for its own merge — and
 * {@link #forget}s the repository there.
 *
 * <p><b>Why it is still re-verified after {@link #revalidateMs}.</b> That hook rides the bus, and a
 * missed event (the bus down during a push, a consumer restarting past it) would otherwise leave an
 * answer standing for as long as the process lives. So an entry older than the window is still
 * answered at once — a display must not wait for git — and one background re-read per entry is
 * started; a missed main move therefore shows for at most one window plus one read, on a display,
 * and never on a decision.
 *
 * <p><b>Only a display reads this.</b> Every decision — the executor, the gates, an approve — asks
 * {@link ApprovalPolicy#requirementFor(eu.wohlben.qits.projects.entity.ReleaseRequest)}, which reads
 * the fold afresh. An answer about an outage ({@link ApprovalPolicy.ContentReading#complete()} false:
 * a read that failed and failed closed) is answered and never kept, as {@link ReleaseGates} never
 * keeps an unknown gate set.
 *
 * <p><b>A window of zero turns the memory off</b>, which is what the suite configures ({@code
 * qits.projects.release-requests.approval-display-revalidate-ms=0}, beside the gates' freshness of
 * zero): its fakes script a fold's content per repository and change the script between two reads
 * of the same sha without moving {@code main}, and every such test must see the script it wrote.
 * The misses are read concurrently either way.
 */
@ApplicationScoped
public class DisplayApprovals {

  private static final Logger LOG = Logger.getLogger(DisplayApprovals.class);

  /** How many remembered answers there are at most; the oldest go first beyond it. */
  static final int MAX_ENTRIES = 4096;

  @Inject ApprovalPolicy policy;

  /** How old a remembered answer may be before it is re-read in the background. 0: never kept. */
  @ConfigProperty(
      name = "qits.projects.release-requests.approval-display-revalidate-ms",
      defaultValue = "300000")
  long revalidateMs;

  /**
   * How many repositories' folds are read at once. Each task is one repository: its mirror, its
   * fetch lock, its subprocesses — two repositories never contend, two folds of one repository would.
   */
  @ConfigProperty(
      name = "qits.projects.release-requests.approval-read-concurrency",
      defaultValue = "8")
  int readConcurrency;

  /** One fold of one repository. */
  public record Fold(String repoId, String mergedSha) {}

  private record Remembered(List<String> reasons, long readAt) {}

  private final Map<Fold, Remembered> remembered = new ConcurrentHashMap<>();

  /** Bumped by every {@link #forget}, so a read that began before it does not put its answer back. */
  private final Map<String, Long> generations = new ConcurrentHashMap<>();

  /** The folds a background re-read is in flight for — at most one each. */
  private final Set<Fold> rereading = ConcurrentHashMap.newKeySet();

  /**
   * Off the caller's thread, carrying none of its CDI context: the caller's request context holds a
   * Hibernate session, which is not to be shared between threads, so each task activates a request
   * context of its own for the one repository row the mirror read looks up ({@link #inRequest}). No
   * transaction and no security context either; the trace is carried explicitly.
   */
  private ManagedExecutor readers;

  @PostConstruct
  void start() {
    readers =
        ManagedExecutor.builder()
            .maxAsync(Math.max(1, readConcurrency))
            .propagated(ThreadContext.ALL_REMAINING)
            .cleared(ThreadContext.CDI, ThreadContext.TRANSACTION, ThreadContext.SECURITY)
            .build();
  }

  @PreDestroy
  void stop() {
    if (readers != null) {
      readers.shutdownNow();
    }
  }

  /**
   * The content reasons for each fold, keyed by fold. Remembered answers are answered at once (an old
   * one also starts its background re-read); the rest are read now, one task per repository and the
   * repositories concurrently — a single repository's misses are read on the caller's thread.
   *
   * @param foldsByRepo the folds of open requests, grouped by repository; only repositories that
   *     still have a row — a missing one has no content half to read
   */
  public Map<Fold, List<String>> contentReasons(Map<String, Set<String>> foldsByRepo) {
    Map<Fold, List<String>> answered = new HashMap<>();
    Map<String, List<String>> misses = new LinkedHashMap<>();
    long now = System.currentTimeMillis();
    boolean keep = revalidateMs > 0;
    foldsByRepo.forEach(
        (repoId, shas) -> {
          for (String sha : shas) {
            Fold fold = new Fold(repoId, sha);
            Remembered known = keep ? remembered.get(fold) : null;
            if (known != null) {
              answered.put(fold, known.reasons());
              if (now - known.readAt() >= revalidateMs) {
                rereadInBackground(fold, known);
              }
            } else {
              misses.computeIfAbsent(repoId, key -> new ArrayList<>()).add(sha);
            }
          }
        });
    if (misses.isEmpty()) {
      return answered;
    }
    if (misses.size() == 1) {
      Map.Entry<String, List<String>> only = misses.entrySet().iterator().next();
      answered.putAll(readRepository(only.getKey(), only.getValue()));
      return answered;
    }
    Map<String, CompletableFuture<Map<Fold, List<String>>>> reading = new LinkedHashMap<>();
    misses.forEach(
        (repoId, shas) ->
            reading.put(
                repoId,
                readers.supplyAsync(traced(() -> inRequest(() -> readRepository(repoId, shas))))));
    reading.forEach(
        (repoId, future) -> {
          try {
            answered.putAll(future.join());
          } catch (RuntimeException e) {
            // readContent fails closed on every git failure; a throw here is the pool's. Fail closed
            // the same way, per fold, and keep nothing.
            LOG.debugf(e, "Reading the folds of %s did not complete", repoId);
            for (String sha : misses.get(repoId)) {
              answered.put(
                  new Fold(repoId, sha),
                  List.of("the changes of this fold could not be read: " + e.getMessage()));
            }
          }
        });
    return answered;
  }

  /** Drop every remembered answer about {@code repoId}: its {@code main} moved. */
  public void forget(String repoId) {
    generations.merge(repoId, 1L, Long::sum);
    remembered.keySet().removeIf(fold -> fold.repoId().equals(repoId));
  }

  /** One repository's folds, in turn — one mirror, one fetch lock. */
  private Map<Fold, List<String>> readRepository(String repoId, List<String> shas) {
    long generation = generations.getOrDefault(repoId, 0L);
    Map<Fold, List<String>> out = new HashMap<>();
    for (String sha : shas) {
      Fold fold = new Fold(repoId, sha);
      ApprovalPolicy.ContentReading reading = policy.readContent(true, repoId, sha);
      out.put(fold, reading.reasons());
      if (reading.complete()) {
        keep(fold, reading.reasons(), generation, null);
      }
    }
    return out;
  }

  private void rereadInBackground(Fold fold, Remembered stale) {
    if (!rereading.add(fold)) {
      return;
    }
    long generation = generations.getOrDefault(fold.repoId(), 0L);
    try {
      readers.supplyAsync(
          traced(
              () ->
                  inRequest(
                      () -> {
                        try {
                          ApprovalPolicy.ContentReading reading =
                              policy.readContent(true, fold.repoId(), fold.mergedSha());
                          if (reading.complete()) {
                            keep(fold, reading.reasons(), generation, stale);
                          } else {
                            // An answer about an outage is never kept: the next reader asks again.
                            remembered.remove(fold, stale);
                          }
                        } catch (RuntimeException e) {
                          LOG.debugf(e, "Re-reading %s did not complete", fold);
                        } finally {
                          rereading.remove(fold);
                        }
                        return null;
                      })));
    } catch (RuntimeException e) {
      // A pool that refuses the task (shutting down) leaves the remembered answer standing.
      rereading.remove(fold);
      LOG.debugf(e, "Could not start a re-read of %s", fold);
    }
  }

  /**
   * Remember an answer, unless {@code main} moved since the read began ({@code generation}) or — for
   * a re-read — the entry it replaces is no longer the one found stale.
   */
  private void keep(Fold fold, List<String> reasons, long generation, Remembered replacing) {
    if (revalidateMs <= 0) {
      return;
    }
    Remembered fresh = new Remembered(List.copyOf(reasons), System.currentTimeMillis());
    remembered.compute(
        fold,
        (key, current) -> {
          if (generations.getOrDefault(fold.repoId(), 0L) != generation) {
            return current;
          }
          if (replacing != null && current != replacing) {
            return current;
          }
          return fresh;
        });
    if (remembered.size() > MAX_ENTRIES) {
      prune();
    }
  }

  /** Drop the oldest answers until there is room again — a bound, not a policy. */
  private void prune() {
    List<Map.Entry<Fold, Remembered>> entries = new ArrayList<>(remembered.entrySet());
    entries.sort(Comparator.comparingLong(entry -> entry.getValue().readAt()));
    int excess = remembered.size() - MAX_ENTRIES * 3 / 4;
    for (int index = 0; index < excess && index < entries.size(); index++) {
      remembered.remove(entries.get(index).getKey(), entries.get(index).getValue());
    }
  }

  /**
   * Runs {@code work} under a request context of its own: the mirror read looks a repository row up
   * outside any transaction, which a Hibernate session bound to the request scope answers.
   */
  private static <T> T inRequest(Supplier<T> work) {
    ManagedContext context = Arc.container().requestContext();
    if (context.isActive()) {
      return work.get();
    }
    context.activate();
    try {
      return work.get();
    } finally {
      context.terminate();
    }
  }

  /** The caller's trace, carried onto the reader thread so the git spans are its children. */
  private static <T> Supplier<T> traced(Supplier<T> task) {
    io.opentelemetry.context.Context context = io.opentelemetry.context.Context.current();
    return () -> {
      try (io.opentelemetry.context.Scope ignored = context.makeCurrent()) {
        return task.get();
      }
    };
  }
}
