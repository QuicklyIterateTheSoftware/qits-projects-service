package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ReleaseGitHost;
import java.util.List;

/**
 * Whether a branch an agent reported pushing is still worth a release request when its work item
 * reaches VERIFIED (qits-1220) — the one decision {@link PhaseAdvance} makes per branch before it
 * asks, kept apart so it can be pinned without a transition, a project or a database.
 *
 * <p><b>Why it is asked at all.</b> The agent's list of pushed branches is a record of what it
 * pushed, not of what is still there: a branch that shipped in an earlier release was deleted by
 * that release, and the agent's list still names it. {@code ReleaseRequests.request} checks a
 * branch's syntax and nothing else, so asking for one that is gone lands a request whose fold the
 * git host answers {@code 404 no-such-source} (qits-projects-service-rr-261, for {@code
 * ticket/qits-1199}, which shipped on 2026-10-11 and was deleted). A branch whose head is already
 * released is the same mistake one step earlier: a request that would release nothing new.
 *
 * <p><b>"Could not ask" is never "no".</b> Every verdict except {@link Verdict#ASK} needs the git
 * host to have <em>said</em> so; an absent host, an unreachable one, a refusal and an answer that
 * is not the one asked about all keep the ask — dropping a release because of a flaky moment is the
 * one outcome nobody would notice, and an ask for a branch that turns out to be gone is merely the
 * old behaviour. The same rule {@link ReleaseGitHost#contains} states for containment.
 */
final class ReleasableBranch {

  /**
   * The git host's error word for a branch read that found no such branch ({@code GET
   * …/repositories/{repoId}/branches/{name}} answering 404). It is matched on <b>by name</b>
   * because the port's {@code retryable=false} alone says too little: every 4xx is not retryable,
   * so a {@code 403} (this service off the route's roles), a {@code 400} and a {@code 404
   * no-such-repository} would all read as "gone" — and a branch is gone only when the repository
   * answered and the branch was not in it.
   */
  static final String NO_SUCH_BRANCH = "no-such-branch";

  /** What to do with one pushed branch. */
  enum Verdict {
    /** Ask for its release — the branch is there and unreleased, or the host could not say. */
    ASK,
    /** The git host answered that the branch does not exist: it was released and deleted. */
    GONE,
    /**
     * Its head is contained in the repository's default branch, or in a release not yet merged
     * there: everything on it has shipped already.
     */
    RELEASED
  }

  private ReleasableBranch() {}

  /**
   * The verdict for {@code branch} of {@code repoId}. Never throws: a throw from the port is a port
   * bug and reads as "could not ask".
   *
   * @param host the git host, or null when none is configured — which is {@link Verdict#ASK}
   * @param defaultBranch the repository's default branch, read for its head as one place a released
   *     head is contained in
   * @param releasedShas the commits of the repository's releases still pending a merge into the
   *     default branch ({@code released_tag_pending_merge.released_sha} with {@code merged_at}
   *     null): between a release and its post-deployment merge the released commit sits on no
   *     branch, so the default branch alone would call a just-shipped head unreleased
   */
  static Verdict of(
      ReleaseGitHost host,
      String repoId,
      String branch,
      String defaultBranch,
      List<String> releasedShas) {
    if (host == null) {
      return Verdict.ASK;
    }
    try {
      ReleaseGitHost.Answer<String> head = host.head(repoId, branch);
      if (!head.ok()) {
        return gone(head) ? Verdict.GONE : Verdict.ASK;
      }
      ReleaseGitHost.Answer<String> main = host.head(repoId, defaultBranch);
      if (main.ok() && containedIn(host, repoId, head.value(), main.value())) {
        return Verdict.RELEASED;
      }
      for (String released : releasedShas) {
        if (containedIn(host, repoId, head.value(), released)) {
          return Verdict.RELEASED;
        }
      }
      return Verdict.ASK;
    } catch (RuntimeException e) {
      return Verdict.ASK;
    }
  }

  /**
   * Whether a failed branch read is the git host saying the branch is not there: final (not
   * retryable) <b>and</b> naming {@link #NO_SUCH_BRANCH}. See that constant for why both.
   */
  static boolean gone(ReleaseGitHost.Answer<String> head) {
    return !head.ok()
        && !head.retryable()
        && head.detail() != null
        && head.detail().contains(NO_SUCH_BRANCH);
  }

  /** True only when the host answered yes; a failed read is "could not ask", never "no". */
  private static boolean containedIn(
      ReleaseGitHost host, String repoId, String commit, String in) {
    if (in == null || in.isBlank()) {
      return false;
    }
    ReleaseGitHost.Answer<Boolean> answer = host.contains(repoId, commit, in);
    return answer.ok() && Boolean.TRUE.equals(answer.value());
  }
}
