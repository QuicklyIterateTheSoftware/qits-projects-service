package eu.wohlben.qits.projects.control;

import java.util.List;

/**
 * <b>Tells the work item behind a release request that the request was withdrawn because its
 * branch was deleted</b> (qits-1671), and whether anything of the item's work was lost by it.
 *
 * <p>Before this port a withdrawal by deletion told nobody: {@link ReleaseRequests#onBranchDeleted}
 * moved the request to WITHDRAWN, cancelled its runs, closed its gate ticket and let the queue move
 * on, while the item that was waiting on the request (qits-1675 on {@code qits-qits-rr-226}) heard
 * nothing and waited on. The request's own thread said it; the item's did not, and the item's
 * thread is the one its agent and its owner read.
 *
 * <p><b>What was lost is a verdict, not a guess.</b> The question is whether the request's last fold
 * added anything to the default branch, and that is lineage: {@link ReleaseGitHost#contains}, asked
 * once by the caller after the withdrawal committed. {@link Lost#NOTHING} only when the git host
 * said the default branch contains the fold — the shape of {@code qits-qits-rr-226}, a ticket
 * branch standing at {@code main}'s head and deleted as merged; {@link Lost#UNRELEASED} when it
 * said it does not; {@link Lost#UNKNOWN} when there was no fold to ask about or the ask failed,
 * because "could not ask" must never be read as "nothing was lost".
 *
 * <p>The work item is named by a branch source, {@code ticket/<qualified id>} or {@code
 * epic/<qualified id>}, {@link ApprovalRequirementComments}' reading. A source naming none gets no
 * comment. The adapter lives in the service, where the entities module is ({@code
 * entitieshost/WorkItemWithdrawalComments}, {@code @DefaultBean}); it never throws, and a comment
 * that could not be written is a WARN. Injected as {@code Instance<T>} like every port here, so its
 * absence — the {@code domain} suite — is a no-op.
 */
public interface ReleaseRequestWithdrawalComments {

  /** What the withdrawn request's fold added to the default branch, as far as could be read. */
  enum Lost {
    /** The default branch contains the fold: nothing of the request's work is unreleased. */
    NOTHING,
    /** The default branch does not contain the fold: the request held unreleased commits. */
    UNRELEASED,
    /** No fold to ask about, or the git host could not answer. */
    UNKNOWN
  }

  /**
   * @param releaseRequestQualifiedId {@code <repo>-rr-<n>}, the name every comment uses
   * @param deletedBranch the branch whose deletion withdrew the request
   * @param branches every branch source the request had before the deletion, the deleted one
   *     included, in order
   * @param defaultBranch the repository's default branch, which was all that was left
   * @param lost the verdict on the request's last fold
   */
  record Withdrawn(
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String repoId,
      String repoName,
      String projectId,
      String deletedBranch,
      List<String> branches,
      String defaultBranch,
      Lost lost) {}

  void withdrawn(Withdrawn withdrawn);
}
