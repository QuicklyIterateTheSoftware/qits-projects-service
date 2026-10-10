package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.control.ReleaseRequestAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestAutomationWaiver;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>QA waits for the pre-run</b> (qits-1133). A fold is announced {@code preRun=PENDING} while its
 * release-request automations are still being settled — qits-ci builds nothing for it — and {@code
 * preRun=DONE}, at most once per sha, the moment every applicable automation is fresh or waived;
 * a fold whose pre-run is settled when it lands is announced DONE once and never PENDING.
 *
 * <p>The fixture is {@link AutomationGateTest}'s: an aliased SERVICE repository, the automations
 * port scripted per repository name, the build gate a verdict away. What is asserted here is the
 * announcement stream ({@link RecordingReleaseRequestAnnouncer}), {@code qa_announced_sha}, the gate
 * order, the {@code preRun} block on the answer and the QA phase's {@code WAITING_FOR_PRE_RUN}.
 */
@QuarkusTest
public class ReleaseRequestPreRunTest {

  private static final String SCREENSHOTS = "screenshot-baselines";
  private static final String BUMP = "dependency-bump";
  private static final String DIAGRAM = "entity-diagram";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingFoldChanges foldChanges;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  @Inject RecordingQaRunCancellations cancellations;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject AutomationLedger ledger;

  @Inject ReleaseRequests releaseRequests;

  private String projectId;
  private String repoId;
  private String repoName;

  private final List<String> requestIds = new ArrayList<>();

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
    requestIds.clear();
    activeBuilds.answer(Optional.of(0));

