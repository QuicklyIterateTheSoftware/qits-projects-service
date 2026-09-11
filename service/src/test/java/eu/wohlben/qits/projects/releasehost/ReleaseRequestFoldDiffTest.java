package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestChangesDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What a release request changed, read against a real repository.</b>
 *
 * <p>The whole of this class is one claim — <b>the base</b>. A fold is diffed against the newest
 * release tag that does not contain it, resolved to a single commit with {@code merge-base}, and the
 * first three tests are the three ways the obvious alternative ({@code mergedSha^1}) is wrong. They
 * are not edge cases: the third of them is the ordinary component release.
 *
 * <p>Driven through real git rather than a stub, the idiom {@code MergeRangeCommitsTest} sets: a
 * fixture bare is cloned as a repository, the fold is made and pushed to the git host, and the
 * mirror is dropped so the read re-clones it — which is also how the commits under test reach the
 * mirror at all, since a mirror fetched seconds ago is trusted rather than refreshed.
 *
 * <p>The request row is written straight into the table at RELEASED with the fold's sha on it: what
 * is under test is the read, and a row that can still move is a row the release sweep would pick up
 * inside somebody else's test.
 */
@QuarkusTest
public class ReleaseRequestFoldDiffTest {

  @Inject ReleaseRequests releaseRequests;

  @Inject RepositoryService repositoryService;

  @Inject ProjectService projectService;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitHostAddress gitHost;

  @Inject GitExecutor git;

  private final List<String> requestIds = new ArrayList<>();

  /** Nothing this class writes may outlive it — the same discipline the rest of the package keeps. */
  @AfterEach
  void dropTheFixturesRequests() {
    for (String id : requestIds) {
      QuarkusTransaction.requiringNew().run(() -> ReleaseRequest.delete("id = ?1", id));
    }
    requestIds.clear();
  }

  /**
   * <b>Trap one: a genuine octopus.</b> Three sources folded in one merge, onto a target that had
   * moved on. {@code git diff-tree} against a merge commit with no base takes the first parent and
   * reports nothing at all, so this is the case that fails silently rather than loudly.
   */
  @Test
  public void anOctopusFoldWithThreeSourcesReportsAllThreeSourcesFiles() throws Exception {
    Repository repo = cloned("Octopus Diff Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;

    branchWithCommit(work, main, "lane-one", "one.txt", "Add one");
    branchWithCommit(work, main, "lane-two", "two.txt", "Add two");
    branchWithCommit(work, main, "lane-three", "three.txt", "Add three");
    // The target moves on under the request, so the fold is a real merge commit rather than a
    // fast-forward onto the first source.
    git.exec(work.toFile(), "git", "checkout", "-q", main);
    commit(work, "later.txt", "Move main on");
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "release/octopus", main);
    git.exec(
        work.toFile(),
        "git",
        "merge",
        "-q",
        "-m",
        "Release request: fold",
        "lane-one",
        "lane-two",
        "lane-three");
    String fold = push(work, repo, "release/octopus");

    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, request(repo, fold));

