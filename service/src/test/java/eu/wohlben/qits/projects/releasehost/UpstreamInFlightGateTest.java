package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.QaRunCancellations;
import eu.wohlben.qits.projects.control.ReleasePipelineRuns;
import eu.wohlben.qits.projects.control.ReleaseRequestAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.UpstreamRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeDownstreamComponents;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate 3: an upstream release building holds its downstream request before QA</b> (epic
 * qits-897, qits-913). Two SERVICE repositories in one project — a frontend and a service — and a
 * fake closure saying the service is downstream of the frontend. While a frontend request is
 * building (a QA run going at its fold, READY, or publishing), a service fold is announced {@code
 * preRun=PENDING} only and held PENDING with {@code upstream-in-flight} naming the frontend request;
 * when the frontend stops building, the service is announced {@code preRun=DONE} once.
 *
 * <p>The service is evaluated through {@link ReleaseRequests#onVerdict} with no verdict on record —
 * the public door that evaluates the PENDING requests at a commit — rather than the sweep, which
 * would also execute a READY frontend. One scenario rides the real trigger instead
 * ({@code ReleasePipelineRuns.record} re-evaluating what is downstream).
 */
@QuarkusTest
public class UpstreamInFlightGateTest {

  private static final String SCREENSHOTS = "screenshot-baselines";

  @Inject BuildStatusListener listener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingFoldChanges foldChanges;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  @Inject RecordingQaRunCancellations cancellations;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject FakeDownstreamComponents downstream;

  @Inject ReleaseRequests releaseRequests;

  @Inject ReleasePipelineRuns pipelineRuns;

  private String projectId;
  private String feRepo;
  private String feName;
  private String svcRepo;
  private String svcName;

  private final List<String> repoIds = new ArrayList<>();

  /** Strictly increasing frame instants, so every run transition is newer than the last. */
  private final AtomicLong clock = new AtomicLong(Instant.now().toEpochMilli());

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    foldChanges.reset();
    automations.reset();
    announcer.reset();
    cancellations.reset();
    downstream.reset();
    repoIds.clear();
    activeBuilds.answer(Optional.of(0));
    gitHost.gatedTree("refs/heads/work", Map.of("README.md", "work"));

    String unique = UUID.randomUUID().toString();
    projectId = "upstream-project-" + unique;
    String token = unique.substring(0, 8);
    feRepo = "upstream-fe-" + unique;
    feName = "fe-" + token;
    svcRepo = "upstream-svc-" + unique;
    svcName = "svc-" + token;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "upstream";
              project.slug = "upstream-" + unique;
              project.persist();
            });
    repository(feRepo, feName, RepositoryArchetype.SERVICE);
    repository(svcRepo, svcName, RepositoryArchetype.SERVICE);
    // The frontend's closure names the service; the service is a leaf.
    downstream.answerFor(feRepo, Optional.of(List.of(svcName)));
    downstream.answerFor(svcRepo, Optional.of(List.of()));
  }

  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleasePipelineRun.delete("repoId in ?1", repoIds);
              ReleasedTagPendingMerge.delete("repoId in ?1", repoIds);
              ReleaseRequest.delete("projectId = ?1", projectId);
            });
    automations.reset();
    downstream.reset();
    foldChanges.reset();
  }

  // -----------------------------------------------------------------------------------------
  // The hold and what clears it
  // -----------------------------------------------------------------------------------------

  /**
   * 1-3. A frontend QA run QUEUED holds the service's fold before QA: announced PENDING only, the
   * gate PENDING with one check naming the frontend request. READY and a RELEASED request whose
   * publish is PENDING still hold; the publish passing clears it, and DONE is announced once.
   */
  @Test
  public void aQueuedUpstreamHoldsThroughReadyAndPublishAndItsPublishPassingReleasesQaOnce() {
    String fe = create(feRepo);
    String feFold = mergedShaOf(feRepo, fe);
    qaRun(fe, feRepo, feFold, "run-fe-1", "QUEUED");

    String svc = create(svcRepo);
    String svcFold = mergedShaOf(svcRepo, svc);
    String feQid = BackingBranches.qualifiedIdOf(fe);

    // 1. Held.
    assertEquals(List.of("PENDING"), preRuns(svc), "announced PENDING only: qits-ci builds nothing");
    assertNull(qaAnnouncedShaOf(svc));
    JsonPath held = request(svcRepo, svc);
    assertEquals("PENDING", held.getString("state"));
    assertEquals("Waiting for upstream releases: " + feQid, held.getString("detail"));
    assertEquals("PENDING", gate(held, "state"));
    assertEquals("pre-run-qa", gate(held, "position"));
    assertEquals("Upstream releases", gate(held, "label"));
    assertEquals(List.of(feQid), held.getList(gatePath("checks.name")));
    assertEquals(List.of("run-fe-1"), held.getList(gatePath("checks.runId")));
    assertEquals("PENDING", held.getString("preRun.state"), "the pre-run reads PENDING while held");
    assertEquals("QA", held.getString("pipeline.phases[0].phase"));
    assertEquals("WAITING_FOR_PRE_RUN", held.getString("pipeline.phases[0].state"));
    List<String> kinds = held.getList("qualityGates.kind");
    assertTrue(
        kinds.indexOf("automations") < kinds.indexOf("upstream-in-flight")
            && kinds.indexOf("upstream-in-flight") < kinds.indexOf("ci"),
        "after the automations, before CI: " + kinds);

    // 2. READY, then RELEASED with its publish PENDING: still held.
    setState(fe, ReleaseRequest.State.READY);
    evaluate(svcRepo, svcFold);
    JsonPath ready = request(svcRepo, svc);
    assertEquals("PENDING", ready.getString("state"));
    assertEquals("Waiting for upstream releases: " + feQid, ready.getString("detail"));
    assertTrue(ready.getString(gatePath("checks[0].detail")).startsWith("Ready to release"));

    setState(fe, ReleaseRequest.State.RELEASED);
    releasedTag(fe, feRepo, feFold, ReleasedTagPendingMerge.PublishState.PENDING);
    evaluate(svcRepo, svcFold);
    JsonPath publishing = request(svcRepo, svc);
    assertEquals("Waiting for upstream releases: " + feQid, publishing.getString("detail"));
    assertTrue(
        publishing.getString(gatePath("checks[0].detail")).startsWith("Publishing "),
        publishing.getString(gatePath("checks[0].detail")));
    assertEquals(List.of("PENDING"), preRuns(svc));

    // 3. The publish passes: DONE, once however often it is asked.
    publishState(fe, ReleasedTagPendingMerge.PublishState.PASSED);
    evaluate(svcRepo, svcFold);
    evaluate(svcRepo, svcFold);
    assertEquals(List.of("PENDING", "DONE"), preRuns(svc));
    assertEquals(svcFold, announcer.announcedFor(svc).get(1).mergedSha());
    assertEquals(svcFold, qaAnnouncedShaOf(svc));
    JsonPath released = request(svcRepo, svc);
    assertEquals("PASSED", gate(released, "state"));
    assertEquals("PASSED", released.getString("preRun.state"));
    assertEquals("Waiting for a CI verdict for " + svcFold.substring(0, 10), released.getString("detail"));
  }

  /**
   * 4-5. The frontend's QA run FAILS instead: the service proceeds — and the trigger, not a sweep,
   * is what evaluates it. Then the frontend is re-armed by a push and builds again while the service
   * has no verdict: the service's next fold is held again.
   */
  @Test
  public void aFailedUpstreamRunReleasesTheHoldAndARearmedUpstreamHoldsAgain() {
    String fe = create(feRepo);
    String feFold = mergedShaOf(feRepo, fe);
    qaRun(fe, feRepo, feFold, "run-fe-1", "RUNNING");
    String svc = create(svcRepo);
    String first = mergedShaOf(svcRepo, svc);
    assertEquals(List.of("PENDING"), preRuns(svc));

    // 4. The run fails: ReleasePipelineRuns.record re-evaluates the service in the background.
    qaRun(fe, feRepo, feFold, "run-fe-1", "FAILED");
    awaitPreRuns(svc, List.of("PENDING", "DONE"));
    assertEquals(first, qaAnnouncedShaOf(svc));

    // 5. A push re-arms the frontend, and its new fold builds.
    releaseRequests.onBranchMoved(feRepo, "work", freshSha());
    String feSecond = mergedShaOf(feRepo, fe);
    assertNotEquals(feFold, feSecond);
    qaRun(fe, feRepo, feSecond, "run-fe-2", "QUEUED");
    // The service's first fold was announced and has no verdict: the frontend building again
    // interrupts it (qits-909) — its QA runs cancelled, the stamp cleared.
    awaitHeldCancels(svc, 1);
    assertNull(qaAnnouncedShaOf(svc));
    // The service is pushed too, before any verdict: its new fold is held again.
    releaseRequests.onBranchMoved(svcRepo, "work", freshSha());
    String second = mergedShaOf(svcRepo, svc);
    assertNotEquals(first, second);

    List<RecordingReleaseRequestAnnouncer.Announced> announced = announcer.announcedFor(svc);
    assertEquals(List.of("PENDING", "DONE", "PENDING"), preRuns(svc));
    assertEquals(second, announced.get(2).mergedSha());
    assertNull(qaAnnouncedShaOf(svc), "the new fold's QA is not asked for");
    JsonPath held = request(svcRepo, svc);
    assertEquals("PENDING", held.getString("state"));
    assertEquals(
        "Waiting for upstream releases: " + BackingBranches.qualifiedIdOf(fe),
        held.getString("detail"));
    assertEquals(List.of("run-fe-2"), held.getList(gatePath("checks.runId")));
  }

  /** 6. A service fold with a QA verdict of its own is past the gate: it does not apply. */
  @Test
  public void aFoldWithAQaVerdictIsNotAsked() {
    String fe = create(feRepo);
    String feFold = mergedShaOf(feRepo, fe);
    qaRun(fe, feRepo, feFold, "run-fe-1", "QUEUED");
    String svc = create(svcRepo);
    String svcFold = mergedShaOf(svcRepo, svc);
    assertEquals(List.of("PENDING"), preRuns(svc));

    // Green, with a second run of its own still in flight, so it stays PENDING.
    activeBuilds.answer(Optional.of(1));
    verdict(svcRepo, svcFold);

    JsonPath answer = request(svcRepo, svc);
    assertEquals("PENDING", answer.getString("state"));
    assertTrue(
        !answer.getList("qualityGates.kind").contains("upstream-in-flight"),
        "a fold with a verdict is not asked: " + answer.getList("qualityGates.kind"));
    assertTrue(
        answer.getString("detail").contains("still in flight"), answer.getString("detail"));
  }

  /** 7a. A frontend in its pre-run has no build going: it does not hold. */
  @Test
  public void anUpstreamInItsPreRunDoesNotHold() {
    automations.answer(feName, SCREENSHOTS, "RUNNING");
    String fe = create(feRepo);
    assertEquals(List.of("PENDING"), preRuns(fe));

    String svc = create(svcRepo);

    assertEquals(List.of("DONE"), preRuns(svc));
    JsonPath answer = request(svcRepo, svc);
    assertEquals("PASSED", gate(answer, "state"));
    assertTrue(answer.getList(gatePath("checks")).isEmpty());
  }

  /** 7b. A frontend whose QA run finished green and that waits only on approval does not hold. */
  @Test
  public void anUpstreamWaitingOnlyOnApprovalDoesNotHold() {
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "SUCCESS");

    String svc = create(svcRepo);

    assertEquals(List.of("DONE"), preRuns(svc));
    assertEquals("PASSED", gate(request(svcRepo, svc), "state"));
  }

  /** 8. The estate wrapper is never held by gate 3, and the gate is not on its answer. */
  @Test
  public void theEstateWrapperIsNeverHeld() {
    String wrapperRepo = "upstream-wrapper-" + UUID.randomUUID();
    String wrapperName = svcName + "-wrapper";
    repository(wrapperRepo, wrapperName, RepositoryArchetype.PROJECT);
    downstream.answerFor(feRepo, Optional.of(List.of(svcName, wrapperName)));
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "QUEUED");

    String wrapper = create(wrapperRepo);

    JsonPath answer = request(wrapperRepo, wrapper);
    assertTrue(!answer.getList("qualityGates.kind").contains("upstream-in-flight"));
    String detail = answer.getString("detail");
    assertTrue(
        detail == null || !detail.startsWith("Waiting for upstream releases"),
        "the wrapper is exempt: " + detail);
  }

  /** 9. A closure that could not be read is UNKNOWN, and UNKNOWN does not hold. */
  @Test
  public void anUnknownClosureIsUnknownAndDoesNotHold() {
    downstream.answerFor(feRepo, Optional.empty());
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "RUNNING");

    String svc = create(svcRepo);

    assertEquals(List.of("DONE"), preRuns(svc));
    JsonPath answer = request(svcRepo, svc);
    assertEquals("UNKNOWN", gate(answer, "state"));
    assertEquals("Could not read what is upstream", gate(answer, "detail"));
  }

  /** 10. Each downstream of the other: no mutual hold, and one WARN says the closure is no DAG. */
  @Test
  public void aCyclicClosureHoldsNothingAndWarns() {
    downstream.answerFor(svcRepo, Optional.of(List.of(feName)));
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "RUNNING");

    List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.logging.Logger log =
        java.util.logging.Logger.getLogger(UpstreamRequests.class.getName());
    java.util.logging.Handler capture = capturing(logged);
    log.addHandler(capture);
    String svc;
    try {
      svc = create(svcRepo);
    } finally {
      log.removeHandler(capture);
    }

    assertEquals(List.of("DONE"), preRuns(svc));
    assertTrue(
        logged.stream().anyMatch(line -> line.contains("not a DAG") && line.contains(svcName)),
        "a WARN names the cycle: " + logged);
  }

  // -----------------------------------------------------------------------------------------
  // The interrupt (qits-909)
  // -----------------------------------------------------------------------------------------

  /**
   * 11-12. The service fold is announced DONE, then the frontend gets a RUNNING QA run: the
   * service's QA runs are cancelled with HELD_BY_PRE_RUN_GATE, it stays PENDING (not REJECTED) and
   * its stamp is cleared, and it reads held. The frontend's publish passing announces DONE once
   * more, at the same sha.
   */
  @Test
  public void anUpstreamStartingInterruptsAnAnnouncedFoldAndItsPublishPassingAnnouncesDoneAgain() {
    String fe = create(feRepo);
    String feFold = mergedShaOf(feRepo, fe);
    String svc = create(svcRepo);
    String svcFold = mergedShaOf(svcRepo, svc);
    String feQid = BackingBranches.qualifiedIdOf(fe);
    assertEquals(List.of("DONE"), preRuns(svc), "nothing upstream builds yet: QA is asked for");
    assertEquals(svcFold, qaAnnouncedShaOf(svc));
    cancellations.reset();

    // 11. The frontend starts building: ReleasePipelineRuns.record re-evaluates the service.
    qaRun(fe, feRepo, feFold, "run-fe-1", "RUNNING");
    awaitHeldCancels(svc, 1);

    assertEquals(
        List.of(
            new RecordingQaRunCancellations.Cancelled(
                svcRepo, svc, true, QaRunCancellations.HELD_BY_PRE_RUN_GATE)),
        cancellations.calls(),
        "one QA-only cancellation of the service's runs, saying why");
    assertNull(qaAnnouncedShaOf(svc), "the stamp is given back");
    assertEquals(List.of("DONE"), preRuns(svc), "the interrupt announces nothing");
    JsonPath held = request(svcRepo, svc);
    assertEquals("PENDING", held.getString("state"), "held, never rejected");
    assertEquals("Waiting for upstream releases: " + feQid, held.getString("detail"));
    assertEquals("PENDING", gate(held, "state"));
    assertEquals(List.of("run-fe-1"), held.getList(gatePath("checks.runId")));
    assertEquals("PENDING", held.getString("preRun.state"), "reads held, like a fresh fold");
    assertEquals("WAITING_FOR_PRE_RUN", held.getString("pipeline.phases[0].state"));

    // Asked again while it holds: a plain hold, no second cancel.
    evaluate(svcRepo, svcFold);
    setState(fe, ReleaseRequest.State.RELEASED);
    releasedTag(fe, feRepo, feFold, ReleasedTagPendingMerge.PublishState.PENDING);
    evaluate(svcRepo, svcFold);
    assertEquals(1, cancellations.calls().size(), cancellations.calls().toString());
    assertEquals(List.of("DONE"), preRuns(svc));

    // 12. The publish passes: DONE once more, at the same sha, however often it is asked.
    publishState(fe, ReleasedTagPendingMerge.PublishState.PASSED);
    evaluate(svcRepo, svcFold);
    evaluate(svcRepo, svcFold);
    assertEquals(List.of("DONE", "DONE"), preRuns(svc));
    assertEquals(svcFold, announcer.announcedFor(svc).get(1).mergedSha());
    assertEquals(svcFold, qaAnnouncedShaOf(svc));
    assertEquals(1, cancellations.calls().size());
    JsonPath released = request(svcRepo, svc);
    assertEquals("PASSED", gate(released, "state"));
    assertEquals("PASSED", released.getString("preRun.state"));
  }

  /** 13. A service fold with a green verdict of its own (still waiting) is never interrupted. */
  @Test
  public void aFoldWithAGreenVerdictIsNotInterrupted() {
    String fe = create(feRepo);
    String feFold = mergedShaOf(feRepo, fe);
    String svc = create(svcRepo);
    String svcFold = mergedShaOf(svcRepo, svc);
    assertEquals(List.of("DONE"), preRuns(svc));
    // Green, with a second run of its own still in flight, so it stays PENDING and waits.
    activeBuilds.answer(Optional.of(1));
    verdict(svcRepo, svcFold);
    cancellations.reset();

    qaRun(fe, feRepo, feFold, "run-fe-1", "RUNNING");
    evaluate(svcRepo, svcFold);
    evaluate(svcRepo, svcFold);

    assertTrue(cancellations.calls().isEmpty(), cancellations.calls().toString());
    assertEquals(svcFold, qaAnnouncedShaOf(svc));
    assertEquals(List.of("DONE"), preRuns(svc));
    JsonPath answer = request(svcRepo, svc);
    assertEquals("PENDING", answer.getString("state"));
    assertTrue(
        answer.getString("detail").contains("still in flight"), answer.getString("detail"));
  }

  /**
   * 14. Two evaluations racing (the sweep and a listener) over one announced fold an upstream now
   * holds: the row lock makes it one cancel, and when the hold clears, one more DONE.
   */
  @Test
  public void twoEvaluationsRacingCancelOnceAndLaterAnnounceDoneOnce() throws Exception {
    String fe = create(feRepo);
    String svc = create(svcRepo);
    String svcFold = mergedShaOf(svcRepo, svc);
    assertEquals(List.of("DONE"), preRuns(svc));
    cancellations.reset();
    // READY is building, and setting it directly triggers no background evaluation: the two
    // racing evaluations below are the only ones.
    setState(fe, ReleaseRequest.State.READY);

    race(() -> evaluate(svcRepo, svcFold), () -> evaluate(svcRepo, svcFold));
    assertEquals(1, cancellations.calls().size(), cancellations.calls().toString());
    assertEquals(QaRunCancellations.HELD_BY_PRE_RUN_GATE, cancellations.calls().get(0).reason());
    assertNull(qaAnnouncedShaOf(svc));
    assertEquals(List.of("DONE"), preRuns(svc));

    // The frontend stops building (rejected): both race again, and DONE is announced once.
    setState(fe, ReleaseRequest.State.REJECTED);
    race(() -> evaluate(svcRepo, svcFold), () -> evaluate(svcRepo, svcFold));
    assertEquals(List.of("DONE", "DONE"), preRuns(svc));
    assertEquals(svcFold, qaAnnouncedShaOf(svc));
    assertEquals(1, cancellations.calls().size());
  }

  /** Both bodies started together on threads of their own; rethrows the first failure. */
  private static void race(Runnable one, Runnable two) throws Exception {
    java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
      for (Runnable body : List.of(one, two)) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                  body.run();
                  return null;
                }));
      }
      for (java.util.concurrent.Future<?> future : futures) {
        future.get(30, java.util.concurrent.TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** Waits until {@code expected} HELD_BY_PRE_RUN_GATE cancellations name {@code id}. */
  private void awaitHeldCancels(String id, int expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      long held =
          cancellations.calls().stream()
              .filter(call -> id.equals(call.releaseRequestId()))
              .filter(call -> QaRunCancellations.HELD_BY_PRE_RUN_GATE.equals(call.reason()))
              .count();
      if (held == expected) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("request " + id + " was cancelled " + cancellations.calls() + ", never " + expected + " held");
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  private void repository(String id, String name, RepositoryArchetype archetype) {
    repoIds.add(id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = Project.findById(projectId);
              Repository repository = new Repository();
              repository.id = id;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = archetype;
              repository.persist();
              RepositoryName alias = new RepositoryName();
              alias.project = project;
              alias.repository = repository;
              alias.name = name;
              alias.persist();
            });
  }

  private String create(String inRepo) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"work\",\"summary\":\"a release\",\"requester\":\"ada\"}")
        .post("/projects/api/repositories/" + inRepo + "/release-requests")
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private JsonPath request(String inRepo, String id) {
    return given()
        .get("/projects/api/repositories/" + inRepo + "/release-requests/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private static String gatePath(String member) {
    return "qualityGates.find { it.kind == 'upstream-in-flight' }." + member;
  }

  private static String gate(JsonPath answer, String member) {
    return answer.getString(gatePath(member));
  }

  private String mergedShaOf(String inRepo, String id) {
    return request(inRepo, id).getString("mergedSha");
  }

  /** The service evaluated now: the door that evaluates the PENDING requests at a commit. */
  private void evaluate(String inRepo, String fold) {
    releaseRequests.onVerdict(inRepo, fold, Set.of());
  }

  private void qaRun(String requestId, String inRepo, String fold, String runId, String status) {
    pipelineRuns.record(
        new ReleasePipelineRuns.Transition(
            runId,
            inRepo,
            requestId,
            ReleasePipelineRuns.PHASE_RELEASE_REQUEST,
            status,
            BackingBranches.of(requestId),
            fold,
            Instant.ofEpochMilli(clock.addAndGet(1000)),
            null));
  }

  private static void setState(String id, ReleaseRequest.State state) {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(id).state = state);
  }

  private static void releasedTag(
      String requestId, String inRepo, String sha, ReleasedTagPendingMerge.PublishState state) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleasedTagPendingMerge tag = new ReleasedTagPendingMerge();
              tag.id = UUID.randomUUID().toString();
              tag.repoId = inRepo;
              tag.tagName = "2026.1023." + (100000 + (int) (Math.random() * 800000));
              tag.releasedSha = sha;
              tag.releaseRequestId = requestId;
              tag.releasedAt = Instant.now();
              tag.publishState = state;
              tag.persist();
            });
  }

  private static void publishState(String requestId, ReleasedTagPendingMerge.PublishState state) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find(
                        "releaseRequestId", requestId)
                    .firstResult()
                    .publishState = state);
  }

  private void verdict(String inRepo, String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\""
                + BackingBranches.atFold(inRepo, sha)
                + "\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + inRepo
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\"}",
            null,
            null,
            null));
  }

  private static String freshSha() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private String qaAnnouncedShaOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).qaAnnouncedSha);
  }

  /** The preRun of every announcement about one request, oldest first. */
  private List<String> preRuns(String id) {
    return announcer.announcedFor(id).stream()
        .map(RecordingReleaseRequestAnnouncer.Announced::preRun)
        .map(
            preRun ->
                preRun == null
                    ? "absent"
                    : ReleaseRequestAnnouncer.PRE_RUN_DONE.equals(preRun) ? "DONE" : preRun)
        .toList();
  }

  private void awaitPreRuns(String id, List<String> expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (expected.equals(preRuns(id))) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("request " + id + " was announced " + preRuns(id) + ", never " + expected);
  }

  /** A JUL handler collecting every formatted message into {@code into}. */
  private static java.util.logging.Handler capturing(List<String> into) {
    return new java.util.logging.Handler() {
      @Override
      public void publish(java.util.logging.LogRecord entry) {
        String message = entry.getMessage();
        Object[] parameters = entry.getParameters();
        try {
          into.add(parameters == null ? message : String.format(message, parameters));
        } catch (RuntimeException e) {
          into.add(String.valueOf(message));
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
  }
}
