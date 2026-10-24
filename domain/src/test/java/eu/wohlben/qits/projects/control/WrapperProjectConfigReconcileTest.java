package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.gitmirror.PushOutcome;
import eu.wohlben.qits.projects.gitmirror.PushSpec;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.testsupport.RecordingFrontDeskLifecycleChanged;
import eu.wohlben.qits.projects.testsupport.RecordingProjectAnnouncer;
import eu.wohlben.qits.projects.testsupport.WrapperProjectYml;
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
  @Inject GitIdentity gitIdentity;
  @Inject RecordingProjectAnnouncer announcer;
  @Inject RecordingFrontDeskLifecycleChanged lifecycleListener;
  @Inject WrapperProjectYml projectYml;
  @Inject ReleaseRequests releaseRequests;
  @Inject jakarta.transaction.TransactionManager transactionManager;

  @BeforeEach
  void clean() {
    projectService.list().stream()
        .filter(p -> p.slug != null && p.slug.startsWith("projcfg"))
        .toList()
        .forEach(p -> projectService.delete(p.id));
    announcer.clear();
    lifecycleListener.clear();
  }

  private Project greenfield(String slug) {
    return projectService.create("Project Config " + slug, slug, null);
  }

  private Repository wrapperOf(Project project) {
    return projectService.findWrapper(project.id).orElseThrow();
  }

  /**
   * Commits {@code content} at {@code .config/qits/project.yml} on the wrapper's {@code main}, with
   * no working tree — {@code amendTree} + {@code commit-tree} + a real {@link RepoMirror#push} —
   * which is what every commit this service makes does, and what a reconcile's now-unconditional
   * {@code readFile} fetch (qits-1440) has to see: see {@link WrapperProjectYml}'s javadoc for why a
   * local {@code update-ref} is no longer enough.
   */
  private void commitProjectYml(Repository wrapper, String content) throws Exception {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    mirror.refreshNow();
    String tip = mirror.resolve("refs/heads/main").orElseThrow();
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
    PushOutcome outcome = mirror.push(PushSpec.of(PushSpec.Ref.branch(commit, "main")));
    if (!outcome.accepted()) {
      throw new IllegalStateException(
          "Could not push project.yml onto the wrapper's main: " + outcome.output());
    }
  }

  /** Removes the file entirely, so the reconcile sees a wrapper that declares nothing. */
  private void removeProjectYml(Repository wrapper) throws Exception {
    RepoMirror mirror = gitMirrors.of(wrapper.id);
    mirror.refreshNow();
    String tip = mirror.resolve("refs/heads/main").orElseThrow();
    String tree =
        mirror.amendTree(tip, List.of(), List.of(), List.of(ProjectConfigParser.CONFIG_PATH));
    String commit =
        mirror.commitTree(
            tree, List.of(tip), "Drop the project declaration", gitIdentity.asCommitIdentity());
    PushOutcome outcome = mirror.push(PushSpec.of(PushSpec.Ref.branch(commit, "main")));
    if (!outcome.accepted()) {
      throw new IllegalStateException(
          "Could not push project.yml onto the wrapper's main: " + outcome.output());
    }
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

  // -------------------------------------------------------------------------------------------
  // front_desk.lifecycle (qits-767)
  // -------------------------------------------------------------------------------------------

  private FrontDeskLifecycle storedLifecycleOf(String projectId) {
    // A transaction of its own: the re-read after a main move commits on another thread, and a read
    // outside any transaction would answer from this thread's request-scoped session.
    return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .call(() -> projectService.get(projectId).frontDeskLifecycle);
  }

  /** A new project's template declares nothing for the desk, so it is ON_DEMAND and stays silent. */
  @Test
  public void aNewProjectIsOnDemandAndTheReconcileCallsNothing() {
    Project project = greenfield("projcfg-desk-new");
    assertEquals(FrontDeskLifecycle.ON_DEMAND, storedLifecycleOf(project.id));

    reconcileService.reconcileProjectConfig(project.id);

    assertEquals(FrontDeskLifecycle.ON_DEMAND, storedLifecycleOf(project.id));
    assertEquals(List.of(), lifecycleListener.callsOf(project.id));
    assertEquals(List.of(), announcer.changedOf(project.id));
  }

  /**
   * ALWAYS_ON is stored, announced on {@code ProjectChanged} and handed to the port — once. The
   * second pass finds nothing moved and says nothing, to either.
   */
  @Test
  public void alwaysOnIsStoredAnnouncedAndHandedToThePortOnlyOnAChange() throws Exception {
    Project project = greenfield("projcfg-desk-on");
    projectYml.commit(wrapperOf(project), "front_desk:\n  lifecycle: ALWAYS_ON\n");

    reconcileService.reconcileProjectConfig(project.id);
    reconcileService.reconcileProjectConfig(project.id);

    assertEquals(FrontDeskLifecycle.ALWAYS_ON, storedLifecycleOf(project.id));
    assertEquals(
        List.of(new RecordingFrontDeskLifecycleChanged.Call(project.id, FrontDeskLifecycle.ALWAYS_ON)),
        lifecycleListener.callsOf(project.id));
    var announced = announcer.changedOf(project.id);
    assertEquals(1, announced.size(), "one change, one frame");
    assertEquals(FrontDeskLifecycle.ALWAYS_ON, announced.get(0).frontDeskLifecycle());
    assertTrue(announced.get(0).supportsEnvironments(), "the other fact is restated as it stands");

    // Back to ON_DEMAND moves it again, and is said again.
    projectYml.commit(wrapperOf(project), "front_desk:\n  lifecycle: ON_DEMAND\n");
    reconcileService.reconcileProjectConfig(project.id);
    assertEquals(FrontDeskLifecycle.ON_DEMAND, storedLifecycleOf(project.id));
    assertEquals(2, lifecycleListener.callsOf(project.id).size());
    assertEquals(2, announcer.changedOf(project.id).size());
  }

  /** Only the environment flag moving announces, and leaves the port alone. */
  @Test
  public void aChangeOfTheEnvironmentFlagAloneDoesNotCallThePort() throws Exception {
    Project project = greenfield("projcfg-desk-env");
    projectYml.commit(wrapperOf(project), "supports_environments: false\n");

    reconcileService.reconcileProjectConfig(project.id);

    assertEquals(1, announcer.changedOf(project.id).size());
    assertEquals(
        FrontDeskLifecycle.ON_DEMAND, announcer.changedOf(project.id).get(0).frontDeskLifecycle());
    assertEquals(List.of(), lifecycleListener.callsOf(project.id));
  }

  /** A typo keeps the stored lifecycle, exactly as it keeps the stored flag. */
  @Test
  public void aLifecycleThatWillNotParseLeavesTheStoredValueAlone() throws Exception {
    Project project = greenfield("projcfg-desk-typo");
    projectYml.commit(wrapperOf(project), "front_desk:\n  lifecycle: ALWAYS_ON\n");
    reconcileService.reconcileProjectConfig(project.id);
    lifecycleListener.clear();
    announcer.clear();

    projectYml.commit(wrapperOf(project), "front_desk:\n  lifecycle: always-on\n");
    reconcileService.reconcileProjectConfig(project.id);

    assertEquals(FrontDeskLifecycle.ALWAYS_ON, storedLifecycleOf(project.id));
    assertEquals(List.of(), lifecycleListener.callsOf(project.id));
    assertEquals(List.of(), announcer.changedOf(project.id));
  }

  /** Trigger 1: the wrapper's {@code main} moving re-reads the declaration. */
  @Test
  public void mainMovingOnTheWrapperReconcilesTheProjectConfig() throws Exception {
    Project project = greenfield("projcfg-desk-moved");
    Repository wrapper = wrapperOf(project);
    String sha = projectYml.commit(wrapper, "front_desk:\n  lifecycle: ALWAYS_ON\n");

    releaseRequests.onMainMoved(wrapper.id, sha);

    awaitLifecycle(project.id, FrontDeskLifecycle.ALWAYS_ON);
    assertEquals(1, lifecycleListener.callsOf(project.id).size());
  }

  /**
   * The head listener calls {@code onMainMoved} inside the event funnel's transaction. The re-read
   * must never join it: it runs on its own thread, and the caller's transaction stays committable.
   */
  @Test
  public void mainMovingInsideACallersTransactionNeverTouchesThatTransaction() throws Exception {
    Project project = greenfield("projcfg-desk-in-tx");
    Repository wrapper = wrapperOf(project);
    String sha = projectYml.commit(wrapper, "front_desk:\n  lifecycle: ALWAYS_ON\n");

    int status =
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  releaseRequests.onMainMoved(wrapper.id, sha);
                  return transactionManager.getStatus();
                });

    assertEquals(jakarta.transaction.Status.STATUS_ACTIVE, status);
    awaitLifecycle(project.id, FrontDeskLifecycle.ALWAYS_ON);
  }

  private void awaitLifecycle(String projectId, FrontDeskLifecycle expected) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    while (storedLifecycleOf(projectId) != expected && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(expected, storedLifecycleOf(projectId));
  }

  /** ... and any other repository's {@code main} moving does not, even of the same project. */
  @Test
  public void mainMovingOnARepositoryThatIsNotAWrapperDoesNot() throws Exception {
    Project project = greenfield("projcfg-desk-other");
    Repository wrapper = wrapperOf(project);
    String sha = projectYml.commit(wrapper, "front_desk:\n  lifecycle: ALWAYS_ON\n");
    Repository component =
        projectService
            .createRepository(project.id, null, "projcfg-desk-javalib", RepositoryArchetype.LIBRARY)
            .repository();
    assertTrue(component.archetype != RepositoryArchetype.PROJECT);

    releaseRequests.onMainMoved(component.id, sha);
    releaseRequests.onMainMoved("no-such-repository", sha);
    Thread.sleep(1_000); // the re-read is asynchronous: give a wrong one time to happen

    assertEquals(FrontDeskLifecycle.ON_DEMAND, storedLifecycleOf(project.id));
    assertEquals(List.of(), lifecycleListener.callsOf(project.id));
  }

  // -------------------------------------------------------------------------------------------
  // front_desk.delivery (epic qits-1440)
  // -------------------------------------------------------------------------------------------

  private ProjectConfig.DeliverySettings storedDeliveryOf(String projectId) {
    return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .call(() -> ProjectConfig.DeliverySettings.of(projectService.get(projectId)));
  }

  /** A new project's loop is off; a pass that finds it so calls nothing. */
  @Test
  public void aNewProjectsDeliveryLoopIsOffAndTheReconcileCallsNothing() {
    Project project = greenfield("projcfg-delivery-new");
    assertEquals(ProjectConfig.DeliverySettings.OFF, storedDeliveryOf(project.id));

    reconcileService.reconcileProjectConfig(project.id);

    assertEquals(ProjectConfig.DeliverySettings.OFF, storedDeliveryOf(project.id));
    assertEquals(List.of(), lifecycleListener.deliveryCallsOf(project.id));
  }

  /**
   * The reconcile applies every delivery value, hands the port the settings once per change, and
   * announces nothing for it — {@code ProjectChanged} carries no delivery fact. Disabling is a
   * change too.
   */
  @Test
  public void theDeliverySettingsAreStoredAndHandedToThePortOnlyOnAChange() throws Exception {
    Project project = greenfield("projcfg-delivery-on");
    projectYml.commit(
        wrapperOf(project),
        "front_desk:\n  delivery:\n    enabled: true\n    max_running: 3\n"
            + "    start_refined: true\n    approves: [manual-review]\n    interval: PT5M\n");

    reconcileService.reconcileProjectConfig(project.id);
    reconcileService.reconcileProjectConfig(project.id);

    ProjectConfig.DeliverySettings expected =
        new ProjectConfig.DeliverySettings(
            true, 3, true, java.util.Set.of("manual-review"), java.time.Duration.ofMinutes(5));
    assertEquals(expected, storedDeliveryOf(project.id));
    Project row =
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> projectService.get(project.id));
    assertEquals("manual-review", row.deliveryApproves);
    assertEquals("PT5M", row.deliveryInterval);
    assertEquals(
        List.of(new RecordingFrontDeskLifecycleChanged.DeliveryCall(project.id, expected)),
        lifecycleListener.deliveryCallsOf(project.id));
    assertEquals(List.of(), lifecycleListener.callsOf(project.id), "the lifecycle did not move");
    assertEquals(List.of(), announcer.changedOf(project.id), "ProjectChanged carries no delivery fact");

    projectYml.commit(wrapperOf(project), "front_desk:\n  delivery:\n    enabled: false\n");
    reconcileService.reconcileProjectConfig(project.id);

    assertEquals(ProjectConfig.DeliverySettings.OFF, storedDeliveryOf(project.id));
    assertEquals(2, lifecycleListener.deliveryCallsOf(project.id).size());
    assertFalse(lifecycleListener.deliveryCallsOf(project.id).get(1).delivery().enabled());
  }

  /** A malformed delivery keeps every stored value, the delivery settings included. */
  @Test
  public void aDeliveryThatWillNotParseLeavesTheStoredSettingsAlone() throws Exception {
    Project project = greenfield("projcfg-delivery-typo");
    projectYml.commit(wrapperOf(project), "front_desk:\n  delivery:\n    enabled: true\n");
    reconcileService.reconcileProjectConfig(project.id);
    lifecycleListener.clear();

    projectYml.commit(
        wrapperOf(project),
        "front_desk:\n  delivery:\n    enabled: false\n    approves: [recorded]\n");
    reconcileService.reconcileProjectConfig(project.id);

    assertTrue(storedDeliveryOf(project.id).enabled(), "a typo may not switch the loop off");
    assertEquals(List.of(), lifecycleListener.deliveryCallsOf(project.id));
  }

  // -------------------------------------------------------------------------------------------
  // release_requests.feature_freeze (epic qits-1580)
  // -------------------------------------------------------------------------------------------

  private int storedFeatureFreezeOf(String projectId) {
    return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .call(() -> projectService.get(projectId).releaseFeatureFreeze);
  }

  /**
   * A new project's freeze is three; a stated one is stored and announces nothing; a malformed file
   * keeps it; removing the key restores three.
   */
  @Test
  public void theFeatureFreezeIsStoredAndAMalformedFileKeepsIt() throws Exception {
    Project project = greenfield("projcfg-freeze");
    assertEquals(3, storedFeatureFreezeOf(project.id));

    projectYml.commit(wrapperOf(project), "release_requests:\n  feature_freeze: 5\n");
    reconcileService.reconcileProjectConfig(project.id);

    assertEquals(5, storedFeatureFreezeOf(project.id));
    assertEquals(List.of(), announcer.changedOf(project.id), "ProjectChanged carries no freeze");
    assertEquals(List.of(), lifecycleListener.deliveryCallsOf(project.id));

    projectYml.commit(wrapperOf(project), "release_requests:\n  feature_freeze: 0\n");
    reconcileService.reconcileProjectConfig(project.id);
    assertEquals(5, storedFeatureFreezeOf(project.id), "a typo may not move the freeze");

    projectYml.commit(wrapperOf(project), "supports_environments: true\n");
    reconcileService.reconcileProjectConfig(project.id);
    assertEquals(3, storedFeatureFreezeOf(project.id));
  }
}
