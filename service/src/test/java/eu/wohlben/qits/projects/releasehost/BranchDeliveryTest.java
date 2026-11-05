package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.DeploymentActiveListener;
import eu.wohlben.qits.projects.bus.ReleasePipelineRunListener;
import eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener;
import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.BranchDeliveryStep;
import eu.wohlben.qits.projects.control.FoldAttempt;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code BranchDeliveryChanged} and its ledger (qits-1719): every step a named branch takes on its
 * way to production, announced once per position after the transaction that took it, and the
 * branch's {@code everywhere} across the repositories of its project.
 *
 * <p>One flow per test, each in the shape of the class that owns that flow: the freeze queue as
 * {@link ReleaseFeatureFreezeTest} drives it, the red verdicts as {@link
 * ReleaseRequestStateChangedTest}, the failed automation as {@link AutomationGateTest}, the hand-on
 * as {@link AutoReleaseTest}, the supersession as {@link ReleaseSupersessionTest} and the
 * finalization as {@link MainFinalizationTest}. Every recorded step also carries the step a fresh
 * transaction read off the ledger when it was announced ({@link RecordingBranchDeliveryAnnouncer}),
 * so each test also proves the row committed before it was told.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class BranchDeliveryTest {

  private static final String SCREENSHOTS = "screenshot-baselines";

  @Inject ReleaseRequests releaseRequests;

  @Inject WorkEntityService entities;

  @Inject eu.wohlben.qits.entities.control.EntityCommentService comments;

  @Inject eu.wohlben.qits.projects.entitieshost.TicketWrapperDone wrapperDone;

  @Inject eu.wohlben.qits.entities.persistence.WorkEntityRepository workRows;

  @Inject BuildStatusListener buildListener;

  @Inject ReleaseRequestHeadListener headListener;

  @Inject DeploymentActiveListener deployments;

  @Inject ReleaseFinalization finalization;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject AutomationLedger automationLedger;

  @Inject eu.wohlben.qits.projects.deploymenthost.FakeDeploymentRequests deploymentRequests;

  @Inject RecordingBranchDeliveryAnnouncer announced;

  @Inject ReleasePipelineRunListener pipelineRunListener;

  @Inject FakePublishRuns publishRuns;

  @Inject eu.wohlben.qits.projects.deploymenthost.RecordingDeploymentRedeploys redeploys;

  private String projectId;
  private String repoA;
  private String repoB;
  private String nameA;
  private String nameB;

  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    automations.reset();
    deploymentRequests.reset();
    announced.reset();
    publishRuns.reset();
    redeploys.reset();
    requestIds.clear();
    // Held: nothing settles a request mid-test unless a test lets it.
    activeBuilds.answer(Optional.of(1));
    String unique = UUID.randomUUID().toString().substring(0, 8);
    projectId = "deliver-" + unique;
    repoA = "deliver-a-" + UUID.randomUUID();
    repoB = "deliver-b-" + UUID.randomUUID();
    nameA = "deliver-a-" + unique;
    nameB = "deliver-b-" + unique;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
              alias(project, repository(project, repoA), nameA);
              alias(project, repository(project, repoB), nameB);
            });
  }

  private static Repository repository(Project project, String repoId) {
    Repository repository = new Repository();
    repository.id = repoId;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = RepositoryArchetype.SERVICE;
    repository.persist();
    return repository;
  }

  private static void alias(Project project, Repository repository, String name) {
    RepositoryName row = new RepositoryName();
    row.project = project;
    row.repository = repository;
    row.name = name;
    row.persist();
  }

  /** The sweeps walk the whole database, so nothing of this fixture may outlive the test. */
  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId in ?1", List.of(repoA, repoB));
              BranchDelivery.delete("projectId = ?1", projectId);
            });
    requestIds.forEach(automationLedger::forget);
    automations.reset();
    deploymentRequests.reset();
    publishRuns.reset();
    redeploys.reset();
  }

  // ---- joining ---------------------------------------------------------------------------------

  @Test
  public void aCreateAndAJoinAreEachJoinedOnceAndMainIsNeverFollowed() {
    String branch = ticketBranch();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);

    assertEquals(List.of("JOINED@" + qid), announced.movesOf(branch));
    assertEquals(List.of(), announced.movesOf("main"), "the default branch is no delivery");
    BranchDeliveryStep joined = announced.stepsOf(branch).get(0);
    assertEquals(projectId, joined.projectId());
    assertEquals(repoA, joined.repoId());
    assertEquals(nameA, joined.repository());
    assertEquals(id, joined.requestId());
    assertEquals("JOINED", joined.everywhere());
    assertEquals("JOINED", announced.recorded().get(0).committedStep(), "told after the commit");

    assertEquals(id, create(repoA, branch), "the re-ask answers where the branch is");
    assertEquals(List.of("JOINED@" + qid), announced.movesOf(branch), "and is no second JOINED");

    String second = ticketBranch();
    releaseRequests.join(id, second, "alice", null);
    assertEquals(List.of("JOINED@" + qid), announced.movesOf(second));
  }

  /** {@link ReleaseFeatureFreezeTest}'s fourth feature: JOINED on the queue it landed in. */
  @Test
  public void aFourthFeaturePastTheFreezeIsJoinedOnTheQueuedRequest() {
    String active = ask(feature()).request().id();
    ask(feature());
    ask(feature());
    String fourth = feature();

    ReleaseRequests.Asked queued = ask(fourth);

    assertTrue(queued.placement().queued(), "the premise: it queued");
    assertNotEquals(active, queued.request().id());
    assertEquals(List.of("JOINED@" + queued.request().qualifiedId()), announced.movesOf(fourth));
    assertNull(announced.stepsOf(fourth).get(0).foldSha(), "a queued request has no fold");
  }

  // ---- failing ---------------------------------------------------------------------------------

  @Test
  public void aRedBuildFailsTheBranchOncePerFold() {
    String branch = ticketBranch();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    String fold = mergedShaOf(repoA, id);
    String red = run("red");

    verdict("BuildFailed", repoA, fold, red, null);
    awaitMoves(branch, List.of("JOINED@" + qid, "FAILED@" + qid));
    BranchDeliveryStep failed = announced.stepsOf(branch).get(1);
    assertEquals("CI", failed.failure());
    assertEquals(red, failed.runId());
    assertEquals(fold, failed.foldSha());
    assertEquals("JOINED", failed.everywhere(), "a failed fold is still on a request");

    // A retry of that run at the same fold reopens the request (JOINED again, qits-1719) and comes
    // back red: FAILED again at the same fold, as a fresh position after the reopen.
    String redAgain = run("red-again");
    verdict("BuildFailed", repoA, fold, redAgain, red);
    List<String> retried =
        List.of("JOINED@" + qid, "FAILED@" + qid, "JOINED@" + qid, "FAILED@" + qid);
    awaitMoves(branch, retried);
    assertEquals("REJECTED", stateOf(repoA, id));
    assertEquals(fold, announced.stepsOf(branch).get(2).foldSha());
    assertEquals(redAgain, last(branch).runId());
    pause(300);
    assertEquals(retried, announced.movesOf(branch), "and nothing more");

    // A push folds anew: JOINED at the new fold, and that fold's red verdict is news.
    headMoved(repoA, branch, sha());
    String refold = mergedShaOf(repoA, id);
    assertNotEquals(fold, refold);
    verdict("BuildFailed", repoA, refold, run("red-refold"), null);
    List<String> refolded = new ArrayList<>(retried);
    refolded.addAll(List.of("JOINED@" + qid, "FAILED@" + qid));
    awaitMoves(branch, refolded);
    assertEquals(refold, announced.stepsOf(branch).get(4).foldSha());
    assertEquals(refold, last(branch).foldSha());
  }

  // ---- recovering (qits-1719) -------------------------------------------------------------------

  /** Red CI, then a push refolds: the branch is JOINED again at the new fold, door and ledger. */
  @Test
  public void aRefoldAfterARedBuildJoinsTheBranchAgainAtTheNewFold() {
    String branch = ticketBranch();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    String fold = mergedShaOf(repoA, id);
    verdict("BuildFailed", repoA, fold, run("red"), null);
    awaitMoves(branch, List.of("JOINED@" + qid, "FAILED@" + qid));
    assertEquals("FAILED", ledgerStep(repoA, branch));

    headMoved(repoA, branch, sha());

    String refold = mergedShaOf(repoA, id);
    assertNotEquals(fold, refold);
    assertEquals("PENDING", stateOf(repoA, id));
    awaitMoves(branch, List.of("JOINED@" + qid, "FAILED@" + qid, "JOINED@" + qid));
    BranchDeliveryStep joined = last(branch);
    assertEquals(refold, joined.foldSha(), "JOINED at the fold the request is at now");
    assertEquals(id, joined.requestId());
    assertNull(joined.failure());
    assertNull(joined.runId());
    assertEquals("JOINED", joined.everywhere());
    assertEquals("JOINED", ledgerStep(repoA, branch));
    io.restassured.path.json.JsonPath door =
        given()
            .queryParam("branch", branch)
            .get("/projects/api/branch-deliveries")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals("JOINED", door.getString("everywhere"));
    assertEquals(List.of("JOINED"), door.getList("deliveries.step"));
    assertEquals(refold, door.getString("deliveries[0].foldSha"));
  }

  /** Red, then a retry going green at the same fold: READY, and JOINED again at that fold. */
  @Test
  public void aRetryGoingGreenAtTheSameFoldJoinsTheBranchAgain() throws InterruptedException {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    stageRelease();
    RecordingReleaseExecutor.Hold held = executor.holdNextRelease();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    String fold = mergedShaOf(repoA, id);
    String red = run("red");
    verdict("BuildFailed", repoA, fold, red, null);
    awaitMoves(branch, List.of("JOINED@" + qid, "FAILED@" + qid));

    verdict("BuildSuccessful", repoA, fold, run("green-retry"), red);

    assertTrue(held.awaitEntered(), "the request went READY and on to its release");
    awaitMoves(branch, List.of("JOINED@" + qid, "FAILED@" + qid, "JOINED@" + qid));
    assertEquals(fold, mergedShaOf(repoA, id), "the premise: the same fold");
    assertEquals(fold, last(branch).foldSha());
    assertEquals("JOINED", ledgerStep(repoA, branch));
    assertEquals(3, announced.stepsOf(branch).size(), "READY adds nothing more");
    held.release();
    awaitState(repoA, id, "RELEASED");
  }

  /** A conflict, then a refold that merges: JOINED again at the fold that landed. */
  @Test
  public void aRefoldThatMergesAfterAConflictJoinsTheBranchAgain() {
    String branch = ticketBranch();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    String fold = mergedShaOf(repoA, id);
    merger.answerOnce(conflictOf(id, "refs/heads/" + branch, "abc1234"));
    headMoved(repoA, branch, sha());
    assertEquals(List.of("JOINED@" + qid, "CONFLICTED@" + qid), announced.movesOf(branch));
    assertEquals("CONFLICTED", stateOf(repoA, id));

    headMoved(repoA, branch, sha());

    String landed = mergedShaOf(repoA, id);
    assertNotEquals(fold, landed);
    assertEquals("PENDING", stateOf(repoA, id));
    assertEquals(
        List.of("JOINED@" + qid, "CONFLICTED@" + qid, "JOINED@" + qid), announced.movesOf(branch));
    BranchDeliveryStep joined = last(branch);
    assertEquals(landed, joined.foldSha());
    assertNull(joined.foldAttempt(), "a fold that landed is no failed attempt");
    assertEquals("JOINED", ledgerStep(repoA, branch));
  }

  /** PENDING to READY with no failure before it: the branch is already JOINED; nothing new. */
  @Test
  public void aPlainReadyPublishesNothingNew() throws InterruptedException {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    stageRelease();
    RecordingReleaseExecutor.Hold held = executor.holdNextRelease();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);

    verdict("BuildSuccessful", repoA, mergedShaOf(repoA, id), run("green"), null);

    assertTrue(held.awaitEntered(), "the request went READY and on to its release");
    assertEquals(List.of("JOINED@" + qid), announced.movesOf(branch));
    assertEquals("JOINED", ledgerStep(repoA, branch));
    held.release();
    awaitState(repoA, id, "RELEASED");
  }

  @Test
  public void aFailedAutomationFailsTheBranchNamingItsRun() {
    automations.answer(nameA, SCREENSHOTS, "FAILED");
    String branch = ticketBranch();
    String id = create(repoA, branch);
    requestIds.add(id);
    String qid = qualifiedIdOf(repoA, id);

    awaitMoves(branch, List.of("JOINED@" + qid, "FAILED@" + qid));
    BranchDeliveryStep failed = announced.stepsOf(branch).get(1);
    assertEquals("AUTOMATION", failed.failure());
    assertEquals("run-" + SCREENSHOTS, failed.runId());
    assertEquals(mergedShaOf(repoA, id), failed.foldSha());

    releaseRequests.sweep(); // re-read, still failed at the same fold: nothing new
    assertEquals(List.of("JOINED@" + qid, "FAILED@" + qid), announced.movesOf(branch));
  }

  @Test
  public void aConflictIsConflictedAndAWithdrawalWithdrawsEveryBranch() {
    String branch = ticketBranch();
    String other = ticketBranch();
    String id = create(repoA, branch);
    releaseRequests.join(id, other, "alice", null);
    String qid = qualifiedIdOf(repoA, id);
    merger.answer(
        BackingBranchMerger.Outcome.conflict(
            BackingBranches.ref(id),
            List.of(
                new BackingBranchMerger.Conflict(
                    "pom.xml", "refs/heads/" + branch, "abc1234", "content"))));

    headMoved(repoA, branch, sha());
    assertEquals(List.of("JOINED@" + qid, "CONFLICTED@" + qid), announced.movesOf(branch));
    assertEquals(List.of("JOINED@" + qid, "CONFLICTED@" + qid), announced.movesOf(other));

    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"not any more\"}")
        .post(base(repoA) + "/" + id + "/withdraw")
        .then()
        .statusCode(200);
    assertEquals(
        List.of("JOINED@" + qid, "CONFLICTED@" + qid, "WITHDRAWN@" + qid),
        announced.movesOf(branch));
    assertNull(
        last(branch).everywhere(), "its one row is withdrawn, and a withdrawn row never counts");
  }

  /**
   * qits-1737: a withdrawn request no longer ships the branch from its repository, so the WITHDRAWN
   * step carries the {@code everywhere} of the repositories it still ships to — LIVE here, where
   * it used to pin the branch at PUSHED for good. The LIVE row in repository B is written straight
   * to the ledger: {@link #aFinalizedReleaseIsLiveAndEverywhereWaitsForEveryRepository} drives that
   * flow.
   */
  @Test
  public void aWithdrawalLeavesTheBranchLiveWhereItIsLiveElsewhere() {
    String branch = ticketBranch();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              BranchDelivery live = new BranchDelivery();
              live.projectId = projectId;
              live.repoId = repoB;
              live.repoName = nameB;
              live.branch = branch;
              live.step = "LIVE";
              live.requestId = UUID.randomUUID().toString();
              live.qualifiedId = nameB + "-rr-1";
              live.foldSha = "f01d";
              live.version = "2026.1029.10000";
              live.changedAt = Instant.now();
              live.persist();
            });
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    assertEquals("JOINED", last(branch).everywhere(), "the premise: repository A is on a request");

    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"not any more\"}")
        .post(base(repoA) + "/" + id + "/withdraw")
        .then()
        .statusCode(200);

    assertEquals(List.of("JOINED@" + qid, "WITHDRAWN@" + qid), announced.movesOf(branch));
    BranchDeliveryStep withdrawn = last(branch);
    assertEquals("WITHDRAWN", withdrawn.step());
    assertEquals("LIVE", withdrawn.everywhere(), "the withdrawn row never counts");
    assertEquals("WITHDRAWN", ledgerStep(repoA, branch), "its row is written as ever");
  }

  /**
   * qits-1725: a conflict lands no fold, so the second one sits at the merged sha the first left.
   * Another head, or the same head at another tip, is another attempt and is announced again.
   */
  @Test
  public void aDifferentConflictAtTheSameFoldIsConflictedAgain() {
    String branch = ticketBranch();
    String other = ticketBranch();
    String id = create(repoA, branch);
    releaseRequests.join(id, other, "alice", null);
    String qid = qualifiedIdOf(repoA, id);
    String fold = mergedShaOf(repoA, id);

    merger.answer(conflictOf(id, "refs/heads/" + branch, "abc1234"));
    headMoved(repoA, branch, sha());
    merger.answer(conflictOf(id, "refs/heads/" + other, "def5678"));
    headMoved(repoA, other, sha());

    List<String> twice = List.of("JOINED@" + qid, "CONFLICTED@" + qid, "CONFLICTED@" + qid);
    assertEquals(twice, announced.movesOf(branch));
    assertEquals(twice, announced.movesOf(other));
    BranchDeliveryStep first = announced.stepsOf(branch).get(1);
    BranchDeliveryStep second = announced.stepsOf(branch).get(2);
    assertEquals(fold, mergedShaOf(repoA, id), "the premise: no conflict moved the merged sha");
    assertEquals(fold, first.foldSha());
    assertEquals(fold, second.foldSha());
    assertEquals(16, first.foldAttempt().length());
    assertNotEquals(first.foldAttempt(), second.foldAttempt());
    assertEquals(second.foldAttempt(), announced.stepsOf(other).get(2).foldAttempt());

    // The same head at a new tip is a new attempt too.
    merger.answer(conflictOf(id, "refs/heads/" + other, "9999999"));
    headMoved(repoA, other, sha());
    assertEquals(4, announced.stepsOf(branch).size(), "steps: " + announced.movesOf(branch));
    assertNotEquals(second.foldAttempt(), last(branch).foldAttempt());
  }

  /** qits-1725: the same heads at the same tips hit again are the position already held. */
  @Test
  public void theSameConflictTwiceIsConflictedOnce() {
    String branch = ticketBranch();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    merger.answer(conflictOf(id, "refs/heads/" + branch, "abc1234"));

    headMoved(repoA, branch, sha());
    headMoved(repoA, branch, sha());

    assertEquals(List.of("JOINED@" + qid, "CONFLICTED@" + qid), announced.movesOf(branch));
    assertEquals(
        FoldAttempt.ofConflicts(
            List.of(
                new BackingBranchMerger.Conflict(
                    "pom.xml", "refs/heads/" + branch, "abc1234", "content"),
                new BackingBranchMerger.Conflict(
                    "README.md", "refs/heads/" + branch, "abc1234", "content"))),
        last(branch).foldAttempt(),
        "keyed on the heads, not on how many paths collide");
  }

  /**
   * qits-1725: a source the git host does not have REJECTS the request (qits-1221), and every
   * branch it carries is FAILED with failure FOLD, keyed by the missing ref — once per ref.
   */
  @Test
  public void aMissingSourceFailsEveryBranchWithAFoldFailure() {
    String branch = ticketBranch();
    String other = ticketBranch();
    String id = create(repoA, branch);
    releaseRequests.join(id, other, "alice", null);
    String qid = qualifiedIdOf(repoA, id);
    String missing = "refs/heads/epic/qits-1681";
    merger.answer(BackingBranchMerger.Outcome.missingSource(missing));

    headMoved(repoA, branch, sha());

    assertEquals("REJECTED", stateOf(repoA, id), "the premise: the fold rejected it");
    for (String each : List.of(branch, other)) {
      assertEquals(List.of("JOINED@" + qid, "FAILED@" + qid), announced.movesOf(each), each);
      BranchDeliveryStep failed = last(each);
      assertEquals("FOLD", failed.failure());
      assertEquals("missing:" + missing, failed.foldAttempt());
      assertNull(failed.runId());
      assertEquals("JOINED", failed.everywhere(), "a failed fold is still on a request");
    }

    headMoved(repoA, other, sha()); // still missing: the same attempt, nothing new
    assertEquals(List.of("JOINED@" + qid, "FAILED@" + qid), announced.movesOf(branch));
  }

  @Test
  public void aDeletedBranchIsWithdrawnFromTheRequestThatStaysOpen() {
    String branch = ticketBranch();
    String other = ticketBranch();
    String id = create(repoA, branch);
    releaseRequests.join(id, other, "alice", null);
    String qid = qualifiedIdOf(repoA, id);

    releaseRequests.onBranchDeleted(repoA, other);

    assertEquals(List.of("JOINED@" + qid, "WITHDRAWN@" + qid), announced.movesOf(other));
    assertEquals(List.of("JOINED@" + qid), announced.movesOf(branch), "the other stays joined");
    assertEquals("PENDING", stateOf(repoA, id));
  }

  // ---- releasing -------------------------------------------------------------------------------

  /**
   * {@link AutoReleaseTest}'s late join: the branch the tag carries is RELEASED with the version,
   * and the one that joined while the tag was cut is MOVED to where it was handed on — never
   * JOINED there.
   */
  @Test
  public void aReleaseIsReleasedAndALateBranchIsMovedToWhereItWasHandedOn()
      throws InterruptedException {
    String branch = ticketBranch();
    String late = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String tag = stageRelease();
    RecordingReleaseExecutor.Hold held = executor.holdNextRelease();
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    verdict("BuildSuccessful", repoA, mergedShaOf(repoA, id), run("green"), null);
    assertTrue(held.awaitEntered(), "the worker reached the executor");

    assertEquals(id, create(repoA, late), "the late branch joins the request being released");
    held.release();
    awaitState(repoA, id, "RELEASED");

    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid));
    BranchDeliveryStep released = last(branch);
    assertEquals(tag, released.version());
    assertEquals("RELEASED", released.everywhere());

    String landed = awaitLastStep(late, "MOVED").qualifiedId();
    assertNotEquals(qid, landed);
    assertEquals(List.of("JOINED@" + qid, "MOVED@" + landed), announced.movesOf(late));
    BranchDeliveryStep moved = last(late);
    assertEquals(id, moved.previousRequestId());
    assertEquals(qid, moved.previousQualifiedId());
    assertEquals("JOINED", moved.everywhere());
  }

  /** {@link ReleaseSupersessionTest}'s red release: its branches are MOVED onto the successor. */
  @Test
  public void aRedReleaseSupersededByANewRequestMovesItsBranchesOntoIt() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String tag = releaseOf(repoA, branch);
    String earlier = firstRequest(repoA);
    String earlierQid = qualifiedIdOf(repoA, earlier);
    redPublish(repoA, tag);

    String successor = create(repoA, ticketBranch());

    assertEquals("OBSOLETE", stateOf(repoA, earlier), "the premise: superseded");
    String successorQid = qualifiedIdOf(repoA, successor);
    assertEquals(
        List.of(
            "JOINED@" + earlierQid,
            "RELEASED@" + earlierQid,
            "FAILED@" + earlierQid,
            "MOVED@" + successorQid),
        announced.movesOf(branch),
        "the red publish failed the branch (qits-1796) before the successor carried it");
    BranchDeliveryStep moved = last(branch);
    assertEquals(earlier, moved.previousRequestId());
    assertEquals(successor, moved.requestId());
  }

  /**
   * qits-1830: the branches a superseded red release carries ride its successor inside the folded
   * tag, not as its sources — here the successor's one named source is {@code main} — and they go
   * RELEASED and LIVE with it, at its version, where they used to stay MOVED for good.
   */
  @Test
  public void aSupersededBranchIsReleasedAndLiveWithItsSuccessor() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String tag = releaseOf(repoA, branch);
    String earlier = firstRequest(repoA);
    String earlierQid = qualifiedIdOf(repoA, earlier);
    redPublish(repoA, tag);

    String successorTag = stageRelease();
    String successor = create(repoA, "main");
    assertEquals("OBSOLETE", stateOf(repoA, earlier), "the premise: superseded");
    String successorQid = qualifiedIdOf(repoA, successor);
    verdict("BuildSuccessful", repoA, mergedShaOf(repoA, successor), run("green"), null);
    awaitState(repoA, successor, "RELEASED");
    deploymentActive(successorTag);
    finalization.sweep();
    awaitState(repoA, successor, "FINALIZED");

    awaitMoves(
        branch,
        List.of(
            "JOINED@" + earlierQid,
            "RELEASED@" + earlierQid,
            "FAILED@" + earlierQid,
            "MOVED@" + successorQid,
            "RELEASED@" + successorQid,
            "LIVE@" + successorQid));
    BranchDeliveryStep released = announced.stepsOf(branch).get(4);
    assertEquals(successorTag, released.version());
    BranchDeliveryStep live = last(branch);
    assertEquals(successor, live.requestId());
    assertEquals(successorTag, live.version());
    assertEquals("LIVE", live.everywhere());
    assertEquals("LIVE", ledgerStep(repoA, branch));
  }

  /**
   * qits-1830: a carry fails with its successor's fold, and comes back to JOINED with it as a
   * source does, so the successor's release still takes it to RELEASED.
   */
  @Test
  public void aCarryFailsWithItsSuccessorsFoldAndIsReleasedOnceItIsGreenAgain() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String tag = releaseOf(repoA, branch);
    String earlierQid = qualifiedIdOf(repoA, firstRequest(repoA));
    redPublish(repoA, tag);
    String successorTag = stageRelease();
    String successor = create(repoA, "main");
    String successorQid = qualifiedIdOf(repoA, successor);
    String fold = mergedShaOf(repoA, successor);

    String red = run("red");
    verdict("BuildFailed", repoA, fold, red, null);

    List<String> failed =
        List.of(
            "JOINED@" + earlierQid,
            "RELEASED@" + earlierQid,
            "FAILED@" + earlierQid,
            "MOVED@" + successorQid,
            "FAILED@" + successorQid);
    awaitMoves(branch, failed);
    assertEquals("CI", last(branch).failure());
    assertEquals(red, last(branch).runId());
    assertEquals("REJECTED", stateOf(repoA, successor));

    verdict("BuildSuccessful", repoA, fold, run("green-retry"), red);
    awaitState(repoA, successor, "RELEASED");

    List<String> released = new ArrayList<>(failed);
    released.addAll(List.of("JOINED@" + successorQid, "RELEASED@" + successorQid));
    awaitMoves(branch, released);
    assertEquals(successorTag, last(branch).version());
  }

  /**
   * qits-1830: a successor that goes red and OBSOLETE in turn hands the carries on to the request
   * that supersedes it, as it hands on its own sources — they ride inside its tag too.
   */
  @Test
  public void aSupersededSuccessorMovesItsCarriesOnToTheNextRequest() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String tagA = releaseOf(repoA, branch);
    String qidA = qualifiedIdOf(repoA, firstRequest(repoA));
    redPublish(repoA, tagA);
    String tagB = stageRelease();
    String b = create(repoA, "main");
    String qidB = qualifiedIdOf(repoA, b);
    verdict("BuildSuccessful", repoA, mergedShaOf(repoA, b), run("green"), null);
    awaitState(repoA, b, "RELEASED");
    redPublish(repoA, tagB);

    String c = create(repoA, "main");

    assertEquals("OBSOLETE", stateOf(repoA, b), "the premise: the successor is superseded too");
    String qidC = qualifiedIdOf(repoA, c);
    awaitMoves(
        branch,
        List.of(
            "JOINED@" + qidA,
            "RELEASED@" + qidA,
            "FAILED@" + qidA,
            "MOVED@" + qidB,
            "RELEASED@" + qidB,
            "FAILED@" + qidB,
            "MOVED@" + qidC));
    BranchDeliveryStep moved = last(branch);
    assertEquals(b, moved.previousRequestId());
    assertEquals(c, moved.requestId());
  }

  /**
   * qits-1830: a branch handed on to a request — MOVED there, a source of it — that is late again
   * when that request releases is handed on again, and is never RELEASED by the request it leaves:
   * the carries that release promotes are only the branches that were no source of it.
   */
  @Test
  public void aHandedOnBranchLateAgainIsNeverReleasedByTheRequestItLeaves()
      throws InterruptedException {
    String branch = ticketBranch();
    String late = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String first = stageRelease();
    RecordingReleaseExecutor.Hold held = executor.holdNextRelease();
    String id = create(repoA, branch);
    verdict("BuildSuccessful", repoA, mergedShaOf(repoA, id), run("green"), null);
    assertTrue(held.awaitEntered(), "the worker reached the executor");
    assertEquals(id, create(repoA, late), "the late branch joins the request being released");
    held.release();
    awaitState(repoA, id, "RELEASED");
    BranchDeliveryStep handedOn = awaitLastStep(late, "MOVED");
    String second = handedOn.requestId();
    String secondQid = handedOn.qualifiedId();
    deploymentActive(first);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");

    // The second request releases while the branch has moved past its fold.
    stageRelease();
    String fold = mergedShaOf(repoA, second);
    gitHost.doesNotContain(repoA, "refs/heads/" + late, fold);
    verdict("BuildSuccessful", repoA, fold, run("green-second"), null);
    awaitState(repoA, second, "RELEASED");

    // The hand-on lands after the release settled: wait for the step that leaves the second request.
    await(() -> announced.stepsOf(late).size() >= 3);
    BranchDeliveryStep again = last(late);
    assertEquals("MOVED", again.step(), "steps: " + announced.movesOf(late));
    assertNotEquals(second, again.requestId(), "handed on again");
    assertEquals(second, again.previousRequestId());
    assertEquals(
        List.of(
            "JOINED@" + qualifiedIdOf(repoA, id),
            "MOVED@" + secondQid,
            "MOVED@" + again.qualifiedId()),
        announced.movesOf(late),
        "never RELEASED by the request it was handed on from");
  }

  /**
   * {@link MainFinalizationTest}'s deployment going live: FINALIZED is LIVE. The branch was pushed
   * to a second repository nothing asked a release of, so it is not LIVE everywhere until that
   * PUSHED row is gone; and a push reusing the name after it went live starts the row over.
   */
  @Test
  public void aFinalizedReleaseIsLiveAndEverywhereWaitsForEveryRepository() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    releaseRequests.onBranchMoved(repoB, branch, sha());
    assertEquals("PUSHED", ledgerStep(repoB, branch), "a push nobody asked about is PUSHED");
    assertEquals(List.of(), announced.movesOf(branch), "and PUSHED is never announced");

    String tag = releaseOf(repoA, branch);
    String id = firstRequest(repoA);
    String qid = qualifiedIdOf(repoA, id);
    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid));
    assertEquals("PUSHED", last(branch).everywhere(), "repository B has not asked");

    releaseRequests.onBranchDeleted(repoB, branch);
    assertNull(ledgerStep(repoB, branch), "a deleted branch that never got past PUSHED goes");

    deploymentActive(tag);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");

    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid, "LIVE@" + qid));
    BranchDeliveryStep live = last(branch);
    assertEquals(tag, live.version());
    assertEquals("LIVE", live.everywhere());

    releaseRequests.onBranchMoved(repoA, branch, sha());
    assertEquals("PUSHED", ledgerStep(repoA, branch), "the name was reused: it starts over");
  }

  /**
   * qits-1736: the workspace daemon pushes agent memory to the project's {@code qits-agent-config}
   * on the ticket branch's own name, and that repository releases only on a desk approval after the
   * work. Its PUSHED row is written as ever and listed by the read door, but it never holds the
   * branch's {@code everywhere} below LIVE.
   */
  @Test
  public void theAgentConfigRepositoryNeverHoldsEverywhereBelowLive() {
    String branch = ticketBranch();
    String agentConfig = "deliver-agent-config-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = Project.findById(projectId);
              Repository repository = repository(project, agentConfig);
              repository.archetype = RepositoryArchetype.AGENT_CONFIG;
              alias(project, repository, "qits-agent-config");
            });
    activeBuilds.answer(Optional.of(0));
    releaseRequests.onBranchMoved(agentConfig, branch, sha());
    assertEquals("PUSHED", ledgerStep(agentConfig, branch), "its row is written as ever");

    String tag = releaseOf(repoA, branch);
    String id = firstRequest(repoA);
    String qid = qualifiedIdOf(repoA, id);
    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid));
    assertEquals("RELEASED", last(branch).everywhere(), "the agent config's PUSHED is left out");

    deploymentActive(tag);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");
    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid, "LIVE@" + qid));
    assertEquals("LIVE", last(branch).everywhere());
    assertEquals("PUSHED", ledgerStep(agentConfig, branch));

    io.restassured.path.json.JsonPath door =
        given()
            .queryParam("branch", branch)
            .get("/projects/api/branch-deliveries")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals("LIVE", door.getString("everywhere"));
    assertEquals(
        List.of("PUSHED", "LIVE"),
        List.of(
            door.getString("deliveries.find { it.repoId == '" + agentConfig + "' }.step"),
            door.getString("deliveries.find { it.repoId == '" + repoA + "' }.step")),
        "the agent config's row is still listed");
  }

  // ---- after the tag (qits-1796) ---------------------------------------------------------------

  /**
   * qits-1796: a red publish run of the tag fails the branch — FAILED, failure PUBLISH, the run —
   * though the request stays RELEASED; the retry's start puts it back to RELEASED once (QUEUED then
   * RUNNING is one position), a second red is announced again, and a green retry ends at LIVE.
   */
  @Test
  public void aRedPublishFailsTheBranchAndEachRetryReArmsItUntilAGreenOneIsLive() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String tag = releaseOf(repoA, branch, stagePublishedRelease());
    String id = firstRequest(repoA);
    String qid = qualifiedIdOf(repoA, id);
    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid));

    String red = run("publish-red");
    publishVerdict("BuildFailed", repoA, tag, red);

    BranchDeliveryStep failed = awaitLastStep(branch, "FAILED");
    assertEquals("PUBLISH", failed.failure());
    assertEquals(red, failed.runId());
    assertEquals(tag, failed.version());
    assertEquals("JOINED", failed.everywhere(), "a failed branch ranks as on a request");
    assertEquals("RELEASED", stateOf(repoA, id), "the tag is cut: the request stays RELEASED");
    assertEquals("FAILED", ledgerStep(repoA, branch));

    String retry = run("publish-retry");
    publishRunTransition(repoA, tag, retry, "QUEUED");
    publishRunTransition(repoA, tag, retry, "RUNNING");

    BranchDeliveryStep rearmed = awaitLastStep(branch, "RELEASED");
    assertEquals(tag, rearmed.version());
    assertEquals(
        List.of("JOINED@" + qid, "RELEASED@" + qid, "FAILED@" + qid, "RELEASED@" + qid),
        announced.movesOf(branch),
        "QUEUED and RUNNING of one retry are one position");

    publishVerdict("BuildFailed", repoA, tag, retry);
    BranchDeliveryStep again = awaitLastStep(branch, "FAILED");
    assertEquals(retry, again.runId(), "the second red is news and names its own run");

    String green = run("publish-green");
    publishRunTransition(repoA, tag, green, "QUEUED");
    awaitLastStep(branch, "RELEASED");
    publishVerdict("BuildSuccessful", repoA, tag, green);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");

    awaitMoves(
        branch,
        List.of(
            "JOINED@" + qid,
            "RELEASED@" + qid,
            "FAILED@" + qid,
            "RELEASED@" + qid,
            "FAILED@" + qid,
            "RELEASED@" + qid,
            "LIVE@" + qid));
    assertEquals(tag, last(branch).version());
    assertEquals("LIVE", last(branch).everywhere());
  }

  /**
   * qits-1796: a failed deployment fails the branch — FAILED, failure DEPLOYMENT, no run — and an
   * accepted DEPLOY rerun of the RELEASED request puts it back to RELEASED, so the same rollback
   * again is announced again; the redeploy going live ends at LIVE.
   */
  @Test
  public void aFailedDeploymentFailsTheBranchAndAnAcceptedRedeployReArmsIt() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    gitHost.tree(
        "refs/heads/main",
        Map.of(
            RecordingReleaseGitHost.RELEASE_CONFIG,
            RecordingReleaseGitHost.GATING_RELEASE_CONFIG,
            ".config/qits/deployments.yml",
            "resources: []\n"));
    String tag = releaseOf(repoA, branch);
    String id = firstRequest(repoA);
    String qid = qualifiedIdOf(repoA, id);
    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid));

    assertTrue(finalization.onDeploymentFailed("delivery", tag, "ROLLED_BACK", "probe failed"));

    BranchDeliveryStep failed = awaitLastStep(branch, "FAILED");
    assertEquals("DEPLOYMENT", failed.failure());
    assertNull(failed.runId(), "a deployment is no run");
    assertEquals(tag, failed.version());
    assertEquals("RELEASED", stateOf(repoA, id));

    given()
        .contentType(ContentType.JSON)
        .post(base(repoA) + "/" + id + "/pipeline/DEPLOY/rerun")
        .then()
        .statusCode(200);
    assertEquals(1, redeploys.asks().size(), "the far side accepted it");

    BranchDeliveryStep rearmed = awaitLastStep(branch, "RELEASED");
    assertEquals(tag, rearmed.version());

    assertFalse(
        finalization.onDeploymentFailed("delivery", tag, "ROLLED_BACK", "probe failed"),
        "the row already says so");
    awaitLastStep(branch, "FAILED");
    assertEquals(
        List.of(
            "JOINED@" + qid,
            "RELEASED@" + qid,
            "FAILED@" + qid,
            "RELEASED@" + qid,
            "FAILED@" + qid),
        announced.movesOf(branch),
        "the same rollback after a redeploy is a new position for the branch");

    deploymentActive(tag);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");
    awaitLastStep(branch, "LIVE");
    assertEquals(
        List.of(
            "JOINED@" + qid,
            "RELEASED@" + qid,
            "FAILED@" + qid,
            "RELEASED@" + qid,
            "FAILED@" + qid,
            "RELEASED@" + qid,
            "LIVE@" + qid),
        announced.movesOf(branch),
        "a redeploy that went live re-arms the branch, and the finalization makes it LIVE");
  }

  /**
   * qits-1796: only an owed tag moves a branch. A red verdict naming no released version, a
   * publish run started for an unknown one, and the first publish run of a tag whose gate is still
   * PENDING write no step; neither does a rerun of a phase on an unreleased request.
   */
  @Test
  public void aVerdictOrARunStartForNoOwedTagWritesNoStep() {
    String branch = ticketBranch();
    activeBuilds.answer(Optional.of(0));
    String tag = releaseOf(repoA, branch, stagePublishedRelease());
    String id = firstRequest(repoA);
    String qid = qualifiedIdOf(repoA, id);
    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid));

    publishRunTransition(repoA, tag, run("first"), "QUEUED");
    publishVerdict("BuildFailed", repoA, "2026.0101.1", run("elsewhere"));
    publishVerdict("BuildFailed", repoB, tag, run("other-repo"));
    publishRunTransition(repoA, "2026.0101.1", run("elsewhere"), "RUNNING");
    finalization.onPublishVerdict(repoA, branch, run("a-branch"), false);
    pause(300);

    assertEquals(List.of("JOINED@" + qid, "RELEASED@" + qid), announced.movesOf(branch));
    assertEquals("RELEASED", ledgerStep(repoA, branch));
  }

  // ---- helpers ---------------------------------------------------------------------------------

  // ---- a wrapper branch's delivery ending closes its ticket (qits-150) --------------------------

  /**
   * qits-150: the wrapper release request carrying a VERIFIED ticket's branch reaches FINALIZED —
   * its row goes LIVE — and the platform moves the ticket to DONE, naming the request on its
   * thread. Nobody asks for it.
   */
  @Test
  public void aFinalizedWrapperReleaseClosesTheVerifiedTicketItsBranchNames() {
    asWrapper(repoA);
    String ticket = verifiedTicket();
    String branch = wrapperBranchOf(ticket);
    activeBuilds.answer(Optional.of(0));

    String tag = releaseOf(repoA, branch);
    String id = firstRequest(repoA);
    String qid = qualifiedIdOf(repoA, id);
    assertEquals("VERIFIED", ticketStatus(ticket), "RELEASED is not FINALIZED: it waits");

    deploymentActive(tag);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");
    awaitMoves(branch, List.of("JOINED@" + qid, "RELEASED@" + qid, "LIVE@" + qid));

    awaitTicket(ticket, "DONE");
    assertTrue(
        threadOf(ticket).stream()
            .anyMatch(body -> body.contains("`" + qid + "`") && body.contains("**FINALIZED**")),
        "the thread names the request that finalized: " + threadOf(ticket));
  }

  /**
   * qits-150: the hook is the branch's ledger row, so a branch carried on by obsolescence closes its
   * ticket when the SUCCESSOR finalizes — the request it first joined never does, and is not named.
   */
  @Test
  public void aWrapperBranchSupersededIntoASuccessorClosesItsTicketWhenTheSuccessorFinalizes() {
    asWrapper(repoA);
    String ticket = verifiedTicket();
    String branch = wrapperBranchOf(ticket);
    activeBuilds.answer(Optional.of(0));
    String tag = releaseOf(repoA, branch);
    String earlier = firstRequest(repoA);
    String earlierQid = qualifiedIdOf(repoA, earlier);
    redPublish(repoA, tag);

    String successorTag = stageRelease();
    String successor = create(repoA, "main");
    assertEquals("OBSOLETE", stateOf(repoA, earlier), "the premise: superseded");
    assertEquals("VERIFIED", ticketStatus(ticket), "an obsoleted request ends no delivery");
    String successorQid = qualifiedIdOf(repoA, successor);
    verdict("BuildSuccessful", repoA, mergedShaOf(repoA, successor), run("green"), null);
    awaitState(repoA, successor, "RELEASED");
    deploymentActive(successorTag);
    finalization.sweep();
    awaitState(repoA, successor, "FINALIZED");
    awaitLastStep(branch, "LIVE");

    awaitTicket(ticket, "DONE");
    List<String> thread = threadOf(ticket);
    assertTrue(
        thread.stream().anyMatch(body -> body.contains("`" + successorQid + "`")),
        "the successor is named: " + thread);
    assertFalse(
        thread.stream().anyMatch(body -> body.contains("`" + earlierQid + "`")),
        "the obsoleted request is not: " + thread);
  }

  /**
   * qits-150: a member repository's release finalizing is no wrapper release, whatever the branch is
   * called — the ticket stays VERIFIED.
   */
  @Test
  public void aMemberReleaseFinalizingClosesNoTicket() {
    String ticket = verifiedTicket();
    String branch = wrapperBranchOf(ticket);
    activeBuilds.answer(Optional.of(0));

    String tag = releaseOf(repoA, branch);
    String id = firstRequest(repoA);
    deploymentActive(tag);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");
    awaitLastStep(branch, "LIVE");

    pause(500);
    assertEquals("VERIFIED", ticketStatus(ticket), "only the wrapper's release ends a ticket");
  }

  /**
   * qits-150: the wrapper may go live before the ticket is VERIFIED. Nothing is closed then — only a
   * VERIFIED ticket is — and the ticket goes DONE when it arrives at VERIFIED, read off the same
   * ledger row.
   */
  @Test
  public void aTicketNotYetVerifiedWhenItsWrapperGoesLiveIsClosedWhenItArrivesAtVerified() {
    asWrapper(repoA);
    String ticket = ticketAt("VERIFYING");
    String branch = wrapperBranchOf(ticket);
    activeBuilds.answer(Optional.of(0));

    String tag = releaseOf(repoA, branch);
    String id = firstRequest(repoA);
    String qid = qualifiedIdOf(repoA, id);
    deploymentActive(tag);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");
    awaitLastStep(branch, "LIVE");
    pause(500);
    assertEquals("VERIFYING", ticketStatus(ticket), "not VERIFIED yet: nothing is closed");

    given()
        .contentType(ContentType.JSON)
        .body("{\"target\":\"VERIFIED\"}")
        .post("/projects/api/work/" + ticket + "/status")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("VERIFIED"));

    awaitTicket(ticket, "DONE");
    assertTrue(
        threadOf(ticket).stream().anyMatch(body -> body.contains("`" + qid + "`")),
        "the request whose delivery ended is named: " + threadOf(ticket));
  }

  /**
   * qits-150: the reconcile is the floor under both prompt paths — a ticket that reached VERIFIED
   * by a move no door made (so nobody read its ledger row), after its wrapper went live, is closed
   * on the next pass.
   */
  @Test
  public void theReconcileClosesAVerifiedTicketWhoseWrapperIsAlreadyLive() {
    asWrapper(repoA);
    String ticket = ticketAt("VERIFYING");
    String branch = wrapperBranchOf(ticket);
    activeBuilds.answer(Optional.of(0));
    String tag = releaseOf(repoA, branch);
    String id = firstRequest(repoA);
    deploymentActive(tag);
    finalization.sweep();
    awaitState(repoA, id, "FINALIZED");
    awaitLastStep(branch, "LIVE");
    entities.transition(Archetype.TICKET, ticket, "VERIFIED", "in-process");
    assertEquals("VERIFIED", ticketStatus(ticket), "an in-process move reads no ledger");

    wrapperDone.reconcile();

    assertEquals("DONE", ticketStatus(ticket));
  }

  // ---- endings the ledger cannot state alone (qits-150 follow-up) -------------------------------

  /**
   * A wrapper branch deleted while its request is open withdraws the request; when the git host
   * says main contains that request's last fold (the withdrawal comment's NOTHING), nothing of the
   * branch is left to add, and its VERIFIED ticket goes DONE at the deletion.
   */
  @Test
  public void aDeletedWrapperBranchWhoseFoldMainContainsClosesItsTicket() {
    asWrapper(repoA);
    String ticket = verifiedTicket();
    String branch = wrapperBranchOf(ticket);
    String id = create(repoA, branch);
    String qid = qualifiedIdOf(repoA, id);
    gitHost.containsCommit(repoA, mergedShaOf(repoA, id), "refs/heads/main");

    releaseRequests.onBranchDeleted(repoA, branch);

    assertEquals("WITHDRAWN", stateOf(repoA, id), "the premise: withdrawn on the deletion");
    awaitTicket(ticket, "DONE");
    assertTrue(
        threadOf(ticket).stream()
            .anyMatch(body -> body.contains("`" + qid + "`") && body.contains("was deleted")),
        "the thread names the request and the deletion: " + threadOf(ticket));
  }

  /**
   * A deleted wrapper branch whose last fold main does NOT contain lost unreleased work (YES), and
   * one whose containment could not be read is UNKNOWN: neither closes the ticket, at the deletion
   * or on the reconcile.
   */
  @Test
  public void aDeletedWrapperBranchWithUnreleasedOrUnknownLineageClosesNothing() {
    asWrapper(repoA);
    String lost = verifiedTicket();
    String lostBranch = wrapperBranchOf(lost);
    String lostId = create(repoA, lostBranch);
    gitHost.doesNotContain(repoA, mergedShaOf(repoA, lostId), "refs/heads/main");
    String unknown = verifiedTicket();
    String unknownBranch = wrapperBranchOf(unknown);
    String unknownId = create(repoA, unknownBranch);
    gitHost.containmentOfUnreadable(
        repoA,
        mergedShaOf(repoA, unknownId),
        eu.wohlben.qits.projects.control.ReleaseGitHost.Answer.failedRetryable("timed out"));

    releaseRequests.onBranchDeleted(repoA, lostBranch);
    releaseRequests.onBranchDeleted(repoA, unknownBranch);
    assertEquals("WITHDRAWN", stateOf(repoA, lostId));
    assertEquals("WITHDRAWN", stateOf(repoA, unknownId));
    pause(500);
    wrapperDone.reconcile();

    assertEquals("VERIFIED", ticketStatus(lost), "unreleased work was lost: not DONE");
    assertEquals("VERIFIED", ticketStatus(unknown), "could not ask is never nothing lost");
  }

  /**
   * The backfill: a row that ended by deletion before anybody closed tickets on it (here, while the
   * git host could not answer) is closed by the reconcile once main is known to contain its fold.
   */
  @Test
  public void theReconcileClosesATicketWhoseWrapperBranchWasDeletedWithItsFoldInMain() {
    asWrapper(repoA);
    String ticket = verifiedTicket();
    String branch = wrapperBranchOf(ticket);
    String id = create(repoA, branch);
    String fold = mergedShaOf(repoA, id);
    gitHost.containmentOfUnreadable(
        repoA, fold, eu.wohlben.qits.projects.control.ReleaseGitHost.Answer.failedRetryable("down"));
    releaseRequests.onBranchDeleted(repoA, branch);
    pause(300);
    assertEquals("VERIFIED", ticketStatus(ticket));

    gitHost.reset();
    gitHost.containsCommit(repoA, fold, "refs/heads/main");
    wrapperDone.reconcile();

    assertEquals("DONE", ticketStatus(ticket));
  }

  /**
   * A row naming a request that was obsoleted — the branch never reached a successor, or the row
   * would name it — with the branch gone and its fold in main closes the ticket; the same row
   * naming a request still open is in flight and closes nothing.
   */
  @Test
  public void anObsoleteRowWhoseFoldMainContainsClosesItsTicketAndAnOpenOneDoesNot() {
    asWrapper(repoA);
    String ticket = verifiedTicket();
    String branch = wrapperBranchOf(ticket);
    String id = create(repoA, branch);
    gitHost.containsCommit(repoA, mergedShaOf(repoA, id), "refs/heads/main");

    wrapperDone.reconcile();
    assertEquals("VERIFIED", ticketStatus(ticket), "a row on an open request is in flight");

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleaseRequest.<ReleaseRequest>findById(id).state = ReleaseRequest.State.OBSOLETE);
    wrapperDone.reconcile();

    assertEquals("DONE", ticketStatus(ticket));
    assertTrue(
        threadOf(ticket).stream().anyMatch(body -> body.contains("already contained in main")),
        threadOf(ticket).toString());
  }

  /**
   * No ledger row and no wrapper branch: nothing will ever be released, so the reconcile closes the
   * ticket — but only past the grace after it reached VERIFIED, never before, and never while the
   * branch exists on the git host.
   */
  @Test
  public void aTicketWithNoRowAndNoBranchClosesOnlyAfterTheGrace() {
    asWrapper(repoA);
    String ticket = verifiedTicket();
    String present = verifiedTicket();
    gitHost.headOf(repoA, wrapperBranchOf(present), sha());

    wrapperDone.reconcile();
    assertEquals("VERIFIED", ticketStatus(ticket), "inside the grace: PhaseAdvance may still ask");

    verifiedSince(ticket, Instant.now().minus(java.time.Duration.ofHours(1)));
    verifiedSince(present, Instant.now().minus(java.time.Duration.ofHours(1)));
    wrapperDone.reconcile();

    assertEquals("DONE", ticketStatus(ticket));
    assertTrue(
        threadOf(ticket).stream().anyMatch(body -> body.contains("No wrapper branch")),
        threadOf(ticket).toString());
    assertEquals("VERIFIED", ticketStatus(present), "a branch on the git host may still ship");
  }

  /** A row still in flight never closes its ticket, gone branch or not, grace or not. */
  @Test
  public void aRowInFlightNeverClosesItsTicket() {
    asWrapper(repoA);
    String ticket = verifiedTicket();
    String branch = wrapperBranchOf(ticket);
    String id = create(repoA, branch);
    gitHost.containsCommit(repoA, mergedShaOf(repoA, id), "refs/heads/main");
    verifiedSince(ticket, Instant.now().minus(java.time.Duration.ofHours(1)));

    wrapperDone.reconcile();

    assertEquals("JOINED", ledgerStep(repoA, branch));
    assertEquals("VERIFIED", ticketStatus(ticket));
  }

  /** Sets the ticket's {@code updated_at}, which a move into VERIFIED stamps, as a test's clock. */
  private void verifiedSince(String ticketId, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workRows
                    .getEntityManager()
                    .createNativeQuery("update entity set updated_at = ?1 where id = ?2")
                    .setParameter(1, java.sql.Timestamp.from(at))
                    .setParameter(2, ticketId)
                    .executeUpdate());
  }

  /** Makes {@code repoId} the project's estate wrapper ({@code PROJECT}) for this test. */
  private void asWrapper(String repoId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                Repository.<Repository>findById(repoId).archetype = RepositoryArchetype.PROJECT);
  }

  /** A ticket of the fixture's project walked to {@code status} in process, past every gate. */
  private String ticketAt(String status) {
    String id =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("Delivered", "it occurs", null, "BUG", null)
                    .withAcceptanceCriteria(List.of("It does what it says.")),
                "seed")
            .entity()
            .id;
    for (String step : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFYING", "VERIFIED")) {
      entities.transition(
          Archetype.TICKET,
          id,
          step,
          eu.wohlben.qits.entities.control.Mover.person("seed"));
      if (step.equals(status)) {
        break;
      }
    }
    return id;
  }

  private String verifiedTicket() {
    return ticketAt("VERIFIED");
  }

  /** {@code ticket/<qualified id>}: the project's slug is its id in this fixture. */
  private String wrapperBranchOf(String ticketId) {
    long number =
        QuarkusTransaction.requiringNew()
            .call(() -> entities.get(Archetype.TICKET, ticketId))
            .number;
    return "ticket/" + projectId + "-" + number;
  }

  private String ticketStatus(String ticketId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> entities.get(Archetype.TICKET, ticketId))
        .status;
  }

  private void awaitTicket(String ticketId, String status) {
    await(() -> status.equals(ticketStatus(ticketId)));
    assertEquals(status, ticketStatus(ticketId));
  }

  private List<String> threadOf(String ticketId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                comments.listComments(ticketId).stream().map(comment -> comment.body).toList());
  }

  private String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String repoId, String branch) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"branch\":\"" + branch + "\",\"summary\":\"a delivery\"}")
            .post(base(repoId))
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    return id;
  }

  private ReleaseRequests.Asked ask(String branch) {
    ReleaseRequests.Asked asked =
        releaseRequests.ask(repoA, branch, "alice", null);
    requestIds.add(asked.request().id());
    return asked;
  }

  /** A branch naming a real ticket, so it counts toward the freeze. */
  private String feature() {
    long number =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("A feature", "because", null, "IMPROVEMENT", null),
                "seed")
            .entity()
            .number;
    return "ticket/" + projectId + "-" + number;
  }

  /** A ticket-shaped branch name no other test uses; it need not name a real ticket. */
  private static String ticketBranch() {
    return "ticket/delivery-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String qualifiedIdOf(String repoId, String id) {
    return given()
        .get(base(repoId) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.qualifiedId");
  }

  private String stateOf(String repoId, String id) {
    return given()
        .get(base(repoId) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.state");
  }

  private String mergedShaOf(String repoId, String id) {
    return given()
        .get(base(repoId) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.mergedSha");
  }

  private String firstRequest(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequest.<ReleaseRequest>find("repoId = ?1 order by createdAt", repoId)
                    .firstResult()
                    .id);
  }

  private String ledgerStep(String repoId, String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                BranchDelivery.<BranchDelivery>find(
                        "projectId = ?1 and repoId = ?2 and branch = ?3", projectId, repoId, branch)
                    .firstResultOptional()
                    .map(row -> row.step)
                    .orElse(null));
  }

  private BranchDeliveryStep last(String branch) {
    List<BranchDeliveryStep> steps = announced.stepsOf(branch);
    return steps.get(steps.size() - 1);
  }

  /** A release of {@code branch} in {@code repoId}, gated green and landed. Answers the tag. */
  private String releaseOf(String repoId, String branch) {
    String tag = stageRelease();
    String id = create(repoId, branch);
    verdict("BuildSuccessful", repoId, mergedShaOf(repoId, id), run("green"), null);
    awaitState(repoId, id, "RELEASED");
    return tag;
  }

  /** A release of {@code branch} in {@code repoId} under the staged {@code tag}. */
  private String releaseOf(String repoId, String branch, String tag) {
    String id = create(repoId, branch);
    verdict("BuildSuccessful", repoId, mergedShaOf(repoId, id), run("green"), null);
    awaitState(repoId, id, "RELEASED");
    return tag;
  }

  /**
   * The next release's version, whose tree declares a release pipeline qits-ci runs and no
   * deployment: the publish gate alone stands between its tag and main.
   */
  private String stagePublishedRelease() {
    String tag = "2026.1031." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of(
            "pom.xml",
            "irrelevant",
            RecordingReleaseGitHost.RELEASE_CONFIG,
            RecordingReleaseGitHost.GATING_RELEASE_CONFIG));
    publishRuns.answer(Optional.of(true));
    return tag;
  }

  /** A publish run's verdict: the branch is the version. */
  private void publishVerdict(String name, String repoId, String version, String runId) {
    buildListener.onFrame(
        frame(
            name,
            "{\"branch\":\""
                + version
                + "\",\"commitSha\":\""
                + RecordingBackingBranchMerger.freshSha()
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\""
                + runId
                + "\""
                + ("BuildFailed".equals(name) ? ",\"outcome\":\"FAILED\"" : "")
                + "}"));
  }

  /** qits-ci's {@code BuildStatusChanged} for a publish run of {@code version}. */
  private void publishRunTransition(String repoId, String version, String runId, String status) {
    pipelineRunListener.onFrame(
        frame(
            "BuildStatusChanged",
            "{\"branch\":\""
                + version
                + "\",\"phase\":\"RELEASE\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\""
                + runId
                + "\",\"status\":\""
                + status
                + "\"}"));
  }

  /** The next release's version, whose tree declares a deployment and no release pipeline. */
  private String stageRelease() {
    String tag = "2026.1028." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of("pom.xml", "irrelevant", ".config/qits/deployments.yml", "irrelevant"));
    return tag;
  }

  private void redPublish(String repoId, String version) {
    buildListener.onFrame(
        frame(
            "BuildFailed",
            "{\"branch\":\""
                + version
                + "\",\"commitSha\":\""
                + RecordingBackingBranchMerger.freshSha()
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\",\"outcome\":\"FAILED\"}"));
  }

  private void deploymentActive(String version) {
    deployments.onFrame(
        frame(
            "DeploymentActive",
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\"delivery\",\"environmentName\":\"dev\",\"version\":\""
                + version
                + "\"}"));
  }

  private void verdict(String name, String repoId, String sha, String runId, String retryOf) {
    buildListener.onFrame(
        frame(
            name,
            "{\"branch\":\""
                + BackingBranches.atFold(repoId, sha)
                + "\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\""
                + (retryOf == null ? "" : ",\"retryOfRunId\":\"" + retryOf + "\"")
                + ",\"runId\":\""
                + runId
                + "\""
                + ("BuildFailed".equals(name) ? ",\"outcome\":\"FAILED\"" : "")
                + "}"));
  }

  private void headMoved(String repoId, String branch, String sha) {
    headListener.onFrame(
        frame(
            "SCMPublishCommit",
            "{\"branch\":\"" + branch + "\",\"repoId\":\"" + repoId + "\",\"sha\":\"" + sha + "\"}"));
  }

  /** A conflict of the request's fold, naming one head at one tip and one path. */
  private static BackingBranchMerger.Outcome conflictOf(String id, String head, String headSha) {
    return BackingBranchMerger.Outcome.conflict(
        BackingBranches.ref(id),
        List.of(new BackingBranchMerger.Conflict("pom.xml", head, headSha, "content")));
  }

  private static EventFrame frame(String name, String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(), name, Instant.now(), payload, null, null, null);
  }

  private void awaitMoves(String branch, List<String> expected) {
    await(() -> announced.movesOf(branch).equals(expected));
    assertEquals(expected, announced.movesOf(branch));
    List<RecordingBranchDeliveryAnnouncer.Recorded> mine =
        announced.recorded().stream().filter(r -> r.step().branch().equals(branch)).toList();
    for (RecordingBranchDeliveryAnnouncer.Recorded each : mine) {
      assertTrue(each.committedStep() != null, "every step was committed before it was told");
    }
  }

  private BranchDeliveryStep awaitLastStep(String branch, String step) {
    await(
        () -> {
          List<BranchDeliveryStep> steps = announced.stepsOf(branch);
          return !steps.isEmpty() && step.equals(steps.get(steps.size() - 1).step());
        });
    BranchDeliveryStep last = last(branch);
    assertEquals(step, last.step(), "steps: " + announced.movesOf(branch));
    return last;
  }

  private void awaitState(String repoId, String id, String expected) {
    await(() -> expected.equals(stateOf(repoId, id)));
    assertEquals(expected, stateOf(repoId, id));
  }

  private static void await(Supplier<Boolean> condition) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.get()) {
        return;
      }
      pause(50);
    }
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted");
    }
  }

  private static String run(String what) {
    return "run-" + what + "-" + UUID.randomUUID();
  }

  private static String sha() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
