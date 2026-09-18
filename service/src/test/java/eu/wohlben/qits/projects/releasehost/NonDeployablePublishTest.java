package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The non-deployable shortcut: a repository that deploys nothing reaches {@code main} at its own
 * release.</b>
 *
 * <p>Two claims, and the negative one is the older: <b>a repository that declares a deployment is
 * left entirely alone</b>, because merging at the release would put the commit on {@code main}
 * before the deployment that justifies it, which is the ordering this epic exists to fix. The only
 * thing standing between the two behaviours is one file's presence in the released tree.
 *
 * <p>The newer claim is <b>where the fork hangs</b> (2026-09-04). It used to hang off qits-ci's
 * {@code SoftwareRelease} — an event only a repository carrying its own {@code
 * ci-event-release.yml} recipe ever emitted, back when those files existed — so every recipe-less
 * repository, every SPA among them, released tags that never reached {@code main} at all. It hangs
 * off this service's own release now, which is a fact it always has, and the catch-up sweep is what
 * heals everything stranded in the meantime.
 */
@QuarkusTest
public class NonDeployablePublishTest {

  @Inject ReleaseFinalization finalization;

  @Inject eu.wohlben.qits.projects.bus.DeploymentActiveListener deployments;

  @Inject eu.wohlben.qits.projects.bus.BuildStatusListener verdicts;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject FakePublishRuns publishRuns;

  @Inject RecordingReleaseExecutor executor;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    publishRuns.reset();
    activeBuilds.answer(Optional.of(1));
    repoId = "publish-lib-repo-" + UUID.randomUUID();
    projectId = "publish-lib-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "publish-lib";
              project.slug = "publish-lib-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /**
   * Nothing of this fixture may outlive the class: the finalization sweep walks every ungated and
   * every owed row in the database, so a row left behind is a git-host call inside somebody else's
   * test.
   */
  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  // -----------------------------------------------------------------------------------------------
  // The publish gate (ticket b27384a3)
  // -----------------------------------------------------------------------------------------------

  /**
   * <b>A release whose tree declares a release pipeline is not finished when the tag is cut.</b> The
   * publish run has to go green first — which is the whole ticket: this repository used to be
   * finalized at the tag, so a publish run that failed afterwards had nothing holding it open.
   */
  @Test
  public void aReleaseWithAPublishPipelineWaitsForItsRunAndThenReachesMain() {
    String tag = freshTag();
    String releasedSha = pendingTag(tag);
    publishGatedTag(tag);

    finalization.onReleased(repoId, tag);

    assertEquals(
        List.of(),
        merger.foldsOf("refs/heads/main"),
        "the tag is cut and nothing else is: the release run has not reported");
    assertEquals(
        ReleasedTagPendingMerge.PublishState.PENDING,
        rowOf(tag).publishState,
        "and the gate is visible on the row rather than implied by an absence");

    publishVerdict("BuildSuccessful", tag, releasedSha, "run-green");

    assertEquals(ReleasedTagPendingMerge.PublishState.PASSED, rowOf(tag).publishState);
    assertEquals(List.of(releasedSha), merger.foldsOf("refs/heads/main").get(0).sources());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * <b>A red publish run is a failed gate on an open release, never a state the request leaves.</b>
   * The tag is cut and cannot be un-cut, so what a failure buys is a request that goes on saying so
   * — with the run named, because {@code qits ci retry} is addressed by run — and a retry whose
   * green verdict finalizes exactly as a first-time green one would.
   */
  @Test
  public void aRedPublishRunLeavesTheReleaseOpenAndTheRetryFinalizesIt() {
    String tag = freshTag();
    pendingTag(tag);
    publishGatedTag(tag);
    finalization.onReleased(repoId, tag);

    publishVerdict("BuildFailed", tag, "sha-red", "run-red");

    ReleasedTagPendingMerge red = rowOf(tag);
    assertEquals(ReleasedTagPendingMerge.PublishState.FAILED, red.publishState);
    assertEquals("run-red", red.publishRunId, "the run to retry is named, not described");
    assertTrue(red.publishDetail.contains("run-red"), red.publishDetail);
    assertNull(red.mergedAt, "nothing reaches main on a red gate");
    assertNull(red.mergeRequestedAt, "and nothing is owed either");

    // The sweep must not talk itself into finalizing a release whose gate is red.
    finalization.sweep();
    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));

