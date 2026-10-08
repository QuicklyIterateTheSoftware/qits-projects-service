package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ApprovalPolicy;
import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The content half of the approval rule, read against a real repository.</b>
 *
 * <p>The gate tests drive the policy through {@link RecordingFoldChanges}' scripted answers; this
 * class is the seam behind that fake. The suite's fake is told to delegate to the real mirror read
 * for each repository here, so what is asserted is git's own diff, against the base the request's
 * {@code …/changes} view uses — the newest release tag that does not contain the fold — and the
 * policy's filtering of it: every kind of change under {@code .config/qits/} counts, a rename out of
 * it counts by its old path, and a gitlink never counts.
 */
@QuarkusTest
public class ApprovalPolicyFoldContentTest {

  @Inject ApprovalPolicy policy;

  @Inject RecordingFoldChanges foldChanges;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RepositoryService repositoryService;

  @Inject ProjectService projectService;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitHostAddress gitHostAddress;

  @Inject GitExecutor git;

  @BeforeEach
  void quiet() {
    // An ordinary main: CI-gated, no manual-review — so whatever asks a person here is the content.
    gitHost.reset();
    foldChanges.reset();
  }

  @AfterEach
  void reset() {
    foldChanges.reset();
  }

  /**
   * Added, modified, deleted, renamed within, and renamed out — each counts, and only paths under
   * {@code .config/qits/} are named. A README beside them and a gitlink mounted inside the directory
   * are not.
   */
  @Test
  public void everyKindOfChangeUnderConfigQitsCountsAndAGitlinkDoesNot() throws Exception {
    Repository repo = cloned("Config Approval Project");
    Path work = checkout(repo);
    write(work, ".config/qits/modified.yml", "before: one\n");
    write(work, ".config/qits/deleted.yml", "this one is going away entirely\n");
    write(work, ".config/qits/renamed.yml", "a file long enough to be recognised as renamed\n");
    write(work, ".config/qits/moved-out.yml", "another file long enough to be followed out\n");
    commitAll(work, "Configure");
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", "HEAD");

    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    write(work, ".config/qits/added.yml", "new: yes\n");
    write(work, ".config/qits/modified.yml", "before: two\n");
    Files.delete(work.resolve(".config/qits/deleted.yml"));
    git.exec(work.toFile(), "git", "mv", ".config/qits/renamed.yml", ".config/qits/renamed-to.yml");
    Files.createDirectories(work.resolve("docs"));
    git.exec(work.toFile(), "git", "mv", ".config/qits/moved-out.yml", "docs/moved-out.yml");
    write(work, "README.md", "not configuration\n");
    git.exec(work.toFile(), "git", "add", "-A");
    // Staged AFTER the add: `add -A` drops a gitlink whose path has no checkout behind it.
    gitlink(work, ".config/qits/vendored", "1111111111111111111111111111111111111111");
    commit(work, "Rewrite the rules");
    String fold = push(work, repo, "work");

    ApprovalPolicy.ApprovalRequirement requirement = policy.requirementFor(repo.id, fold);

    assertTrue(requirement.required(), requirement.toString());
    String prefix = "changes .config/qits/: ";
    assertTrue(requirement.detail().startsWith(prefix), requirement.detail());
    assertEquals(
        new TreeSet<>(
            Set.of(
                ".config/qits/added.yml",
                ".config/qits/modified.yml",
                ".config/qits/deleted.yml",
                ".config/qits/renamed.yml",
                ".config/qits/renamed-to.yml",
                ".config/qits/moved-out.yml")),
        new TreeSet<>(Arrays.asList(requirement.detail().substring(prefix.length()).split(", "))),
        "every side of every change under the directory, the gitlink and the README excluded");
    assertTrue(
        foldChanges.changes(repo.id, fold, ApprovalPolicy.CONFIG_DIRECTORY).stream()
            .anyMatch(eu.wohlben.qits.projects.dto.CommitFileChangeDto::touchesGitlink),
        "the gitlink IS in the diff, so its absence from the detail is the policy's filter");
  }

  /** A fold that only moves pins, and one that touches nothing configured, ask nobody. */
  @Test
  public void aGitlinkOnlyFoldAndAnOrdinaryFoldRequireNothing() throws Exception {
    Repository repo = cloned("Config Approval Gitlinks");
    Path work = checkout(repo);
    gitlink(work, "components/qits-thing/qits-thing-service", "1111111111111111111111111111111111111111");
    commit(work, "Mount");
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", "HEAD");
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    gitlink(work, "components/qits-thing/qits-thing-service", "2222222222222222222222222222222222222222");
    commit(work, "Move the pin");
    String pinsOnly = push(work, repo, "work");
    write(work, "src/Main.java", "class Main {}\n");
    git.exec(work.toFile(), "git", "add", "src/Main.java");
    commit(work, "Ordinary work");
    String ordinary = push(work, repo, "work");

    assertEquals(ApprovalPolicy.ApprovalRequirement.NOT_REQUIRED, policy.requirementFor(repo.id, pinsOnly));
    assertEquals(ApprovalPolicy.ApprovalRequirement.NOT_REQUIRED, policy.requirementFor(repo.id, ordinary));
    assertEquals(
        ApprovalPolicy.ApprovalRequirement.NOT_REQUIRED,
        policy.requirementFor(repo.id, null),
        "no fold yet: there is no content to have an opinion about");
  }

