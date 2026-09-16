package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The seven conditions a gitlink conflict has to meet before this service decides it, one test each
 * — and <b>every one of them asserts that there is no answer</b> rather than that the answer is
 * something else. That is the shape the feature lives or dies by: a resolver that guesses wrong
 * writes a wrapper estate nobody reviewed, silently and with a green fold on top of it, while a
 * resolver that declines costs a person the conflict they were going to get anyway.
 *
 * <p>A plain JUnit test, no container: the two seams that need one — the database lookup and the git
 * host — are package-private on the class for exactly this, so the decision logic is exercised
 * against hand-built facts rather than against a seeded schema. What is <em>not</em> covered here is
 * the wiring into a fold; that is {@code ReleaseRequestConflictResolutionTest} in the service suite,
 * which is where "exactly two folds were asked for" can be observed.
 */
public class ConflictResolverTest {

  private static final String PROJECT = "project-1";
  private static final String WRAPPER = "wrapper-repo";
  private static final String MEMBER = "member-a";
  private static final String PATH = "components/member-a/member-a";
  private static final String HEAD = "refs/heads/epic/x";
  private static final String HEAD_SHA = "head000000000000000000000000000000000000";
  private static final String OLDER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String NEWER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  private static final String MEMBER_REPO = "member-repo-id";

  private static final String GITMODULES =
      "[submodule \"member-a\"]\n\tpath = "
          + PATH
          + "\n\turl = ../member-a.git\n";

  // -------------------------------------------------------------------------------------------
  // The happy path
  // -------------------------------------------------------------------------------------------

  @Test
  public void theLaterReleaseWinsWhenItContainsTheEarlierOne() {
    Fixture fixture = fixture();
    ConflictResolver.Attempt attempt = fixture.attempt(gitlink(PATH, OLDER, NEWER));

    assertTrue(attempt.complete(), attempt.detail());
    assertEquals(1, attempt.directives().size());
    assertEquals(PATH, attempt.directives().get(0).path());
    assertEquals(NEWER, attempt.directives().get(0).gitlink());
  }

  @Test
  public void theDecisionIsWrittenIntoTheMergeMessageAsATrailer() {
    ConflictResolver.Attempt attempt = fixture().attempt(gitlink(PATH, OLDER, NEWER));

    // One line per path, in a shape `git log` renders and `git interpret-trailers` can find, so the
    // decision lives in the backing branch's history and not only in a database column.
    assertEquals(
        "Resolved-Gitlink: " + PATH + " " + OLDER + " -> " + NEWER + " (member-a 2026.902.2)\n",
        attempt.trailer());
  }

  @Test
  public void oursBeingTheLaterReleaseIsDecidedTheSameWay() {
    Fixture fixture = fixture();
    // The sides are not ordered: `ours` is simply the target's side and may perfectly well be the
    // newer release. Nothing here may prefer a side.
    ConflictResolver.Attempt attempt = fixture.attempt(gitlink(PATH, NEWER, OLDER));

    assertTrue(attempt.complete(), attempt.detail());
    assertEquals(NEWER, attempt.directives().get(0).gitlink());
  }

  @Test
  public void oneReportOfOnePathUnderTwoHeadsYieldsOneDirective() {
    Fixture fixture = fixture();
    // The far side answers 400 to a directive list that repeats a path, and a report repeats a path
    // once per head that introduced it — so the keying by path is load-bearing rather than tidy.
    ConflictResolver.Attempt attempt =
        fixture.attempt(gitlink(PATH, OLDER, NEWER), gitlink(PATH, OLDER, NEWER));

    assertTrue(attempt.complete(), attempt.detail());
    assertEquals(1, attempt.directives().size());
  }

  // -------------------------------------------------------------------------------------------
  // The seven conditions, each asserting no answer
  // -------------------------------------------------------------------------------------------

  @Test
  public void aContentConflictIsNotDecided() {
    ConflictResolver.Attempt attempt =
        fixture()
            .attempt(
                new BackingBranchMerger.Conflict("pom.xml", HEAD, HEAD_SHA, "content"));

    assertNoAnswer(attempt, "pom.xml", "not over a submodule pin");
  }

  @Test
  public void aSideThatDeletesTheSubmoduleIsAPersonsCall() {
    ConflictResolver.Attempt attempt = fixture().attempt(gitlink(PATH, OLDER, null));

    assertNoAnswer(attempt, PATH, "one side does not have the submodule");
  }

