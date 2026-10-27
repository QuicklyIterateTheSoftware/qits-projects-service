package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A new release waits behind one in flight, and supersedes only a red one</b> (qits-1471).
 *
 * <p>Since a release request stays open until its tag reaches {@code main} (ticket b27384a3), a
 * repository can be asked for a new release while an earlier one is still owed its publish run or
 * its deployment. The new request folds the earlier tag in either way, so its content is a superset
 * of what is shipping. What changed is what happens to the earlier <em>request</em>: FINALIZATION is
 * the green light, not the tag, so a release still in flight is left to finish and the new request
 * runs its gates and then waits before READY until it has. Until qits-1471 it was obsoleted on the
 * spot, and a release that was deploying fine never finalized and its tag never merged.
 *
 * <p>A release that went <b>red</b> — its publish run FAILED, or its deployment failed and never
 * went live — is the one nothing will finish, and that one is still superseded, with all four halves
 * of ending it together: the state, the pointer at the successor, the cancellation, and the
 * abandoned merge. The last is the one only a negative can show — the deployment that would have
 * finalized the old release arrives, and nothing moves.
 */
@QuarkusTest
public class ReleaseSupersessionTest {

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.DeploymentActiveListener deployments;

  @Inject ReleaseFinalization finalization;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingQaRunCancellations cancellations;

  @Inject RecordingReleaseRequestStateAnnouncer states;

  @Inject eu.wohlben.qits.projects.deploymenthost.FakeDeploymentRequests deploymentRequests;

