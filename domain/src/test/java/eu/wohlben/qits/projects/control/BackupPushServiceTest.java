package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The automatic backup: the main branch and every tag the git host holds, mirrored onto the
 * repository's forge twin.
 *
 * <p>The twin is a real throwaway bare here, and every assertion is made against <em>it</em> — the
 * whole point of a backup is what the other side ends up holding, and a test that only checked this
 * service's own state would prove nothing about that.
 *
 * <p>The debounce is turned down to something a test can wait through, but not to zero: zero would
 * remove the very window the collapse happens in and the burst case would pass for the wrong reason.
 */
@QuarkusTest
@TestProfile(BackupPushServiceTest.TestProfile.class)
public class BackupPushServiceTest {

  /** Long enough for a burst to land inside it, short enough to wait out twice per test. */
  private static final long DEBOUNCE_MS = 400;

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.projects.backup.debounce-ms", String.valueOf(DEBOUNCE_MS));
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject BackupPushService backupPushService;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;
  @Inject GitHostRepositories gitHostRepositories;
  @Inject GitMirrorRegistry gitMirrors;

  private Path hostOf(String repoId) {
    return Path.of(gitHost.fetchUrl(repoId));
  }

  private String in(Path repo, String... argv) throws Exception {
    return git.exec(repo.toFile(), argv).trim();
  }

  /** A writable bare standing in for the forge twin, seeded from a committed fixture. */
  private Path twin(String name) throws Exception {
    Path parent = Files.createTempDirectory("qits-backup-twin");
    Path bare = parent.resolve(name + ".git");
    git.exec(
        parent.toFile(), "git", "clone", "--bare", GitFixtures.path("testing-repo.git"), bare.toString());
    return bare;
  }

  /** A repository whose backup twin is {@code twin} — the row's url IS the twin. */
  private Repository repositoryBackedUpTo(Project project, Path twin) {
    return repositoryService.cloneRepository(
        twin.toString(), RepositoryArchetype.SERVICE, project);
  }