  @Test
  public void aPathNoGitmodulesDeclaresIsNotDecided() {
    ConflictResolver.Attempt attempt =
        fixture().attempt(gitlink("components/stranger/stranger", OLDER, NEWER));

    assertNoAnswer(attempt, "components/stranger/stranger", "no submodule is declared there");
  }

  @Test
  public void aDeclarationThatCouldNotBeReadIsNotDecided() {
    Fixture fixture = fixture();
    fixture.host.files.clear();

    assertNoAnswer(
        fixture.attempt(gitlink(PATH, OLDER, NEWER)),
        PATH,
        "the submodule declarations at " + HEAD + " could not be read");
  }

  @Test
  public void aSectionNamingNoRepositoryOfThisProjectIsNotDecided() {
    Fixture fixture = fixture();
    fixture.adopted = false;

    assertNoAnswer(
        fixture.attempt(gitlink(PATH, OLDER, NEWER)),
        PATH,
        "'member-a' names no repository of this project");
  }

  @Test
  public void aShaThatIsNoRecordedReleaseIsNotDecided() {
    Fixture fixture = fixture();
    fixture.releases.remove(NEWER);

    assertNoAnswer(
        fixture.attempt(gitlink(PATH, OLDER, NEWER)), PATH, "is no recorded release of 'member-a'");
  }

  @Test
  public void twoReleasesCutAtTheSameInstantAreNotDecided() {
    Fixture fixture = fixture();
    fixture.releases.get(NEWER).releasedAt = fixture.releases.get(OLDER).releasedAt;

    assertNoAnswer(
        fixture.attempt(gitlink(PATH, OLDER, NEWER)), PATH, "cut at the same instant");
  }

  @Test
  public void aLaterReleaseThatDoesNotContainTheEarlierOneIsNotDecided() {
    Fixture fixture = fixture();
    // The whole reason the containment read exists: released_tag_pending_merge orders by when a
    // release happened, so a release cut from a side branch is later in time and contains none of
    // what the older one shipped. Pinning it would drop released work out of the estate silently.
    fixture.host.contained.clear();

    assertNoAnswer(
        fixture.attempt(gitlink(PATH, OLDER, NEWER)),
        PATH,
        "does not contain 2026.901.1, so pinning it would drop released work");
  }

  @Test
  public void aContainmentReadThatFailedIsNotAnAnswerEither() {
    Fixture fixture = fixture();
    fixture.host.contained.clear();
    fixture.host.containmentFailure =
        ReleaseGitHost.Answer.failedRetryable("qits-githost could not be reached");

    assertNoAnswer(
        fixture.attempt(gitlink(PATH, OLDER, NEWER)),
        PATH,
        "could not be read: qits-githost could not be reached");
  }

  @Test
  public void noGitHostAtAllDecidesNothing() {
    Fixture fixture = fixture();
    fixture.hostConfigured = false;

    ConflictResolver.Attempt attempt = fixture.attempt(gitlink(PATH, OLDER, NEWER));
    assertFalse(attempt.complete());
    assertTrue(attempt.detail().contains("no git host is configured"), attempt.detail());
  }

  // -------------------------------------------------------------------------------------------
  // All of them or none of them
  // -------------------------------------------------------------------------------------------

  @Test
  public void oneUndecidablePathWithdrawsEveryOtherDirective() {
    ConflictResolver.Attempt attempt =
        fixture()
            .attempt(
                gitlink(PATH, OLDER, NEWER),
                new BackingBranchMerger.Conflict("pom.xml", HEAD, HEAD_SHA, "content"));

    // A fold half-decided by a machine and half by a person is worse than one decided entirely by a
    // person, so the decidable path's directive goes with the undecidable one.
    assertFalse(attempt.complete());
    assertTrue(attempt.directives().isEmpty());
    assertTrue(attempt.decisions().isEmpty());
  }

