package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.ReleasePipelineRunListener;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
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
 * What a request says about the release read as ONE PIPELINE — the phases it is made of, and the
 * gates placed between them.
 *
 * <p>Three claims here can only be made end to end, which is why this is a {@code @QuarkusTest}
 * against the real read surface rather than a unit test of the assembler: that a {@code
 * BuildStatusChanged} really reaches a phase row through the real consumer, that the request
 * correlation really inverts the backing branch and the released tag, and that the two gate lists
 * are one evaluation. It carries <b>no {@code @TestProfile}</b> deliberately — it needs the same
 * application {@code ReleaseGateReportingTest} beside it needs, and a profile of its own would be a
 * second Quarkus start for nothing.
 *
 * <p>The load-bearing assertion is the first one: <b>no phase run means an ABSENT block, never an
 * empty one</b>, which is what keeps every request open across the cutover rendering exactly as it
 * did.
 */
@QuarkusTest
public class ReleasePipelineReportingTest {

  @Inject BuildStatusListener verdicts;

  @Inject ReleasePipelineRunListener transitions;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakePublishRuns publishRuns;

  @Inject eu.wohlben.qits.projects.control.ReleaseFinalization finalization;

  @Inject eu.wohlben.qits.projects.deploymenthost.FakeDeploymentRequests deployments;

  @Inject
  eu.wohlben.qits.projects.deploymenthost.RecordingDeploymentRedeploys redeploys;

  @Inject RecordingPipelinePhaseReruns reruns;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    publishRuns.reset();
    deployments.reset();
    redeploys.reset();
    reruns.reset();
    activeBuilds.answer(Optional.of(0));
    repoId = "pipeline-repo-" + UUID.randomUUID();
    projectId = "pipeline-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "pipeline";
              project.slug = "pipeline-" + UUID.randomUUID();
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
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleasePipelineRun.delete("repoId = ?1", repoId);
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  /**
   * <b>A request whose runs carry no phase has no pipeline block at all.</b> That is every request
   * open across the cutover — qits-ci began carrying the phase word on 2026-09-16 and nothing in
   * this database records which historical run was which half of which release — and the answer has
   * to be ABSENT rather than an empty block, because an empty one claims the pipeline is known and
   * has no phases. The existing flat gate list is what such a request keeps rendering from, so it is
   * asserted here beside the null.
   */
  @Test
  public void aRequestWhoseRunsCarryNoPhaseHasNoPipelineBlockAndItsGatesRenderAsBefore() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    String id = create("work");

    // A transition of an ordinary run of this very repository: no phase, so no phase row, so no
    // block. This is the frame every push-shaped build on the platform produces.
    transition("run-ordinary", null, "main", "RUNNING");

