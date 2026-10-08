package eu.wohlben.qits.projects.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ask qits-maintenance where a release request's <b>automations</b> stand at one fold — the
 * regenerations that have to land inside the request before it may release (estate pins, screenshot
 * baselines, whatever kind is added next; epic qits-978) — and forward a person's re-run of one.
 *
 * <h2>Why the ask leaves this service at all</h2>
 *
 * <p>An automation is a real piece of machinery — decide from the repository whether it applies,
 * plan, dispatch a CI run, commit only the paths it owns, join the result to the request — and
 * qits-maintenance owns all of it, one interface implementation per kind. This context owns none of
 * it and must not grow it: it used to compute the wrapper's gitlink changes itself and ask for a
 * targeted bump, and that computation now lives in maintenance's {@code estate-pins} kind. What is
 * left here is the gate: ask on every fold, read the answer back, and hold the request until every
 * kind that applies is FRESH for the fold it is about to release.
 *
 * <p>So the answer is a <b>statement about one fold</b>, cached by {@link AutomationLedger} against
 * the sha it names. A run's commit lands later and is discovered the way every other commit is — the
 * branch moves, {@code SCMPublishCommit} arrives, the request re-folds and asks again. A red run is
 * not a commit and would never be discovered that way, which is why {@link #status} exists: a held
 * request re-reads it on the sweep, so a failure is learned rather than waited out.
 *
 * <h2>The three rules every port in this package carries, restated because they bind here</h2>
 *
 * <p><b>Nothing here may throw.</b> This is asked on the arming path, after a fold has already
 * landed, and an arming must never fail over an enrichment — an implementation turns every failure
 * into {@link Optional#empty()} (or {@link Run#unreachable}) itself rather than leaving the caller to
 * catch it, exactly as {@link DownstreamComponents} does.
 *
 * <p><b>It must be bounded</b> — one short request, never a retry loop. The caller is a fold, and a
 * fold that sat on a retry ladder would hold the bus consumption that produced it. Asking again is
 * the sweep's job and costs nothing, because the caller's record of the answer is positive (see
 * {@link AutomationLedger}) and an ask that could not be made simply is not one.
 *
 * <p><b>Absent is a supported configuration</b>, and here that sentence needs its consequence spelled
 * out rather than left implied. Injected as an {@code Instance<T>} like every port here; with no
 * implementation, or one that says it is not {@link #configured()}, the automation gate is not
 * configured: <em>every ordinary repository</em> releases exactly as it always did, and a wrapper
 * request <b>waits, and says so</b> — it holds PENDING with a sentence naming the reason, as it did
 * before automations were generalised. "No implementation" cannot mean "release the wrapper's stale
 * pins silently", because that is precisely the failure the estate gate exists to remove and it
 * would arrive through the one path nobody configures and therefore nobody tests. Configured and
 * unreachable is different again: then the gate is configured, nothing is known, and every request
 * holds with a sentence — holding is the only answer that is wrong in a direction somebody can see
 * and fix.
 */
public interface ReleaseRequestAutomations {

  /**
   * One kind's outcome at one fold, as qits-maintenance answers it — its {@code AutomationDto}, field
   * for field.
   *
   * @param kind the wire name, {@code screenshot-baselines}
   * @param label what a page prints, {@code Screenshot baselines}
   * @param state FRESH, REQUESTED, RUNNING, COMMITTED, FAILED, UNKNOWN or SUPERSEDED — a plain
   *     string, because the far side owns the vocabulary and a word this side has never heard of is a
   *     hold rather than a parse failure
   * @param detail the far side's sentence, or null
   * @param bumpId the far side's row that decides the state, null on UNKNOWN
   * @param runIds the qits-ci runs behind it, oldest first; never null
   * @param branch the branch the deciding row writes, or null
   * @param resultSha the commit a COMMITTED outcome left, null otherwise
   * @param updatedAt when the deciding row last changed
   * @param failure why the deciding row's run went red, or null — absent from an older far side
   */
  record Automation(
      String kind,
      String label,
      String state,
      String detail,
      String bumpId,
      List<String> runIds,
      String branch,
      String resultSha,
      Instant updatedAt,
      AutomationLedger.Failure failure) {

    public Automation {
      runIds = runIds == null ? List.of() : List.copyOf(runIds);
    }
  }

  /**
   * Where a request's automations stand at one fold.
   *
   * @param foldSha the fold every entry is about; null on a read of a request nothing was ever asked
   *     about
   * @param automations one entry per kind that applies. <b>Empty means no kind applies</b> on the
   *     trigger's answer — a repository no automation touches, which releases as it always did — but
   *     on a {@link #status} read it can also mean nothing was ever asked at that fold, so a reader
   *     never takes an empty read as FRESH on its own
   */
  record Answer(String requestId, String foldSha, List<Automation> automations) {

    public Answer {
      automations = automations == null ? List.of() : List.copyOf(automations);
    }
  }

  /**
   * What a re-run ask came to. Three outcomes and the caller acts on each differently, which is why
   * this is not an {@code Optional}: an accepted run answers 202 with its id, a far side that refused
   * (a run already active, the request not open, bumping off, an unknown kind) has a sentence the
   * person pressing the button has not got and that is passed through with its status, and a far side
   * that could not be asked at all is a 503.
   *
   * @param id the far side's row id; set only when accepted
   * @param refusal the far side's status (a 4xx) when it refused; null otherwise
   * @param detail the far side's sentence on a refusal, or why it could not be asked
   */
  record Run(String id, Integer refusal, String detail) {

    public static Run accepted(String id) {
      return new Run(id, null, null);
    }

    public static Run refused(int status, String detail) {
      return new Run(null, status, detail);
    }

    public static Run unreachable(String detail) {
      return new Run(null, null, detail);
    }

    public boolean wasAccepted() {
      return id != null;
    }

    public boolean wasRefused() {
      return refusal != null;
    }
  }

  /**
   * Whether this implementation has somewhere to ask — false for the shipped adapter with no
   * address. Not configured is the same answer as no implementation at all: the gate is off for
   * ordinary repositories and a wrapper holds. Never throws.
   */
  default boolean configured() {
    return true;
  }

  /**
   * <b>The every-fold trigger.</b> Settle every kind at this fold and answer where each stands.
   * Idempotent per (request, fold) at the far side, so asking again is cheap and is how an UNKNOWN is
   * re-decided.
   *
   * @param repositoryName the repository as <b>qits-maintenance's catalogue</b> names it, or null for
   *     one with no alias — an implementation that addresses by name answers empty for that
   * @param previousFoldSha the fold this one replaced, or null on the first
   * @param changedSincePrevious every path that changed between the two folds, or null when there is
   *     no previous fold or the diff could not be read — null never carries an outcome over
   * @param sourceBranches the request's named branches, the default branch and the automations' own
   *     already left out
   * @param workItem the work item a commit subject names, or null
   * @return the answer, or <b>empty when the ask could not be made</b> — no address, unreachable,
   *     refusing, unparseable. Never null, never thrown.
   */
  Optional<Answer> request(
      String repositoryName,
      String requestId,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches,
      String workItem);

  /**
   * The read a held request sweeps: where the kinds that have rows at {@code foldSha} stand. A kind
   * with no row there is simply not listed. Empty when the read could not be made.
   */
  Optional<Answer> status(String repositoryName, String requestId, String foldSha);

  /**
   * Re-run one kind on the request's current fold now — the operator's door, forwarded. The
   * repository travels so a request's <em>first</em> run of a kind (nothing asked about yet) can be
   * addressed.
   *
   * @return what came of it; never null and never thrown
   */
  Run rerun(String repositoryName, String requestId, String kind);
}