    String unique = UUID.randomUUID().toString();
    projectId = "pre-run-project-" + unique;
    repoId = "pre-run-repo-" + unique;
    repoName = "pre-run-repo-" + unique.substring(0, 8);
    gitHost.gatedTree("refs/heads/work", Map.of("README.md", "work"));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "pre-run";
              project.slug = "pre-run-" + unique;
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
              RepositoryName name = new RepositoryName();
              name.project = project;
              name.repository = repository;
              name.name = repoName;
              name.persist();
            });
  }

  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              if (!requestIds.isEmpty()) {
                ReleaseRequestAutomationWaiver.delete("requestId in ?1", requestIds);
              }
            });
    requestIds.forEach(ledger::forget);
    automations.reset();
    foldChanges.reset();
  }

  // -----------------------------------------------------------------------------------------
  // The two announcements
  // -----------------------------------------------------------------------------------------

  /**
   * The whole point: a fold whose automations are still moving is announced PENDING and nothing
   * builds it; once they are fresh the sweep announces DONE — once, however often it passes.
   */
  @Test
  public void aMovingPreRunIsAnnouncedPendingThenDoneOnceWhenFresh() {
    automations.answer(repoName, SCREENSHOTS, "RUNNING");

    String id = create("work");
    String merged = mergedShaOf(id);

    assertEquals(List.of("PENDING"), preRuns(id), "the fold is announced, and nothing may build it");
    assertEquals(merged, announcer.announcedFor(id).get(0).mergedSha());
    assertNull(qaAnnouncedShaOf(id));
    JsonPath pending = request(id);
    assertEquals("RUNNING", pending.getString("preRun.state"));
    assertEquals(
        "WAITING_FOR_PRE_RUN",
        pending.getString("pipeline.phases[0].state"),
        "the QA phase waits for the pre-run, and the block is drawn to say so");
    assertEquals("QA", pending.getString("pipeline.phases[0].phase"));
    assertNull(pending.getString("pipeline.phases[0].runId"));

    // The run ends having moved nothing: fresh at this very fold.
    automations.answer(repoName, SCREENSHOTS, "FRESH");
    releaseRequests.sweep(); // held; the tail re-reads and the note turns FRESH
    releaseRequests.sweep(); // passes the pre-run, announces DONE
    releaseRequests.sweep();
    releaseRequests.sweep();

    assertEquals(List.of("PENDING", "DONE"), preRuns(id), "DONE once per sha, however many sweeps");
    assertEquals(merged, announcer.announcedFor(id).get(1).mergedSha());
    assertEquals(merged, qaAnnouncedShaOf(id), "recorded durably");
    JsonPath done = request(id);
    assertEquals("PASSED", done.getString("preRun.state"));
    assertEquals(
        "Waiting for a CI verdict for " + merged.substring(0, 10),
        done.getString("detail"),
        "and now it waits for the QA run it just asked for");

    verdict(merged);
    awaitState(id, "RELEASED");
  }

  /** A pre-run already settled when the fold lands — FRESH on the spot — is one DONE, no PENDING. */
  @Test
  public void aPreRunFreshOnTheSpotIsOneDoneAndNoPending() {
    automations.answer(repoName, BUMP, "FRESH");

    String id = create("work");

    assertEquals(List.of("DONE"), preRuns(id));
    assertEquals(mergedShaOf(id), qaAnnouncedShaOf(id));
    assertEquals("PASSED", request(id).getString("preRun.state"));
    assertNull(request(id).get("pipeline"), "nothing to wait for and no run yet: no block");
  }

  /** A repository no automation gates announces DONE straight away. */
  @Test
  public void aRepositoryTheAutomationsDoNotGateAnnouncesDoneStraightAway() {
    automations.unconfigure();

    String id = create("work");

    assertEquals(List.of("DONE"), preRuns(id));
    JsonPath answer = request(id);
    assertEquals("PASSED", answer.getString("preRun.state"));
    assertTrue(!answer.getList("gates.kind").contains("AUTOMATIONS"));
  }

  /**
   * Restart safety: the ledger is memory, the stamp is not. A restart forgets the note; the sweep
   * re-asks, finds the fold fresh again, and announces nothing a second time.
   */
  @Test
  public void aRestartDoesNotAnnounceDoneTwice() {
    automations.answer(repoName, SCREENSHOTS, "RUNNING");
    String id = create("work");
    automations.answer(repoName, SCREENSHOTS, "FRESH");
    releaseRequests.sweep();
    releaseRequests.sweep();
    assertEquals(List.of("PENDING", "DONE"), preRuns(id));

    ledger.forget(id); // the restart
    assertEquals(
        "PASSED",
        request(id).getString("preRun.state"),
        "with no note the stamp says this fold's pre-run was found done");
    releaseRequests.sweep(); // no note: holds and re-asks
    releaseRequests.sweep(); // fresh again: passes, and the stamp says DONE was already sent

    assertEquals(List.of("PENDING", "DONE"), preRuns(id));
  }

  /**
   * The other restart: the pre-run turned fresh while nobody announced (the stamp is behind the
   * fold). The sweep announces DONE, because the stamp and not the ledger is what says it was sent.
   */
  @Test
  public void theSweepAnnouncesDoneForAFoldWhoseStampIsBehind() {
    automations.answer(repoName, BUMP, "FRESH");
    String id = create("work");
    String merged = mergedShaOf(id);
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(id).qaAnnouncedSha = "an-older-fold");
    announcer.reset();

    releaseRequests.sweep();
    releaseRequests.sweep();

    assertEquals(List.of("DONE"), preRuns(id));
    assertEquals(merged, qaAnnouncedShaOf(id));
  }

  /**
   * A DONE publish that throws gives its stamp back (compare-and-set), so the next evaluation
   * announces it — exactly once — instead of the request waiting for a push that may never come.
   */
  @Test
  public void aDonePublishThatThrowsIsAnnouncedAgainOnTheNextEvaluationExactlyOnce() {
    automations.answer(repoName, BUMP, "FRESH");
    announcer.failNext(1);

    String id = create("work");
    String merged = mergedShaOf(id);

    assertEquals(List.of(), preRuns(id), "the one announcement threw");
    assertNull(qaAnnouncedShaOf(id), "and the stamp was given back");

    releaseRequests.sweep();
    releaseRequests.sweep();
    releaseRequests.sweep();

    assertEquals(List.of("DONE"), preRuns(id), "announced again, once");
    assertEquals(merged, announcer.announcedFor(id).get(0).mergedSha());
    assertEquals(merged, qaAnnouncedShaOf(id));
  }

  // -----------------------------------------------------------------------------------------
  // A failed automation holds
  // -----------------------------------------------------------------------------------------

  /**
   * A failed automation holds — PENDING, no DONE, no rejection — even beside a red verdict, which at
   * a sha whose pre-run is not done answers no QA run this request asked for. A waiver is one way
   * on, and it announces DONE.
   */
  @Test
  public void aFailedAutomationHoldsWithNoQaUntilAWaiver() {
    automations.answer(repoName, SCREENSHOTS, "FAILED");
    String id = create("work");
    String merged = mergedShaOf(id);

    redVerdict(merged);
    releaseRequests.sweep();
    releaseRequests.sweep();

    JsonPath held = request(id);
    assertEquals("PENDING", held.getString("state"), "held, never rejected");
    assertEquals("FAILED", held.getString("preRun.state"));
    assertEquals(
        "Screenshot baselines failed at "
            + merged.substring(0, 10)
            + "; push, re-run, or waive this fold",
        held.getString("detail"));
    assertEquals(List.of("PENDING"), preRuns(id), "no QA was asked for");
    assertNull(rejectingRunIdOf(id));

    waive(id, merged);
    releaseRequests.sweep();

    assertEquals(List.of("PENDING", "DONE"), preRuns(id), "the waiver lets QA start");
    assertEquals("WAIVED", request(id).getString("preRun.state"));
  }

  /** A re-run that comes back fresh is the second way on. */
  @Test
  public void aFailedAutomationReRunToFreshAnnouncesDone() {
    automations.answer(repoName, SCREENSHOTS, "FAILED");
    String id = create("work");
    releaseRequests.sweep();
    assertEquals(List.of("PENDING"), preRuns(id));

    automations.answer(repoName, SCREENSHOTS, "FRESH"); // the re-run moved nothing
    releaseRequests.sweep(); // re-read
    releaseRequests.sweep();

    assertEquals(List.of("PENDING", "DONE"), preRuns(id));
  }

  // -----------------------------------------------------------------------------------------
  // Moves
  // -----------------------------------------------------------------------------------------

  /** A source moving during the pre-run re-folds and restarts it: PENDING again, at the new sha. */
  @Test
  public void aMoveDuringThePreRunRestartsIt() {
    automations.answer(repoName, SCREENSHOTS, "RUNNING");
    String id = create("work");
    String first = mergedShaOf(id);

    headMoved("work");
    awaitFoldToMove(id, first);
    String second = mergedShaOf(id);

    List<RecordingReleaseRequestAnnouncer.Announced> announced = announcer.announcedFor(id);
    assertEquals(List.of("PENDING", "PENDING"), preRuns(id));
    assertEquals(second, announced.get(1).mergedSha());
    assertTrue(automations.askedAbout(id).stream().anyMatch(ask -> second.equals(ask.foldSha())));
  }

  /**
   * A source moving during QA cancels QA and restarts the pre-run — PENDING at the new sha, not
   * straight to QA.
   */
  @Test
  public void aMoveDuringQaCancelsItAndRestartsThePreRun() {
    String id = create("work"); // nothing applies: DONE at once, QA asked for
    String first = mergedShaOf(id);
    assertEquals(List.of("DONE"), preRuns(id));

    automations.answer(repoName, DIAGRAM, "RUNNING"); // the push needs a new diagram
    headMoved("work");
    awaitFoldToMove(id, first);
    String second = mergedShaOf(id);

    assertTrue(cancellations.cancelledRequests().contains(id), "the running QA was cancelled");
    assertEquals(List.of("DONE", "PENDING"), preRuns(id));
    assertEquals(second, announcer.announcedFor(id).get(1).mergedSha());
    assertEquals(first, qaAnnouncedShaOf(id), "the new fold's QA is not asked for yet");
  }

  // -----------------------------------------------------------------------------------------
  // The wire
  // -----------------------------------------------------------------------------------------

  /**
   * WAITING is a kind waiting for others: listed, holds the request, and the pre-run reads PENDING
   * while nothing is running; the gates read AUTOMATIONS before CI.
   */
  @Test
  public void waitingIsListedAndTheAutomationsGateComesFirst() {
    automations.answer(repoName, BUMP, "FRESH");
    automations.answer(repoName, DIAGRAM, "WAITING");
    automations.answer(repoName, SCREENSHOTS, FakeReleaseRequestAutomations.NOT_APPLICABLE);

    String id = create("work");

    JsonPath answer = request(id);
    assertEquals("PENDING", answer.getString("state"));
    assertEquals(List.of(BUMP, DIAGRAM, SCREENSHOTS), answer.getList("automations.kind"));
    assertEquals(
        List.of("FRESH", "WAITING", "NOT_APPLICABLE"), answer.getList("automations.state"));
    assertEquals("PENDING", answer.getString("preRun.state"), "waiting is not running");
    assertTrue(
        answer.getString("detail").contains("Entity diagram waiting for the source automations"),
        answer.getString("detail"));
    assertEquals(List.of("AUTOMATIONS", "CI"), answer.getList("gates.kind"));
    assertEquals("automations", answer.getString("qualityGates[0].kind"));
    assertEquals("pre-run-qa", answer.getString("qualityGates[0].position"));
    assertEquals("ci", answer.getString("qualityGates[1].kind"));
    assertEquals(
        "PRE_RUN_QA",
        answer.getString("pipeline.gates.find { it.kind == 'AUTOMATIONS' }.between"));
    assertEquals(List.of("PENDING"), preRuns(id));
  }

  /**
   * A green verdict at a sha whose pre-run is not done releases nothing: no QA was asked for, and
   * the automations gate is the one that holds.
   */
  @Test
  public void aVerdictBeforeThePreRunIsDoneIsNotRead() {
    automations.answer(repoName, SCREENSHOTS, "RUNNING");
    String id = create("work");
    String merged = mergedShaOf(id);

    verdict(merged);

    JsonPath held = request(id);
    assertEquals("PENDING", held.getString("state"));
    assertTrue(held.getString("detail").startsWith("Waiting for automations at "));
    assertEquals(0, executor.calls().size());
  }

  // -----------------------------------------------------------------------------------------
  // qits-maintenance's two doors: a main-only request and its withdrawal
  // -----------------------------------------------------------------------------------------

  /**
   * qits-maintenance opens a request for a dependency bump with no person's branch: {@code branch:
   * main}, as {@code qits:system}. It folds main alone and its pre-run writes the bump. If the
   * pre-run finds nothing to bump, maintenance withdraws it through the same role.
   */
  @Test
  public void maintenanceOpensAMainOnlyRequestAndWithdrawsIt() {
    automations.answer(repoName, BUMP, "RUNNING");

    String id =
        asMaintenance()
            .body("{\"branch\":\"main\",\"summary\":\"bump(deps)\",\"priority\":\"LOWEST\"}")
            .post(base())
            .then()
            .statusCode(200)
            .body("request.sources.name", contains("main"))
            .body("request.priority", org.hamcrest.Matchers.equalTo("LOWEST"))
            .extract()
            .path("request.id");
    requestIds.add(id);
    assertEquals(
        List.of("refs/heads/main"), merger.foldsOf("refs/heads/release/" + id).get(0).sources());
    assertEquals(
        List.of(),
        automations.askedAbout(id).get(0).sourceBranches(),
        "main is a source and never a target: the bump is the kind's own branch");
    assertEquals(List.of("PENDING"), preRuns(id));

    asMaintenance()
        .body("{\"reason\":\"dependency-bump is fresh: nothing to bump\"}")
        .post(base() + "/" + id + "/withdraw")
        .then()
        .statusCode(200)
        .body("request.state", org.hamcrest.Matchers.equalTo("WITHDRAWN"))
        .body(
            "request.detail",
            org.hamcrest.Matchers.equalTo("dependency-bump is fresh: nothing to bump"));
    assertTrue(cancellations.cancelledRequests().contains(id));
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  private static io.restassured.specification.RequestSpecification asMaintenance() {
    return given()
        .header("X-Qits-User", "dev-qits-maintenance")
        .header("X-Qits-Roles", "qits:system")
        .contentType(ContentType.JSON);
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"branch\":\"" + branch + "\",\"summary\":\"a release\",\"requester\":\"ada\"}")
            .post(base())
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    return id;
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

  private JsonPath request(String id) {
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private String mergedShaOf(String id) {
    return request(id).getString("mergedSha");
  }

  private String qaAnnouncedShaOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).qaAnnouncedSha);
  }

  private String rejectingRunIdOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).rejectingRunId);
  }

  private void waive(String requestId, String mergedSha) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestAutomationWaiver waiver = new ReleaseRequestAutomationWaiver();
              waiver.id = UUID.randomUUID().toString();
              waiver.requestId = requestId;
              waiver.mergedSha = mergedSha;
              waiver.actor = "ada";
              waiver.reason = "the baselines are wrong and this is their fix";
              waiver.waivedAt = Instant.now();
              waiver.persist();
            });
  }

  private void verdict(String sha) {
    frame("BuildSuccessful", sha, "");
  }

  private void redVerdict(String sha) {
    frame("BuildFailed", sha, ",\"outcome\":\"FAILED\"");
  }

  private void frame(String name, String sha, String extra) {
    listener.onFrame(
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
                + "\""
                + extra
                + "}",
            null,
            null,
            null));
  }

  private void headMoved(String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + branch
                + "\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\"}",
            null,
            null,
            null));
  }

  private void awaitFoldToMove(String id, String previousFold) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = mergedShaOf(id);
      if (last != null && !last.equals(previousFold)) {
        // The fold is applied before it is announced: wait for the announcement too.
        long settle = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < settle
            && announcer.announcedFor(id).stream().noneMatch(a -> a.mergedSha().equals(mergedShaOf(id)))) {
          sleep();
        }
        assertNotEquals(previousFold, last);
        return;
      }
      sleep();
    }
    fail("request " + id + " never re-folded past " + previousFold + "; last seen " + last);
  }

  private void awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = request(id).getString("state");
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
