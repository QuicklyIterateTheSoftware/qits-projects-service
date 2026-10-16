package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which of a fold's CI verdicts are <b>this request's own</b>, and which of those is its answer
 * (qits-1266). The one reading of the build-status ledger that the CI gate's display ({@link
 * CiBuildGate}) and its decision ({@code ReleaseRequests.evaluate}, and the two paths that reopen a
 * CI rejection) share, so the two cannot disagree about which run answered.
 *
 * <p><b>Why a filter at all.</b> The ledger is keyed on {@code (repoId, commitSha)} and answers every
 * build of the commit: the request's QA run, but also {@code main}'s build once the fold became
 * {@code main}, another request's QA run and its estate-pins automation on the same commit, and the
 * previous release's tag build where a fold reproduces an already-released commit. Measured on
 * {@code qits-qits-rr-143} (FINALIZED as {@code 2026.1016.62734} at {@code 0adc43cb}): its own QA run
 * {@code 5f3d9844} on {@code release/qits-qits-rr-143} and its tag build {@code 88c177db} on {@code
 * 2026.1016.62734} were green, and its CI gate read FAILED because {@code qits-qits-rr-144}'s
 * automation run {@code 9e73d843} — on {@code main}, at the same commit, an hour after rr-143 shipped
 * — died on a registry timeout. Its retry {@code 400174f1} passed, and the ledger had not superseded
 * the red with it, so supersession is no answer either.
 *
 * <p><b>Own is decided by the branch qits-ci built</b>, the one discriminator every verdict carries
 * and the one qits-ci itself correlates by:
 *
 * <ul>
 *   <li>a <b>QA run</b> builds the request's backing branch — {@link ReleaseRequest#backingBranch()},
 *       stored since qits-1158: {@code release/<qualifiedId>} for a request made since, {@code
 *       release/<uuid>} for one made before V41. {@code release/<uuid>} is accepted on every request
 *       too: it names this request and no other, so it costs nothing and covers a row whose column
 *       was ever written differently. {@code ReleasePipelineRuns} correlates a QA run the same way;
 *   <li>the <b>tag build</b> (the publish run) builds the released version, whose name is the
 *       branch — the identical join {@code ReleaseFinalization.onPublishVerdict} makes on {@link
 *       ReleasedTagPendingMerge#tagName}. Only once the request has a released tag, naturally.
 * </ul>
 *
 * A verdict with no branch, on {@code main}, on another request's backing branch or on another
 * release's tag is not this request's and is never counted, red or green. {@code causationId} was
 * considered and rejected: it names the bus frame that carried the verdict, not the request that
 * asked for the run, so it correlates nothing here.
 *
 * <p><b>The newest own run answers</b>, ordered by {@code finishedAt} (then run id, only so that a
 * tie reads the same on every call). A newer green replaces an older red and a newer red an older
 * green: {@code qits ci retry} already deletes the run it re-fires ({@code BuildStatusLedger.record}),
 * but a rerun of the phase, or a retry whose lineage did not reach this service, leaves the older
 * row standing, and "any red wins" kept such a request red for ever behind a passing build. The
 * ledger answers newest first already; this class sorts anyway rather than lean on a query's {@code
 * order by} from two classes away.
 *
 * <p><b>Nothing is frozen.</b> The same rule answers an open and a concluded request alike: the
 * overview shows each gate's latest result, so a request that has shipped still reads its newest
 * own run — which for a released request is normally its tag build. No time cutoff and no stored
 * snapshot stands between the ledger and the answer (decided on qits-1266's thread, 2026-10-16).
 */
public final class CiVerdicts {

  private CiVerdicts() {}

  /** Newest first: {@code finishedAt} descending, then run id descending. */
  static final Comparator<CommitBuildStatusDto> NEWEST_FIRST =
      Comparator.comparing(
              CommitBuildStatusDto::finishedAt,
              Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
          .thenComparing(
              CommitBuildStatusDto::runId, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
          .reversed();

  /** qits-ci's green word. Every other terminal word is red. */
  public static boolean success(CommitBuildStatusDto verdict) {
    return verdict != null && "SUCCESS".equals(verdict.status());
  }

  /**
   * The request's own verdicts among {@code verdicts}, newest first.
   *
   * @param released the request's released tag, or null where it has not released — and always
   *     null before the tag, where only QA runs can be the request's own
   */
  public static List<CommitBuildStatusDto> own(
      List<CommitBuildStatusDto> verdicts, ReleaseRequest request, ReleasedTagPendingMerge released) {
    if (verdicts == null || verdicts.isEmpty() || request == null) {
      return List.of();
    }
    return verdicts.stream()
        .filter(verdict -> isOwn(verdict, request, released))
        .sorted(NEWEST_FIRST)
        .toList();
  }

  /** The newest of {@link #own}: the run whose word is the CI gate's, or empty where none spoke. */
  public static Optional<CommitBuildStatusDto> newestOwn(
      List<CommitBuildStatusDto> verdicts, ReleaseRequest request, ReleasedTagPendingMerge released) {
    List<CommitBuildStatusDto> own = own(verdicts, request, released);
    return own.isEmpty() ? Optional.empty() : Optional.of(own.get(0));
  }

  /** Whether one verdict is the request's own QA run or tag build — see the class. */
  public static boolean isOwn(
      CommitBuildStatusDto verdict, ReleaseRequest request, ReleasedTagPendingMerge released) {
    if (verdict == null || request == null) {
      return false;
    }
    String branch = bare(verdict.branch());
    if (branch == null) {
      return false;
    }
    if (isBackingBranch(branch, request)) {
      return true;
    }
    return released != null && released.tagName != null && branch.equals(bare(released.tagName));
  }

  private static boolean isBackingBranch(String branch, ReleaseRequest request) {
    String stored = bare(request.backingBranch());
    if (stored != null && branch.equals(stored)) {
      return true;
    }
    return request.id != null && branch.equals(ReleaseRequest.BACKING_BRANCH_PREFIX + request.id);
  }

  /** The branch without a {@code refs/heads/} or {@code refs/tags/} prefix, or null when blank. */
  static String bare(String ref) {
    if (ref == null || ref.isBlank()) {
      return null;
    }
    String trimmed = ref.trim();
    if (trimmed.startsWith("refs/heads/")) {
      return trimmed.substring("refs/heads/".length());
    }
    if (trimmed.startsWith("refs/tags/")) {
      return trimmed.substring("refs/tags/".length());
    }
    return trimmed;
  }
}