  @Test
  public void aConflictWithNoPathsOnItIsNotAnAnswer() {
    ConflictResolver.Attempt attempt = fixture().attempt();

    assertFalse(attempt.complete());
    assertTrue(attempt.detail().contains("named no conflicting path"), attempt.detail());
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private static void assertNoAnswer(
      ConflictResolver.Attempt attempt, String path, String reason) {
    assertFalse(attempt.complete(), "expected no answer, got " + attempt.directives());
    assertTrue(attempt.directives().isEmpty());
    assertTrue(attempt.detail().contains(path), attempt.detail());
    assertTrue(attempt.detail().contains(reason), attempt.detail());
  }

  private static BackingBranchMerger.Conflict gitlink(String path, String ours, String theirs) {
    return new BackingBranchMerger.Conflict(
        path,
        HEAD,
        HEAD_SHA,
        "content",
        BackingBranchMerger.KIND_GITLINK,
        null,
        ours,
        theirs);
  }

  private static Fixture fixture() {
    Fixture fixture = new Fixture();
    fixture.host.files.put(HEAD_SHA + "|.gitmodules", GITMODULES);
    fixture.releases.put(OLDER, release(OLDER, "2026.901.1", Instant.parse("2026-09-01T10:00:00Z")));
    fixture.releases.put(NEWER, release(NEWER, "2026.902.2", Instant.parse("2026-09-02T10:00:00Z")));
    fixture.host.contained.add(MEMBER_REPO + "@" + OLDER + "->" + NEWER);
    return fixture;
  }

  private static ReleasedTagPendingMerge release(String sha, String tag, Instant at) {
    ReleasedTagPendingMerge row = new ReleasedTagPendingMerge();
    row.repoId = MEMBER_REPO;
    row.releasedSha = sha;
    row.tagName = tag;
    row.releasedAt = at;
    return row;
  }

  /** The resolver with its two seams replaced by hand-built facts. */
  private static final class Fixture extends ConflictResolver {

    private final StubGitHost host = new StubGitHost();
    private final Map<String, ReleasedTagPendingMerge> releases = new LinkedHashMap<>();
    private boolean adopted = true;
    private boolean hostConfigured = true;

    ConflictResolver.Attempt attempt(BackingBranchMerger.Conflict... conflicts) {
      return attempt(
          "request-1",
          WRAPPER,
          PROJECT,
          BackingBranchMerger.Outcome.conflict("refs/heads/release/1", List.of(conflicts)));
    }

    @Override
    ReleaseGitHost gitHost() {
      return hostConfigured ? host : null;
    }

    @Override
    SiblingReleases releasesOf(String projectId, String name, String ours, String theirs) {
      assertEquals(PROJECT, projectId);
      if (!adopted || !MEMBER.equals(name)) {
        return null;
      }
      return new SiblingReleases(MEMBER_REPO, releases.get(ours), releases.get(theirs));
    }
  }

  /** A git host that holds a few files and a few ancestries, and nothing else. */
  private static final class StubGitHost implements ReleaseGitHost {

    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<String> contained = new ArrayList<>();
    private Answer<Boolean> containmentFailure;

    @Override
    public Answer<List<String>> tree(String repoId, String rev) {
      return Answer.of(List.copyOf(files.keySet()));
    }

    @Override
    public Answer<String> file(String repoId, String rev, String path) {
      String content = files.get(rev + "|" + path);
      return content == null ? Answer.failed("no-such-path: " + path) : Answer.of(content);
    }

    @Override
    public Answer<String> commit(
        String repoId,
        String ref,
        String message,
        Map<String, String> files,
        Map<String, String> gitlinks) {
      throw new UnsupportedOperationException("no release is being made here");
    }

    @Override
    public Answer<String> head(String repoId, String branch) {
      return Answer.failed("no-such-branch: " + branch);
    }

    @Override
    public Answer<String> gitlinkAt(String projectId, String repoName, String rev, String path) {
      return Answer.of(null);
    }

    @Override
    public Answer<Boolean> resolves(String projectId, String repoName, String sha) {
      return Answer.of(false);
    }

    @Override
    public Answer<Boolean> contains(String repoId, String commit, String in) {
      if (contained.contains(repoId + "@" + commit + "->" + in)) {
        return Answer.of(true);
      }
      return containmentFailure != null ? containmentFailure : Answer.of(false);
    }

    @Override
    public TagAnswer tag(String repoId, String name, String sha, String message) {
      throw new UnsupportedOperationException("no release is being made here");
    }

    @Override
    public void deleteBranch(String repoId, String name) {
      throw new UnsupportedOperationException("no release is being made here");
    }
  }
}
