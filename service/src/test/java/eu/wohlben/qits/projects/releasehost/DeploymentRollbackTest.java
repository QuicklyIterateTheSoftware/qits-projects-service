package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.DeploymentActiveListener;
import eu.wohlben.qits.projects.bus.DeploymentFailedListener;
import eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import eu.wohlben.qits.projects.deploymenthost.FakeDeploymentRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
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
 * <b>A release whose deployment was rolled back does not stay RELEASED and "waiting" for ever</b>
 * (qits-1690). qits-containers-service 2026.1026.210534 was rolled back by docker swarm, its
 * deployment ended {@code ROLLED_BACK}, and the request sat RELEASED with a deployment gate
 * "Waiting for … to go live" — no {@code DeploymentFailed} was consumed, the event never reached the
 * log anyway, and the list read never asks qits-deployments.
 *
 * <p>Pinned here: a {@code DeploymentFailed} records the failure on the released tag; the reconcile
 * records it from qits-deployments' own answer when no event came; the deployment gate reads FAILED
 * on the list read, naming the remedy, and {@code retryable} says a redeploy finishes it; a redeploy
 * that goes live clears the failure and finalizes as before. Nothing redeploys on its own.
 *
 * <p>And since qits-2296, a release's declared variants count: qits-edge-service-rr-165 — main
 * ACTIVE, its private variant FAILED, the request FINALIZED anyway — is replayed and now holds.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class DeploymentRollbackTest {

  private static final String GATE = "qualityGates.find { it.kind == 'deployment-not-rolled-back' }";

  @Inject BuildStatusListener listener;

  @Inject DeploymentActiveListener actives;

  @Inject DeploymentFailedListener failures;

  @Inject ReleaseFinalization finalization;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeDeploymentRequests deploymentRequests;

  @Inject eu.wohlben.qits.projects.deploymenthost.RecordingDeploymentRedeploys redeploys;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    deploymentRequests.reset();
    redeploys.reset();
    activeBuilds.answer(Optional.of(0));
    repoId = "rollback-repo-" + UUID.randomUUID();
    projectId = "rollback-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "rollback";
              project.slug = "rollback-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
            });
  }

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
   * The event path: a {@code DeploymentFailed} naming the released version records the status and
   * the first line of the deployer's detail, and the list read — which asks qits-deployments nothing
   * — draws the gate FAILED with the remedy, and the request retryable.
   */
  @Test
  public void aDeploymentFailedRecordsTheRollbackAndTheListReadsTheGateFailed() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    assertEquals(
        "PENDING", listed(request).getString(GATE + ".state"), "the premise: waiting to go live");
    assertFalse(listed(request).getBoolean("retryable"));

    deploymentFailed(tag, "ROLLED_BACK", "task: non-zero exit (1)\\nsecond line is not kept");

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentFailedAt);
    assertEquals("ROLLED_BACK: task: non-zero exit (1)", row.deploymentFailedDetail);
    assertNull(row.deploymentActiveAt);
    int asked = deploymentRequests.asked().size();
    JsonPath listedRow = listed(request);
    assertEquals(asked, deploymentRequests.asked().size(), "a list read asks nothing");
    assertEquals("FAILED", listedRow.getString(GATE + ".state"));
    String detail = listedRow.getString(GATE + ".detail");
    assertTrue(
        detail.startsWith(tag + " was rolled back: task: non-zero exit (1) — redeploy"), detail);
    assertTrue(
        detail.endsWith(
            "redeploy with `qits release-request rerun --request "
                + qualifiedIdOf(request)
                + " --phase DEPLOY`"),
        detail);
    assertTrue(listedRow.getBoolean("retryable"), "a redeploy is what finishes it");
    assertEquals("RELEASED", listedRow.getString("state"), "the tag is cut; it stays open");
    assertEquals(
        "FAILED",
        given()
            .get(base() + "/" + request)
            .then()
            .statusCode(200)
            .extract()
            .path("request." + GATE + ".state"),
        "the single read agrees");
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty(), "nothing reached main");
  }

  /**
   * A redeploy that goes live clears the recorded failure and finalizes as a first deployment
   * would; and a failure arriving after the version went live changes nothing.
   */
  @Test
  public void aLaterDeploymentActiveClearsTheFailureAndFinalizes() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    deploymentFailed(tag, "FAILED", "no suitable node");
    assertEquals("FAILED: no suitable node", rowOf(tag).deploymentFailedDetail);
    assertTrue(
        listed(request).getString(GATE + ".detail").startsWith(tag + " failed to deploy: no"));

    deploymentActive(tag);

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentActiveAt);
    assertNull(row.deploymentFailedAt, "the redeploy went live, so the failure is history");
    assertNull(row.deploymentFailedDetail);
    assertNotNull(row.mergeRequestedAt, "and finalization goes on exactly as before");
    assertEquals(1, merger.foldsOf("refs/heads/main").size());

    deploymentFailed(tag, "ROLLED_BACK", "a later rollback");
    assertNull(rowOf(tag).deploymentFailedAt, "a version that went live did ship");
  }

  /**
   * The belt (qits-1690's live case had no event on the log at all): the reconcile asks
   * qits-deployments about a release still waiting, and a ROLLED_BACK newest deployment is recorded.
   * Not before the settle window, and never for a newest deployment that is merely running.
   */
  @Test
  public void theReconcileRecordsARollbackNoEventAnnounced() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    deploymentRequests.answerStatus("deployment-1", "ROLLED_BACK");

    assertEquals(0, finalization.reconcileDeployments(Duration.ofHours(1)), "too fresh to ask");
    assertNull(rowOf(tag).deploymentFailedAt);

    deploymentRequests.answerStatus("deployment-1", "STARTING");
    finalization.reconcileDeployments(Duration.ZERO);
    assertNull(rowOf(tag).deploymentFailedAt, "a deployment still starting is no failure");

    deploymentRequests.answerStatus("deployment-1", "ROLLED_BACK");
    finalization.reconcileDeployments(Duration.ZERO);

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentFailedAt);
    assertEquals("ROLLED_BACK", row.deploymentFailedDetail);
    assertTrue(
        deploymentRequests.asked().stream()
            .anyMatch(asked -> asked.repoId().equals(repoId) && asked.version().equals(tag)));
    assertEquals("FAILED", listed(request).getString(GATE + ".state"));
    assertTrue(listed(request).getBoolean("retryable"));
    assertEquals("RELEASED", listed(request).getString("state"), "nothing redeployed, nothing moved");
  }

  /**
   * qits-2217's live case: the deployment failed, the failure was recorded from its {@code
   * DeploymentFailed}, and qits-deployments later found it running and flipped it to ACTIVE —
   * "recovered by observation" — announcing nothing. The reconcile reads the newest deployment
   * ACTIVE and passes the gate exactly as a {@code DeploymentActive} would: live, the failure
   * cleared, the merge to main owed and folded.
   */
  @Test
  public void theReconcilePassesAFailedDeploymentThatRecoveredNoEventAnnounced() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    deploymentFailed(
        tag, "FAILED", "service rollback still not started yet (no update status) after 180s");
    assertNotNull(rowOf(tag).deploymentFailedAt, "the premise: recorded failed");
    deploymentRequests.answerStatus("deployment-1", "ACTIVE");

    assertEquals(1, finalization.reconcileDeployments(Duration.ZERO), "one row went live");

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentActiveAt, "the reconcile found it live");
    assertNull(row.deploymentFailedAt, "the recovery is what counts; the failure is history");
    assertNull(row.deploymentFailedDetail);
    assertNotNull(row.mergeRequestedAt, "and finalization goes on exactly as on the event");
    assertEquals(1, merger.foldsOf("refs/heads/main").size());
    assertEquals(
        "PASSED",
        given()
            .get(base() + "/" + request)
            .then()
            .statusCode(200)
            .extract()
            .path("request." + GATE + ".state"));
  }

  /** A {@code DeploymentActive} that never reached the log: the reconcile finalizes it anyway. */
  @Test
  public void theReconcilePassesALiveDeploymentWhoseEventWasLost() {
    String tag = releaseOf("work-a");
    deploymentRequests.answerStatus("deployment-1", "ACTIVE");

    assertEquals(1, finalization.reconcileDeployments(Duration.ZERO));

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentActiveAt);
    assertNull(row.deploymentFailedAt);
    assertNotNull(row.mergeRequestedAt);
    assertEquals(1, merger.foldsOf("refs/heads/main").size());
  }

  /** A release younger than the settle window is not asked about, live or not. */
  @Test
  public void theReconcileLeavesALiveDeploymentInsideTheSettleWindowAlone() {
    String tag = releaseOf("work-a");
    deploymentRequests.answerStatus("deployment-1", "ACTIVE");

    assertEquals(0, finalization.reconcileDeployments(Duration.ofHours(1)), "too fresh to ask");

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNull(row.deploymentActiveAt);
    assertNull(row.mergeRequestedAt);
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());
  }

  /**
   * A failure already recorded is never recorded again by the reconcile: its sentence carries no
   * detail, so re-recording would re-word the row (and fail its branches again) on every pass.
   */
  @Test
  public void theReconcileNeverReWordsARecordedFailure() {
    String tag = releaseOf("work-a");
    deploymentFailed(tag, "FAILED", "no suitable node");
    ReleasedTagPendingMerge before = rowOf(tag);
    assertEquals("FAILED: no suitable node", before.deploymentFailedDetail);

    deploymentRequests.answerStatus("deployment-1", "FAILED");
    assertEquals(0, finalization.reconcileDeployments(Duration.ZERO));
    deploymentRequests.answerStatus("deployment-1", "ROLLED_BACK");
    assertEquals(0, finalization.reconcileDeployments(Duration.ZERO));

    ReleasedTagPendingMerge after = rowOf(tag);
    assertEquals(
        "FAILED: no suitable node", after.deploymentFailedDetail, "the event's words stay");
    assertEquals(before.deploymentFailedAt, after.deploymentFailedAt);
    assertNull(after.deploymentActiveAt);
  }

  /** Only ACTIVE is live: a decommissioned or still-starting newest deployment changes nothing. */
  @Test
  public void onlyActiveIsLiveForTheReconcile() {
    String tag = releaseOf("work-a");
    deploymentFailed(tag, "FAILED", "no suitable node");

    for (String status : new String[] {"DECOMMISSIONED", "STARTING"}) {
      deploymentRequests.answerStatus("deployment-1", status);
      assertEquals(0, finalization.reconcileDeployments(Duration.ZERO), status);
      ReleasedTagPendingMerge row = rowOf(tag);
      assertNull(row.deploymentActiveAt, status + " is not live");
      assertEquals("FAILED: no suitable node", row.deploymentFailedDetail, status);
      assertNull(row.mergeRequestedAt, status);
    }
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());
  }

  /** A deployments port that cannot answer, or throws, costs the pass and nothing else. */
  @Test
  public void anUnanswerableReconcileLeavesTheRowAlone() {
    String tag = releaseOf("work-a");
    deploymentRequests.answerCouldNotAsk();

    assertEquals(0, finalization.reconcileDeployments(Duration.ZERO));
    assertNull(rowOf(tag).deploymentFailedAt);
  }

  // -----------------------------------------------------------------------------------------------
  // Variant deployments (qits-1985)
  // -----------------------------------------------------------------------------------------------

  @Test
  public void aNullVariantFailureIsTheReleasesOwnAndRecordsAsBefore() {
    String tag = releaseOf("work-a");

    deploymentFailed(tag, "ROLLED_BACK", "no suitable node", null);

    assertNotNull(rowOf(tag).deploymentFailedAt, "no variant on the payload reads exactly as before");
  }

  @Test
  public void aBlankVariantFailureIsTheReleasesOwnAndRecordsAsBefore() {
    String tag = releaseOf("work-a");

    deploymentFailed(tag, "ROLLED_BACK", "no suitable node", "   ");

    assertNotNull(rowOf(tag).deploymentFailedAt, "a blank variant is unchanged behaviour");
  }

  // -----------------------------------------------------------------------------------------------
  // A release's variants count (qits-2296): qits-edge-service-rr-165 replayed
  // -----------------------------------------------------------------------------------------------

  private static final String MAIN_APP = "rollback";
  private static final String VARIANT_APP = "rollback-private";

  /** The release's main row, ACTIVE, declaring {@code private}. */
  private static DeploymentRequestView mainRow(String id) {
    return new DeploymentRequestView(
        id,
        "ACTIVE",
        Instant.parse("2026-11-16T03:14:21Z"),
        null,
        null,
        null,
        null,
        null,
        null,
        MAIN_APP,
        null,
        List.of("private"));
  }

  private static DeploymentRequestView variantRow(String id, String status) {
    return new DeploymentRequestView(
        id,
        status,
        Instant.parse("2026-11-16T03:15:35Z"),
        null,
        null,
        null,
        null,
        null,
        null,
        VARIANT_APP,
        "private",
        List.of());
  }

  /** qits-deployments' two listings: the main row alone, and the release with its variants. */
  private void listing(DeploymentRequestView... withVariants) {
    List<DeploymentRequestView> rows = List.of(withVariants);
    deploymentRequests.answer(
        Optional.of(rows.stream().filter(row -> !row.isVariant()).toList()));
    deploymentRequests.answerWithVariants(Optional.of(rows));
  }

  /**
   * qits-edge-service-rr-165: the main went ACTIVE, then its private variant FAILED. The request
   * stays RELEASED with a deployment failure naming the variant's application, main never moves,
   * and the gate and the DEPLOY phase say which deployment failed.
   */
  @Test
  public void rr165AVariantFailingAfterTheMainWentLiveHoldsTheRequest() {
    String tag = releaseOf("work-a");
    String request = firstRequest();

    listing(mainRow("m1"));
    deploymentActive(tag, MAIN_APP, null);
    assertNull(rowOf(tag).deploymentActiveAt, "the variant has not gone live yet");
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());

    listing(variantRow("v1", "FAILED"), mainRow("m1"));
    deploymentFailed(tag, "FAILED", "registry DNS lookup failed\\nsecond line", "private");

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentFailedAt);
    assertEquals("rollback-private FAILED: registry DNS lookup failed", row.deploymentFailedDetail);
    assertNull(row.deploymentActiveAt);
    assertNull(row.mergeRequestedAt);
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty(), "never merged");
    assertEquals("RELEASED", listed(request).getString("state"));
    assertEquals("FAILED", listed(request).getString(GATE + ".state"));
    assertTrue(listed(request).getBoolean("retryable"));

    JsonPath single =
        given().get(base() + "/" + request).then().statusCode(200).extract().jsonPath();
    assertEquals("FAILED", single.getString("request." + GATE + ".state"));
    assertTrue(
        single.getString("request." + GATE + ".detail").contains("rollback-private FAILED"),
        single.getString("request." + GATE + ".detail"));
    assertEquals(
        List.of("Deployment request m1", "rollback-private"),
        single.getList("request." + GATE + ".checks.name"));
    assertEquals("FAILED", single.getString("request." + GATE + ".checks[1].state"));
    assertEquals(
        "FAILED", single.getString("request.pipeline.phases.find { it.phase == 'DEPLOY' }.state"));
    assertEquals(
        "rollback-private FAILED: registry DNS lookup failed",
        single.getString("request.pipeline.phases.find { it.phase == 'DEPLOY' }.detail"));
  }

  /** The main's event alone, with the declared variant's row not written yet, merges nothing. */
  @Test
  public void theMainGoingLiveBeforeItsVariantHasARowMergesNothing() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    listing(mainRow("m1"));

    deploymentActive(tag, MAIN_APP, null);

    assertNull(rowOf(tag).deploymentActiveAt);
    assertNull(rowOf(tag).deploymentFailedAt);
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());
    JsonPath single =
        given().get(base() + "/" + request).then().statusCode(200).extract().jsonPath();
    assertEquals("PENDING", single.getString("request." + GATE + ".state"));
    assertEquals("PENDING", single.getString("request." + GATE + ".checks[1].state"));
    assertEquals(
        "RUNNING", single.getString("request.pipeline.phases.find { it.phase == 'DEPLOY' }.state"));
  }

  /** Main ACTIVE, then the variant ACTIVE: the variant's event finalizes. */
  @Test
  public void theVariantGoingLiveAfterTheMainFinalizes() {
    String tag = releaseOf("work-a");
    listing(mainRow("m1"));
    deploymentActive(tag, MAIN_APP, null);
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());

    listing(variantRow("v1", "ACTIVE"), mainRow("m1"));
    deploymentActive(tag, VARIANT_APP, "private");

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentActiveAt);
    assertNotNull(row.mergeRequestedAt);
    assertEquals(1, merger.foldsOf("refs/heads/main").size());
  }

  /** A variant's event naming a release whose main is not live yet finalizes nothing. */
  @Test
  public void aLiveVariantNeverFinalizesAReleaseWhoseMainIsNotLive() {
    String tag = releaseOf("work-a");
    DeploymentRequestView starting =
        new DeploymentRequestView(
            "m1", "STARTING", Instant.now(), null, null, null, null, null, null, MAIN_APP, null,
            List.of("private"));
    deploymentRequests.answerWithVariants(
        Optional.of(List.of(variantRow("v1", "ACTIVE"), starting)));

    deploymentActive(tag, VARIANT_APP, "private");

    assertNull(rowOf(tag).deploymentActiveAt);
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());
  }

  /**
   * The remedy: a DEPLOY rerun re-posts the release. Its main goes ACTIVE while the variant's newest
   * row is still the old failure (older than the new main row) — still waiting, nothing re-failed —
   * and the rerun's variant going ACTIVE clears the failure and finalizes.
   */
  @Test
  public void aDeployRerunThatBringsTheVariantLiveClearsTheFailureAndFinalizes() {
    String tag = releaseOf("work-a");
    String request = firstRequest();
    listing(variantRow("v1", "FAILED"), mainRow("m1"));
    deploymentActive(tag, MAIN_APP, null);
    assertEquals("rollback-private FAILED", rowOf(tag).deploymentFailedDetail,
        "the main's event finds the variant failed and records it");

    given()
        .contentType(ContentType.JSON)
        .post(base() + "/" + request + "/pipeline/DEPLOY/rerun")
        .then()
        .statusCode(200);
    assertEquals(1, redeploys.asks().size(), "the release was re-posted");

    listing(mainRow("m2"), variantRow("v1", "FAILED"), mainRow("m1"));
    deploymentActive(tag, MAIN_APP, null);
    ReleasedTagPendingMerge waiting = rowOf(tag);
    assertNull(waiting.deploymentActiveAt, "the rerun's variant has no row yet");
    assertEquals("rollback-private FAILED", waiting.deploymentFailedDetail);

    listing(variantRow("v2", "ACTIVE"), mainRow("m2"), variantRow("v1", "FAILED"), mainRow("m1"));
    deploymentActive(tag, VARIANT_APP, "private");

    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentActiveAt);
    assertNull(row.deploymentFailedAt, "the rerun went live, so the failure is history");
    assertNull(row.deploymentFailedDetail);
    assertNotNull(row.mergeRequestedAt);
    assertEquals(1, merger.foldsOf("refs/heads/main").size());

    deploymentFailed(tag, "ROLLED_BACK", "a later rollback", "private");
    assertNull(rowOf(tag).deploymentFailedAt, "a version that went live did ship");
  }

  /** A release whose main declares no variants finalizes on the main's event, as before. */
  @Test
  public void aReleaseDeclaringNoVariantsFinalizesOnTheMainAsBefore() {
    String tag = releaseOf("work-a");
    deploymentRequests.answerWithVariants(
        Optional.of(List.of(new DeploymentRequestView("m1", "ACTIVE", Instant.now()))));

    deploymentActive(tag, MAIN_APP, null);

    assertNotNull(rowOf(tag).deploymentActiveAt);
    assertEquals(1, merger.foldsOf("refs/heads/main").size());
  }

  /**
   * qits-deployments cannot be asked about the variants when the main's event arrives: nothing is
   * finalized on a guess, and the reconcile settles it once it can ask.
   */
  @Test
  public void anUnreadableVariantListingLeavesTheMainsEventToTheReconcile() {
    String tag = releaseOf("work-a");
    deploymentRequests.answerWithVariants(Optional.empty());

    deploymentActive(tag, MAIN_APP, null);
    assertNull(rowOf(tag).deploymentActiveAt);
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());

    listing(variantRow("v1", "ACTIVE"), mainRow("m1"));
    assertEquals(1, finalization.reconcileDeployments(Duration.ZERO));
    assertNotNull(rowOf(tag).deploymentActiveAt);
    assertEquals(1, merger.foldsOf("refs/heads/main").size());
  }

  /** The reconcile reaches every verdict with no event on the log at all. */
  @Test
  public void theReconcileAppliesTheVariantVerdictNoEventAnnounced() {
    String tag = releaseOf("work-a");

    listing(mainRow("m1"));
    assertEquals(0, finalization.reconcileDeployments(Duration.ZERO), "a variant still pending");
    assertNull(rowOf(tag).deploymentActiveAt);

    listing(variantRow("v1", "ROLLED_BACK"), mainRow("m1"));
    assertEquals(1, finalization.reconcileDeployments(Duration.ZERO));
    assertEquals("rollback-private ROLLED_BACK", rowOf(tag).deploymentFailedDetail);
    assertEquals(
        0, finalization.reconcileDeployments(Duration.ZERO), "a recorded failure is not re-worded");
    assertTrue(merger.foldsOf("refs/heads/main").isEmpty());

    listing(variantRow("v2", "ACTIVE"), variantRow("v1", "ROLLED_BACK"), mainRow("m1"));
    assertEquals(1, finalization.reconcileDeployments(Duration.ZERO));
    ReleasedTagPendingMerge row = rowOf(tag);
    assertNotNull(row.deploymentActiveAt);
    assertNull(row.deploymentFailedAt);
    assertEquals(1, merger.foldsOf("refs/heads/main").size());
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture — ReleaseSupersessionTest's, trimmed
  // -----------------------------------------------------------------------------------------------

  /** One branch driven to RELEASED on a main that declares a deployment, and left there. */
  private String releaseOf(String branch) {
    Map<String, String> main = new HashMap<>(RecordingReleaseGitHost.GATED_MAIN);
    main.put(".config/qits/deployments.yml", "resources: []\n");
    gitHost.tree("refs/heads/main", main);
    String tag = "2026.1026." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of("pom.xml", "irrelevant", ".config/qits/deployments.yml", "irrelevant"));
    String id = create(branch);
    verdict(mergedShaOf(id));
    awaitState(id, "RELEASED");
    return tag;
  }

  private void deploymentFailed(String version, String status, String detail) {
    deploymentFailed(version, status, detail, null);
  }

  private void deploymentFailed(String version, String status, String detail, String variant) {
    failures.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentFailed",
            Instant.now(),
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\""
                + (variant == null || variant.isBlank() ? MAIN_APP : MAIN_APP + "-" + variant.trim())
                + "\",\"environmentName\":\"dev\",\"version\":\""
                + version
                + "\",\"status\":\""
                + status
                + "\",\"detail\":\""
                + detail
                + "\""
                + (variant == null ? "" : ",\"variant\":\"" + variant + "\"")
                + "}",
            null,
            null,
            null));
  }

  private void deploymentActive(String version) {
    deploymentActive(version, MAIN_APP, null);
  }

  private void deploymentActive(String version, String application, String variant) {
    actives.onFrame(
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
                + "\""
                + (variant == null ? "" : ",\"variant\":\"" + variant + "\"")
                + "}",
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

  /** The request as the LIST read draws it — no deployments call behind it. */
  private JsonPath listed(String id) {
    JsonPath page = given().get(base()).then().statusCode(200).extract().jsonPath();
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
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.qualifiedId");
  }

  private String stateOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.state");
  }

  private String mergedShaOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.mergedSha");
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
