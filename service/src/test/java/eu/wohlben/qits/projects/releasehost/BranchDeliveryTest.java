package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
public class BranchDeliveryTest {

  private static final String SCREENSHOTS = "screenshot-baselines";

  @Inject ReleaseRequests releaseRequests;

  @Inject WorkEntityService entities;

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

    // A retry of that run at the same fold comes back red again: the same position, no second word.
    verdict("BuildFailed", repoA, fold, run("red-again"), red);
    pause(300);
    assertEquals("REJECTED", stateOf(repoA, id));
    assertEquals(List.of("JOINED@" + qid, "FAILED@" + qid), announced.movesOf(branch));

    // A push folds anew; that fold's red verdict is news.
    headMoved(repoA, branch, sha());
    String refold = mergedShaOf(repoA, id);
    assertNotEquals(fold, refold);
    verdict("BuildFailed", repoA, refold, run("red-refold"), null);
    awaitMoves(branch, List.of("JOINED@" + qid, "FAILED@" + qid, "FAILED@" + qid));
    assertEquals(refold, announced.stepsOf(branch).get(2).foldSha());
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
    assertEquals("PUSHED", last(branch).everywhere(), "nothing is delivering it any more");
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
        List.of("JOINED@" + earlierQid, "RELEASED@" + earlierQid, "MOVED@" + successorQid),
        announced.movesOf(branch));
    BranchDeliveryStep moved = last(branch);
    assertEquals(earlier, moved.previousRequestId());
    assertEquals(successor, moved.requestId());
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

  // ---- helpers ---------------------------------------------------------------------------------

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
