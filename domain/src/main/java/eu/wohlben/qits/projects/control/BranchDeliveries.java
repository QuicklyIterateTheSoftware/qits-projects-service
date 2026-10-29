package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.BranchDelivery;
import eu.wohlben.qits.projects.entity.BranchDelivery.Step;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.persistence.BranchDeliveryRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * <b>Where each branch stands on its way to production</b> (qits-1719): the one writer of the
 * {@code branch_delivery} ledger, and so the one place {@code BranchDeliveryChanged} is announced
 * from.
 *
 * <p>{@code ReleaseRequestStateChanged} is keyed by the request, and the request is the wrong key
 * for whoever works on a branch: a release hands a late branch on to the next request, a successor
 * supersedes a red release and carries its branches, and a ticket's branch in an estate rides one
 * request per repository. So {@code ReleaseRequests} calls a verb here at each point a branch's
 * position changes — the hook table is in AGENTS.md — and this writes the row and announces the
 * step by the branch's name.
 *
 * <h2>Which branches</h2>
 *
 * <p>The named {@code BRANCH} sources a caller put on a request, and nothing else: never the
 * repository's default branch (a source of every request, and delivered by none), never a {@code
 * release/} backing branch or a {@code maintenance/} bump, and never a released tag (an implicit
 * source is a release, not a branch).
 *
 * <h2>Once per position, and {@code everywhere}</h2>
 *
 * <p>A step the row already holds — the same step, request, fold and fold attempt — is neither
 * written nor announced again. That is the whole of the deduplication: a retried red run at the
 * same fold is one FAILED, the same conflict hit again is one CONFLICTED, and a request is JOINED
 * once. A new fold's verdict is a new position and is announced — and so is a new failed attempt
 * at the same fold (qits-1725): a conflict or a missing source lands no fold, so the sha alone
 * cannot tell two of them apart, and {@link FoldAttempt} keys them.
 *
 * <p><b>A failure is a position, and leaving it is one too</b> (qits-1719). FAILED and CONFLICTED
 * are written when the request goes REJECTED, FAILED or CONFLICTED, but the request does not stay
 * there: a push refolds it, a fold that lands lifts a conflict or a missing source, a retried run
 * goes green at the same fold — and each of those moves it back to PENDING (or on to READY) with no
 * step of its own. Left alone, the row kept saying FAILED for a branch that was healthy again: the
 * read door reported it, and {@code qits wait branch --until live}, which reads the ledger first and
 * fires early on FAILED or CONFLICTED, told an agent that had just fixed its build that it had
 * already failed. So {@link ReleaseRequestStates#move} — the one writer of a request's state, and so
 * the one place every such path passes — calls {@link #resumed} on a move to PENDING or READY, and
 * every row still naming that request at FAILED or CONFLICTED takes JOINED at the fold the request
 * is at now: written, heard by the listeners and announced like any step. The position check above
 * lets it through because the step differs. One consequence is deliberate: a retried run that comes
 * back red again reopens the request for a moment (PENDING, then REJECTED), so the branch reads
 * JOINED then FAILED again at the same fold — two steps where there used to be none — while its
 * agent is told once, the notice's key being per fold ({@link BranchDeliveryNotices}).
 *
 * <p>After the row is written, in the same transaction, the branch's rows in every repository of
 * the project are ranked ({@link Step#rank}) and the least advanced is the step's {@code
 * everywhere}: a branch is LIVE everywhere only when every repository it was pushed to has it live.
 * That is what the ledger-only {@code PUSHED} row is for — {@link #pushed} writes it for a branch
 * no request carries yet, so a repository that has not asked for a release holds the branch at
 * PUSHED rather than being absent from the minimum.
 *
 * <p><b>The project's {@code qits-agent-config} never counts</b> (qits-1736). The workspace daemon
 * pushes an agent's memory to the {@link RepositoryArchetype#AGENT_CONFIG AGENT_CONFIG} repository
 * on the same branch name as its ticket branch, and that repository is platform state rather than
 * software: it releases only after a desk approval, long after the work itself is live, or its
 * request is withdrawn. Ranked with the rest, its row held the branch at PUSHED, JOINED or
 * WITHDRAWN and {@code qits wait branch --until live} never fired. So {@code everywhere} ranges
 * over the rows of every other repository — {@link BranchDeliveryRepository#stepsOf} is the one
 * definition, asked by the write and the read door alike — and a branch known only there has {@code
 * everywhere} null. Its row is still written and announced like any other, so a wait naming that
 * repository sees its {@code step}; only the minimum leaves it out.
 *
 * <p><b>A withdrawn row never counts</b> (qits-1737). WITHDRAWN ranks 0, as PUSHED does, but it
 * says the branch no longer ships from that repository: its request was withdrawn, the branch was
 * deleted while on an open request, or the wrapper's empty fold was withdrawn. Ranked with the
 * rest, such a row pinned {@code everywhere} at PUSHED for good — a branch LIVE in every code
 * repository read PUSHED because its {@code qits-qits} row was WITHDRAWN — and {@code qits wait
 * branch --until live} never fired. So {@link BranchDeliveryRepository#stepsOf} leaves WITHDRAWN
 * rows out as well. A re-push resets the row to PUSHED and a new ask makes it JOINED, and it counts
 * again from then; a branch whose every counted row is WITHDRAWN has {@code everywhere} null. The
 * WITHDRAWN step is still written, announced and listed, and carries the {@code everywhere} of the
 * repositories the branch still ships to.
 *
 * <h2>Announced after the commit, heard inside it</h2>
 *
 * <p>Every verb runs inside the caller's transaction and requires one. The step is announced
 * through {@link BranchDeliveryAnnouncer} once that transaction commits, on this thread, in the
 * order the steps were taken — the {@link ReleaseRequestStates#move} technique, an interposed
 * synchronization — so a rolled-back fold or release announces nothing. A {@link
 * BranchDeliveryStepListener} hears the same step at once, inside the transaction, for a consumer
 * whose own row must commit with it (qits-1722).
 *
 * <p><b>Nothing here throws to a caller.</b> The ledger is a record beside the release flow, never
 * a gate on it: a failure is a WARN and the flow goes on. The writes are native upserts for the
 * same reason (see {@link BranchDeliveryRepository}): a push and an ask racing to insert one key
 * must not fail the ask.
 */
@ApplicationScoped
public class BranchDeliveries {

  private static final Logger LOG = Logger.getLogger(BranchDeliveries.class);

  /** The key of this transaction's steps still to be announced, in the registry. */
  private static final Object PENDING = BranchDeliveries.class.getName() + ".pending";

  /** The fallback default branch, for a repository row that names none. */
  private static final String DEFAULT_MAIN = "main";

  /** Why a fold FAILED, as {@code BranchDeliveryChanged.failure} spells it. */
  public enum Failure {
    /** The request's own CI run at the fold finished red. */
    CI,
    /** A release-request automation at the fold failed and nothing else is in flight. */
    AUTOMATION,
    /** The release itself was refused or could not be made. */
    RELEASE,
    /**
     * The sources could not be folded because a source ref is missing on the git host
     * (qits-1725): the fold's {@code MISSING_SOURCE}, which REJECTS the request. No run; the step's
     * fold attempt is {@code missing:<ref>}.
     */
    FOLD
  }

  /** Set while {@link #handingOn} runs: an add there is a MOVED its caller writes, not a JOINED. */
  private static final ThreadLocal<Boolean> HANDING_ON = new ThreadLocal<>();

  @Inject BranchDeliveryRepository ledger;

  @Inject ReleaseRequestSourceRepository sources;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject Instance<BranchDeliveryAnnouncer> announcers;

  @Inject Instance<BranchDeliveryStepListener> listeners;

  @Inject Instance<TransactionSynchronizationRegistry> transactions;

  // ---------------------------------------------------------------------------------------------
  // The verbs
  // ---------------------------------------------------------------------------------------------

  /**
   * <b>PUSHED</b>, ledger-only: {@code branch} moved in {@code repoId}. A branch with no row gets a
   * {@code PUSHED} one — unless {@code carried}, an unreleased request already naming it, whose
   * own JOINED is the position. A LIVE or WITHDRAWN row starts over at {@code PUSHED} with no request:
   * the name was reused. Any other row is left alone. Nothing is announced.
   */
  public void pushed(String repoId, String branch, boolean carried) {
    try {
      Repository repository = repositories.findByIdOptional(repoId).orElse(null);
      if (repository == null
          || repository.project == null
          || !considered(branch, mainOf(repository))) {
        return;
      }
      String projectId = repository.project.id;
      Instant now = Instant.now();
      if (ledger.resetToPushed(projectId, repoId, branch, now) > 0) {
        LOG.debugf("Branch %s of %s was pushed again after it ended; it is PUSHED", branch, repoId);
        return;
      }
      if (!carried) {
        ledger.insertPushed(projectId, repoId, nameOf(repository, null), branch, now);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not record that branch %s of %s was pushed", branch, repoId);
    }
  }

  /**
   * {@code branch} was deleted from {@code repoId}: a row still {@code PUSHED} goes, any other
   * stays.
   */
  public void deleted(String repoId, String branch) {
    try {
      ledger.deletePushed(repoId, branch);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not drop the PUSHED row of deleted branch %s of %s", branch, repoId);
    }
  }

  /**
   * <b>JOINED</b>: {@code branch} was put on {@code row}. Not inside {@link #handingOn}, whose
   * caller writes MOVED for the same add.
   */
  public void joined(ReleaseRequest row, String branch) {
    if (Boolean.TRUE.equals(HANDING_ON.get())) {
      return;
    }
    step(row, branch, Step.JOINED, null, null, null, null, null, null, false);
  }

  /**
   * <b>JOINED again</b>: {@code row} went back to PENDING or on to READY — a push refolded it, a
   * rejection was lifted, a retried run went green at the same fold — so every branch whose ledger
   * row still names it at FAILED or CONFLICTED is on that request again, at the fold it is at now
   * ({@code row.mergedSha}). Rows naming another request, or at any other step, are left alone.
   * Called by {@link ReleaseRequestStates#move} alone, for every move to those two states, so the
   * caller must have set {@code mergedSha} to the fold before the move.
   */
  public void resumed(ReleaseRequest row) {
    if (row == null || row.id == null || row.repoId == null) {
      return;
    }
    List<String> branches;
    try {
      branches = ledger.failedBranchesOf(row.repoId, row.id);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the failed branches of release request %s", row.qualifiedId);
      return;
    }
    for (String branch : branches) {
      step(row, branch, Step.JOINED, null, null, null, null, null, null, false);
    }
  }

  /**
   * <b>MOVED</b>: {@code branch} left the request {@code previousId} and is carried by {@code to} —
   * handed on by a release that did not carry it, or superseded with the red release it was in.
   * Written only while the ledger still places the branch on {@code previousId} (or nowhere): a row
   * that has gone on to another request since is a newer position and is left alone.
   */
  public void moved(
      String previousId, String previousQualifiedId, ReleaseRequest to, String branch) {
    step(to, branch, Step.MOVED, previousId, previousQualifiedId, null, null, null, null, true);
  }

  /**
   * <b>MOVED</b> for every branch {@code earlier} carries: it is OBSOLETE, and {@code successor},
   * which folds its tag in and releases past it, carries them now.
   */
  public void superseded(ReleaseRequest earlier, ReleaseRequest successor) {
    for (String branch : branchesOf(earlier)) {
      moved(earlier.id, earlier.qualifiedId, successor, branch);
    }
  }

  /** <b>FAILED</b>: {@code row}'s fold failed; every branch it carries. */
  public void failed(ReleaseRequest row, Failure failure, String runId) {
    failed(row, failure, runId, null);
  }

  /**
   * <b>FAILED</b> for a fold attempt that landed nothing — {@link Failure#FOLD}, keyed by {@code
   * foldAttempt} ({@link FoldAttempt#ofMissingSource}) so a second missing ref at the same merged
   * sha is news and the same one again is not (qits-1725); every branch {@code row} carries.
   */
  public void failed(ReleaseRequest row, Failure failure, String runId, String foldAttempt) {
    for (String branch : branchesOf(row)) {
      step(row, branch, Step.FAILED, null, null, foldAttempt, failure.name(), runId, null, false);
    }
  }

  /**
   * <b>CONFLICTED</b>: {@code row}'s sources could not be folded; every branch it carries. The
   * attempt ({@link FoldAttempt#ofConflicts} of {@code conflicts}) keys it: the merged sha a
   * conflict leaves is the last fold that landed, so without it a second, different conflict there
   * would read as the position already held (qits-1725). {@code conflicts} ride the step to the
   * in-transaction listeners — never the event — so the notices tell only the heads they name.
   */
  public void conflicted(ReleaseRequest row, List<BackingBranchMerger.Conflict> conflicts) {
    String foldAttempt = FoldAttempt.ofConflicts(conflicts);
    for (String branch : branchesOf(row)) {
      step(
          row, branch, Step.CONFLICTED, null, null, foldAttempt, null, null, null, false, conflicts);
    }
  }

  /** <b>WITHDRAWN</b>: every branch {@code row} still carries. */
  public void withdrawn(ReleaseRequest row) {
    withdrawn(row, branchesOf(row));
  }

  /**
   * <b>WITHDRAWN</b> for the named branches — those left on a withdrawn request, or one that left
   * an open request because it was deleted.
   */
  public void withdrawn(ReleaseRequest row, Collection<String> branches) {
    for (String branch : branches) {
      step(row, branch, Step.WITHDRAWN, null, null, null, null, null, null, false);
    }
  }

  /** <b>RELEASED</b>: the tag {@code row.version} carries each of {@code branches}. */
  public void released(ReleaseRequest row, Collection<String> branches) {
    for (String branch : branches) {
      step(row, branch, Step.RELEASED, null, null, null, null, null, row.version, false);
    }
  }

  /**
   * <b>LIVE</b>: {@code row} is FINALIZED, so every ledger row it RELEASED — and that no later
   * position has overwritten — is live, at the version it was released as.
   */
  public void live(ReleaseRequest row) {
    try {
      for (BranchDeliveryRepository.Released released : ledger.releasedBy(row.id)) {
        write(
            new BranchDeliveryStep(
                released.projectId(),
                released.repoId(),
                released.repoName(),
                released.branch(),
                row.id,
                row.qualifiedId,
                row.mergedSha,
                null,
                Step.LIVE.name(),
                null,
                null,
                null,
                null,
                released.version() != null ? released.version() : row.version,
                null,
                Instant.now()),
            false,
            null);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not record the branches of release request %s as LIVE", row.qualifiedId);
    }
  }

  /**
   * Runs {@code work} — an ask that hands a branch on — with {@link #joined} silenced on this
   * thread: the add it makes is the arrival half of a MOVED the caller writes once it knows where
   * the branch landed, and announcing it as a JOINED first would tell a waiter the branch started
   * over.
   */
  public <T> T handingOn(Supplier<T> work) {
    Boolean outer = HANDING_ON.get();
    HANDING_ON.set(Boolean.TRUE);
    try {
      return work.get();
    } finally {
      if (outer == null) {
        HANDING_ON.remove();
      } else {
        HANDING_ON.set(outer);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The read door (qits-1720)
  // ---------------------------------------------------------------------------------------------

  /** What a branch's read is told when its rows span projects and none was named. */
  public static final String SEVERAL_PROJECTS = "branch in several projects; name --project";

  /** One read of a branch's rows and the {@code everywhere} ranked in the same transaction. */
  private record Read(List<BranchDelivery> rows, String everywhere) {}

  /**
   * A branch's rows as the read door answers them (qits-1720).
   *
   * @param branch the branch asked about
   * @param everywhere the least advanced step over the branch's rows in its project — before
   *     {@code repository} narrowed the rows, and leaving out the {@code AGENT_CONFIG} repository's
   *     (qits-1736) and every WITHDRAWN row (qits-1737) — or null where no row counts
   * @param deliveries the rows, by repository name
   */
  public record Answer(String branch, String everywhere, List<BranchDelivery> deliveries) {}

  /**
   * <b>Where {@code branch} stands</b>: its ledger rows, read in a transaction of their own.
   *
   * <ul>
   *   <li>{@code projectId} (already resolved from an id or a slug by the caller) narrows to that
   *       project. Without it the branch's rows in every project are answered — and a branch with
   *       rows in two projects is a 400 ({@link #SEVERAL_PROJECTS}): {@code everywhere} is a fact
   *       about one project, and answering two side by side would leave the caller to guess which
   *       one a single word described.
   *   <li>{@code repository}, a repository's name or id, narrows {@code deliveries} and never
   *       {@code everywhere}, which is computed over the project's rows first.
   *   <li>{@code everywhere} never counts the project's {@code qits-agent-config} row (qits-1736)
   *       nor a WITHDRAWN row (qits-1737), which {@code deliveries} still lists ("Once per
   *       position, and everywhere" above).
   *   <li>A branch with no rows is no error: an empty answer with {@code everywhere} null, because
   *       "nothing has happened to it yet" is exactly what a waiter polling a fresh branch is told.
   *       So is a branch whose rows all go uncounted — known only in {@code qits-agent-config}, or
   *       WITHDRAWN everywhere else: its rows, and {@code everywhere} null.
   * </ul>
   */
  public Answer read(String branch, String repository, String projectId) {
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("Name the branch: ?branch=<branch>");
    }
    String named = branch.trim();
    Read read =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<BranchDelivery> found =
                      ledger.listByBranch(named).stream()
                          .filter(row -> projectId == null || projectId.equals(row.projectId))
                          .toList();
                  List<String> projects =
                      found.stream().map(row -> row.projectId).distinct().toList();
                  // The rows the write ranks: never the agent configuration's (qits-1736), never
                  // a WITHDRAWN one (qits-1737).
                  return new Read(
                      found,
                      projects.size() == 1
                          ? everywhere(ledger.stepsOf(projects.get(0), named))
                          : null);
                });
    List<BranchDelivery> rows = read.rows();
    if (rows.stream().map(row -> row.projectId).distinct().count() > 1) {
      throw new BadRequestException(SEVERAL_PROJECTS);
    }
    String everywhere = read.everywhere();
    List<BranchDelivery> deliveries =
        repository == null || repository.isBlank()
            ? rows
            : rows.stream()
                .filter(
                    row ->
                        repository.trim().equals(row.repoName)
                            || repository.trim().equals(row.repoId))
                .toList();
    return new Answer(named, everywhere, deliveries);
  }

  /**
   * Whether {@code branch} is one this ledger follows in a repository whose default branch is
   * {@code main}: see "Which branches" in the class javadoc.
   */
  public static boolean considered(String branch, String main) {
    return branch != null
        && !branch.isBlank()
        && !branch.equals(main)
        && !branch.startsWith("release/")
        && !branch.startsWith("maintenance/");
  }

  /**
   * The least advanced of {@code steps} as {@code everywhere} names it — {@code PUSHED}, {@code
   * JOINED}, {@code RELEASED} or {@code LIVE} — or null for no steps at all.
   */
  public static String everywhere(Collection<String> steps) {
    Integer lowest = null;
    for (String step : steps) {
      int rank = Step.valueOf(step).rank();
      lowest = lowest == null ? rank : Math.min(lowest, rank);
    }
    if (lowest == null) {
      return null;
    }
    return switch (lowest) {
      case 0 -> Step.PUSHED.name();
      case 1 -> Step.JOINED.name();
      case 2 -> Step.RELEASED.name();
      default -> Step.LIVE.name();
    };
  }

  // ---------------------------------------------------------------------------------------------
  // The write
  // ---------------------------------------------------------------------------------------------

  /** One step of one branch carried by {@code row}, with no conflicts. */
  private void step(
      ReleaseRequest row,
      String branch,
      Step step,
      String previousId,
      String previousQualifiedId,
      String foldAttempt,
      String failure,
      String runId,
      String version,
      boolean onlyFromPrevious) {
    step(
        row,
        branch,
        step,
        previousId,
        previousQualifiedId,
        foldAttempt,
        failure,
        runId,
        version,
        onlyFromPrevious,
        null);
  }

  /** One step of one branch carried by {@code row}, if it is a branch this ledger follows. */
  private void step(
      ReleaseRequest row,
      String branch,
      Step step,
      String previousId,
      String previousQualifiedId,
      String foldAttempt,
      String failure,
      String runId,
      String version,
      boolean onlyFromPrevious,
      List<BackingBranchMerger.Conflict> conflicts) {
    if (row == null) {
      return;
    }
    try {
      Repository repository = repositories.findByIdOptional(row.repoId).orElse(null);
      String projectId =
          row.projectId != null
              ? row.projectId
              : repository == null || repository.project == null ? null : repository.project.id;
      if (projectId == null || !considered(branch, mainOf(repository))) {
        return;
      }
      write(
          new BranchDeliveryStep(
              projectId,
              row.repoId,
              nameOf(repository, row),
              branch,
              row.id,
              row.qualifiedId,
              row.mergedSha,
              foldAttempt,
              step.name(),
              previousId,
              previousQualifiedId,
              failure,
              runId,
              version,
              null,
              Instant.now(),
              conflicts),
          onlyFromPrevious,
          previousId);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not record branch %s of release request %s as %s",
          branch,
          row.qualifiedId,
          step);
    }
  }

  /**
   * Writes {@code taken} unless the row already holds its position, then computes {@code
   * everywhere}, tells the listeners and owes the announcement.
   */
  private void write(BranchDeliveryStep taken, boolean onlyFromPrevious, String previousId) {
    Optional<BranchDeliveryRepository.Position> current =
        ledger.position(taken.projectId(), taken.repoId(), taken.branch());
    if (current.isPresent()) {
      BranchDeliveryRepository.Position held = current.get();
      if (taken.step().equals(held.step())
          && Objects.equals(taken.requestId(), held.requestId())
          && Objects.equals(taken.foldSha(), held.foldSha())
          && Objects.equals(taken.foldAttempt(), held.foldAttempt())) {
        return;
      }
      if (onlyFromPrevious
          && held.requestId() != null
          && !held.requestId().equals(previousId)
          && !held.requestId().equals(taken.requestId())) {
        LOG.debugf(
            "Branch %s of %s is on %s now; it is not moved from %s",
            taken.branch(), taken.repoId(), held.requestId(), previousId);
        return;
      }
    }
    BranchDelivery row = new BranchDelivery();
    row.projectId = taken.projectId();
    row.repoId = taken.repoId();
    row.repoName = taken.repository();
    row.branch = taken.branch();
    row.step = taken.step();
    row.requestId = taken.requestId();
    row.qualifiedId = taken.qualifiedId();
    row.foldSha = taken.foldSha();
    row.foldAttempt = taken.foldAttempt();
    row.runId = taken.runId();
    row.version = taken.version();
    row.changedAt = taken.changedAt();
    ledger.upsert(row);
    BranchDeliveryStep written =
        new BranchDeliveryStep(
            taken.projectId(),
            taken.repoId(),
            taken.repository(),
            taken.branch(),
            taken.requestId(),
            taken.qualifiedId(),
            taken.foldSha(),
            taken.foldAttempt(),
            taken.step(),
            taken.previousRequestId(),
            taken.previousQualifiedId(),
            taken.failure(),
            taken.runId(),
            taken.version(),
            everywhere(ledger.stepsOf(taken.projectId(), taken.branch())),
            taken.changedAt(),
            taken.conflicts());
    for (BranchDeliveryStepListener listener : listeners) {
      try {
        listener.onStep(written);
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "A branch-delivery listener failed on %s %s of %s",
            written.branch(),
            written.step(),
            written.qualifiedId());
      }
    }
    owe(written);
  }

  // ---------------------------------------------------------------------------------------------
  // After the commit
  // ---------------------------------------------------------------------------------------------

  /** Registers {@code step} for announcement when the active transaction commits. */
  private void owe(BranchDeliveryStep step) {
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      // Nothing here writes without one; were it to, the write has already happened.
      announce(step);
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      List<BranchDeliveryStep> pending = (List<BranchDeliveryStep>) registry.getResource(PENDING);
      if (pending == null) {
        pending = new ArrayList<>();
        registry.putResource(PENDING, pending);
        registry.registerInterposedSynchronization(new AfterCommit(pending));
      }
      pending.add(step);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not register the delivery step of branch %s", step.branch());
    }
  }

  /** Announces this transaction's steps, in the order they were taken, once it has committed. */
  private final class AfterCommit implements Synchronization {

    private final List<BranchDeliveryStep> pending;

    AfterCommit(List<BranchDeliveryStep> pending) {
      this.pending = pending;
    }

    @Override
    public void beforeCompletion() {}

    @Override
    public void afterCompletion(int status) {
      if (status != Status.STATUS_COMMITTED) {
        return;
      }
      pending.forEach(BranchDeliveries.this::announce);
    }
  }

  private void announce(BranchDeliveryStep step) {
    if (!announcers.isResolvable()) {
      return;
    }
    try {
      announcers.get().onStep(step);
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "Could not announce the delivery step %s of branch %s", step.step(), step.branch());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------------------------

  /** The branches {@code row} carries that this ledger follows, in the order they were added. */
  private List<String> branchesOf(ReleaseRequest row) {
    if (row == null) {
      return List.of();
    }
    try {
      return sources.listByRequest(row.id).stream()
          .filter(source -> source.kind == ReleaseRequestSource.Kind.BRANCH)
          .map(source -> source.name)
          .toList();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the branches of release request %s", row.qualifiedId);
      return List.of();
    }
  }

  private static String mainOf(Repository repository) {
    return repository == null || repository.mainBranch == null || repository.mainBranch.isBlank()
        ? DEFAULT_MAIN
        : repository.mainBranch;
  }

  /** The repository's name: the request's copy, else its alias, else its id. */
  private String nameOf(Repository repository, ReleaseRequest row) {
    if (row != null && row.repoName != null && !row.repoName.isBlank()) {
      return row.repoName;
    }
    if (repository == null) {
      return row == null ? null : row.repoId;
    }
    return names.nameFor(repository).orElse(repository.id);
  }

  private TransactionSynchronizationRegistry activeTransaction() {
    if (!transactions.isResolvable()) {
      return null;
    }
    try {
      TransactionSynchronizationRegistry registry = transactions.get();
      int status = registry.getTransactionStatus();
      return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK
          ? registry
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