  /**
   * A fold the mirror does not hold is fetched for once and then, still absent, is unreadable —
   * which requires approval rather than reading as "changes nothing".
   */
  @Test
  public void aFoldTheRepositoryDoesNotHoldRequiresApproval() throws Exception {
    Repository repo = cloned("Config Approval Unreadable");
    String nowhere = "3333333333333333333333333333333333333333";

    ApprovalPolicy.ApprovalRequirement requirement = policy.requirementFor(repo.id, nowhere);

    assertTrue(requirement.required());
    assertTrue(
        requirement.detail().startsWith("the changes to .config/qits/ could not be read: "),
        requirement.detail());
    assertFalse(requirement.detail().contains("; "), "manual-review is not configured here");
  }

  /**
   * <b>A fold that adds nothing to main asks a person</b> (qits-760) — read against main with git's
   * own three-dot diff, not against the newest release tag. Main's own head, an empty commit on top
   * of it, and a fold main has since moved past all add nothing; a branch with one real commit adds
   * something and asks nobody. The fixture configures no approval and touches no {@code
   * .config/qits/}, so the empty-fold sentence is the whole detail.
   */
  @Test
  public void aFoldThatAddsNothingToMainAsksAPersonAndOneThatAddsSomethingDoesNot()
      throws Exception {
    Repository repo = cloned("Empty Fold Approval");
    Path work = checkout(repo);
    String main = git.exec(work.toFile(), "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    String mainHead = git.exec(work.toFile(), "git", "rev-parse", "HEAD").trim();

    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    commit(work, "--allow-empty", "Nothing at all");
    String emptyCommit = push(work, repo, "work");
    write(work, "src/Main.java", "class Main {}\n");
    git.exec(work.toFile(), "git", "add", "src/Main.java");
    commit(work, "Real work");
    String something = push(work, repo, "work");

    // Main moves on past the first fold (a pending tag finalizing, say): still nothing of its own.
    git.exec(work.toFile(), "git", "checkout", "-q", main);
    write(work, "docs/later.md", "landed on main afterwards\n");
    git.exec(work.toFile(), "git", "add", "docs/later.md");
    commit(work, "Main moves");
    push(work, repo, main);

    ApprovalPolicy.ApprovalRequirement expected =
        new ApprovalPolicy.ApprovalRequirement(true, ApprovalPolicy.NO_CHANGES_DETAIL);
    assertEquals(expected, policy.requirementFor(repo.id, mainHead), "main's own (old) head");
    assertEquals(expected, policy.requirementFor(repo.id, emptyCommit), "an empty commit on main");
    assertEquals(
        ApprovalPolicy.ApprovalRequirement.NOT_REQUIRED,
        policy.requirementFor(repo.id, something),
        "a fold carrying a change is not held by this rule");
  }

  /** A repository with no row is settled history, and no rule holds it. */
  @Test
  public void aRepositoryWithNoRowRequiresNothing() {
    ApprovalPolicy.ApprovalRequirement requirement =
        policy.requirementFor("config-approval-vanished-" + UUID.randomUUID(), "4444444444444444444444444444444444444444");
    assertFalse(requirement.required());
    assertNull(requirement.detail());
  }

  // -----------------------------------------------------------------------------------------
  // Fixtures — FoldDiffTest's idiom: a fixture bare cloned as a repository, pushed to, mirror dropped
  // -----------------------------------------------------------------------------------------

  private Repository cloned(String projectName) throws Exception {
    var project = projectService.create(projectName + " " + UUID.randomUUID(), null);
    Repository repo =
        repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
    foldChanges.useMirror(repo.id);
    return repo;
  }

  private Path checkout(Repository repo) throws Exception {
    Path parent = Files.createTempDirectory("config-approval");
    parent.toFile().deleteOnExit();
    git.exec(parent.toFile(), "git", "clone", "-q", gitHostAddress.fetchUrl(repo.id), "work");
    Path work = parent.resolve("work");
    git.exec(work.toFile(), "git", "config", "user.email", "fixtures@qits.local");
    git.exec(work.toFile(), "git", "config", "user.name", "qits fixtures");
    return work;
  }

  private void write(Path work, String path, String content) throws IOException {
    Path file = work.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private void commitAll(Path work, String message) throws Exception {
    git.exec(work.toFile(), "git", "add", "-A");
    commit(work, message);
  }

  /** Commits what is staged — the only way to keep a gitlink with no checkout behind it. */
  private void commit(Path work, String message) throws Exception {
    git.exec(work.toFile(), "git", "commit", "-q", "-m", message);
  }

  /** The same with one extra flag — {@code --allow-empty}, for a commit that changes no tree. */
  private void commit(Path work, String flag, String message) throws Exception {
    git.exec(work.toFile(), "git", "commit", "-q", flag, "-m", message);
  }

  private void gitlink(Path work, String path, String sha) throws Exception {
    git.exec(
        work.toFile(), "git", "update-index", "--add", "--cacheinfo", "160000," + sha + "," + path);
  }

  /** Pushes the branch and every tag, drops the mirror, and answers the pushed head. */
  private String push(Path work, Repository repo, String branch) throws Exception {
    git.exec(work.toFile(), "git", "push", "-q", "--tags", "origin", branch);
    String head = git.exec(work.toFile(), "git", "rev-parse", branch).trim();
    goCold(repo);
    return head;
  }

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