    assertEquals("2026.900.100000", changes.baseTag(), "the newest tag that does not contain it");
    assertEquals(
        List.of("one.txt", "three.txt", "two.txt"),
        paths(changes),
        "every source's file, not the first parent's alone");
    assertNull(changes.detail());
    assertFalse(changes.truncated());
  }

  /**
   * <b>Trap two: a re-fold.</b> The second fold's first parent is the <em>first</em> fold, so {@code
   * ^1} answers only what the last push added. A release is the whole of what it merges.
   */
  @Test
  public void aRefoldedRequestReportsTheWholeReleaseAndNotOnlyTheLastPush() throws Exception {
    Repository repo = cloned("Refold Diff Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;

    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    branchWithCommit(work, main, "wave-one", "one.txt", "Add one");
    branchWithCommit(work, main, "wave-two", "two.txt", "Add two");
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "release/refold", main);
    git.exec(work.toFile(), "git", "merge", "-q", "-m", "Release request: first fold", "wave-one");
    git.exec(work.toFile(), "git", "merge", "-q", "-m", "Release request: second fold", "wave-two");
    String fold = push(work, repo, "release/refold");

    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, request(repo, fold));

    assertEquals(
        List.of("one.txt", "two.txt"),
        paths(changes),
        "both waves; ^1 here is the first fold and would report two.txt alone");
    assertEquals("2026.900.100000", changes.baseTag());
  }

  /**
   * <b>Trap three, and the ordinary component release.</b> qits-githost's merge primitive drops a
   * head another head contains, so a branch that already contains {@code main} is a single effective
   * head and {@code release/<id>} fast-forwards onto it: there is no merge commit, and {@code ^1} is
   * just the branch's own previous commit. What the release adds is everything since the last one.
   */
  @Test
  public void aFastForwardedRequestReportsEverythingTheBranchAddsSinceThePreviousRelease()
      throws Exception {
    Repository repo = cloned("Fast Forward Diff Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;

    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    branchWithCommit(work, main, "work", "first.txt", "Add the first");
    commit(work, "second.txt", "Add the second");
    // The fold IS the branch head: with nothing to merge, the backing branch fast-forwards and no
    // merge commit is ever written.
    String fold = push(work, repo, "work");

    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, request(repo, fold));

    assertEquals(
        List.of("first.txt", "second.txt"),
        paths(changes),
        "the whole release; ^1 here is the branch's previous commit and reports second.txt alone");
    assertEquals("2026.900.100000", changes.baseTag());
    assertNotNull(changes.base(), "a released repository has a base commit to stand on");
  }

  /**
   * A repository that has never released has no earlier release to diff against, so the base is the
   * empty tree and its whole history reads as added. That is correct rather than an error — and the
   * reason the caps exist.
   */
  @Test
  public void aRepositoryWithNoTagsDiffsAgainstTheEmptyTree() throws Exception {
    Repository repo = cloned("Never Released Project");
    Path work = checkout(repo);

    branchWithCommit(work, repo.mainBranch, "work", "first.txt", "Add the first");
    String fold = push(work, repo, "work");

    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, request(repo, fold));

    assertNull(changes.base(), "no tag, so no base: --root against the empty tree");
    assertNull(changes.baseTag());
    assertTrue(paths(changes).contains("first.txt"));
    assertTrue(
        changes.files().stream().allMatch(file -> "ADDED".equals(file.changeType())),
        "every path of the history reads as added against the empty tree: " + changes.files());
  }

  /** A request whose first fold has not landed has nothing to diff, and says so. */
  @Test
  public void aRequestWithNoFoldYetAnswersEmptyWithASentence() throws Exception {
    Repository repo = cloned("Unfolded Diff Project");

    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, request(repo, null));

    assertNull(changes.mergedSha());
    assertEquals(List.of(), changes.files());
    assertEquals("Nothing has been folded yet", changes.detail());
  }

  /**
   * A fold the repository no longer holds — a withdrawn request's backing branch is deleted, and the
   * git host prunes what no ref reaches — is a fact about the repository, not a failure of the read,
   * and it carries its own sentence rather than the one above.
   */
  @Test
  public void aPrunedFoldAnswersEmptyWithItsOwnSentence() throws Exception {
    Repository repo = cloned("Pruned Diff Project");
    String gone = "0123456789abcdef0123456789abcdef01234567";

    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, request(repo, gone));

    assertEquals(gone, changes.mergedSha());
    assertEquals(List.of(), changes.files());
    assertEquals("The fold is no longer in the repository's history", changes.detail());
    assertEquals(
        "", releaseRequests.foldFileDiff(repo.id, requestIds.getLast(), "one.txt").diff(),
        "and the single-file read answers the empty patch rather than an error");
  }

  /**
   * <b>The regression a {@code merge-base(mergedSha, main)} base would introduce.</b> Once the
   * release reaches {@code main} that merge base is the fold itself and the diff goes empty — and a
   * released request's page is read months later. The tag that now contains the fold is this release
   * or a later one, so it is never subtracted.
   */
  @Test
  public void aReleaseThatHasReachedMainStillReportsItsOwnChanges() throws Exception {
    Repository repo = cloned("Landed Release Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;

    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    branchWithCommit(work, main, "work", "first.txt", "Add the first");
    String fold = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();
    // The release ships and lands: its own tag contains the fold, and main now contains it too.
    git.exec(work.toFile(), "git", "tag", "2026.900.200000", "work");
    git.exec(work.toFile(), "git", "checkout", "-q", main);
    git.exec(work.toFile(), "git", "merge", "-q", "--ff-only", "work");
    git.exec(work.toFile(), "git", "push", "-q", "--tags", "origin", main, "work");
    goCold(repo);

    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, request(repo, fold));

    assertEquals(
        List.of("first.txt"),
        paths(changes),
        "the release still answers for itself after it reached main");
    assertEquals(
        "2026.900.100000",
        changes.baseTag(),
        "the previous release, never this one's own tag or a later one");
  }

  /**
   * The wrapper's release is mostly gitlinks, and a gitlink is a {@code 160000} tree entry rather
   * than a file: it has to read as an ordinary modified path, and its patch is the pair of
   * subproject lines the viewer renders.
   */
  @Test
  public void aGitlinkChangeReportsAsAModifiedPathCarryingTheSubprojectPair() throws Exception {
    Repository repo = cloned("Wrapper Diff Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;
    String submodule = "components/qits-thing/qits-thing-service";
    String before = "1111111111111111111111111111111111111111";
    String after = "2222222222222222222222222222222222222222";

    gitlink(work, submodule, before);
    git.exec(work.toFile(), "git", "commit", "-q", "-m", "Add the submodule");
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", "HEAD");
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    gitlink(work, submodule, after);
    git.exec(work.toFile(), "git", "commit", "-q", "-m", "Bank the submodule");
    git.exec(work.toFile(), "git", "push", "-q", "--tags", "origin", main, "work");
    String fold = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();
    goCold(repo);

    String requestId = request(repo, fold);
    ReleaseRequestChangesDto changes = releaseRequests.foldChanges(repo.id, requestId);
    assertEquals(List.of(submodule), paths(changes));
    assertEquals("MODIFIED", changes.files().getFirst().changeType(), "a moved gitlink is a change");

    CommitFileDiffDto diff = releaseRequests.foldFileDiff(repo.id, requestId, submodule);
    assertTrue(diff.diff().contains("-Subproject commit " + before), diff.diff());
    assertTrue(diff.diff().contains("+Subproject commit " + after), diff.diff());
    assertTrue(diff.diff().contains("160000"), "the mode says what kind of entry it is");
  }

  /** The routes' own wiring: the same two answers, over HTTP, scoped by the repository. */
  @Test
  public void theRoutesAnswerTheChangesAndOnePatch() throws Exception {
    Repository repo = cloned("Fold Diff Route Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;

    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    branchWithCommit(work, main, "work", "first.txt", "Add the first");
    String fold = push(work, repo, "work");
    String requestId = request(repo, fold);
    String base = "/projects/api/repositories/" + repo.id + "/release-requests/" + requestId;

    given()
        .get(base + "/changes")
        .then()
        .statusCode(200)
        .body("mergedSha", equalTo(fold))
        .body("baseTag", equalTo("2026.900.100000"))
        .body("truncated", equalTo(false))
        .body("detail", nullValue())
        .body("files", hasSize(1))
        .body("files[0].path", equalTo("first.txt"))
        .body("files[0].changeType", equalTo("ADDED"));

    given()
        .queryParam("path", "first.txt")
        .get(base + "/changes/diff")
        .then()
        .statusCode(200)
        .body("path", equalTo("first.txt"))
        .body("changeType", equalTo("ADDED"));

    // A blank path is a 400 rather than a diff of the whole fold.
    given().queryParam("path", " ").get(base + "/changes/diff").then().statusCode(400);

    // And the scope is part of the address: another repository's route does not answer for it.
    given()
        .get("/projects/api/repositories/somebody-else/release-requests/" + requestId + "/changes")
        .then()
        .statusCode(404);
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private Repository cloned(String projectName) throws Exception {
    var project = projectService.create(projectName + " " + UUID.randomUUID(), null);
    return repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
  }

  /** A request row at RELEASED holding {@code mergedSha} — the read's whole input. */
  private String request(Repository repo, String mergedSha) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = new ReleaseRequest();
              row.id = id;
              row.repoId = repo.id;
              row.projectId = repo.project.id;
              row.summary = "a release worth diffing";
              row.state = ReleaseRequest.State.RELEASED;
              row.mergedSha = mergedSha;
              row.createdAt = Instant.now();
              row.armedAt = row.createdAt;
              row.updatedAt = row.createdAt;
              row.persist();
            });
    requestIds.add(id);
    return id;
  }

  /** A working clone of the repository's bare on the git host, with an identity to commit under. */
  private Path checkout(Repository repo) throws Exception {
    Path parent = Files.createTempDirectory("fold-diff");
    parent.toFile().deleteOnExit();
    git.exec(parent.toFile(), "git", "clone", "-q", gitHost.fetchUrl(repo.id), "work");
    Path work = parent.resolve("work");
    git.exec(work.toFile(), "git", "config", "user.email", "fixtures@qits.local");
    git.exec(work.toFile(), "git", "config", "user.name", "qits fixtures");
    return work;
  }

  private void branchWithCommit(Path work, String from, String branch, String file, String message)
      throws Exception {
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", branch, from);
    commit(work, file, message);
  }

  private void commit(Path work, String file, String message) throws Exception {
    Files.writeString(work.resolve(file), message + "\n", StandardCharsets.UTF_8);
    git.exec(work.toFile(), "git", "add", "-A");
    git.exec(work.toFile(), "git", "commit", "-q", "-m", message);
  }

  /**
   * Stages a {@code 160000} entry without a submodule checkout behind it — the tree entry is the
   * whole of what a gitlink is, and the commit it names need not be present to write one.
   */
  private void gitlink(Path work, String path, String sha) throws Exception {
    git.exec(
        work.toFile(),
        "git",
        "update-index",
        "--add",
        "--cacheinfo",
        "160000," + sha + "," + path);
  }

  /** Pushes the branch and its tags, drops the mirror, and answers the pushed head. */
  private String push(Path work, Repository repo, String branch) throws Exception {
    git.exec(work.toFile(), "git", "push", "-q", "--tags", "origin", branch);
    String head = git.exec(work.toFile(), "git", "rev-parse", branch).trim();
    goCold(repo);
    return head;
  }

  private List<String> paths(ReleaseRequestChangesDto changes) {
    return changes.files().stream().map(CommitFileChangeDto::path).sorted().toList();
  }

  /**
   * Drop the mirror, so the next read clones it afresh. A mirror fetched inside {@code
   * qits.projects.git.mirror-freshness-ms} is trusted rather than refreshed, and everything these
   * tests push happens well inside that window.
   */
  private void goCold(Repository repo) throws IOException {
    Path mirror = gitMirrors.of(repo.id).gitDir();
    if (!Files.exists(mirror)) {
      return;
    }
    try (var paths = Files.walk(mirror)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