    publishVerdict("BuildSuccessful", tag, "sha-green", "run-retry");

    assertEquals(ReleasedTagPendingMerge.PublishState.PASSED, rowOf(tag).publishState);
    assertNotNull(rowOf(tag).mergedAt, "the retry is what finishes it");
  }

  /**
   * <b>A release that both publishes and deploys waits for both</b>, and the two arrive in whichever
   * order they arrive: each keeps its own fact on the row, so neither can be forgotten by the other
   * having been first.
   */
  @Test
  public void aDeployableReleaseWithAPipelineWaitsForBothGatesInEitherOrder() {
    String first = freshTag();
    pendingTag(first);
    publishGatedTag(first, ".config/qits/deployments.yml");
    finalization.onReleased(repoId, first);

    publishVerdict("BuildSuccessful", first, "sha-1", "run-1");
    assertEquals(
        List.of(), merger.foldsOf("refs/heads/main"), "green, and the deployment has not happened");
    deploymentActive("qits-thing", first);
    assertNotNull(rowOf(first).mergedAt, "both gates passed, so the tag is owed main and lands");

    String second = freshTag();
    pendingTag(second);
    publishGatedTag(second, ".config/qits/deployments.yml");
    finalization.onReleased(repoId, second);

    deploymentActive("qits-thing", second);
    assertNull(rowOf(second).mergedAt, "deployed, and the publish run has not reported");
    assertNotNull(rowOf(second).deploymentActiveAt, "but the deployment is remembered for later");
    publishVerdict("BuildSuccessful", second, "sha-2", "run-2");
    assertNotNull(rowOf(second).mergedAt, "the other order finishes the same way");
  }

  /**
   * <b>The one arm there is</b>: qits-ci composes the pipelines from the archetype {@code
   * release.yml} names, and where the composition has a {@code release:} slot it says so and the
   * gate applies. This was "the migrated repository's arm" while a second one read a repository's
   * own pipeline file out of the tree; that file left the estate and the arm went with it, so the
   * question is asked of every released tag that declares anything at all.
   */
  @Test
  public void aReleaseQitsCiRunsAReleaseForIsPublishGatedToo() {
    String tag = freshTag();
    pendingTag(tag);
    releaseYamlAtTag(tag);
    publishRuns.answer(Optional.of(true));

    finalization.onReleased(repoId, tag);

    assertEquals(
        ReleasedTagPendingMerge.PublishState.PENDING,
        rowOf(tag).publishState,
        "a composed pipeline is a pipeline");
    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertEquals(
        List.of(new FakePublishRuns.Asked(repoId, "refs/tags/" + tag)),
        publishRuns.asked(),
        "and the question is asked about the released TAG, not about main");
  }

  /**
   * <b>The defect this port exists to close.</b> A {@code release.yml} naming an archetype with no
   * {@code release:} slot — {@code spa-frontend}, {@code cli} — composes no release run, so nothing
   * would ever answer a publish gate stamped for it. This service cannot see that from the file (the
   * archetype is in the wrapper and the slot can be overridden wholesale), so qits-ci is asked, and
   * "no" means there is no gate here and the tag is finalized.
   */
  @Test
  public void aReleaseQitsCiRunsNoReleaseForIsNotPublishGatedAtAll() {
    String tag = freshTag();
    String releasedSha = pendingTag(tag);
    releaseYamlAtTag(tag);
    publishRuns.answer(Optional.of(false));

    finalization.onReleased(repoId, tag);

    assertNull(rowOf(tag).publishState, "no gate, deliberately not a PENDING one");
    assertEquals(List.of(releasedSha), merger.foldsOf("refs/heads/main").get(0).sources());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * <b>And it heals the rows the old reading stranded, with no database surgery.</b> A release
   * stamped PENDING while "names an archetype" was the predicate reports a pending PUBLISH gate for
   * ever, because {@code gateReport} reads the gate's very existence off that column. qits-ci
   * answering "no release run" is what makes the stamp wrong, so the stamp goes.
   */
  @Test
  public void aStalePendingStampIsClearedWhenQitsCiSaysThereIsNoReleaseRun() {
    String tag = freshTag();
    pendingTag(tag);
    releaseYamlAtTag(tag);
    publishRuns.answer(Optional.of(true));
    finalization.onReleased(repoId, tag);
    assertEquals(ReleasedTagPendingMerge.PublishState.PENDING, rowOf(tag).publishState);

    // The predicate is qits-ci's, so the answer changing is all it takes.
    publishRuns.answer(Optional.of(false));
    finalization.sweep();

    ReleasedTagPendingMerge healed = rowOf(tag);
    assertNull(healed.publishState, "the column is the gate's own record, and there is no gate");
    assertNull(healed.publishDetail, "and no sentence about a run nobody is waiting for");
    assertNotNull(healed.mergedAt, "so the release finally reaches main");
  }

  /**
   * <b>"Could not ask" is not "no release run".</b> An unconfigured, unreachable or 503-answering
   * qits-ci leaves the released tag exactly as unreadable as a git host that could not list the tree
   * — nothing is gated, nothing is merged, and the sweep is what asks again. Reading the silence as
   * "this publishes nothing" would put a commit on {@code main} whose publish was never checked.
   */
  @Test
  public void aQitsCiThatCannotBeAskedMergesNothingAndTheSweepRetries() {
    String tag = freshTag();
    pendingTag(tag);
    releaseYamlAtTag(tag);
    publishRuns.answer(Optional.empty());

    finalization.onReleased(repoId, tag);
    finalization.sweep();

    assertNull(rowOf(tag).publishState, "not gated: nothing is known about this release yet");
    assertNull(rowOf(tag).mergeRequestedAt, "and nothing is owed main");
    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));

    publishRuns.answer(Optional.of(false));
    finalization.sweep();

    assertNotNull(rowOf(tag).mergedAt, "the sweep asked again and qits-ci answered");
  }

  /**
   * <b>A released tree declaring no release configuration asks qits-ci nothing</b>, which is the
   * negative the port's own cost depends on: the question is one HTTP call per gate evaluation of a
   * released tag, and a repository that declares nothing must not pay it.
   *
   * <p>This class used to assert the same negative about a different tree — one carrying the
   * repository's <em>own</em> {@code .config/qits/ci-event-release.yml}, whose presence was the
   * whole answer and short-circuited the port. That arm went on 2026-09-18 with the last of those
   * files: a tag carrying one now takes exactly this path instead, declares no {@code release.yml}
   * and is not publish-gated. The claim that survived is the one worth keeping — <b>an unasked
   * question</b> — and the arm below is where it is now made.
   */
  @Test
  public void aTreeDeclaringNoReleaseConfigurationAsksQitsCiNothing() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml", "README.md");

    finalization.onReleased(repoId, tag);

    assertNull(rowOf(tag).publishState, "nothing composes a release run, so there is no gate");
    assertEquals(List.of(), publishRuns.asked(), "and nobody was asked about it");
  }

  // -----------------------------------------------------------------------------------------------
  // A gate that never answers (requirement: make the stall audible, never pass it)
  // -----------------------------------------------------------------------------------------------

  /**
   * <b>A publish gate that has been PENDING longer than the patience says so on the row</b>, where
   * the release-request read already looks — and it still merges nothing, because a run that is
   * merely slow must keep {@code main} waiting.
   */
  @Test
  public void aPublishGatePendingTooLongSaysHowLongItHasWaited() {
    String tag = freshTag();
    pendingTag(tag);
    releaseYamlAtTag(tag);
    publishRuns.answer(Optional.of(true));
    finalization.onReleased(repoId, tag);
    releasedAgo(tag, Duration.ofHours(3));

    finalization.sweep();

    ReleasedTagPendingMerge stuck = rowOf(tag);
    assertEquals(ReleasedTagPendingMerge.PublishState.PENDING, stuck.publishState);
    assertTrue(stuck.publishDetail.contains("3h"), stuck.publishDetail);
    assertTrue(stuck.publishDetail.contains(tag), stuck.publishDetail);
    assertEquals(List.of(), merger.foldsOf("refs/heads/main"), "and no timeout passes the gate");
  }

  /** And it says nothing while the run is still within the window it is allowed. */
  @Test
  public void aPublishGatePendingForAMomentSaysNothingYet() {
    String tag = freshTag();
    pendingTag(tag);
    releaseYamlAtTag(tag);
    publishRuns.answer(Optional.of(true));

    finalization.onReleased(repoId, tag);
    finalization.sweep();

    assertEquals(
        "Waiting for the release run of " + tag + " to finish",
        rowOf(tag).publishDetail,
        "the ordinary sentence, with nothing alarming in it");
  }

  @Test
  public void aRepositoryThatDeclaresNoDeploymentReachesMainAtItsRelease() {
    String tag = freshTag();
    String releasedSha = pendingTag(tag);
    treeAtTag(tag, "pom.xml", "README.md");

    finalization.onReleased(repoId, tag);

    List<RecordingBackingBranchMerger.Fold> intoMain = merger.foldsOf("refs/heads/main");
    assertEquals(1, intoMain.size(), "nothing deploys this, so nothing else will ever gate it");
    assertEquals(List.of(releasedSha), intoMain.get(0).sources());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * The SPA case, which is the one that was broken: a frontend repository publishes no artifact
   * event at all, so the old {@code SoftwareRelease} gate never fired for it and its tag sat off
   * {@code main} for ever. It declares no deployment either, so it takes exactly the arm above.
   */
  @Test
  public void aRepositoryThatPublishesNoArtifactEventIsFinalizedAllTheSame() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "package.json", "angular.json", "src/main.ts");

    finalization.onReleased(repoId, tag);

    assertEquals(1, merger.foldsOf("refs/heads/main").size());
    assertNotNull(rowOf(tag).mergedAt, "a released SPA tag must not sit off main for ever");
  }

  @Test
  public void aRepositoryThatDeclaresADeploymentIsLeftToItsDeployment() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml", ".config/qits/deployments.yml");

    finalization.onReleased(repoId, tag);

    assertEquals(
        List.of(),
        merger.foldsOf("refs/heads/main"),
        "a release is not the same statement as what it released serving");
    assertNull(
        rowOf(tag).mergeRequestedAt, "and the tag is not even gated: the deployment gates it");

    // And then the deployment arrives, which is the gate this release was always waiting for.
    deploymentActive("qits-thing", tag);

    assertEquals(1, merger.foldsOf("refs/heads/main").size());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * The fork is made once. A second telling — the release path and the catch-up racing, a replayed
   * anything — must not even ask the git host, which is what the scripted tree failure proves: a
   * read that happened would answer "retryable" and nothing would merge.
   */
  @Test
  public void theSecondTellingOfOneReleaseAsksTheGitHostNothing() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml");

    finalization.onReleased(repoId, tag);
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("nobody may ask a second time"));

    assertDoesNotThrow(() -> finalization.onReleased(repoId, tag));
    finalization.sweep();

    assertEquals(1, merger.foldsOf("refs/heads/main").size(), "one release, one merge to main");
  }

  /**
   * <b>The catch-up.</b> A git host that could not say whether the repository deploys leaves the tag
   * ungated rather than guessing — and the release, which has already happened, is never failed by
   * it. The sweep is what asks again, and it is the same sweep that heals every tag stranded by the
   * gate this fork replaced.
   */
  @Test
  public void aGitHostThatCannotAnswerLeavesTheTagUngatedAndTheCatchUpHealsIt() {
    String tag = freshTag();
    pendingTag(tag);
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("qits-githost answered 503"));

    assertDoesNotThrow(
        () -> finalization.onReleased(repoId, tag),
        "a tag exists by now; nothing after it may fail the release that made it");

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);

    gitHost.reset();
    treeAtTag(tag, "pom.xml");
    finalization.sweep();

    assertNotNull(rowOf(tag).mergedAt, "the catch-up asked again and it landed");
  }

  /**
   * A released tag whose fork never ran at all — the process died between the two, or the release
   * predates the fork living here — is exactly what the catch-up is for, and it needs no event and
   * no operator.
   */
  @Test
  public void theCatchUpFinalizesAReleaseNothingEverForkedOn() {
    String tag = freshTag();
    String releasedSha = pendingTag(tag);
    treeAtTag(tag, "package.json");

    finalization.sweep();

    assertEquals(List.of(releasedSha), merger.foldsOf("refs/heads/main").get(0).sources());
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * And the catch-up's own negative: a deployable release still waiting on its deployment is looked
   * at on every sweep and left exactly where it is. Merging it would be this class advancing {@code
   * main} on no gate at all.
   */
  @Test
  public void theCatchUpLeavesADeployableTagWaitingForItsDeployment() {
    String tag = freshTag();
    pendingTag(tag);
    treeAtTag(tag, "pom.xml", ".config/qits/deployments.yml");

    finalization.sweep();
    finalization.sweep();

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);
    assertNull(rowOf(tag).mergedAt);
  }

  /**
   * A refusal that is not about the moment — a tag this git host does not know — finalizes nothing
   * either: the released tag stays visibly unfinished for a deployment, a later sweep or a person to
   * complete, and is never read as "this repository deploys nothing".
   */
  @Test
  public void aTagTheGitHostDoesNotKnowSettlesWithoutFinalizingAnything() {
    String tag = freshTag();
    pendingTag(tag);

    assertDoesNotThrow(() -> finalization.onReleased(repoId, tag));

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergeRequestedAt);
    assertNull(rowOf(tag).mergedAt);
  }

  @Test
  public void aVersionThisServiceNeverReleasedAsksTheGitHostNothing() {
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("nobody should be asking"));

    assertDoesNotThrow(() -> finalization.onReleased(repoId, "2026.101.10101"));

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private static String freshTag() {
    return "2026.903." + (100000 + (int) (Math.random() * 800000));
  }

  /** A released tag of the fixture repository, in flight. Answers the sha it points at. */
  private String pendingTag(String tag) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleasedTagPendingMerge row = new ReleasedTagPendingMerge();
              row.id = UUID.randomUUID().toString();
              row.repoId = repoId;
              row.tagName = tag;
              row.releasedSha = RecordingBackingBranchMerger.freshSha();
              row.releasedAt = Instant.now();
              row.persist();
              return row.releasedSha;
            });
  }

  /**
   * A released tree carrying a repository's release declaration. <b>The content is irrelevant on
   * purpose</b>: nothing here reads the file any more, because whether the archetype it names
   * composes a release run is qits-ci's answer and not this service's.
   */
  private void releaseYamlAtTag(String tag, String... alsoDeclares) {
    Map<String, String> tree = new LinkedHashMap<>();
    tree.put("pom.xml", "irrelevant");
    tree.put(".config/qits/release.yml", "archetype: spa-frontend\n");
    for (String path : alsoDeclares) {
      tree.put(path, "irrelevant");
    }
    gitHost.tree("refs/tags/" + tag, tree);
  }

  /**
   * <b>A release that really is publish-gated</b>, which now takes two staged facts rather than one
   * path in a tree: the declaration, and qits-ci saying it composes a release run for it.
   *
   * <p>Until 2026-09-18 a tree carrying {@code .config/qits/ci-event-release.yml} was the whole of
   * it — presence decided the gate and the port was never asked — and the tests below that are about
   * what a publish-gated release <em>does</em> staged exactly that. They assert the same things
   * through the one path that is left, because the gate they are about never changed; what changed
   * is only which fact turns it on.
   */
  private void publishGatedTag(String tag, String... alsoDeclares) {
    releaseYamlAtTag(tag, alsoDeclares);
    publishRuns.answer(Optional.of(true));
  }

  /** Age the released tag, which is the only clock the PENDING-too-long signal has. */
  private void releasedAgo(String tag, Duration ago) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleasedTagPendingMerge.update(
                    "releasedAt = ?1 where repoId = ?2 and tagName = ?3",
                    Instant.now().minus(ago),
                    repoId,
                    tag));
  }

  /** The released tree, as the git host would list it — the paths are the whole of what is read. */
  private void treeAtTag(String tag, String... paths) {
    Map<String, String> tree = new LinkedHashMap<>();
    for (String path : paths) {
      tree.put(path, "irrelevant");
    }
    gitHost.tree("refs/tags/" + tag, tree);
  }

  private ReleasedTagPendingMerge rowOf(String tag) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find(
                        "repoId = ?1 and tagName = ?2", repoId, tag)
                    .firstResult());
  }

  /**
   * A verdict for the tag's own release run, through the listener that hears every verdict — so what
   * is under test includes the correlation this platform actually makes: <b>a publish run's branch
   * is the version</b>, and nothing else about the event says it is a publish run at all.
   */
  private void publishVerdict(String name, String tag, String sha, String runId) {
    verdicts.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"runId\":\""
                + runId
                + "\",\"repoId\":\""
                + repoId
                + "\",\"branch\":\""
                + tag
                + "\",\"commitSha\":\""
                + sha
                + "\"}",
            null,
            null,
            null));
  }

  private void deploymentActive(String application, String version) {
    deployments.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\""
                + application
                + "\",\"environmentName\":\"dev\",\"version\":\""
                + version
                + "\"}",
            null,
            null,
            null));
  }
}
