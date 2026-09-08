package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.gitmirror.AheadBehind;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * How far a refinement's branch has drifted from the wrapper's default branch — {@code ahead},
 * {@code behind} and whether merging back would conflict — held as a <b>cached fact this service
 * recomputes in the background</b>, never as work a read performs.
 *
 * <h2>Why this is a bean and not four lines in {@code view()}</h2>
 *
 * <p>Until 2026-09-08 {@link RefinementService#view} computed all three inline, and the first call
 * in that block was {@code mirror.refresh()}. {@link RepoMirror#refresh()} is a {@code git fetch}
 * under a lock when the mirror is warm and a <b>full {@code git clone} when it is cold</b> — and
 * cold is exactly the state a refinement is in the moment it is created, because cutting {@code
 * refining/<slug>} is the first thing this service ever does against that wrapper. So the one read
 * the refining page opens with was the one read that could sit behind an unbounded network
 * operation, and the page it feeds rendered nothing at all until it answered. Measured live on
 * 2026-09-08 against a <em>warm</em> mirror: the project's refinements listing 12&nbsp;ms, an
 * established row 18&nbsp;ms, a freshly created row <b>139&nbsp;ms</b> — real while warm, and
 * unbounded while cold.
 *
 * <p>The trade this makes is the one the numbers argue for. Drift is three integers on one status
 * strip on one tab; it is {@code null} for a brand-new refinement whatever anybody does, since a
 * branch cut at its parent's tip is zero-by-zero and only becomes interesting after somebody
 * pushes. A read that blocks on the wire to produce a number that is not yet a number is paying a
 * page's whole first paint for nothing.
 *
 * <h2>The contract</h2>
 *
 * <ul>
 *   <li><b>{@link #of} never touches git.</b> It answers whatever this bean last computed — {@link
 *       Drift#UNKNOWN}, meaning "not known yet", when it has computed nothing — and returns that
 *       value <em>before</em> it decides whether to schedule a refresh, so what a caller gets can
 *       never depend on a worker that happens to be quick. A cold mirror therefore degrades to
 *       three nulls, which the DTO and the strip already render, rather than to a slow response.
 *   <li><b>A refresh is scheduled, at most one per row at a time, and throttled.</b> A snapshot
 *       younger than {@link #stalenessMillis} is left alone, so a page that re-reads the row on
 *       every hint does not queue a fetch per read; a row already being computed schedules nothing.
 *       The work runs on this bean's own small pool, so an unreachable git host stalls the drift of
 *       the rows being looked at and no request thread anywhere.
 *   <li><b>A drift that CHANGED fires {@code GIT_STATUS}.</b> That is the existing per-refinement
 *       hint the daemon already publishes when the working tree flips clean/dirty, and the SPA
 *       already maps it to "re-read the row" — so drift arriving late needs no new channel, no
 *       poll and no frontend change: the number lands, the row is invalidated, the strip redraws.
 *       An unchanged answer is deliberately silent; firing on every pass would redraw the page
 *       every window for ever.
 * </ul>
 *
 * <p>A pass that could not ask keeps the previous answer and re-stamps it. "Could not reach the git
 * host" is not evidence that the branch stopped being three commits ahead, and re-stamping is what
 * stops a persistently failing mirror from being retried on every single read.
 */
@ApplicationScoped
public class RefinementDrift {

  private static final Logger LOG = Logger.getLogger(RefinementDrift.class);

  /**
   * How long a computed drift is served before a read schedules the next pass. Comfortably longer
   * than {@code qits.projects.git.mirror-freshness-ms} (5s), which throttles the fetch underneath
   * it: this window is about how often a row is worth asking about at all, and drift only moves
   * when somebody pushes.
   */
  @ConfigProperty(name = "qits.projects.refinement.drift-staleness-ms", defaultValue = "30000")
  long stalenessMillis;

  @Inject GitMirrorRegistry mirrors;
  @Inject RefinementChangePublisher changes;

  /**
   * The three numbers the status strip reads. {@code ahead}/{@code behind} are {@code null}
   * together — either git resolved both refs or it resolved neither — and {@code
   * conflictsWithParent} is {@code false} unless a real three-way merge said otherwise, because a
   * conflict warning nobody verified is worse than no warning.
   */
  public record Drift(Integer ahead, Integer behind, boolean conflictsWithParent) {

    /** Not known yet: a cold mirror, a first read, or a pass that never succeeded. */
    public static final Drift UNKNOWN = new Drift(null, null, false);
  }

  private record Snapshot(Drift drift, long computedAtMillis) {}

  private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

  /** Rows with a pass in flight, so N reads in a window queue one recompute rather than N. */
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

  /** Completed passes, for the suite: the read path's whole claim is that it moves this by zero. */
  private final AtomicInteger passes = new AtomicInteger();

  /**
   * Two threads, and deliberately not a cached pool. The work is one git fetch per repository
   * behind a per-mirror lock, so more threads would only queue harder on the same lock; two is
   * enough that one wedged host does not starve every other refinement's drift.
   */
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "refinement-drift");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /**
   * The drift as last computed, and a refresh scheduled if that answer is stale or missing. Does no
   * git work on the calling thread and cannot throw.
   */
  public Drift of(Refinement refinement) {
    Snapshot snapshot = snapshots.get(refinement.id);
    // Read first, decide second: the answer this call returns is the one that was there when it was
    // asked, so a worker finishing mid-call cannot make the result depend on scheduling luck.
    if (snapshot == null
        || System.currentTimeMillis() - snapshot.computedAtMillis() >= stalenessMillis) {
      schedule(refinement);
    }
    return snapshot == null ? Drift.UNKNOWN : snapshot.drift();
  }

  /** Forget a row — the discard path, so a re-opened epic starts at "not known yet". */
  public void forget(long refinementId) {
    snapshots.remove(refinementId);
  }

  /** What has been computed for a row, if anything. The suite's window onto the cache. */
  Optional<Drift> peek(long refinementId) {
    return Optional.ofNullable(snapshots.get(refinementId)).map(Snapshot::drift);
  }

  /** How many passes have completed. */
  int passes() {
    return passes.get();
  }

  private void schedule(Refinement refinement) {
    long id = refinement.id;
    if (!inFlight.add(id)) {
      return;
    }
    // The entity is detached by the time a view is projected, but a background thread must not hold
    // one at all: everything the pass needs is three strings, copied here.
    String repositoryId = refinement.repositoryId;
    String parent = refinement.parent;
    String branch = refinement.branch;
    try {
      executor.submit(() -> compute(id, repositoryId, parent, branch));
    } catch (RuntimeException rejected) {
      inFlight.remove(id);
      LOG.debugf("Could not schedule a drift pass for refinement %s: %s", id, rejected.getMessage());
    }
  }

  private void compute(long id, String repositoryId, String parent, String branch) {
    try {
      Snapshot previous = snapshots.get(id);
      Drift computed;
      try {
        computed = read(repositoryId, parent, branch);
      } catch (RuntimeException e) {
        // Could not ask is not an answer. Keep what we knew and re-stamp it, so a mirror that stays
        // unreachable is retried once a window rather than once a read.
        LOG.debugf("Could not compute drift for refinement %s: %s", id, e.getMessage());
        computed = previous == null ? Drift.UNKNOWN : previous.drift();
      }
      snapshots.put(id, new Snapshot(computed, System.currentTimeMillis()));
      passes.incrementAndGet();
      if (previous == null || !previous.drift().equals(computed)) {
        // The row the browser holds is stale now. GIT_STATUS is the hint it already re-reads the
        // row on; nothing new is invented for the late arrival.
        changes.fire(id, RefinementChangeHint.Topic.GIT_STATUS);
      }
    } finally {
      inFlight.remove(id);
    }
  }

  /** The git half, on the drift thread: refresh the mirror, count, and only then preview a merge. */
  private Drift read(String repositoryId, String parent, String branch) {
    RepoMirror mirror = mirrors.of(repositoryId);
    mirror.refresh();
    AheadBehind counted = mirror.aheadBehind("refs/heads/" + parent, "refs/heads/" + branch);
    if (AheadBehind.UNKNOWN.equals(counted)) {
      return Drift.UNKNOWN;
    }
    Integer ahead = counted.ahead();
    Integer behind = counted.behind();
    boolean conflicts =
        ahead != null
            && behind != null
            && ahead > 0
            && behind > 0
            && wouldConflict(mirror, parent, branch);
    return new Drift(ahead, behind, conflicts);
  }

  /** Only asked when the two have both moved — a merge preview is the expensive half of the pass. */
  private boolean wouldConflict(RepoMirror mirror, String parent, String branch) {
    try {
      return !mirror.previewMerge("refs/heads/" + branch, "refs/heads/" + parent).clean();
    } catch (RuntimeException e) {
      return false; // never a false warning
    }
  }
}
