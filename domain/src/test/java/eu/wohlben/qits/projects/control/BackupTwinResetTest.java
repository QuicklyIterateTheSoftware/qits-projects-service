package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.dto.RepositoryBackupResetDto;
import eu.wohlben.qits.projects.entity.BackupOutcome;
import eu.wohlben.qits.projects.entity.BackupResetKind;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The backup twin reset (qits-1885): {@link BackupPushService#resetTwin} over {@link
 * RepositoryService#resetBackupTwin}, against a real throwaway bare standing in for the forge twin,
 * as {@code BackupPushServiceTest} does. Every assertion that matters is made on the twin itself —
 * what it holds afterwards is the whole point of the door, and what it must still hold after a
 * refusal is the other half.
 */
@QuarkusTest
public class BackupTwinResetTest {

  private static final Map<String, String> IDENTITY =
      Map.of(
          "GIT_AUTHOR_NAME", "test",
          "GIT_AUTHOR_EMAIL", "test@test",
          "GIT_COMMITTER_NAME", "test",
          "GIT_COMMITTER_EMAIL", "test@test");

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject BackupPushService backupPushService;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;
  @Inject GitMirrorRegistry gitMirrors;

  private Path hostOf(String repoId) {
    return Path.of(gitHost.fetchUrl(repoId));
  }

  private String in(Path repo, String... argv) throws Exception {
    return git.exec(repo.toFile(), argv).trim();
  }

  private Path twin() throws Exception {
    Path parent = Files.createTempDirectory("qits-backup-reset-twin");
    Path bare = parent.resolve("testing-repo.git");
    git.exec(
        parent.toFile(),
        "git",
        "clone",
        "--bare",
        GitFixtures.path("testing-repo.git"),
        bare.toString());
    return bare;
  }

  private Repository backedUpTo(String slug, Path twin) {
    var project = projectService.create("Backup Reset " + slug, "backup-reset-" + slug, null);
    return repositoryService.cloneRepository(twin.toString(), RepositoryArchetype.SERVICE, project);
  }

  private String mainOf(Path bare, Repository repo) throws Exception {
    return in(bare, "git", "rev-parse", "refs/heads/" + repo.mainBranch);
  }

  /** A commit with {@code main}'s tree in {@code bare}: on top of {@code parent}, or a new root. */
  private String commit(Path bare, Repository repo, String parent, String message)
      throws Exception {
    String tree = in(bare, "git", "rev-parse", "refs/heads/" + repo.mainBranch + "^{tree}");
    return parent == null
        ? git.exec(bare.toFile(), IDENTITY, "git", "commit-tree", tree, "-m", message).trim()
        : git.exec(bare.toFile(), IDENTITY, "git", "commit-tree", tree, "-p", parent, "-m", message)
            .trim();
  }

  private void setMain(Path bare, Repository repo, String sha) throws Exception {
    git.exec(bare.toFile(), "git", "update-ref", "refs/heads/" + repo.mainBranch, sha);
  }

  /** Moves the host's main one synthetic commit on: our main, which the twin has never seen. */
  private String advanceHost(Repository repo) throws Exception {
    Path host = hostOf(repo.id);
    String sha = commit(host, repo, mainOf(host, repo), "advance main on the host");
    setMain(host, repo, sha);
    return sha;
  }

  private BackupOutcome outcomeOf(String repoId) {
    return QuarkusTransaction.requiringNew().call(() -> repositoryService.get(repoId).lastBackupOutcome);
  }

  private String probeRefs(Repository repo) throws Exception {
    return in(gitMirrors.of(repo.id).gitDir(), "git", "for-each-ref", "refs/qits-backup-probe/");
  }

  private DomainException refused(Repository repo) {
    return assertThrows(
        DomainException.class,
        () -> backupPushService.resetTwin(repo.id, "the twin is wrong", "wohlben"));
  }

  /**
   * The live case: the forge seeded the twin with a README commit of its own, so every backup is
   * refused as non-fast-forward. The reset replaces main, records who and why, and the very next
   * backup succeeds.
   */
  @Test
  public void anUnrelatedTwinIsResetAndTheNextBackupSucceeds() throws Exception {
    Path twin = twin();
    var repo = backedUpTo("unrelated", twin);
    String seed = commit(twin, repo, null, "Initial commit");
    setMain(twin, repo, seed);
    String ours = advanceHost(repo);
    backupPushService.backupNow(repo.id);
    assertEquals(BackupOutcome.FAILED, outcomeOf(repo.id), "the backup alone cannot get past it");

    RepositoryBackupResetDto reset =
        backupPushService.resetTwin(repo.id, "  GitHub seeded the twin with a README  ", "wohlben");

    assertEquals(BackupResetKind.UNRELATED_RESET, reset.kind());
    assertEquals(seed, reset.twinShaBefore());
    assertEquals(ours, reset.pushedSha());
    assertEquals("wohlben", reset.decidedBy());
    assertEquals("GitHub seeded the twin with a README", reset.reason());
    assertEquals(ours, mainOf(twin, repo), "the twin's main is ours now");
    assertEquals(BackupOutcome.SUCCEEDED, outcomeOf(repo.id));
    assertEquals("", probeRefs(repo), "the probe ref does not survive the reset");

    String next = advanceHost(repo);
    backupPushService.backupNow(repo.id);
    assertEquals(BackupOutcome.SUCCEEDED, outcomeOf(repo.id), "the backup works again");
    assertEquals(next, mainOf(twin, repo));

    List<RepositoryBackupResetDto> listed = repositoryService.backupResets(repo.id);
    assertEquals(1, listed.size());
    assertEquals(BackupResetKind.UNRELATED_RESET, listed.get(0).kind());
    assertEquals(seed, listed.get(0).twinShaBefore());
    assertEquals("wohlben", listed.get(0).decidedBy());
  }

  /** A twin merely behind ours is fast-forwarded by the backup's own push, nothing forced. */
  @Test
  public void aTwinBehindOursIsFastForwarded() throws Exception {
    Path twin = twin();
    var repo = backedUpTo("behind", twin);
    String before = mainOf(twin, repo);
    String ours = advanceHost(repo);

    RepositoryBackupResetDto reset = backupPushService.resetTwin(repo.id, "catch up", "wohlben");

    assertEquals(BackupResetKind.FAST_FORWARD, reset.kind());
    assertEquals(before, reset.twinShaBefore());
    assertEquals(ours, mainOf(twin, repo));
    assertEquals(BackupOutcome.SUCCEEDED, outcomeOf(repo.id));
    assertEquals("", probeRefs(repo), "the probe ref does not survive a fast-forward either");
  }

  /** A twin with no main at all takes ours with a plain push. */
  @Test
  public void aTwinWithNoMainTakesOursWithAPlainPush() throws Exception {
    Path twin = twin();
    var repo = backedUpTo("no-main", twin);
    git.exec(twin.toFile(), "git", "update-ref", "-d", "refs/heads/" + repo.mainBranch);
    String ours = mainOf(hostOf(repo.id), repo);

    RepositoryBackupResetDto reset = backupPushService.resetTwin(repo.id, "an empty forge", "desk");

    assertEquals(BackupResetKind.FAST_FORWARD, reset.kind());
    assertNull(reset.twinShaBefore());
    assertEquals(ours, mainOf(twin, repo));
  }

  /** The twin holds commits we do not: 409 naming both shas, nothing pushed, nothing recorded. */
  @Test
  public void aTwinAheadOfOursIsRefusedAndLeftAlone() throws Exception {
    Path twin = twin();
    var repo = backedUpTo("ahead", twin);
    String ours = mainOf(twin, repo);
    String ahead = commit(twin, repo, ours, "work only the forge has");
    setMain(twin, repo, ahead);

    DomainException e = refused(repo);

    assertEquals(409, e.statusCode());
    assertTrue(e.getMessage().contains(ahead) && e.getMessage().contains(ours), e.getMessage());
    assertEquals(ahead, mainOf(twin, repo), "the twin is unchanged");
    assertEquals(List.of(), repositoryService.backupResets(repo.id), "nothing recorded");
    assertNull(outcomeOf(repo.id), "no backup outcome either");
    assertEquals("", probeRefs(repo), "the probe ref does not survive a refusal");
  }

  /** Diverged from a common base: also the person's to look at, never this door's to discard. */
  @Test
  public void aDivergedTwinIsRefusedAndLeftAlone() throws Exception {
    Path twin = twin();
    var repo = backedUpTo("diverged", twin);
    String base = mainOf(twin, repo);
    String theirs = commit(twin, repo, base, "a forge-side edit");
    setMain(twin, repo, theirs);
    String ours = advanceHost(repo);

    DomainException e = refused(repo);

    assertEquals(409, e.statusCode());
    assertTrue(e.getMessage().contains("diverged"), e.getMessage());
    assertTrue(e.getMessage().contains(theirs) && e.getMessage().contains(ours), e.getMessage());
    assertEquals(theirs, mainOf(twin, repo), "the twin is unchanged");
    assertEquals(List.of(), repositoryService.backupResets(repo.id));
  }

  /**
   * The lease: the twin moves after it was probed and before the push. Simulated with a {@code
   * reference-transaction} hook in the mirror, which fires when the probe ref is written — i.e.
   * exactly between the probe and the push — and moves the twin's main to a second unrelated root.
   * The lease names the first root, so the push is refused, nothing is overwritten, and the answer
   * is a 409.
   */
  @Test
  public void theLeaseRefusesATwinThatMovedAfterTheProbe() throws Exception {
    Path twin = twin();
    var repo = backedUpTo("lease", twin);
    String seed = commit(twin, repo, null, "Initial commit");
    String moved = commit(twin, repo, null, "Somebody else's initial commit");
    setMain(twin, repo, seed);
    advanceHost(repo);
    Path mirror = gitMirrors.of(repo.id).gitDir();
    Path marker = mirror.resolve("lease-test-moved");
    Path hook = mirror.resolve("hooks").resolve("reference-transaction");
    Files.createDirectories(hook.getParent());
    Files.writeString(
        hook,
        "#!/bin/sh\n"
            + "input=$(cat)\n"
            + "[ \"$1\" = committed ] || exit 0\n"
            + "printf '%s' \"$input\" | grep -q 'refs/qits-backup-probe/' || exit 0\n"
            + "[ -e '" + marker + "' ] && exit 0\n"
            + "touch '" + marker + "'\n"
            + "git --git-dir='" + twin + "' update-ref refs/heads/" + repo.mainBranch + " " + moved
            + "\n",
        StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));

    try {
      DomainException e = refused(repo);

      assertTrue(Files.exists(marker), "the hook ran between the probe and the push");
      assertEquals(409, e.statusCode());
      assertTrue(e.getMessage().contains("moved since it was probed"), e.getMessage());
      assertEquals(moved, mainOf(twin, repo), "the twin's newer main was not overwritten");
      assertEquals(List.of(), repositoryService.backupResets(repo.id));
    } finally {
      Files.deleteIfExists(hook);
    }
  }

  @Test
  public void aBlankReasonIsRefused() throws Exception {
    var repo = backedUpTo("blank", twin());
    assertThrows(
        BadRequestException.class, () -> backupPushService.resetTwin(repo.id, "  ", "wohlben"));
    assertThrows(
        BadRequestException.class, () -> backupPushService.resetTwin(repo.id, null, "wohlben"));
  }

  @Test
  public void aRepositoryWithNoTwinIsAConflict() {
    var project = projectService.create("Backup Reset No Twin", "backup-reset-no-twin", null);
    var wrapper = projectService.findWrapper(project.id).orElseThrow();

    DomainException e =
        assertThrows(
            DomainException.class,
            () -> backupPushService.resetTwin(wrapper.id, "why not", "wohlben"));

    assertEquals(409, e.statusCode());
  }

  @Test
  public void anUnknownRepositoryIsNotFound() {
    DomainException e =
        assertThrows(
            DomainException.class,
            () -> backupPushService.resetTwin("no-such-repository", "why", "wohlben"));
    assertEquals(404, e.statusCode());
    assertNotEquals(null, e.getMessage());
  }
}
