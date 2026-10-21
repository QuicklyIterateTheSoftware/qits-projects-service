package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ConfigDirectorySource;
import eu.wohlben.qits.projects.control.approval.StoredRequirements;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

/**
 * <b>The content half of the approval question, read in the background and written onto the
 * request</b> (qits-1201) — what every content source of {@link ApprovalPolicy} requires of a fold
 * ({@link ApprovalPolicy#readContent}), stored as {@code release_request.approval_content_reasons}
 * for the fold named by {@code approval_content_fold}, in {@link StoredRequirements}' shape (epic
 * qits-1038; a value from before it reads as not read yet).
 *
 * <p><b>Why it is stored rather than remembered.</b> qits-1203's {@code DisplayApprovals} kept the
 * answer in memory per {@code (repoId, mergedSha)}, and every miss was read on the list's own thread:
 * a mirror refresh ({@code git fetch} once the mirror is five seconds old) and about ten git
 * subprocesses per fold. Its memory was emptied by every movement of {@code main} and by every
 * restart, and nothing made two concurrent list reads share one read of a fold — they queued on
 * {@code RepoMirror}'s fetch lock and each redid the work. So the list now reads only the column, and
 * this class is the only thing that runs the git work, on its own pool, <b>once per fold at a
 * time</b>.
 *
 * <p><b>Three triggers, none on a reader's thread:</b>
 *
 * <ul>
 *   <li>a fold lands — {@code ReleaseRequests.remerge} {@link #enqueue}s it once the fold's
 *       transaction has committed;
 *   <li>{@code main} moves — {@code ReleaseRequests.onMainMoved} calls {@link #mainMoved}, which
 *       {@link #reread}s every fold of the repository's requests that are still before their tag,
 *       because both halves diff against {@code main}. The stored reasons keep showing until the
 *       re-read lands;
 *   <li>a list meets an open request whose stored fold is not its current one (a row from before
 *       V44, or a trigger that was lost to a restart) — {@code ReleaseRequests.render} {@link
 *       #enqueue}s it and shows the fold as not read yet ({@link #NOT_YET_READ}), which fails closed
 *       into "approval required" exactly as an unreadable fold always has.
 * </ul>
 *
 * <p><b>Single flight per fold.</b> A fold already being read is not read again by a second {@link
 * #enqueue} — however many lists meet it, it costs one {@code readContent}. A {@link #reread} that
 * finds it in flight instead marks it to be read once more when the current read finishes, since
 * that read may have diffed against the {@code main} from before the move.
 *
 * <p><b>Written only while it is still true.</b> The write is its own {@code requiringNew()}
 * transaction and matches the rows by {@code (repo_id, merged_sha)}, so a request that moved to
 * another fold while this was reading is not touched. Only a complete reading is written ({@link
 * ApprovalPolicy.ContentReading#complete()}): an answer about an outage is not a fact about the fold,
 * and the next trigger reads again.
 *
 * <p><b>Only a display reads what this writes.</b> Every decision — the executor, the gates, an
 * approve or a decline — asks {@link ApprovalPolicy#requirementsFor(ReleaseRequest)}, which reads the
 * fold afresh as it always has.
 *
 * <p><b>The suite reads synchronously</b> ({@code
 * qits.projects.release-requests.display-reads-synchronously=true}): its fakes script a fold's content
 * per repository and change the script for the same sha without moving {@code main}, and a test that
 * lists right after must see what it scripted. There a list calls {@link #readNow} for every open
 * fold it shows, on its own thread, and nothing is read in the background at all. That switch is a
 * test seam; a deployment never sets it.
 */
@ApplicationScoped
public class ApprovalContentRecorder {

  private static final Logger LOG = Logger.getLogger(ApprovalContentRecorder.class);

  /** What a list shows for an open request whose current fold has not been read yet. */
  public static final String NOT_YET_READ = "the changes of this fold have not been read yet";

  /**
   * What a list shows for such a fold: one requirement only a person may satisfy, failing closed as
   * an unreadable fold always has.
   */
  public static List<ApprovalRequirement> notYetRead() {
    return List.of(ApprovalRequirement.person(ConfigDirectorySource.ID, NOT_YET_READ, null));
  }

  @Inject ApprovalPolicy policy;

  @Inject ReleaseRequestRepository requests;

  /**
   * How many folds are read at once. Two folds of one repository share its mirror and its fetch lock,
   * so the bound is about how much git this process runs at a time rather than about contention.
   */
  @ConfigProperty(
      name = "qits.projects.release-requests.approval-read-concurrency",
      defaultValue = "8")
  int readConcurrency;

  /** The suite's switch: read on the list's thread and never in the background. See the javadoc. */
  @ConfigProperty(
      name = "qits.projects.release-requests.display-reads-synchronously",
      defaultValue = "false")
  boolean displayReadsSynchronously;

  /** One fold of one repository. */
  public record Fold(String repoId, String mergedSha) {}

  /** A read in flight; {@link #again} is set by a {@link #reread} that arrived during it. */
  private static final class Flight {
    volatile boolean again;
  }

  private final Map<Fold, Flight> inFlight = new ConcurrentHashMap<>();