  /** Moves a branch on the GIT HOST, which is what a backup is supposed to carry to the twin. */
  private void branchOnHost(Repository repo, String branch) throws Exception {
    String sha = in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch);
    git.exec(hostOf(repo.id).toFile(), "git", "update-ref", "refs/heads/" + branch, sha);
  }

  /** The identity env a synthetic {@code commit-tree} needs — it has no working tree to read one from. */
  private static final Map<String, String> SYNTHETIC_IDENTITY =
      Map.of(
          "GIT_AUTHOR_NAME", "test",
          "GIT_AUTHOR_EMAIL", "test@test",
          "GIT_COMMITTER_NAME", "test",
          "GIT_COMMITTER_EMAIL", "test@test");

  /**
   * Advances {@code repo.mainBranch} on the GIT HOST by one synthetic commit — a real history
   * change, as opposed to {@link #branchOnHost}, which only points a new name at main's existing
   * sha. The twin starts out equal to the host (both are clones of the same fixture), so proving a
   * backup carries main requires moving main somewhere the twin has never seen.
   */
  private String advanceMainOnHost(Repository repo) throws Exception {
    Path host = hostOf(repo.id);
    String base = in(host, "git", "rev-parse", repo.mainBranch);
    String tree = in(host, "git", "rev-parse", repo.mainBranch + "^{tree}");
    String sha =
        git.exec(
                host.toFile(),
                SYNTHETIC_IDENTITY,
                "git",
                "commit-tree",
                tree,
                "-p",
                base,
                "-m",
                "advance main on the host")
            .trim();
    git.exec(host.toFile(), "git", "update-ref", "refs/heads/" + repo.mainBranch, sha);
    return sha;
  }

  private void awaitRuns(String repoId, long atLeast) throws Exception {
    long deadline = System.currentTimeMillis() + 15_000;
    while (backupPushService.completedRuns(repoId) < atLeast
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
  }

  @Test
  public void aBackupCarriesMainButNoOtherBranch() throws Exception {
    var project = projectService.create("Backup Basic", "backup-basic", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    advanceMainOnHost(repo);
    branchOnHost(repo, "only-on-the-platform");

    backupPushService.backupNow(repo.id);

    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch),
        in(twin, "git", "rev-parse", repo.mainBranch),
        "the advanced main reached the twin");
    assertEquals(
        "",
        in(twin, "git", "for-each-ref", "refs/heads/only-on-the-platform"),
        "a branch that exists only on the platform must not reach the twin");
  }

  /**
   * The failure the old wildcard refspec produced: a branch name the platform deleted and reused
   * for unrelated later work. The twin, having kept its own copy under that name, rejected the
   * reused name as non-fast-forward and failed the whole backup — including main.
   */
  @Test
  public void aReusedBranchNameOnTheTwinNoLongerFailsTheBackup() throws Exception {
    var project = projectService.create("Backup Reused Branch", "backup-reused", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);

    // A branch on the TWIN, pointing at a commit only the twin ever held under this name.
    String twinBase = in(twin, "git", "rev-parse", repo.mainBranch);
    String twinTree = in(twin, "git", "rev-parse", repo.mainBranch + "^{tree}");
    String twinOnlyCommit =
        git.exec(
                twin.toFile(),
                SYNTHETIC_IDENTITY,
                "git",
                "commit-tree",
                twinTree,
                "-p",
                twinBase,
                "-m",
                "a commit only the twin ever held under this branch name")
            .trim();
    git.exec(twin.toFile(), "git", "update-ref", "refs/heads/reused", twinOnlyCommit);

    // The platform later reuses the same name for unrelated work, and main moves too.
    branchOnHost(repo, "reused");
    advanceMainOnHost(repo);

    backupPushService.backupNow(repo.id);

    assertEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.SUCCEEDED,
        outcomeOf(repo.id),
        "a reused branch name on the twin must not fail the backup");
    assertEquals(null, detailOf(repo.id), "there is nothing to explain about a success");
    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch),
        in(twin, "git", "rev-parse", repo.mainBranch),
        "main still reached the twin");
  }

  /** A tag is never reused on the platform the way a branch name is, so it is always carried. */
  @Test
  public void aTagCreatedOnTheHostReachesTheTwin() throws Exception {
    var project = projectService.create("Backup Tag", "backup-tag", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    String mainSha = in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch);
    git.exec(hostOf(repo.id).toFile(), "git", "tag", "a-backup-tag", mainSha);

    backupPushService.backupNow(repo.id);

    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", "a-backup-tag"),
        in(twin, "git", "rev-parse", "a-backup-tag"),
        "the tag reached the twin");
  }

  /**
   * One {@code git push} of several branches fires several post-receives. Without the debounce that
   * is several backups of one repository, concurrently, against one mirror.
   */
  @Test
  public void aBurstOfPushEventsProducesOneBackupRun() throws Exception {
    var project = projectService.create("Backup Debounce", "backup-debounce", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    advanceMainOnHost(repo);
    long before = backupPushService.completedRuns(repo.id);

    backupPushService.onPush(repo.id);
    backupPushService.onPush(repo.id);
    backupPushService.onPush(repo.id);

    awaitRuns(repo.id, before + 1);
    Thread.sleep(DEBOUNCE_MS * 2); // long enough for a second run to have shown up
    assertEquals(before + 1, backupPushService.completedRuns(repo.id), "three events, one run");
    // And the one run carried the advanced main, which is why collapsing them is safe.
    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch),
        in(twin, "git", "rev-parse", repo.mainBranch),
        "main reached the twin");
  }

  /** An event that arrives after a run has started gets a run of its own — it may have missed it. */
  @Test
  public void anEventAfterTheDebounceElapsedSchedulesAnotherRun() throws Exception {
    var project = projectService.create("Backup Second", "backup-second", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    long before = backupPushService.completedRuns(repo.id);

    backupPushService.onPush(repo.id);
    awaitRuns(repo.id, before + 1);
    advanceMainOnHost(repo);
    backupPushService.onPush(repo.id);
    awaitRuns(repo.id, before + 2);

    assertEquals(before + 2, backupPushService.completedRuns(repo.id));
    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch),
        in(twin, "git", "rev-parse", repo.mainBranch),
        "the second run is what carried the advanced main");
  }

  /**
   * An adopted repository has no mirror until something needs one — no clone, no pull, no push ever
   * ran for it. The backup is allowed to be that something: {@code requireMirror} clones from the
   * git host on first use, so a row that has never been touched still backs up.
   */
  @Test
  public void anAdoptedRepositoryWithNoMirrorYetIsClonedOnFirstBackup() throws Exception {
    var project = projectService.create("Backup Adopted", "backup-adopted", null);
    Path twin = twin("testing-repo");
    // The bootstrap's shape: a bare the host already serves, which this service did not create.
    gitHostRepositories.ensure("adopted-backup-target", "master");
    git.exec(
        hostOf("adopted-backup-target").toFile(),
        "git",
        "fetch",
        GitFixtures.path("testing-repo.git"),
        "+refs/heads/*:refs/heads/*");
    var adopted =
        repositoryService.adoptExistingOrigin(
            project,
            "adopted-backup-target",
            "adopted-backup-target",
            twin.toString(),
            RepositoryArchetype.SERVICE);
    assertTrue(
        !Files.isDirectory(gitMirrors.of(adopted.id).gitDir()),
        "an adopted repository starts with no mirror at all");
    advanceMainOnHost(adopted);

    backupPushService.backupNow(adopted.id);

    assertTrue(
        Files.isDirectory(gitMirrors.of(adopted.id).gitDir()),
        "the backup cloned the mirror it needed");
    assertEquals(
        in(hostOf(adopted.id), "git", "rev-parse", adopted.mainBranch),
        in(twin, "git", "rev-parse", adopted.mainBranch),
        "and carried the host's main to the twin");
  }

  @Test
  public void aRepositoryWithNoTwinIsSkippedRatherThanFailing() {
    var project = projectService.create("Backup No Twin", "backup-no-twin", null);
    var wrapper = projectService.findWrapper(project.id).orElseThrow();
    long before = backupPushService.completedRuns(wrapper.id);

    // A greenfield wrapper has no forge twin. Backing it up is a no-op, not an error — and above
    // all not something that reaches the git hook that asked for it.
    assertDoesNotThrow(() -> backupPushService.backupNow(wrapper.id));
    assertEquals(
        before + 1,
        backupPushService.completedRuns(wrapper.id),
        "it ran and swallowed the refusal");
  }

  @Test
  public void anUnknownRepositoryIsSwallowedLikeEveryOtherFailure() {
    assertDoesNotThrow(() -> backupPushService.backupNow("no-such-repository"));
    assertDoesNotThrow(() -> backupPushService.onPush("no-such-repository"));
  }

  @Test
  public void theSweepBacksUpEveryRepositoryThatHasATwin() throws Exception {
    var project = projectService.create("Backup Sweep", "backup-sweep", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    advanceMainOnHost(repo);

    backupPushService.backupAll();

    assertEquals(
        in(hostOf(repo.id), "git", "rev-parse", repo.mainBranch),
        in(twin, "git", "rev-parse", repo.mainBranch),
        "the sweep carried the advanced main");
  }

  // -------------------------------------------------------------------------------------------
  // what the attempt left on the row
  // -------------------------------------------------------------------------------------------

  private eu.wohlben.qits.projects.entity.BackupOutcome outcomeOf(String repoId) {
    return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .call(() -> repositoryService.get(repoId).lastBackupOutcome);
  }

  private String detailOf(String repoId) {
    return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .call(() -> repositoryService.get(repoId).lastBackupDetail);
  }

  private java.time.Instant atOf(String repoId) {
    return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .call(() -> repositoryService.get(repoId).lastBackupAt);
  }

  /** Repoints a row at a twin that is not there, so the next backup really fails. */
  private void breakTheTwin(Repository repo) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () ->
                repositoryService.get(repo.id).url =
                    Path.of("/nonexistent", "no-such-twin.git").toString());
  }

  private void pointAtTwin(Repository repo, Path twin) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(() -> repositoryService.get(repo.id).url = twin.toString());
  }

  @Test
  public void aRepositoryThatHasNeverBeenBackedUpSaysNothingAtAll() {
    var project = projectService.create("Backup Never", "backup-never", null);
    var wrapper = projectService.findWrapper(project.id).orElseThrow();

    assertEquals(null, outcomeOf(wrapper.id), "never attempted is not a status");
    assertEquals(null, atOf(wrapper.id));
  }

  @Test
  public void aSuccessfulBackupIsRecordedWithNoDetail() throws Exception {
    var project = projectService.create("Backup Records Ok", "backup-rec-ok", null);
    var repo = repositoryBackedUpTo(project, twin("testing-repo"));

    backupPushService.backupNow(repo.id);

    assertEquals(eu.wohlben.qits.projects.entity.BackupOutcome.SUCCEEDED, outcomeOf(repo.id));
    assertEquals(null, detailOf(repo.id), "there is nothing to explain about a success");
    assertNotEquals(null, atOf(repo.id));
  }

  /**
   * The case the whole record exists for: a repository whose twin has been failing has to look
   * different from one that is fine — and has to stop looking that way the moment it is fixed.
   */
  @Test
  public void aSuccessClearsAPriorFailureAndItsDetail() throws Exception {
    var project = projectService.create("Backup Recovers", "backup-recovers", null);
    Path twin = twin("testing-repo");
    var repo = repositoryBackedUpTo(project, twin);
    breakTheTwin(repo);

    backupPushService.backupNow(repo.id);
    assertNotEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.SUCCEEDED,
        outcomeOf(repo.id),
        "the broken twin was recorded as a failure");
    assertNotEquals(null, detailOf(repo.id), "with git's own first line to read");

    pointAtTwin(repo, twin);
    backupPushService.backupNow(repo.id);

    assertEquals(eu.wohlben.qits.projects.entity.BackupOutcome.SUCCEEDED, outcomeOf(repo.id));
    assertEquals(null, detailOf(repo.id), "a stale reason beside a green outcome is worse than none");
  }

  /** Every trigger records, not just the manual one — a record with holes in it teaches nobody. */
  @Test
  public void aPushTriggeredBackupRecordsToo() throws Exception {
    var project = projectService.create("Backup Records Push", "backup-rec-push", null);
    var repo = repositoryBackedUpTo(project, twin("testing-repo"));
    long before = backupPushService.completedRuns(repo.id);

    backupPushService.onPush(repo.id);
    awaitRuns(repo.id, before + 1);

    assertEquals(eu.wohlben.qits.projects.entity.BackupOutcome.SUCCEEDED, outcomeOf(repo.id));
  }

  @Test
  public void aSweptBackupRecordsToo() throws Exception {
    var project = projectService.create("Backup Records Sweep", "backup-rec-sweep", null);
    var repo = repositoryBackedUpTo(project, twin("testing-repo"));

    backupPushService.backupAll();

    assertEquals(eu.wohlben.qits.projects.entity.BackupOutcome.SUCCEEDED, outcomeOf(repo.id));
  }

  /** A repository with no twin is behaving correctly; it must not collect a red status line. */
  @Test
  public void aRepositoryWithNoTwinRecordsNothing() {
    var project = projectService.create("Backup No Twin Record", "backup-ntr", null);
    var wrapper = projectService.findWrapper(project.id).orElseThrow();

    backupPushService.backupNow(wrapper.id);

    assertEquals(null, outcomeOf(wrapper.id), "nothing to do is not a failure");
  }

  /**
   * The classifier, against the sentences git actually prints. Three outcomes rather than one,
   * because each asks something different of a person — and matching on git's English is only safe
   * because {@code GitExecutor} pins {@code LC_ALL=C}.
   */
  @Test
  public void gitsOwnSentencesAreSortedIntoTheThreeKindsOfFailure() {
    assertEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.AUTH_REQUIRED,
        BackupPushService.classify("fatal: Authentication failed for 'https://github.com/o/r.git/'"));
    assertEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.AUTH_REQUIRED,
        BackupPushService.classify("fatal: could not read Username for 'https://github.com'"));
    assertEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.UNREACHABLE,
        BackupPushService.classify("fatal: unable to access '...': Could not resolve host: github.com"));
    assertEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.UNREACHABLE,
        BackupPushService.classify("ssh: connect to host forge port 22: Connection refused"));
    assertEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.FAILED,
        BackupPushService.classify("! [rejected] main -> main (non-fast-forward)"));
    assertEquals(
        eu.wohlben.qits.projects.entity.BackupOutcome.FAILED, BackupPushService.classify(null));
  }

  /** The detail column is 1000 characters and git's message is many lines; the first is the one. */
  @Test
  public void theRecordedDetailIsGitsFirstLine() throws Exception {
    var project = projectService.create("Backup Detail", "backup-detail", null);
    var repo = repositoryBackedUpTo(project, twin("testing-repo"));
    breakTheTwin(repo);

    backupPushService.backupNow(repo.id);

    String detail = detailOf(repo.id);
    assertTrue(detail.length() <= 1000, "it fits the column: " + detail.length());
    assertTrue(!detail.contains("\n"), "one line, not a transcript: " + detail);
  }
}
