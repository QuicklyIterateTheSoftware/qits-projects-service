package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.DeploymentActiveListener;
import eu.wohlben.qits.projects.bus.DeploymentRequestDeclinedListener;
import eu.wohlben.qits.projects.control.DeploymentRequests;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import eu.wohlben.qits.projects.deploymenthost.FakeDeploymentRequests;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A declined deploy ends its release request WITHDRAWN, and its tag never ships</b> (qits-2271).
 *
 * <p>A deploy held for a person (qits-2268) can be declined at qits-deployments, which announces
 * {@code DeploymentRequestDeclined} per settled deployment request. Pinned here: the event ends a
 * RELEASED request WITHDRAWN with the decline in its detail, its branches WITHDRAWN and its tag row
 * stamped declined and out of the implicit source set — never merged, so the tag stays on the git
 * host; a second event, a FINALIZED request and an unknown one change nothing; the successor held
 * behind it re-folds without the declined tag and releases; the reconcile ends a declined request
 * whose event was lost; the rollback gate reads FAILED; and a DEPLOY rerun of the withdrawn request
 * is refused.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class DeploymentDeclineTest {

  private static final String GATE = "qualityGates.find { it.kind == 'deployment-not-rolled-back' }";

  @Inject BuildStatusListener listener;

  @Inject DeploymentActiveListener actives;

  @Inject DeploymentRequestDeclinedListener declines;

  @Inject ReleaseFinalization finalization;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingQaRunCancellations cancellations;

  @Inject FakeDeploymentRequests deploymentRequests;

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
    repoId = "decline-repo-" + UUID.randomUUID();
    projectId = "decline-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "decline";
              project.slug = "decline-" + UUID.randomUUID();
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
    deploymentRequests.reset();
  }

  /**
   * The event path: WITHDRAWN with the decline in the detail and nothing to retry, the branch
   * WITHDRAWN, QA runs cancelled, and the tag row declined — out of the implicit set, never merged.
   */
  @Test
  public void aDeclinedDeployWithdrawsTheReleasedRequest() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    String qualified = qualifiedIdOf(request);
    assertEquals("RELEASED", ledgerStep("work-a"), "the premise: the branch is released");
    cancellations.reset();

    declined("dr-1", qualified, tag, "alice", "wrong change");

    JsonPath row = single(request);
    assertEquals("WITHDRAWN", row.getString("state"));
    assertEquals(
        "Deploy of " + tag + " declined by alice (deployment request dr-1): wrong change",
        row.getString("detail"));
    assertFalse(row.getBoolean("retryable"), "nothing finishes a declined release");
    assertEquals("WITHDRAWN", ledgerStep("work-a"), "a --until live wait returns");
    assertTrue(
        cancellations.cancelledRequests().contains(request),
        "its runs are cancelled: " + cancellations.cancelledRequests());
    ReleasedTagPendingMerge tagRow = rowOf(tag);
    assertNotNull(tagRow.declinedAt);
    assertNull(tagRow.mergedAt, "never merged, so the git tag stays on the host");
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> pendingTags.listPending(repoId).stream().noneMatch(t -> t.id.equals(tagRow.id))),
        "the declined tag left the implicit source set");
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty(), "and nothing reached main");
  }

  /** A note-less decline leaves the colon out; a second event — a sibling's — changes nothing. */
  @Test
  public void aSecondDeclineIsANoOp() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    String qualified = qualifiedIdOf(request);

    declined("dr-1", qualified, tag, "alice", null);
    JsonPath first = single(request);
    assertEquals(
        "Deploy of " + tag + " declined by alice (deployment request dr-1)",
        first.getString("detail"));
    Instant stamped = rowOf(tag).declinedAt;

    declined("dr-2", qualified, tag, "alice", null);
    assertFalse(
        finalization.onDeployDeclined(qualified, tag, "bob", "again", "dr-3"),
        "the method answers that it ended nothing");

    JsonPath after = single(request);
    assertEquals("WITHDRAWN", after.getString("state"));
    assertEquals(first.getString("detail"), after.getString("detail"), "the first word stays");
    assertEquals(first.getString("updatedAt"), after.getString("updatedAt"));
    assertEquals(stamped, rowOf(tag).declinedAt);
  }

  /** A request that already finalized, an unknown one and a wrong version are all left alone. */
  @Test
  public void aFinalizedOrUnknownRequestIsLeftAlone() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    String qualified = qualifiedIdOf(request);

    assertFalse(finalization.onDeployDeclined(qualified, "2026.1.1", "alice", null, "dr-1"));
    assertEquals("RELEASED", stateOf(request), "a version that is not its own ends nothing");
    assertFalse(finalization.onDeployDeclined("nobody-rr-404", tag, "alice", null, "dr-1"));
    declined("dr-1", "nobody-rr-404", tag, "alice", null);

    deploymentActive(tag);
    finalization.sweep();
    awaitState(request, "FINALIZED");

    declined("dr-1", qualified, tag, "alice", "too late");

    assertEquals("FINALIZED", stateOf(request));
    assertNull(rowOf(tag).declinedAt);
    assertNotNull(rowOf(tag).mergedAt);
  }

  /**
   * <b>The successor held behind a declined release re-folds without it and releases.</b> It was
   * PENDING "Waiting for … to finalize" with the declined tag folded in as an implicit source; the
   * decline re-folds it at once, the new fold drops the tag, and with its QA green it releases.
   */
  @Test
  public void theSuccessorRefoldsWithoutTheDeclinedTagAndReleases() {
    String tagA = releaseOf("work-a");
    String earlier = firstRequest();
    String successor = create("work-b");
    verdict(mergedShaOf(successor));
    awaitDetail(successor, qualifiedIdOf(earlier));
    assertTrue(
        lastFoldSources(successor).contains("refs/tags/" + tagA),
        "the premise: the declined tag is an implicit source: " + lastFoldSources(successor));
    String heldAt = mergedShaOf(successor);
    String tagB = stageRelease();

    declined("dr-1", qualifiedIdOf(earlier), tagA, "alice", "wrong change");

    assertEquals("WITHDRAWN", stateOf(earlier));
    String refolded = mergedShaOf(successor);
    assertNotEquals(heldAt, refolded, "the decline re-folded the successor at once");
    assertFalse(
        lastFoldSources(successor).contains("refs/tags/" + tagA),
        "and the new fold carries none of the declined commits: " + lastFoldSources(successor));

    verdict(refolded);

    awaitState(successor, "RELEASED");
    assertEquals(tagB, versionOf(successor));
    assertNull(rowOf(tagA).mergedAt, "the declined tag never reaches main");
  }

  /** A lost event: the reconcile reads the newest deployment request declined and ends it. */
  @Test
  public void theReconcileEndsADeclineWhoseEventWasLost() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    Instant at = Instant.parse("2026-11-16T10:00:00Z");
    deploymentRequests.answer(
        Optional.of(
            List.of(
                new DeploymentRequests.DeploymentRequestView(
                    "dr-7",
                    null,
                    at,
                    "UNMET",
                    at,
                    "declined by alice: wrong change",
                    true,
                    "alice",
                    at))));

    assertEquals(0, finalization.reconcileDeployments(Duration.ofHours(1)), "too fresh to ask");
    assertEquals("RELEASED", stateOf(request));

    assertEquals(1, finalization.reconcileDeployments(Duration.ZERO));

    JsonPath row = single(request);
    assertEquals("WITHDRAWN", row.getString("state"));
    assertEquals(
        "Deploy of " + tag + " declined by alice (deployment request dr-7): wrong change",
        row.getString("detail"));
    assertNotNull(rowOf(tag).declinedAt);
    assertEquals(0, finalization.reconcileDeployments(Duration.ZERO), "and it asks no more");
  }

  /**
   * The rollback gate reads FAILED: on a single read whose newest deployment request is declined,
   * before anything ended the request, and on the list read of the withdrawn request afterwards.
   */
  @Test
  public void theRollbackGateReadsADeclineFailed() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    Instant at = Instant.parse("2026-11-16T10:00:00Z");
    deploymentRequests.answer(
        Optional.of(
            List.of(
                new DeploymentRequests.DeploymentRequestView(
                    "dr-7", null, at, "UNMET", at, "declined by alice", true, "alice", at))));

    JsonPath asked = single(request);
    assertEquals("RELEASED", asked.getString("state"), "the premise: nothing ended it yet");
    assertEquals("FAILED", asked.getString(GATE + ".state"));
    assertEquals("Deploy of " + tag + " declined by alice", asked.getString(GATE + ".detail"));

    declined("dr-7", qualifiedIdOf(request), tag, "alice", null);
    deploymentRequests.reset();

    JsonPath listed = listed(request);
    assertEquals("WITHDRAWN", listed.getString("state"));
    assertEquals("FAILED", listed.getString(GATE + ".state"));
    assertTrue(
        listed.getString(GATE + ".detail").startsWith("Deploy of " + tag + " declined"),
        listed.getString(GATE + ".detail"));
  }

  /** A DEPLOY rerun of the withdrawn request is refused: it would re-announce the declined version. */
  @Test
  public void aDeployRerunOfTheWithdrawnRequestIsRefused() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    declined("dr-1", qualifiedIdOf(request), tag, "alice", null);

    String message =
        given()
            .contentType(ContentType.JSON)
            .post(base() + "/" + request + "/pipeline/DEPLOY/rerun")
            .then()
            .statusCode(409)
            .extract()
            .path("message");
    assertTrue(message.contains("is WITHDRAWN"), message);
  }

  /**
   * The listener reads the payload exactly as qits-deployments' {@code DeployEventsTest} pins it,
   * and a poison payload settles with nothing done.
   */
  @Test
  public void theListenerDecodesARealPayloadAndSettlesPoison() {
    String tag = releaseOf("work-a");
    String request = firstRequest();

    declines.onFrame(frame("not json at all"));
    declines.onFrame(frame("{\"appName\":\"decline\",\"version\":\"" + tag + "\"}"));
    assertEquals("RELEASED", stateOf(request), "poison and an unnamed request end nothing");

    declines.onFrame(
        frame(
            "{\"appName\":\"decline\",\"declinedAt\":\"2026-08-12T10:00:00Z\","
                + "\"declinedBy\":\"alice\",\"deploymentRequestId\":\"r-1\","
                + "\"environment\":\"dev\",\"note\":\"wrong change\","
                + "\"releaseRequestQualifiedId\":\""
                + qualifiedIdOf(request)
                + "\",\"version\":\""
                + tag
                + "\"}"));

    assertEquals(
        "Deploy of " + tag + " declined by alice (deployment request r-1): wrong change",
        single(request).getString("detail"));
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture — DeploymentRollbackTest's and ReleaseSupersessionTest's
  // -----------------------------------------------------------------------------------------------

  /** One branch driven to RELEASED on a main that declares a deployment, and left there. */
  private String releaseOf(String branch) {
    Map<String, String> main = new HashMap<>(RecordingReleaseGitHost.GATED_MAIN);
    main.put(".config/qits/deployments.yml", "resources: []\n");
    gitHost.tree("refs/heads/main", main);
    String tag = stageRelease();
    String id = create(branch);
    verdict(mergedShaOf(id));
    awaitState(id, "RELEASED");
    return tag;
  }

  /** What the next release will be: a fresh version whose tree declares a deployment. */
  private String stageRelease() {
    String tag = "2026.1116." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of("pom.xml", "irrelevant", ".config/qits/deployments.yml", "irrelevant"));
    return tag;
  }

  private void declined(
      String deploymentRequestId, String qualifiedId, String version, String who, String note) {
    declines.onFrame(
        frame(
            "{\"appName\":\"decline\",\"declinedAt\":\""
                + Instant.now()
                + "\",\"declinedBy\":\""
                + who
                + "\",\"deploymentRequestId\":\""
                + deploymentRequestId
                + "\",\"environment\":\"dev\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"")
                + ",\"releaseRequestQualifiedId\":\""
                + qualifiedId
                + "\",\"version\":\""
                + version
                + "\"}"));
  }

  private static EventFrame frame(String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "DeploymentRequestDeclined",
        Instant.now(),
        payload,
        null,
        null,
        null);
  }

  private void deploymentActive(String version) {
    actives.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\"decline\",\"environmentName\":\"dev\",\"version\":\""
                + version
                + "\"}",
            null,
            null,
            null));
  }

  private void verdict(String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\""
                + BackingBranches.atFold(repoId, sha)
                + "\",\"commitSha\":\""
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

  private List<String> lastFoldSources(String requestId) {
    List<RecordingBackingBranchMerger.Fold> folds =
        merger.foldsOf(BackingBranches.ref(requestId));
    return folds.get(folds.size() - 1).sources();
  }

  private String ledgerStep(String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                BranchDelivery.<BranchDelivery>find(
                        "projectId = ?1 and repoId = ?2 and branch = ?3", projectId, repoId, branch)
                    .firstResultOptional()
                    .map(row -> row.step)
                    .orElse(null));
  }

  /** The request as the single read draws it — the read that asks qits-deployments. */
  private JsonPath single(String id) {
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  /** The request as the LIST read of every state draws it — no deployments call behind it. */
  private JsonPath listed(String id) {
    JsonPath page =
        given().get(base() + "?state=all").then().statusCode(200).extract().jsonPath();
    String root = "requests.find { it.id == '" + id + "' }";
    assertNotNull(page.get(root), "request " + id + " is on the list");
    return page.setRootPath(root);
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

  private String qualifiedIdOf(String id) {
    return single(id).getString("qualifiedId");
  }

  private String stateOf(String id) {
    return single(id).getString("state");
  }

  private String versionOf(String id) {
    return single(id).getString("version");
  }

  private String mergedShaOf(String id) {
    return single(id).getString("mergedSha");
  }

  private String detailOf(String id) {
    String detail = single(id).getString("detail");
    return detail == null ? "" : detail;
  }

  private void awaitDetail(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (detailOf(id).contains(expected)) {
        return;
      }
      sleep();
    }
    fail("request " + id + " never said " + expected + "; last said " + detailOf(id));
  }

  private void awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(id);
      if (expected.equals(last)) {
        return;
      }
      sleep();
    }
    fail("request " + id + " never reached " + expected + "; last seen " + last);
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted");
    }
  }
}