    assertNull(pipeline(id), "absent, and deliberately not an empty block");
    assertEquals(List.of("CI"), strings(id, "request.gates.kind"), "the flat list is untouched");
    assertEquals(List.of("PENDING"), strings(id, "request.gates.state"));
  }

  /**
   * <b>The QA phase, driven from the transitions themselves.</b> The request is correlated off the
   * backing branch — {@code release/<id>}, which this service derived from the id in the first place
   * — so the inverse is exact rather than a lookup that can be wrong. The two instants are the
   * frames' own: this event carries neither a start nor a finish in its payload, because its {@code
   * occurredAt} IS the run row's timestamp for the state it just reached.
   */
  @Test
  public void theQaPhaseRunsAndThenSucceeds() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    String id = create("work");
    String backing = ReleaseRequest.backingBranchOf(id);

    Instant queued = Instant.parse("2026-09-16T10:00:00Z");
    transitionAt("run-qa", "RELEASE_REQUEST", backing, "QUEUED", queued);
    assertEquals(List.of("QA"), strings(id, "request.pipeline.phases.phase"));
    assertEquals(List.of("PENDING"), strings(id, "request.pipeline.phases.state"));
    assertEquals(List.of("run-qa"), strings(id, "request.pipeline.phases.runId"));

    Instant started = Instant.parse("2026-09-16T10:00:05Z");
    transitionAt("run-qa", "RELEASE_REQUEST", backing, "RUNNING", started);
    assertEquals(List.of("RUNNING"), strings(id, "request.pipeline.phases.state"));
    assertEquals(
        List.of(started.toString()),
        strings(id, "request.pipeline.phases.startedAt"),
        "the RUNNING frame's own instant, never this read's");

    Instant finished = Instant.parse("2026-09-16T10:01:00Z");
    transitionAt("run-qa", "RELEASE_REQUEST", backing, "SUCCESS", finished);
    assertEquals(List.of("SUCCESS"), strings(id, "request.pipeline.phases.state"));
    assertEquals(List.of(finished.toString()), strings(id, "request.pipeline.phases.finishedAt"));

    // And a late QUEUED frame — which is exactly what a catch-up over a disconnect delivers — must
    // not walk the settled run backwards. The ordering fact is the frame's occurredAt, not its
    // arrival.
    transitionAt("run-qa", "RELEASE_REQUEST", backing, "QUEUED", queued);
    assertEquals(
        List.of("SUCCESS"),
        strings(id, "request.pipeline.phases.state"),
        "an out-of-order frame is refused rather than applied");
  }

  /**
   * <b>The publish phase appears after the tag and not before</b>, and it is correlated the way the
   * publish gate already correlates: a release run's branch IS the version, which is the tag name
   * {@code released_tag_pending_merge} is keyed on beside the repository. Nothing before the tag can
   * name that request, so a publish-phase frame arriving early belongs to nobody — which is a drop
   * rather than a guess.
   */
  @Test
  public void thePublishPhaseAppearsOnceTheTagExists() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    String id = create("work");
    String backing = ReleaseRequest.backingBranchOf(id);
    transitionAt(
        "run-qa", "RELEASE_REQUEST", backing, "SUCCESS", Instant.parse("2026-09-16T10:00:00Z"));
    assertEquals(
        List.of("QA"), strings(id, "request.pipeline.phases.phase"), "no tag, so no publish phase");

    verdict("BuildSuccessful", mergedShaOf(id));
    awaitState(id, "RELEASED");
    String version = versionOf(id);

    transitionAt(
        "run-publish", "RELEASE", version, "RUNNING", Instant.parse("2026-09-16T10:05:00Z"));

    assertEquals(
        List.of("QA", "PUBLISH"),
        strings(id, "request.pipeline.phases.phase"),
        "in pipeline order, and the list grows as the release proceeds");
    assertEquals(List.of("SUCCESS", "RUNNING"), strings(id, "request.pipeline.phases.state"));
    assertEquals(List.of("run-qa", "run-publish"), strings(id, "request.pipeline.phases.runId"));
  }

  /**
   * <b>The gates are PLACED and not re-decided.</b> Every kind and state in the pipeline block is the
   * flat list's, position for position, and what the block adds is the slot each gate stands in:
   * {@code CI} and {@code APPROVAL} in front of the publish phase, {@code PUBLISH} in front of the
   * deployment, {@code DEPLOYMENT} in front of the end. Two gates sharing one slot is why placement
   * is a field on the gate rather than on the phase.
   */
  @Test
  public void theGatesArePlacedBetweenThePhasesTheySeparate() {
    gitHost.tree(
        "refs/heads/main",
        Map.of(
            RecordingReleaseGitHost.RELEASE_CONFIG, RecordingReleaseGitHost.GATING_RELEASE_CONFIG,
            ".config/qits/release-requests.yml", "manual-review: true\n",
            ".config/qits/deployments.yml", "resources: []\n"));
    String id = create("work");
    transitionAt(
        "run-qa",
        "RELEASE_REQUEST",
        ReleaseRequest.backingBranchOf(id),
        "SUCCESS",
        Instant.parse("2026-09-16T10:00:00Z"));

    assertEquals(
        List.of("CI", "APPROVAL", "DEPLOYMENT"), strings(id, "request.pipeline.gates.kind"));
    assertEquals(
        List.of("QA_PUBLISH", "QA_PUBLISH", "DEPLOY_FINALIZED"),
        strings(id, "request.pipeline.gates.between"));
    assertEquals(
        strings(id, "request.gates.kind"),
        strings(id, "request.pipeline.gates.kind"),
        "the same gates, in the same order: one evaluation answered twice");
    assertEquals(
        strings(id, "request.gates.state"), strings(id, "request.pipeline.gates.state"));
    assertEquals(
        List.<String>of(),
        strings(id, "request.pipeline.gates.detail").stream()
            .filter(java.util.Objects::nonNull)
            .toList(),
        "no sentence is invented: these three gates' existing answers carry none");
  }

  /**
   * The publish gate's slot, which needs a tag to exist at all — and its {@code detail}, which is
   * {@code publish_detail} on the released tag's own row rather than anything computed here.
   */
  @Test
  public void thePublishGateStandsBetweenPublishAndDeployAndCarriesItsOwnSentence() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    String id = create("work");
    transitionAt(
        "run-qa",
        "RELEASE_REQUEST",
        ReleaseRequest.backingBranchOf(id),
        "SUCCESS",
        Instant.parse("2026-09-16T10:00:00Z"));
    verdict("BuildSuccessful", mergedShaOf(id));
    awaitState(id, "RELEASED");
    String version = versionOf(id);
    gitHost.tree(
        "refs/tags/" + version,
        Map.of(
            "pom.xml", "irrelevant",
            RecordingReleaseGitHost.RELEASE_CONFIG, RecordingReleaseGitHost.GATING_RELEASE_CONFIG));
    // Whether the archetype composes a release run is qits-ci's answer and never this service's,
    // so the declaration alone stamps no gate — the port has to say yes.
    publishRuns.answer(Optional.of(true));
    finalization.sweep();

    assertEquals(List.of("CI", "PUBLISH"), strings(id, "request.pipeline.gates.kind"));
    assertEquals(
        List.of("QA_PUBLISH", "PUBLISH_DEPLOY"), strings(id, "request.pipeline.gates.between"));

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleasedTagPendingMerge.update(
                    "publishDetail = ?1 where repoId = ?2", "no run has reported yet", repoId));
    assertEquals(
        java.util.Arrays.asList(null, "no run has reported yet"),
        strings(id, "request.pipeline.gates.detail"),
        "sourced from the row the gate is already answered off, never invented");
  }

  // ---- phase 3: the deployment ------------------------------------------------------------------

  /**
   * <b>A repository that declares no deployment gets TWO phases, not three.</b> A library, an SPA
   * and a docs repository deploy nothing, so an eternally pending third phase would be a lie drawn
   * on every one of their releases. {@code ReleaseGates} already separates "not configured" from
   * "pending" and that separation is reused rather than re-read — which is also why qits-deployments
   * is never even asked here, the second assertion and the cheaper one to regress.
   */
  @Test
  public void aRepositoryThatDeclaresNoDeploymentGetsTwoPhasesAndNotThree() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    String id = releasedRequest();
    transitionAt(
        "run-publish",
        "RELEASE",
        versionOf(id),
        "SUCCESS",
        Instant.parse("2026-09-16T10:05:00Z"));
    deployments.answerStatus("dr-1", "ACTIVE");

    assertEquals(
        List.of("QA", "PUBLISH"),
        strings(id, "request.pipeline.phases.phase"),
        "no deployments.yml at main, so the pipeline is two phases long");
    assertEquals(
        List.of(),
        deployments.asked(),
        "and qits-deployments is not asked at all: the gate set already said there is no phase");
  }

  /**
   * <b>Every {@code deploymentStatus} qits-deployments can answer, folded onto a phase state.</b>
   * Three decisions are pinned here at once: the four reds are one {@code FAILED}, because which one
   * it was belongs on the deployment's own page; stopped is {@code CANCELLED} and not red, because a
   * reader offered a rerun needs to know whether anything actually failed; and a word this service
   * cannot place is {@code UNKNOWN} rather than whichever neighbour looked closest — that vocabulary
   * is the far side's and may grow.
   */
  @Test
  public void theDeploymentPhaseIsTheNewestRequestsStatusFoldedOntoAPhaseState() {
    String id = releasedDeployableRequest();

    Map<String, String> expected = new java.util.LinkedHashMap<>();
    expected.put("QUEUED", "RUNNING");
    expected.put("STARTING", "RUNNING");
    expected.put("ACTIVE", "SUCCESS");
    expected.put("IMAGE_MISSING", "FAILED");
    expected.put("SPEC_UNREADABLE", "FAILED");
    expected.put("FAILED", "FAILED");
    expected.put("DECLARATION_REFUSED", "FAILED");
    expected.put("SUPERSEDED", "CANCELLED");
    expected.put("ROLLED_BACK", "CANCELLED");
    expected.put("GONE", "CANCELLED");
    expected.put("SCALED_TO_ZERO", "CANCELLED");
    expected.put("DECOMMISSIONED", "CANCELLED");
    expected.put("SOMETHING_NEW_OVER_THERE", "UNKNOWN");

    for (Map.Entry<String, String> each : expected.entrySet()) {
      deployments.answerStatus("dr-1", each.getKey());
      assertEquals(
          List.of("QA", "DEPLOY"),
          strings(id, "request.pipeline.phases.phase"),
          "the deploy phase is drawn for " + each.getKey());
      assertEquals(
          each.getValue(),
          strings(id, "request.pipeline.phases.state").get(1),
          each.getKey() + " reads as " + each.getValue());
      assertEquals(
          "dr-1",
          strings(id, "request.pipeline.phases.runId").get(1),
          "runId carries the deployment request's id, which is what addresses its page");
    }

    // A null status is a real answer over there — the request exists and no deployment was ever
    // created for it — and it is the phase having begun with its state unknown, never PENDING.
    deployments.answerStatus("dr-1", null);
    assertEquals("UNKNOWN", strings(id, "request.pipeline.phases.state").get(1));
  }

  /**
   * <b>qits-deployments unreachable is {@code UNKNOWN}, and never a false "no deployment".</b> This
   * is the whole reason the port answers an {@code Optional} of a list rather than a list: an outage
   * that read as "nothing is owed" would draw a release that is stuck waiting as though it had
   * finished. A present, empty listing is the opposite answer and is {@code PENDING} — asked, and
   * nothing for this version yet.
   */
  @Test
  public void qitsDeploymentsUnreachableIsUnknownAndAnEmptyListingIsPending() {
    String id = releasedDeployableRequest();

    deployments.answerCouldNotAsk();
    assertEquals(List.of("QA", "DEPLOY"), strings(id, "request.pipeline.phases.phase"));
    assertEquals(
        List.of("UNKNOWN"),
        List.of(strings(id, "request.pipeline.phases.state").get(1)),
        "could not be asked is UNKNOWN, never an absent phase and never a finished one");
    assertEquals(
        java.util.Collections.singletonList(null),
        strings(id, "request.pipeline.phases.runId").stream().skip(1).toList(),
        "and it names no deployment request, because it saw none");

    deployments.answerNothingYet();
    assertEquals(
        List.of("PENDING"),
        List.of(strings(id, "request.pipeline.phases.state").get(1)),
        "asked, and there is nothing for this version yet: a different answer entirely");
  }

  /**
   * <b>A list read asks qits-deployments nothing.</b> Its listing is keyed on the {@code (repoId,
   * version)} pair and refuses a question naming only a repository, so a page of released requests
   * would be one HTTP call per row on the busiest read this service has. The deploy phase is
   * therefore <em>absent</em> from a list's rows — not {@code UNKNOWN}, which would claim the far
   * side had been asked — while the run phases and the gates are drawn exactly as before.
   */
  @Test
  public void aListReadDrawsTheRunPhasesAndAsksQitsDeploymentsNothing() {
    String id = releasedDeployableRequest();
    deployments.reset();
    deployments.answerStatus("dr-1", "ACTIVE");

    List<String> phases =
        given()
            .get(base() + "?state=all")
            .then()
            .statusCode(200)
            .extract()
            .path("requests.find { it.id == '" + id + "' }.pipeline.phases.phase");

    assertEquals(List.of("QA"), phases, "the run phases, and the deploy phase simply absent");
    assertEquals(List.of(), deployments.asked(), "not one call for the whole page");
  }

  // ---- the rerun doors --------------------------------------------------------------------------

  /**
   * <b>The two run phases go back to qits-ci under ITS phase words</b>, which is the one translation
   * this seam makes: {@code QA} and {@code PUBLISH} are the reader's words and {@code
   * RELEASE_REQUEST} and {@code RELEASE} are the storage's. The answer is the same envelope every
   * other verb on this controller answers with, so the caller replaces its row — and nothing about
   * the request has moved, which is the point of a rerun.
   */
  @Test
  public void theQaAndPublishPhasesAreRerunThroughQitsCi() {
    String id = releasedDeployableRequest();

    assertEquals(id, rerun(id, "QA", 200).getString("request.id"));
    assertEquals(id, rerun(id, "PUBLISH", 200).getString("request.id"));

    assertEquals(
        List.of("RELEASE_REQUEST", "RELEASE"),
        reruns.asked().stream().map(RecordingPipelinePhaseReruns.Asked::ciPhase).toList());
    assertEquals(
        List.of(id, id),
        reruns.asked().stream()
            .map(RecordingPipelinePhaseReruns.Asked::releaseRequestId)
            .toList());
    assertEquals("RELEASED", stateOf(id), "a rerun moves no state and re-decides no gate");
  }

  /**
   * <b>qits-ci's 409 reaches the caller with its message intact.</b> That sentence says the QA phase
   * succeeded and its verdict was spent on cutting the tag, so there is nothing to ask again — which
   * is a fact about this release the person pressing the button has not got, and a paraphrase would
   * be strictly less than what was already known.
   */
  @Test
  public void aRefusalFromQitsCiKeepsItsStatusAndItsSentence() {
    String id = releasedDeployableRequest();
    String message =
        "The QA phase of release request "
            + id
            + " succeeded (CI run run-7), so there is nothing to ask again: that verdict was spent"
            + " on cutting the tag, and the fold it built no longer exists.";
    reruns.failWith(new eu.wohlben.qits.projects.error.DomainException(409, message));

    assertEquals(message, rerun(id, "QA", 409).getString("message"));
  }

  /** The deployment phase is re-asked through the release intake — the only redeploy door there is. */
  @Test
  public void theDeployPhaseIsRerunByRepostingTheReleaseIntake() {
    String id = releasedDeployableRequest();

    rerun(id, "DEPLOY", 200);

    assertEquals(1, redeploys.asks().size());
    assertEquals(repoId, redeploys.asks().get(0).repoId());
    assertEquals(projectId, redeploys.asks().get(0).projectId());
    assertEquals(versionOf(id), redeploys.asks().get(0).version(), "the pair the far side keys on");
  }

  /**
   * The two refusals this service decides for itself, because both are facts only it holds: an
   * unreleased request has no version, and a repository that declares no deployment has no such
   * phase at all.
   */
  @Test
  public void theDeployPhaseRefusesWithNoVersionAndWithNoDeploymentDeclared() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    String unreleased = create("work");
    assertTrue(
        rerun(unreleased, "DEPLOY", 409).getString("message").contains("has not released"),
        "no tag, so no version, so nothing to deploy");

    String id = releasedRequest();
    assertTrue(
        rerun(id, "DEPLOY", 409).getString("message").contains("declares no deployment"),
        "two phases, so there is no third to run again");
    assertEquals(List.of(), redeploys.asks(), "and nothing was posted anywhere");
  }

  /** A word naming no phase is a 400: a typo must never quietly re-run a different phase. */
  @Test
  public void aWordNamingNoPhaseIsRefused() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    String id = create("work");

    assertTrue(rerun(id, "STEP", 400).getString("message").contains("QA, PUBLISH or DEPLOY"));
    assertEquals(List.of(), reruns.asked());
  }

  // -----------------------------------------------------------------------------------------------

  /** A released request of a repository that declares no deployment. */
  private String releasedRequest() {
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    return releaseOne();
  }

  /** A released request of a repository that declares one, which is what draws a third phase. */
  private String releasedDeployableRequest() {
    gitHost.tree(
        "refs/heads/main",
        Map.of(
            RecordingReleaseGitHost.RELEASE_CONFIG, RecordingReleaseGitHost.GATING_RELEASE_CONFIG,
            ".config/qits/deployments.yml", "resources: []\n"));
    return releaseOne();
  }

  private String releaseOne() {
    String id = create("work");
    transitionAt(
        "run-qa",
        "RELEASE_REQUEST",
        ReleaseRequest.backingBranchOf(id),
        "SUCCESS",
        Instant.parse("2026-09-16T10:00:00Z"));
    verdict("BuildSuccessful", mergedShaOf(id));
    awaitState(id, "RELEASED");
    return id;
  }

  private io.restassured.path.json.JsonPath rerun(String id, String phase, int status) {
    return given()
        .contentType(ContentType.JSON)
        .post(base() + "/" + id + "/pipeline/" + phase + "/rerun")
        .then()
        .statusCode(status)
        .extract()
        .jsonPath();
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a pipeline\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  /**
   * One JSON path off a fresh read of the request. Two typed readers rather than one generic one:
   * {@code assertEquals} overloads on boxed numerics, so an inferred {@code T} at a call site makes
   * the compiler choose between {@code Byte} and {@code Short} and refuse.
   */
  private List<String> strings(String id, String path) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path(path);
  }

  private String string(String id, String path) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path(path);
  }

  private Map<String, Object> pipeline(String id) {
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.pipeline");
  }

  private void transition(String runId, String phase, String branch, String status) {
    transitionAt(runId, phase, branch, status, Instant.now());
  }

  private void transitionAt(
      String runId, String phase, String branch, String status, Instant at) {
    transitions.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildStatusChanged",
            at,
            "{\"branch\":\""
                + branch
                + "\",\"commitSha\":\"sha-"
                + runId
                + "\""
                + (phase == null ? "" : ",\"phase\":\"" + phase + "\"")
                + ",\"repoId\":\""
                + repoId
                + "\",\"runId\":\""
                + runId
                + "\",\"status\":\""
                + status
                + "\"}",
            null,
            null,
            null));
  }

  private void verdict(String name, String sha) {
    verdicts.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\""
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

  private String stateOf(String id) {
    return string(id, "request.state");
  }

  private String versionOf(String id) {
    return string(id, "request.version");
  }

  private String mergedShaOf(String id) {
    return string(id, "request.mergedSha");
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
