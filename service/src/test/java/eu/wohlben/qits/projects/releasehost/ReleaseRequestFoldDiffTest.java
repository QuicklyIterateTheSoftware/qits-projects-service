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
import eu.wohlben.qits.projects.dto.CommitDto;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestChangesDto;
import eu.wohlben.qits.projects.dto.SubmoduleChangesDto;
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

  /**
   * <b>The gitlink expanded: a wrapper's release read as what it actually ships.</b>
   *
   * <p>The fold moves one {@code 160000} entry from one commit of a sibling repository to another.
   * What the wrapper's own diff can say about that is two forty-character strings; what a person
   * reviewing the release needs is the sibling's commits and the files they changed, and this is the
   * read that answers it. The sibling is a real repository of the same project, its two pins are
   * real commits of it, and the manifest at the fold is what joins the two.
   */
  @Test
  public void aMovedGitlinkExpandsIntoTheSiblingsOwnCommitsAndFiles() throws Exception {
    Repository wrapper = cloned("Submodule Expansion Project");
    Repository sibling = sibling(wrapper, SIBLING);
    String[] pins = twoPins(sibling);
    String submodule = "components/qits-sibling/" + SIBLING;
    String fold = wrapperMovingThePin(wrapper, submodule, pins[0], pins[1]);
    String requestId = request(wrapper, fold);

    SubmoduleChangesDto changes =
        releaseRequests.foldSubmoduleChanges(wrapper.id, requestId, submodule);

    assertNull(changes.detail(), "a descendant pin over a resolvable sibling needs no sentence");
    assertEquals(sibling.id, changes.repositoryId(), "the url tail resolved to the sibling's row");
    assertEquals(SIBLING, changes.name());
    assertEquals(pins[0], changes.oldSha());
    assertEquals(pins[1], changes.newSha());
    assertEquals(
        List.of("Add beta"),
        changes.commits().stream().map(CommitDto::message).toList(),
        "the sibling's own commits between the pins, not the wrapper's");
    assertEquals(
        List.of("beta.txt"),
        changes.files().stream().map(CommitFileChangeDto::path).toList(),
        "and the files those commits changed, inside the sibling");
    assertFalse(changes.truncated());

    // A hoisted file's diff is the sibling's real patch, taken in the sibling against the old pin.
    CommitFileDiffDto diff =
        releaseRequests.foldSubmoduleFileDiff(wrapper.id, requestId, submodule, "beta.txt");
    assertEquals("ADDED", diff.changeType());
    assertTrue(diff.diff().contains("+Add beta"), diff.diff());
  }

  /**
   * The same fold's <b>change list</b> labels the gitlink without expanding it: the two pins and the
   * repository the manifest resolves to, from one read of {@code .gitmodules} at the fold and one
   * row lookup. Nothing here may touch the sibling's mirror — a twenty-seven submodule estate fold
   * draws this list, and a clone per row would make the page unaffordable.
   */
  @Test
  public void theChangeListLabelsAGitlinkWithItsPinsAndTheRepositoryItResolvesTo()
      throws Exception {
    Repository wrapper = cloned("Submodule Label Project");
    Repository sibling = sibling(wrapper, SIBLING);
    String[] pins = twoPins(sibling);
    String submodule = "components/qits-sibling/" + SIBLING;
    String requestId = request(wrapper, wrapperMovingThePin(wrapper, submodule, pins[0], pins[1]));

    CommitFileChangeDto entry =
        releaseRequests.foldChanges(wrapper.id, requestId).files().stream()
            .filter(file -> submodule.equals(file.path()))
            .findFirst()
            .orElseThrow();

    assertEquals("MODIFIED", entry.changeType());
    assertEquals("160000", entry.oldMode(), "--raw is what says this is not a file");
    assertEquals("160000", entry.newMode());
    assertNotNull(entry.submodule());
    assertEquals(sibling.id, entry.submodule().repositoryId());
    assertEquals(SIBLING, entry.submodule().name());
    assertEquals(pins[0], entry.submodule().oldSha());
    assertEquals(pins[1], entry.submodule().newSha());
    assertNull(entry.submodule().detail(), "it resolved, so there is nothing to explain");

    // An ordinary file of the same fold carries no ref: the labelling pass touches gitlinks and
    // leaves every other entry exactly as git reported it.
    CommitFileChangeDto ordinary =
        releaseRequests.foldChanges(wrapper.id, requestId).files().stream()
            .filter(file -> "notes.txt".equals(file.path()))
            .findFirst()
            .orElseThrow();
    assertNull(ordinary.submodule());
    assertEquals("100644", ordinary.newMode());
  }

  /**
   * <b>An added and a removed gitlink are not expandable, and each says so.</b> One pin is not a
   * range: "every commit up to here" is not what a release that mounts a submodule did, and a list
   * claiming it would be a worse answer than the sentence.
   */
  @Test
  public void anAddedAndARemovedGitlinkEachAnswerTheirOwnSentence() throws Exception {
    String submodule = "components/qits-sibling/" + SIBLING;
    String pin = "3333333333333333333333333333333333333333";

    // Two wrappers, because the two cases need opposite bases and one history cannot provide both:
    // a removal has to be diffed against a release that HELD the gitlink, and tagging the addition
    // to arrange that would make that tag contain the removal, which drops it from the base.
    Repository mounting = cloned("Submodule Mount Project");
    Path mountWork = checkout(mounting);
    manifest(mountWork, submodule);
    git.exec(mountWork.toFile(), "git", "commit", "-q", "-m", "Declare the submodule");
    git.exec(mountWork.toFile(), "git", "tag", "2026.900.100000", "HEAD");
    git.exec(mountWork.toFile(), "git", "checkout", "-q", "-b", "work");
    gitlink(mountWork, submodule, pin);
    git.exec(mountWork.toFile(), "git", "commit", "-q", "-m", "Mount the submodule");
    String addedFold = push(mountWork, mounting, "work");

    SubmoduleChangesDto added =
        releaseRequests.foldSubmoduleChanges(mounting.id, request(mounting, addedFold), submodule);
    assertEquals("Added by this release", added.detail());
    assertEquals(pin, added.newSha());
    assertNull(added.oldSha(), "there is no base side to have a pin");
    assertEquals(List.of(), added.commits(), "one pin is not a range");
    assertEquals(List.of(), added.files(), "and so there is nothing to list");

    Repository unmounting = cloned("Submodule Unmount Project");
    Path unmountWork = checkout(unmounting);
    manifest(unmountWork, submodule);
    gitlink(unmountWork, submodule, pin);
    git.exec(unmountWork.toFile(), "git", "commit", "-q", "-m", "Declare and mount the submodule");
    git.exec(unmountWork.toFile(), "git", "tag", "2026.900.100000", "HEAD");
    git.exec(unmountWork.toFile(), "git", "checkout", "-q", "-b", "work");
    git.exec(unmountWork.toFile(), "git", "update-index", "--force-remove", submodule);
    git.exec(unmountWork.toFile(), "git", "commit", "-q", "-m", "Unmount the submodule");
    String removedFold = push(unmountWork, unmounting, "work");

    SubmoduleChangesDto removed =
        releaseRequests.foldSubmoduleChanges(
            unmounting.id, request(unmounting, removedFold), submodule);
    assertEquals("Removed by this release", removed.detail());
    assertEquals(pin, removed.oldSha());
    assertNull(removed.newSha());
    assertEquals(List.of(), removed.files());
  }

  /**
   * <b>The three ways the chain stops after the path resolved, each a sentence and none an
   * exception.</b> A wrapper fold is twenty-seven of these reads and one unreachable or unknown
   * sibling must not be able to fail the other twenty-six — which is why every one of them is a 200
   * carrying a {@code detail} rather than a status code.
   */
  @Test
  public void anUnresolvableNameAndAMissingPinEachAnswerTheirOwnSentence() throws Exception {
    Repository wrapper = cloned("Submodule Fallback Project");
    String submodule = "components/qits-sibling/" + SIBLING;
    String absent = "1111111111111111111111111111111111111111";
    String alsoAbsent = "2222222222222222222222222222222222222222";

    // No sibling row at all: the manifest names a repository this project has not got.
    String fold = wrapperMovingThePin(wrapper, submodule, absent, alsoAbsent);
    SubmoduleChangesDto unknown =
        releaseRequests.foldSubmoduleChanges(wrapper.id, request(wrapper, fold), submodule);
    assertEquals("This submodule is not a repository of this project", unknown.detail());
    assertNull(unknown.repositoryId());
    assertEquals(SIBLING, unknown.name(), "the name is known even when nothing answers to it");
    assertEquals(absent, unknown.oldSha(), "and the pins are the fold's own, always");

    // The row exists and the pins do not: a real repository asked about commits it never had.
    Repository other = cloned("Submodule Missing Pin Project");
    sibling(other, SIBLING);
    String otherFold = wrapperMovingThePin(other, submodule, absent, alsoAbsent);
    SubmoduleChangesDto missing =
        releaseRequests.foldSubmoduleChanges(other.id, request(other, otherFold), submodule);
    assertEquals(shortOf(absent) + " is not in " + SIBLING + "'s history", missing.detail());
    assertNotNull(missing.repositoryId(), "it did resolve; what it has not got is the commit");
  }

  /**
   * <b>A path that is not a gitlink of this fold is refused, and the refusal is the whole
   * authorisation.</b> The caller sends a path and never a repository id, so the set of repositories
   * this route can reach is exactly the set of submodules the request under review moves. A path the
   * fold does not touch, and a path it touches as an ordinary file, both stop with a sentence and
   * neither answers somebody else's repository.
   */
  @Test
  public void aPathThatIsNotAGitlinkOfThisFoldIsRefused() throws Exception {
    Repository wrapper = cloned("Submodule Scope Project");
    Path work = checkout(wrapper);
    String main = wrapper.mainBranch;
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", main);
    branchWithCommit(work, main, "work", "ordinary.txt", "Add an ordinary file");
    String requestId = request(wrapper, push(work, wrapper, "work"));

    SubmoduleChangesDto ordinary =
        releaseRequests.foldSubmoduleChanges(wrapper.id, requestId, "ordinary.txt");
    assertEquals("This path is not a submodule in this release", ordinary.detail());
    assertNull(ordinary.repositoryId());

    SubmoduleChangesDto untouched =
        releaseRequests.foldSubmoduleChanges(
            wrapper.id, requestId, "components/qits-sibling/" + SIBLING);
    assertEquals("This release does not change this path", untouched.detail());
    assertNull(untouched.repositoryId());

    // A chain that stopped answers the empty patch on the diff read, never somebody's file.
    assertEquals(
        "",
        releaseRequests
            .foldSubmoduleFileDiff(wrapper.id, requestId, "ordinary.txt", "anything.txt")
            .diff());
  }

  /**
   * The two new routes' own wiring, over HTTP and scoped by the repository like their neighbours.
   */
  @Test
  public void theRoutesAnswerTheSubmoduleExpansionAndOnePatchInsideIt() throws Exception {
    Repository wrapper = cloned("Submodule Route Project");
    Repository sibling = sibling(wrapper, SIBLING);
    String[] pins = twoPins(sibling);
    String submodule = "components/qits-sibling/" + SIBLING;
    String requestId = request(wrapper, wrapperMovingThePin(wrapper, submodule, pins[0], pins[1]));
    String base = "/projects/api/repositories/" + wrapper.id + "/release-requests/" + requestId;

    given()
        .queryParam("path", submodule)
        .get(base + "/changes/submodule")
        .then()
        .statusCode(200)
        .body("repositoryId", equalTo(sibling.id))
        .body("name", equalTo(SIBLING))
        .body("oldSha", equalTo(pins[0]))
        .body("newSha", equalTo(pins[1]))
        .body("detail", nullValue())
        .body("commits", hasSize(1))
        .body("files", hasSize(1))
        .body("files[0].path", equalTo("beta.txt"));

    given()
        .queryParam("path", submodule)
        .queryParam("file", "beta.txt")
        .get(base + "/changes/submodule/diff")
        .then()
        .statusCode(200)
        .body("path", equalTo("beta.txt"))
        .body("changeType", equalTo("ADDED"));

    // Both parameters are @NotBlank, matching the neighbouring patch route's spelling.
    given().queryParam("path", " ").get(base + "/changes/submodule").then().statusCode(400);
    given()
        .queryParam("path", submodule)
        .queryParam("file", " ")
        .get(base + "/changes/submodule/diff")
        .then()
        .statusCode(400);
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

  /**
   * The name every submodule fixture here mounts. It is a role-suffixed component name because that
   * is what the estate's wrappers declare, and because it is the name the entry's url tail has to
   * resolve to for {@code findByProjectAndName} to answer.
   */
  private static final String SIBLING = "qits-sibling-service";

  private Repository cloned(String projectName) throws Exception {
    var project = projectService.create(projectName + " " + UUID.randomUUID(), null);
    return repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
  }

  /**
   * A second repository of {@code wrapper}'s project, registered under {@code name} — the row the
   * wrapper's {@code url = ../<name>.git} has to resolve to. The name is passed explicitly rather
   * than derived from the fixture's url basename, which every repository here would otherwise share.
   */
  private Repository sibling(Repository wrapper, String name) throws Exception {
    return repositoryService.cloneRepository(
        GitFixtures.path("testing-repo.git"), null, wrapper.project, name);
  }

  /**
   * Two commits of {@code sibling}, pushed, with the mirror dropped: {@code [older, newer]}, one
   * file added by each, so the expansion has a commit to list and a file to hoist.
   */
  private String[] twoPins(Repository sibling) throws Exception {
    Path work = checkout(sibling);
    commit(work, "alpha.txt", "Add alpha");
    String older = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();
    commit(work, "beta.txt", "Add beta");
    String newer = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();
    git.exec(work.toFile(), "git", "push", "-q", "origin", "HEAD:" + sibling.mainBranch);
    goCold(sibling);
    return new String[] {older, newer};
  }

  /**
   * Stages a {@code .gitmodules} declaring {@code path} as a submodule, in the house grammar — the
   * relative url whose tail is the sibling's addressable name, which is what the label resolves by.
   */
  private void manifest(Path work, String path) throws Exception {
    String name = path.substring(path.lastIndexOf('/') + 1);
    Files.writeString(
        work.resolve(".gitmodules"),
        "[submodule \""
            + name
            + "\"]\n\tpath = "
            + path
            + "\n\turl = ../"
            + name
            + ".git\n\tbranch = main\n\tignore = all\n\tupdate = merge\n",
        StandardCharsets.UTF_8);
    git.exec(work.toFile(), "git", "add", ".gitmodules");
  }

  /**
   * A wrapper whose released base pins {@code before} and whose fold pins {@code after}, manifest
   * and all — the ordinary shape of an estate release. Answers the fold's sha, with the mirror
   * dropped.
   */
  private String wrapperMovingThePin(Repository wrapper, String path, String before, String after)
      throws Exception {
    Path work = checkout(wrapper);
    String main = wrapper.mainBranch;
    manifest(work, path);
    gitlink(work, path, before);
    git.exec(work.toFile(), "git", "commit", "-q", "-m", "Declare and mount the submodule");
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", "HEAD");
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    gitlink(work, path, after);
    // An ordinary file beside the gitlink, so a fold's change list holds both kinds of entry.
    Files.writeString(work.resolve("notes.txt"), "Why the pin moved\n", StandardCharsets.UTF_8);
    git.exec(work.toFile(), "git", "add", "notes.txt");
    git.exec(work.toFile(), "git", "commit", "-q", "-m", "Move the pin");
    String fold = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();
    git.exec(work.toFile(), "git", "push", "-q", "--tags", "origin", main, "work");
    goCold(wrapper);
    return fold;
  }

  /**
   * The house abbreviation {@code ReleaseRequests.shortSha} uses for every other sentence it writes,
   * so a detail's wording is asserted rather than pattern-matched.
   */
  private String shortOf(String sha) {
    return sha.substring(0, 10);
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
