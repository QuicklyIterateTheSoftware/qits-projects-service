package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.testsupport.RecordingProjectAnnouncer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The other half of {@link ProjectConfigParserTest}: the parser is pure and says what bytes mean,
 * and this is the seam that reads those bytes off the wrapper, stores the answer and announces it.
 *
 * <p><b>Both halves need a test because neither implies the other.</b> A parser that is right about
 * every input still proves nothing about a reconcile that never calls it, reads the wrong path, or
 * publishes a {@code ProjectChanged} on every pass. So each case here commits a real blob into the
 * wrapper's {@code main} with the same no-worktree plumbing the service itself uses, and then runs
 * the reconcile.
 *
 * <p>The announcements are asserted through {@link RecordingProjectAnnouncer} — under {@code %test}
 * the bus is dark, so without it "announced once" and "never announced" look identical.
 */
@QuarkusTest
public class WrapperProjectConfigReconcileTest {

  @Inject ProjectService projectService;
  @Inject WrapperReconcileService reconcileService;
  @Inject GitMirrorRegistry gitMirrors;
  @Inject GitExecutor git;
  @Inject GitIdentity gitIdentity;
  @Inject RecordingProjectAnnouncer announcer;

  @BeforeEach
  void clean() {
    projectService.list().stream()
        .filter(p -> p.slug != null && p.slug.startsWith("projcfg"))
        .toList()
        .forEach(p -> projectService.delete(p.id));
    announcer.clear();
  }

  private Project greenfield(String slug) {
    return projectService.create("Project Config " + slug, slug, null);
  }

  private Repository wrapperOf(Project project) {
    return projectService.findWrapper(project.id).orElseThrow();
  }

  /**
   * Commits {@code content} at {@code .config/qits/project.yml} on the wrapper's {@code main}, with
   * no working tree — {@code amendTree} + {@code commit-tree} + {@code update-ref}, which is what
   * every commit this service makes does.
   */
  private void commitProjectYml(Repository wrapper, String content) throws Exception {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    String tip = git.exec(mirror.gitDir().toFile(), "git", "rev-parse", "refs/heads/main").trim();
    String tree =
        mirror.amendTree(
            tip,
            List.of(
                new RepoMirror.TreeEntry(
                    ProjectConfigParser.CONFIG_PATH,
                    "100644",
                    content.getBytes(StandardCharsets.UTF_8))),
            List.of(),
            List.of());
    String commit =
        mirror.commitTree(
            tree, List.of(tip), "Declare supports_environments", gitIdentity.asCommitIdentity());
    git.exec(mirror.gitDir().toFile(), "git", "update-ref", "refs/heads/main", commit);
  }

  /** Removes the file entirely, so the reconcile sees a wrapper that declares nothing. */
  private void removeProjectYml(Repository wrapper) throws Exception {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    String tip = git.exec(mirror.gitDir().toFile(), "git", "rev-parse", "refs/heads/main").trim();
    String tree =
        mirror.amendTree(tip, List.of(), List.of(), List.of(ProjectConfigParser.CONFIG_PATH));
    String commit =
        mirror.commitTree(
            tree, List.of(tip), "Drop the project declaration", gitIdentity.asCommitIdentity());
    git.exec(mirror.gitDir().toFile(), "git", "update-ref", "refs/heads/main", commit);
  }

  private boolean storedFlagOf(String projectId) {
    return projectService.get(projectId).supportsEnvironments;
  }

  /**
   * A new project is seeded with the template's declaration, which says {@code true} — so the
   * reconcile reads it, agrees with the stored value and announces nothing. <b>The silence is the
   * assertion</b>: a reconcile runs on a timer, and an announcement per pass would be a frame a
   * consumer has to learn to ignore.
   */
  @Test
  public void aWrapperThatAgreesWithTheStoredFlagChangesNothingAndAnnouncesNothing() {
    Project project = greenfield("projcfg-agree");
    assertTrue(storedFlagOf(project.id), "a new project supports environments");

    reconcileService.reconcile(project.id);
    reconcileService.reconcile(project.id);

    assertTrue(storedFlagOf(project.id));
    assertEquals(
        List.of(),
        announcer.changedOf(project.id),
        "nothing moved, so there is nothing to announce — twice over");
  }

  /** An explicit {@code false} is the one value that changes anything, and it is announced once. */
  @Test
  public void anExplicitFalseIsStoredAndAnnouncedExactlyOnce() throws Exception {
    Project project = greenfield("projcfg-false");
    Repository wrapper = wrapperOf(project);
    commitProjectYml(wrapper, "supports_environments: false\n");

    reconcileService.reconcile(project.id);

    assertFalse(storedFlagOf(project.id), "the wrapper is the project's configuration");
    var announced = announcer.changedOf(project.id);
    assertEquals(1, announced.size(), "one change, one frame");
    assertFalse(announced.get(0).supportsEnvironments());
    assertEquals(project.slug, announced.get(0).slug(), "the identity fields are restated");

    // And the second pass is a no-op: the frame says the value MOVED, not what it is.
    reconcileService.reconcile(project.id);
    assertEquals(1, announcer.changedOf(project.id).size());
  }

  /**
   * Removing the file restores the default, and that is the design rather than an accident: absence
   * and {@code true} are one answer, so a project opts out of the declaration the same way it opted
   * in.
   */
  @Test
  public void removingTheDeclarationRestoresTheDefaultAndAnnouncesThatToo() throws Exception {
    Project project = greenfield("projcfg-removed");
    Repository wrapper = wrapperOf(project);
    commitProjectYml(wrapper, "supports_environments: false\n");
    reconcileService.reconcile(project.id);
    assertFalse(storedFlagOf(project.id));

    removeProjectYml(wrapper);
    reconcileService.reconcile(project.id);

    assertTrue(storedFlagOf(project.id), "an absent file means the project supports environments");
    assertEquals(2, announcer.changedOf(project.id).size(), "it moved twice, so it was said twice");
  }

  /**
   * <b>The load-bearing negative.</b> A file that will not parse must not re-route the project: the
   * parser throws, the reconcile keeps what is stored, and the rest of the pass carries on. Reading
   * a typo as {@code true} would silently undo a declaration somebody made on purpose — which is
   * exactly what a "fail open" default would do here.
   */
  @Test
  public void aDeclarationThatWillNotParseLeavesTheStoredFlagAlone() throws Exception {
    Project project = greenfield("projcfg-broken");
    Repository wrapper = wrapperOf(project);
    commitProjectYml(wrapper, "supports_environments: false\n");
    reconcileService.reconcile(project.id);
    assertFalse(storedFlagOf(project.id));
    announcer.clear();

    commitProjectYml(wrapper, "supports_environments: 'nope'\n");
    var reconciliation = reconcileService.reconcile(project.id);

    assertFalse(storedFlagOf(project.id), "a typo may not flip a project back to the default");
    assertEquals(List.of(), announcer.changedOf(project.id), "nothing moved, so nothing is said");
    assertTrue(
        reconciliation != null && reconciliation.entries() != null,
        "and the repositories half of the reconcile is unaffected either way");
  }
}
