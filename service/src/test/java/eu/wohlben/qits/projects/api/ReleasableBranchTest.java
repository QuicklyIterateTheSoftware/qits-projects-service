package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.api.ReleasableBranch.Verdict;
import eu.wohlben.qits.projects.control.ReleaseGitHost.Answer;
import eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The per-branch decision a move into VERIFIED makes before it asks for a release (qits-1220),
 * pinned without a transition: <b>gone and released need the git host to have said so</b>, and
 * every other answer asks.
 */
class ReleasableBranchTest {

  private static final String REPO = "repo-1";
  private static final String BRANCH = "ticket/qits-1199";

  private final RecordingReleaseGitHost host = new RecordingReleaseGitHost();

  private Verdict verdict(List<String> releasedShas) {
    return ReleasableBranch.of(host, REPO, BRANCH, "main", releasedShas);
  }

  @Test
  void anUnreleasedBranchThatIsThereIsAskedFor() {
    host.headOf(REPO, BRANCH, "b1");
    host.headOf(REPO, "main", "m1");

    assertEquals(Verdict.ASK, verdict(List.of("r1")));
  }

  /** The git host's own 404 body for a branch read, as the HTTP adapter carries it. */
  @Test
  void aBranchTheHostAnswersNoSuchBranchForIsGone() {
    host.headUnreadable(
        REPO,
        BRANCH,
        Answer.failed(
            "qits-githost answered 404: {\"error\":\"no-such-branch\",\"detail\":\"refs/heads/"
                + BRANCH
                + "\"}"));

    assertEquals(Verdict.GONE, verdict(List.of()));
  }

  /**
   * A final refusal that is not about the branch — the repository unknown, or this service off the
   * route's roles — is not "gone": the host never said the branch was missing.
   */
  @Test
  void aFinalRefusalAboutAnythingElseStillAsks() {
    host.headUnreadable(
        REPO,
        BRANCH,
        Answer.failed("qits-githost answered 404: {\"error\":\"no-such-repository\"}"));
    assertEquals(Verdict.ASK, verdict(List.of()));

    host.headUnreadable(REPO, BRANCH, Answer.failed("qits-githost answered 403: "));
    assertEquals(Verdict.ASK, verdict(List.of()));
  }

  /** The regression guard: a host that could not answer is "cannot tell", never "gone". */
  @Test
  void aRetryableFailureStillAsks() {
    host.headUnreadable(
        REPO, BRANCH, Answer.failedRetryable("no-such-branch, says a proxy that timed out"));

    assertEquals(Verdict.ASK, verdict(List.of()));
  }

  @Test
  void noGitHostAtAllAsks() {
    assertEquals(Verdict.ASK, ReleasableBranch.of(null, REPO, BRANCH, "main", List.of()));
  }

  @Test
  void aHeadTheDefaultBranchContainsIsReleased() {
    host.headOf(REPO, BRANCH, "b1");
    host.headOf(REPO, "main", "m1");
    host.containsCommit(REPO, "b1", "m1");

    assertEquals(Verdict.RELEASED, verdict(List.of()));
  }

  /** Between a release and its merge into main the released commit sits on no branch. */
  @Test
  void aHeadAPendingReleaseContainsIsReleased() {
    host.headOf(REPO, BRANCH, "b1");
    host.headOf(REPO, "main", "m1");
    host.containsCommit(REPO, "b1", "r2");

    assertEquals(Verdict.RELEASED, verdict(List.of("r1", "r2")));
  }

  /** Containment that could not be read is "could not ask", never "yes". */
  @Test
  void anUnreadableContainmentStillAsks() {
    host.headOf(REPO, BRANCH, "b1");
    host.headOf(REPO, "main", "m1");
    host.containmentOfUnreadable(REPO, "b1", Answer.failedRetryable("timeout"));

    assertEquals(Verdict.ASK, verdict(List.of("r1")));
  }

  /** A default branch that cannot be read leaves the pending releases to decide. */
  @Test
  void anUnreadableDefaultBranchStillChecksThePendingReleases() {
    host.headOf(REPO, BRANCH, "b1");
    host.headUnreadable(REPO, "main", Answer.failedRetryable("timeout"));

    assertEquals(Verdict.ASK, verdict(List.of()));
    host.containsCommit(REPO, "b1", "r1");
    assertEquals(Verdict.RELEASED, verdict(List.of("r1")));
  }
}