  @Inject eu.wohlben.qits.projects.control.ReleaseRequests releaseRequests;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    cancellations.reset();
    deploymentRequests.reset();
    activeBuilds.answer(Optional.of(0));
    repoId = "supersede-repo-" + UUID.randomUUID();
    projectId = "supersede-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "supersede";
              project.slug = "supersede-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
            });
  }

  /** Both sweeps walk the whole database, so nothing of this fixture may outlive the class. */
  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
    // The fake is application-wide: a FAILED staged here must not read as red to another class.
    deploymentRequests.reset();
  }

  /**
   * <b>The bug, pinned the right way round.</b> The earlier release is RELEASED and still owed its
   * deployment — in flight, nothing red. A new ask leaves it exactly where it was: RELEASED, its tag
   * row not abandoned, none of its runs cancelled. The new request runs its gates, and with every one
   * of them green it still stays PENDING, saying which release it waits for.
   */
  @Test
  public void aNewRequestWaitsBehindTheReleaseInFlightAndLeavesItAlone() {
    String tag = releaseOf("work-a");
    String earlier = firstRequest();
    assertEquals("RELEASED", stateOf(earlier), "the premise: released and not finished");
    cancellations.reset();

    String successor = create("work-b");

    assertEquals("RELEASED", stateOf(earlier), "a release in flight is not superseded");
    assertTrue(
        states.movesOf(earlier).stream().noneMatch(move -> move.endsWith(">OBSOLETE")),
        "nor announced as if it were: " + states.movesOf(earlier));
    assertNull(rowOf(tag).abandonedAt, "its merge is still owed, so main still gets it");
    assertNull(
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"));
    assertTrue(
        !cancellations.cancelledRequests().contains(earlier),
        "and nothing of it is cancelled: " + cancellations.cancelledRequests());

    verdict("BuildSuccessful", mergedShaOf(successor));

    String waitingFor = qualifiedIdOf(earlier);
    awaitDetail(successor, waitingFor);
    assertEquals("PENDING", stateOf(successor), "every gate is green and it still waits");
    assertTrue(
        detailOf(successor).contains("to finalize"),
        "and says what for: " + detailOf(successor));
    assertEquals("RELEASED", stateOf(earlier));
  }

  /**
   * <b>Finalization is what lets the held request go.</b> The earlier release's deployment arrives,
   * its tag merges into {@code main} and it FINALIZES; that move re-folds every unreleased request
   * of the repository, which re-evaluates the held one — no sweep needed. {@code main} moved, so the
   * fold is a new commit and QA answers for it again; then it is READY and releases.
   */
  @Test
  public void theHeldRequestReleasesOnceTheEarlierOneFinalizes() {
    String tag = releaseOf("work-a");
    String earlier = firstRequest();
    String successor = create("work-b");
    verdict("BuildSuccessful", mergedShaOf(successor));
    awaitDetail(successor, qualifiedIdOf(earlier));
    String heldAt = mergedShaOf(successor);
    String tagB = stageRelease();

    deploymentActive(tag);
    finalization.sweep();

    awaitState(earlier, "FINALIZED");
    ReleasedTagPendingMerge merged = rowOf(tag);
    assertNotNull(merged.mergedAt, "its tag reached main under its own name");
    assertNull(merged.abandonedAt);
    assertTrue(
        merger.foldsOf("refs/heads/main").stream()
            .anyMatch(fold -> fold.sources().contains(merged.releasedSha)),
        "the merge into main is of the earlier release itself: "
            + merger.foldsOf("refs/heads/main"));
    String refolded = mergedShaOf(successor);
    assertTrue(
        !heldAt.equals(refolded),
        "main moving re-folded the held request without waiting for the sweep");

    verdict("BuildSuccessful", refolded);

    awaitState(successor, "RELEASED");
    assertEquals(tagB, versionOf(successor));
    assertEquals("FINALIZED", stateOf(earlier), "and nothing superseded what finished");
    assertNull(
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"));
  }

  /**
   * <b>A red release is superseded as it always was.</b> Its release run came back FAILED, so
   * nothing will finish it: the fresh request makes it OBSOLETE at create, names itself as the
   * successor, cancels its QA runs (and only those — qits-733) and abandons its merge.
   */
  @Test
  public void aNewRequestObsoletesARedReleaseAndStopsItsQaRuns() {
    String tag = releaseOf("work-a");
    assertEquals("RELEASED", stateOf(firstRequest()), "the premise: released and not finished");
    String earlier = firstRequest();
    redPublish(tag);
    assertEquals(
        ReleasedTagPendingMerge.PublishState.FAILED,
        rowOf(tag).publishState,
        "the premise: its release run is red");
    assertEquals("RELEASED", stateOf(earlier), "a red publish alone never moves the request");
    cancellations.reset();

    String successor = create("work-b");

    assertEquals("OBSOLETE", stateOf(earlier));
    assertTrue(
        states.movesOf(earlier).contains("RELEASED>OBSOLETE"),
        "the supersession is announced as a state change (qits-1153): " + states.movesOf(earlier));
    assertEquals(
        successor,
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"),
        "the row names what overtook it, so a reader is never left guessing");
    assertTrue(
        detailOf(earlier).contains(successor),
        "and says so in a sentence too: " + detailOf(earlier));
    assertEquals(
        List.of(earlier),
        cancellations.cancelledRequests(),
        "the overtaken request's runs are cancelled, and only its own");
    assertTrue(
        cancellations.calls().get(0).qaOnly(),
        "a superseded release keeps its publish run (qits-733)");

    ReleasedTagPendingMerge abandoned = rowOf(tag);
    assertNotNull(abandoned.abandonedAt, "and its merge is abandoned rather than left to the sweep");

    // The negative that matters: the gate the old release was waiting for arrives, and nothing
    // moves. Its content is not lost — the successor folds the tag in, one assertion down.
    merger.reset();
    deploymentActive(tag);
    finalization.sweep();

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergedAt, "a superseded release reaches main inside its successor");
  }

  /**
   * <b>A release that turns red while its successor waits is superseded by that successor's
   * release.</b> The red sibling stops holding on the next evaluation — the sweep's here — so the
   * successor goes READY and releases, and the release ends the red one with the create arm's
   * bookkeeping: OBSOLETE, pointing at the successor, its tag abandoned, its QA runs cancelled.
   */
  @Test
  public void anEarlierReleaseThatTurnsRedWhileTheSuccessorWaitsIsSupersededByItsRelease() {
    String tag = releaseOf("work-a");
    String earlier = firstRequest();
    String successor = create("work-b");
    verdict("BuildSuccessful", mergedShaOf(successor));
    awaitDetail(successor, qualifiedIdOf(earlier));
    String tagB = stageRelease();
    cancellations.reset();

    redPublish(tag);
    assertEquals("RELEASED", stateOf(earlier), "the premise: red, and still open");
    releaseRequests.sweep();

    awaitState(successor, "RELEASED");
    assertEquals(tagB, versionOf(successor));
    awaitState(earlier, "OBSOLETE");
    assertTrue(
        states.movesOf(earlier).contains("RELEASED>OBSOLETE"), "" + states.movesOf(earlier));
    assertEquals(
        successor,
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"));
    assertTrue(detailOf(earlier).contains(qualifiedIdOf(successor)), detailOf(earlier));
    assertNotNull(rowOf(tag).abandonedAt, "its merge is abandoned");
    assertTrue(
        cancellations.calls().stream()
            .anyMatch(call -> call.releaseRequestId().equals(earlier) && call.qaOnly()),
        "its QA runs are cancelled, and only those: " + cancellations.calls());
  }

  /**
   * <b>A red deployment is red too</b>: the repository deploys (its {@code main} declares {@code
   * deployments.yml}), qits-deployments says the newest deployment of the earlier version FAILED,
   * and that version never went live. Nothing will finish it, so a fresh request supersedes it just
   * as it supersedes a red publish.
   */
  @Test
  public void aRedDeploymentIsSupersededLikeARedPublish() {
    Map<String, String> deployingMain = new java.util.HashMap<>(RecordingReleaseGitHost.GATED_MAIN);
    deployingMain.put(".config/qits/deployments.yml", "resources: []\n");
    String tag = releaseOf("work-a", deployingMain);
    String earlier = firstRequest();
    deploymentRequests.answerStatus("deployment-1", "FAILED");
    cancellations.reset();

    String successor = create("work-b");

    assertEquals("OBSOLETE", stateOf(earlier));
    assertEquals(
        successor,
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"));
    assertTrue(detailOf(earlier).contains("deployment"), detailOf(earlier));
    assertNotNull(rowOf(tag).abandonedAt);
    assertEquals(List.of(earlier), cancellations.cancelledRequests());
    assertTrue(cancellations.calls().get(0).qaOnly());
  }

  /**
   * <b>A rolled-back deployment is red too</b> (qits-1690). {@code ROLLED_BACK} folds to CANCELLED
   * for the pipeline panel, and the hold used to read it through that fold — so a rolled-back
   * release never turned red and held every later request of its repository for ever. It is a
   * deployment that never went live: the new request is not held behind it, and supersedes it.
   */
  @Test
  public void aRolledBackDeploymentIsRedAndHoldsNothing() {
    Map<String, String> deployingMain = new java.util.HashMap<>(RecordingReleaseGitHost.GATED_MAIN);
    deployingMain.put(".config/qits/deployments.yml", "resources: []\n");
    String tag = releaseOf("work-a", deployingMain);
    String earlier = firstRequest();
    deploymentRequests.answerStatus("deployment-1", "ROLLED_BACK");

    String successor = create("work-b");

    assertEquals("OBSOLETE", stateOf(earlier));
    assertEquals(
        successor,
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"));
    assertTrue(detailOf(earlier).contains("deployment"), detailOf(earlier));
    assertNotNull(rowOf(tag).abandonedAt);
    assertTrue(
        !detailOf(successor).contains("to finalize"),
        "the successor is not held behind it: " + detailOf(successor));
  }

  /**
   * The same, from the failure RECORDED on the row (qits-1690) rather than from a live answer:
   * qits-deployments cannot be asked here, which alone would read as in flight, yet the release is
   * red because a {@code DeploymentFailed} (or the reconcile) said so.
   */
  @Test
  public void aRecordedDeploymentFailureIsRedWithoutAskingQitsDeployments() {
    Map<String, String> deployingMain = new java.util.HashMap<>(RecordingReleaseGitHost.GATED_MAIN);
    deployingMain.put(".config/qits/deployments.yml", "resources: []\n");
    String tag = releaseOf("work-a", deployingMain);
    String earlier = firstRequest();
    deploymentRequests.answerCouldNotAsk();
    finalization.onDeploymentFailed("supersede", tag, "ROLLED_BACK", "rolled back by swarm");
    assertNotNull(rowOf(tag).deploymentFailedAt, "the premise: recorded on the row");

    String successor = create("work-b");

    assertEquals("OBSOLETE", stateOf(earlier));
    assertEquals(
        successor,
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"));
    assertNotNull(rowOf(tag).abandonedAt);
  }

  /**
   * The other half, and the reason superseding a red release is safe at all: the tag really was
   * cut, so the successor folds it in — whether the earlier release is red or still in flight — and
   * every commit it carried ships under the successor's version.
   */
  @Test
  public void theSuccessorFoldsTheSupersededTagIn() {
    String tag = releaseOf("work-a");

    String successor = create("work-b");

    List<String> sources =
        merger.foldsOf(BackingBranches.ref(successor)).get(0).sources();
    assertTrue(
        sources.contains("refs/tags/" + tag),
        "the superseded release is an implicit source of what supersedes it: " + sources);
  }

  /**
   * <b>A superseded tag leaves the implicit set when its successor reaches main</b> — the half of
   * "it reaches main inside its successor" that the bookkeeping has to notice. Nothing ever merges
   * the obsoleted tag itself, so a stamp that only named the tag just merged left it pending for
   * ever, and every later request went on folding a tag main already held.
   *
   * <p>The lineage is the real one: B's release folded A's tag in, and finalization merges B into
   * main — so main contains A through B, and the fake's containment answers exactly that.
   */
  @Test
  public void aSupersededTagLeavesTheImplicitSetWhenItsSuccessorReachesMain() {
    String tagA = releaseOf("work-a");
    String requestA = firstRequest();
    // Red, or B would wait for A to finalize rather than supersede it (qits-1471).
    redPublish(tagA);
    String tagB = releaseOf("work-b");
    String requestB = rowOf(tagB).releaseRequestId;
    assertEquals("OBSOLETE", stateOf(requestA), "the premise: B superseded the red A");
    gitHost.containsCommit(repoId, rowOf(tagA).releasedSha, rowOf(tagB).releasedSha);

    deploymentActive(tagB);
    finalization.sweep();

    assertNotNull(rowOf(tagB).mergedAt, "the successor's own tag reached main");
    assertNotNull(rowOf(tagA).mergedAt, "and the tag it superseded reached main inside it");
    assertEquals("FINALIZED", stateOf(requestB));
    assertEquals("OBSOLETE", stateOf(requestA), "a superseded request is not resurrected");

    String requestC = create("work-c");
    List<String> sources = merger.foldsOf(BackingBranches.ref(requestC)).get(0).sources();
    assertTrue(
        sources.stream().noneMatch(source -> source.startsWith("refs/tags/")),
        "nothing main already holds is folded in again: " + sources);
  }

  /**
   * <b>"Could not ask" is not "yes"</b>: where the git host cannot say whether main contains the
   * superseded tag, the tag stays pending and keeps being folded in — the safe direction, since
   * dropping a tag that is not on main would be a step backwards from what shipped.
   */
  @Test
  public void aSupersededTagWhoseContainmentCannotBeAskedStaysAnImplicitSource() {
    String tagA = releaseOf("work-a");
    redPublish(tagA);
    String tagB = releaseOf("work-b");
    gitHost.containmentOfUnreadable(
        repoId,
        rowOf(tagA).releasedSha,
        eu.wohlben.qits.projects.control.ReleaseGitHost.Answer.failedRetryable(
            "git host unreachable"));

    deploymentActive(tagB);
    finalization.sweep();

    assertNotNull(rowOf(tagB).mergedAt, "the successor itself still finalizes");
    assertEquals("FINALIZED", stateOf(rowOf(tagB).releaseRequestId));
    assertNull(rowOf(tagA).mergedAt, "an unanswered question stamps nothing");

    String requestC = create("work-c");
    List<String> sources = merger.foldsOf(BackingBranches.ref(requestC)).get(0).sources();
    assertTrue(
        sources.contains("refs/tags/" + tagA),
        "so it is still an implicit source of what comes next: " + sources);
  }

  /**
   * <b>Convergence and obsolescence never fight, because they cannot see the same rows.</b> Every
   * convergence read is over the unreleased states and every obsolescence read is over RELEASED, so
   * asking again about a branch that already participates in an open, untagged request answers that
   * request and obsoletes nothing.
   */
  @Test
  public void aConvergingAskAnswersTheOpenRequestAndObsoletesNothing() {
    String first = create("work-a");

    String again = create("work-a");

    assertEquals(first, again, "the same branch converges, as it always did");
    assertEquals("PENDING", stateOf(first), "and nothing about it was obsoleted");
    assertNull(
        given().get(base() + "/" + first).then().extract().path("request.supersededBy"),
        "there is no successor, because there was no second request to be one");
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  /**
   * Drive one branch all the way to RELEASED and leave it there, waiting on a deployment that never
   * comes — in flight, which is exactly the state a later ask waits behind. Answers the version it
   * released as.
   */
  private String releaseOf(String branch) {
    return releaseOf(branch, RecordingReleaseGitHost.GATED_MAIN);
  }

  /** {@link #releaseOf(String)} with the repository's {@code main} as given — its gate set. */
  private String releaseOf(String branch, Map<String, String> main) {
    gitHost.tree("refs/heads/main", main);
    String tag = stageRelease();
    String id = create(branch);
    verdict("BuildSuccessful", mergedShaOf(id));
    awaitState(id, "RELEASED");
    return tag;
  }

  /**
   * What the next release will be: the executor answers a fresh version, whose released tree
   * declares a deployment and no release pipeline. Answers the version.
   */
  private String stageRelease() {
    String tag = "2026.915." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of("pom.xml", "irrelevant", ".config/qits/deployments.yml", "irrelevant"));
    return tag;
  }

  /**
   * The released tag's own release run comes back FAILED — a publish verdict, whose branch is the
   * version — which is what makes a RELEASED request red (qits-1471).
   */
  private void redPublish(String version) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildFailed",
            Instant.now(),
            "{\"branch\":\""
                + version
                + "\",\"commitSha\":\""
                + RecordingBackingBranchMerger.freshSha()
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\",\"outcome\":\"FAILED\"}",
            null,
            null,
            null));
  }

  private String qualifiedIdOf(String id) {
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.qualifiedId");
  }

  private String versionOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.version");
  }

  private void awaitDetail(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (detailOf(id).contains(expected)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("request " + id + " never said " + expected + "; last said " + detailOf(id));
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a release of " + branch + "\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  /** The oldest request of the fixture repository — the one {@link #releaseOf} made. */
  private String firstRequest() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequest.<ReleaseRequest>find("repoId = ?1 order by createdAt", repoId)
                    .firstResult()
                    .id);
  }

  private ReleasedTagPendingMerge rowOf(String tag) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find(
                        "repoId = ?1 and tagName = ?2", repoId, tag)
                    .firstResult());
  }

  private String stateOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.state");
  }

  private String detailOf(String id) {
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    return detail == null ? "" : detail;
  }

  private String mergedShaOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.mergedSha");
  }

  private void verdict(String name, String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"" + BackingBranches.atFold(repoId, sha) + "\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\"}",
            null,
            null,
            null));
  }

  private void deploymentActive(String version) {
    deployments.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\"supersede\",\"environmentName\":\"dev\",\"version\":\""
                + version
                + "\"}",
            null,
            null,
            null));
  }

  private void awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(id);
      if (expected.equals(last)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("request " + id + " never reached " + expected + "; last seen " + last);
  }
}
