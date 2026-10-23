package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ApprovalPolicy;
import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ApprovalRole;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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

    List<ApprovalRequirement> requirements = policy.requirementsFor(repo.id, fold);

    assertEquals(List.of("config-directory"), ids(requirements), requirements.toString());
    assertEquals(List.of(ApprovalRole.PERSON), requirements.get(0).admits());
    String prefix = "changes .config/qits/: ";
    String detail = detail(requirements);
    assertTrue(detail.startsWith(prefix), detail);
    assertEquals(
        new TreeSet<>(
            Set.of(
                ".config/qits/added.yml",
                ".config/qits/modified.yml",
                ".config/qits/deleted.yml",
                ".config/qits/renamed.yml",
                ".config/qits/renamed-to.yml",
                ".config/qits/moved-out.yml")),
        new TreeSet<>(Arrays.asList(detail.substring(prefix.length()).split(", "))),
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

    assertEquals(List.of(), policy.requirementsFor(repo.id, pinsOnly));
    assertEquals(List.of(), policy.requirementsFor(repo.id, ordinary));
    assertEquals(
        List.of(),
        policy.requirementsFor(repo.id, null),
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

    List<ApprovalRequirement> requirements = policy.requirementsFor(repo.id, nowhere);

    assertEquals(
        List.of("config-directory"),
        ids(requirements),
        "one fail-closed requirement, and no second read: " + requirements);
    assertEquals(List.of(ApprovalRole.PERSON), requirements.get(0).admits(), "a person only");
    assertTrue(
        detail(requirements).startsWith("the changes to .config/qits/ could not be read: "),
        detail(requirements));
    assertFalse(detail(requirements).contains("; "), "manual-review is not configured here");
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

    List<ApprovalRequirement> expected =
        List.of(ApprovalRequirement.person("no-changes", ApprovalPolicy.NO_CHANGES_DETAIL, null));
    assertEquals(expected, policy.requirementsFor(repo.id, mainHead), "main's own (old) head");
    assertEquals(
        expected, policy.requirementsFor(repo.id, emptyCommit), "an empty commit on main");
    assertEquals(
        List.of(),
        policy.requirementsFor(repo.id, something),
        "a fold carrying a change is not held by this rule");
  }

  /**
   * qits-1517: widening {@code front_desk.delivery.approves} to every other name it may hold —
   * {@code manual-review}, {@code artifact-declarations}, {@code automation}, {@code contract} —
   * never admits the desk to {@code no-changes} or {@code config-directory}: both stay a person's.
   * qits-1560: naming {@code config-directory} too admits the desk to a fold that changes {@code
   * .config/qits/}, and still never to {@code no-changes}.
   */
  @Test
  public void aWidenedApprovesListAdmitsTheDeskToConfigDirectoryOnlyWhenItIsNamed()
      throws Exception {
    Repository repo = cloned("Widened Desk Approval");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                Project.update(
                    "deliveryApproves = ?1 where id = ?2",
                    "artifact-declarations automation contract manual-review",
                    repo.project.id));
    Path work = checkout(repo);
    String main = git.exec(work.toFile(), "git", "rev-parse", "--abbrev-ref", "HEAD").trim();

    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    commit(work, "--allow-empty", "Nothing at all");
    String emptyFold = push(work, repo, "work");
    assertEquals(
        List.of(ApprovalRequirement.person("no-changes", ApprovalPolicy.NO_CHANGES_DETAIL, null)),
        policy.requirementsFor(repo.id, emptyFold),
        "no-changes admits a person alone");

    git.exec(work.toFile(), "git", "checkout", "-q", main);
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "config");
    write(work, ".config/qits/release-requests.yml", "manual-review: false\n");
    commitAll(work, "Loosen the rules");
    String configFold = push(work, repo, "config");
    List<ApprovalRequirement> requirements = policy.requirementsFor(repo.id, configFold);
    assertEquals(List.of("config-directory"), ids(requirements), requirements.toString());
    assertEquals(List.of(ApprovalRole.PERSON), requirements.get(0).admits());

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                Project.update(
                    "deliveryApproves = ?1 where id = ?2",
                    "artifact-declarations automation config-directory contract manual-review",
                    repo.project.id));
    List<ApprovalRequirement> admitted = policy.requirementsFor(repo.id, configFold);
    assertEquals(List.of("config-directory"), ids(admitted), admitted.toString());
    assertEquals(List.of(ApprovalRole.PERSON, ApprovalRole.DESK), admitted.get(0).admits());
    assertEquals(
        List.of(ApprovalRequirement.person("no-changes", ApprovalPolicy.NO_CHANGES_DETAIL, null)),
        policy.requirementsFor(repo.id, emptyFold),
        "no-changes still admits a person alone");
  }

  /** A repository with no row is settled history, and no rule holds it. */
  @Test
  public void aRepositoryWithNoRowRequiresNothing() {
    assertEquals(
        List.of(),
        policy.requirementsFor(
            "config-approval-vanished-" + UUID.randomUUID(),
            "4444444444444444444444444444444444444444"));
  }

  // -----------------------------------------------------------------------------------------
  // Artifact declarations (epic qits-1038): release.yml declaring what a release publishes
  // -----------------------------------------------------------------------------------------

  private static final String RELEASE_YML_BEFORE =
      "archetype: java-service\n"
          + "artifacts:\n"
          + "  - { type: docker, name: qits/thing, sbom: .sbom/sbom.json }\n"
          + "contracts:\n"
          + "  application: thing\n"
          + "  pacts:\n"
          + "    qits-ci-service: { packages: [maven] }\n";

  /**
   * <b>Declaring what a release publishes is the agent's own change to acknowledge</b>: a fold whose
   * only {@code .config/qits/} change adds an artifact entry, a pact package and a comment is held by
   * {@code artifact-declarations}, which a person or the request's own agent may approve — and not
   * by {@code config-directory}.
   */
  @Test
  public void aReleaseYmlThatOnlyDeclaresArtifactsAdmitsTheAgent() throws Exception {
    Repository repo = cloned("Artifact Declarations");
    Path work = checkout(repo);
    write(work, ".config/qits/release.yml", RELEASE_YML_BEFORE);
    commitAll(work, "Configure");
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", "HEAD");
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    write(
        work,
        ".config/qits/release.yml",
        "archetype: java-service\n"
            + "artifacts:\n"
            + "  - { type: docker, name: qits/thing, sbom: .sbom/sbom.json }\n"
            + "  - { type: docker, name: qits/thing-worker, sbom: .sbom/worker.json }\n"
            + "  # the API document, published as docs\n"
            + "  - { type: docs, name: \"@apidocs/thing\", path: docs/openapi.yml }\n"
            + "\n"
            + "contracts:\n"
            + "  application: thing\n"
            + "  golden-masters: { from: golden-masters/, packages: [maven, npm] }\n"
            + "  pacts:\n"
            + "    qits-ci-service: { packages: [maven] }\n"
            + "    qits-projects-service: { packages: [maven] }\n");
    write(work, "src/Main.java", "class Main {}\n");
    commitAll(work, "Declare a worker image, the API docs and a pact");
    String fold = push(work, repo, "work");

    List<ApprovalRequirement> requirements = policy.requirementsFor(repo.id, fold);

    assertEquals(List.of("artifact-declarations"), ids(requirements), requirements.toString());
    assertEquals(
        List.of(ApprovalRole.PERSON, ApprovalRole.AGENT), requirements.get(0).admits());
    assertEquals(".config/qits/release.yml", requirements.get(0).detail());
    assertFalse(requirements.get(0).excludesAuthors());
  }

  /**
   * <b>A slot is never a declaration</b>: the same file gaining a {@code release:} slot beside a
   * declaration is the directory's ordinary person requirement, and so is a second file under the
   * directory changing beside a pure declaration.
   */
  @Test
  public void aReleaseSlotOrASecondFileIsAPersonsToApprove() throws Exception {
    Repository repo = cloned("Release Slot");
    Path work = checkout(repo);
    write(work, ".config/qits/release.yml", RELEASE_YML_BEFORE);
    commitAll(work, "Configure");
    git.exec(work.toFile(), "git", "tag", "2026.900.100000", "HEAD");
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "work");
    write(
        work,
        ".config/qits/release.yml",
        RELEASE_YML_BEFORE
            + "release:\n  - { name: publish, run: ./publish.sh }\n");
    commitAll(work, "Add a release slot");
    String slot = push(work, repo, "work");
    write(work, ".config/qits/release.yml", RELEASE_YML_BEFORE + "# only a comment\n");
    write(work, ".config/qits/deployments.yml", "application: thing\n");
    commitAll(work, "A comment, and a deployment");
    String twoFiles = push(work, repo, "work");

    List<ApprovalRequirement> forSlot = policy.requirementsFor(repo.id, slot);
    assertEquals(List.of("config-directory"), ids(forSlot), forSlot.toString());
    assertEquals(List.of(ApprovalRole.PERSON), forSlot.get(0).admits());
    List<ApprovalRequirement> forTwo = policy.requirementsFor(repo.id, twoFiles);
    assertEquals(List.of("config-directory"), ids(forTwo), forTwo.toString());
  }

  private static List<String> ids(List<ApprovalRequirement> requirements) {
    return requirements.stream().map(ApprovalRequirement::id).toList();
  }

  /** The requirements' sentences, joined as the deprecated single detail joins them. */
  private static String detail(List<ApprovalRequirement> requirements) {
    return String.join(
        "; ", requirements.stream().map(ApprovalRequirement::sentence).toList());
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