  /**
   * Off the caller's thread, carrying none of its CDI context: each task activates a request context
   * of its own for the repository row the mirror read looks up ({@link #inRequest}). No transaction
   * and no security context either; the trace is carried explicitly.
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

  /** Whether a list reads the folds itself — the suite's switch, never a deployment's. */
  public boolean displayReadsSynchronously() {
    return displayReadsSynchronously;
  }

  /**
   * Read {@code mergedSha} of {@code repoId} in the background and store it, unless a read of it is
   * already in flight — then that read is the answer. Never blocks and never throws.
   */
  public void enqueue(String repoId, String mergedSha) {
    submit(repoId, mergedSha, false);
  }

  /**
   * {@link #enqueue}, for a fold whose stored answer may be out of date because {@code main} moved: a
   * read already in flight is followed by one more.
   */
  public void reread(String repoId, String mergedSha) {
    submit(repoId, mergedSha, true);
  }

  /**
   * {@code repoId}'s {@code main} moved: re-read every fold of its requests that are still before their
   * tag. The rows are looked up in the background too, so the caller — the head listener inside the
   * event funnel's transaction, or a finalization merge — waits for nothing.
   */
  public void mainMoved(String repoId) {
    if (displayReadsSynchronously || readers == null || repoId == null) {
      return;
    }
    try {
      readers.execute(
          traced(
              () -> {
                try {
                  List<String> folds =
                      QuarkusTransaction.requiringNew()
                          .call(() -> requests.foldsOf(repoId, ApprovalPolicy.BEFORE_RELEASE));
                  folds.forEach(sha -> reread(repoId, sha));
                } catch (RuntimeException e) {
                  LOG.warnf(e, "Could not list the open folds of %s to re-read after main moved", repoId);
                }
                return null;
              }));
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not start re-reading the folds of %s", repoId);
    }
  }

  /**
   * The suite's path: read the fold on the caller's thread, store it when complete, and answer its
   * requirements either way (an incomplete reading's are the fail-closed ones).
   */
  public List<ApprovalRequirement> readNow(String repoId, String mergedSha) {
    return readAndStore(new Fold(repoId, mergedSha));
  }

  /**
   * The requirements stored on {@code row} for its <b>current</b> fold, or empty where the stored
   * ones are about another fold, there are none, or they will not parse (a value from before epic
   * qits-1038 included) — all of which mean "not read yet".
   */
  public static Optional<List<ApprovalRequirement>> storedFor(ReleaseRequest row) {
    if (row.mergedSha == null
        || row.approvalContentReasons == null
        || !row.mergedSha.equals(row.approvalContentFold)) {
      return Optional.empty();
    }
    Optional<List<ApprovalRequirement>> stored =
        StoredRequirements.read(row.approvalContentReasons);
    if (stored.isEmpty()) {
      LOG.debugf("Release request %s has approval content in no shape this reads", row.id);
    }
    return stored;
  }

  private void submit(String repoId, String mergedSha, boolean invalidate) {
    if (displayReadsSynchronously || readers == null) {
      return;
    }
    if (repoId == null || mergedSha == null || mergedSha.isBlank()) {
      return;
    }
    Fold fold = new Fold(repoId, mergedSha);
    boolean[] launch = {false};
    inFlight.compute(
        fold,
        (key, current) -> {
          if (current == null) {
            launch[0] = true;
            return new Flight();
          }
          if (invalidate) {
            current.again = true;
          }
          return current;
        });
    if (launch[0]) {
      launch(fold);
    }
  }

  private void launch(Fold fold) {
    try {
      readers.execute(traced(() -> run(fold)));
    } catch (RuntimeException e) {
      // A pool that refuses the task (shutting down): nothing is in flight, the next trigger reads.
      inFlight.remove(fold);
      LOG.debugf(e, "Could not start reading %s", fold);
    }
  }

  private Void run(Fold fold) {
    try {
      inRequest(() -> readAndStore(fold));
    } catch (RuntimeException e) {
      LOG.debugf(e, "Reading %s did not complete", fold);
    }
    boolean[] again = {false};
    inFlight.computeIfPresent(
        fold,
        (key, current) -> {
          if (current.again) {
            current.again = false;
            again[0] = true;
            return current;
          }
          return null;
        });
    if (again[0]) {
      launch(fold);
    }
    return null;
  }

  /** One read of one fold, and the write when it is complete. */
  private List<ApprovalRequirement> readAndStore(Fold fold) {
    ApprovalPolicy.ContentReading reading =
        policy.readContent(true, fold.repoId(), fold.mergedSha());
    if (!reading.complete()) {
      // An answer about an outage is not written: the stored one (if any) keeps showing, and the
      // next trigger — a list meeting the fold, a fold, a main move — reads again.
      return reading.requirements();
    }
    try {
      String reasons = StoredRequirements.write(reading.requirements());
      QuarkusTransaction.requiringNew()
          .run(() -> requests.recordApprovalContent(fold.repoId(), fold.mergedSha(), reasons));
    } catch (Exception e) {
      LOG.warnf(e, "Could not record the approval content of %s", fold);
    }
    return reading.requirements();
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
  private static Runnable traced(Supplier<Void> task) {
    io.opentelemetry.context.Context context = io.opentelemetry.context.Context.current();
    return () -> {
      try (io.opentelemetry.context.Scope ignored = context.makeCurrent()) {
        task.get();
      }
    };
  }
}
