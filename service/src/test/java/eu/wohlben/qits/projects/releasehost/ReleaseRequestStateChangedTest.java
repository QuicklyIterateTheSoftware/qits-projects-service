package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseRequestStateAnnouncer.StateChange;
import eu.wohlben.qits.projects.control.ReleaseRequestStates;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code ReleaseRequestStateChanged} (qits-1153, W7): one announcement per real state change,
 * after its transaction commits — one test per family of moves, and the cases that must announce
 * nothing. FINALIZED and OBSOLETE are asserted where their flows already live: {@code
 * MainFinalizationTest} and {@code ReleaseSupersessionTest}.
 *
 * <p>Every recorded change also carries the state a fresh transaction read when it was announced
 * ({@link RecordingReleaseRequestStateAnnouncer}), so each test also proves the change was
 * committed before it was told.
 */
@QuarkusTest
public class ReleaseRequestStateChangedTest {

  @Inject BuildStatusListener listener;

  @Inject ReleaseRequestHeadListener headListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject ReleaseRequestStates states;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseRequestStateAnnouncer announced;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    announced.reset();
    repoId = "state-repo-" + UUID.randomUUID();
    projectId = "state-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "release-states";
              project.slug = "release-states-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /** Open requests must not outlive the class: {@code sweep()} walks every open row. */
  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  // ---- creation --------------------------------------------------------------------------------

  @Test
  public void aNewRequestMovesFromNothingToPendingWithItsBranches() {
    activeBuilds.answer(Optional.of(1));
    String id = create("ticket/qits-1153");

    assertEquals(List.of("null>PENDING"), announced.movesOf(id));
    StateChange change = announced.changesOf(id).get(0);
    assertNull(change.previousState(), "a new request moves from nothing");
    assertEquals(repoId, change.repoId());
    assertEquals(projectId, change.projectId());
    assertEquals(
        List.of("main", "ticket/qits-1153"),
        change.branches(),
        "the sources added after the state, in the same transaction, travel with it");
    assertEquals("PENDING", announced.recorded().get(0).committedState());
  }

  // ---- the gate and the release -----------------------------------------------------------------

  @Test
  public void aGreenVerdictAnnouncesReadyThenReleasedWithTheVersion() {
    String id = create("work");
    verdict("BuildSuccessful", mergedShaOf(id), run("green"), null, "");

    awaitMoves(id, List.of("null>PENDING", "PENDING>READY", "READY>RELEASED"));
    StateChange released = announced.changesOf(id).get(2);
    assertEquals("2026.831.90000", released.version());
    assertEquals(mergedShaOf(id), released.mergedSha());
    assertNull(announced.changesOf(id).get(1).version(), "no version before the release");
  }

  @Test
  public void aRedVerdictAnnouncesRejectedWithItsDetailAndThePushThatAnswersItAnnouncesPending() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    verdict("BuildFailed", mergedShaOf(id), run("red"), null, ",\"outcome\":\"FAILED\"");
    awaitMoves(id, List.of("null>PENDING", "PENDING>REJECTED"));
    assertTrue(announced.changesOf(id).get(1).detail().contains("FAILED"));

    headMoved("work", sha());
    awaitMoves(id, List.of("null>PENDING", "PENDING>REJECTED", "REJECTED>PENDING"));
  }

  /** The retry of the rejecting run reconsiders the same fold: REJECTED to PENDING, then on. */
  @Test
  public void aGreenRetryOfTheRejectingRunAnnouncesTheWayBack() {
    String id = create("work");
    String merged = mergedShaOf(id);
    String red = run("red");
    verdict("BuildFailed", merged, red, null, ",\"outcome\":\"FAILED\"");
    awaitMoves(id, List.of("null>PENDING", "PENDING>REJECTED"));

    verdict("BuildSuccessful", merged, run("green"), red, "");
    awaitMoves(
        id,
        List.of(
            "null>PENDING",
            "PENDING>REJECTED",
            "REJECTED>PENDING",
            "PENDING>READY",
            "READY>RELEASED"));
  }

  @Test
  public void aRefusedReleaseAnnouncesFailedAndTheRetryAnnouncesTheRelease() {
    executor.answer(ReleaseExecutor.Outcome.refusedRetryable("the door could not be reached"));
    String id = create("work");
    verdict("BuildSuccessful", mergedShaOf(id), run("green"), null, "");
    awaitMoves(id, List.of("null>PENDING", "PENDING>READY", "READY>FAILED"));
    assertTrue(announced.changesOf(id).get(2).detail().contains("could not be reached"));

    executor.answer(ReleaseExecutor.Outcome.released("2026.831.90001", "released-sha-1"));
    releaseRequests.sweep();
    awaitLastMove(id, "RELEASED");
    assertEquals("2026.831.90001", last(id).version());
  }

  // ---- folds ------------------------------------------------------------------------------------

  @Test
  public void aConflictAnnouncesConflictedAndTheCleanFoldAnnouncesPending() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    merger.answer(
        BackingBranchMerger.Outcome.conflict(
            BackingBranches.ref(id),
            List.of(
                new BackingBranchMerger.Conflict(
                    "pom.xml", "refs/heads/work", "abc1234", "content"))));
    headMoved("work", sha());
    assertEquals(List.of("null>PENDING", "PENDING>CONFLICTED"), announced.movesOf(id));
    assertTrue(last(id).detail().contains("pom.xml"));

    merger.answerFreshMerges();
    headMoved("work", sha());
    assertEquals(
        List.of("null>PENDING", "PENDING>CONFLICTED", "CONFLICTED>PENDING"), announced.movesOf(id));
  }

  /** A re-arm of a PENDING request writes PENDING again: no change, no announcement. */
  @Test
  public void aRefoldOfAPendingRequestAnnouncesNothing() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    String before = mergedShaOf(id);

    headMoved("work", sha());

    assertFalse(before.equals(mergedShaOf(id)), "the premise: the request was re-armed");
    assertEquals(List.of("null>PENDING"), announced.movesOf(id));
  }

  // ---- withdrawals ------------------------------------------------------------------------------

  @Test
  public void anOperatorsWithdrawalIsAnnouncedWithTheReason() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"shipped another way\"}")
        .post(base() + "/" + id + "/withdraw")
        .then()
        .statusCode(200);

    assertEquals(List.of("null>PENDING", "PENDING>WITHDRAWN"), announced.movesOf(id));
    assertEquals("shipped another way", last(id).detail());
  }

  @Test
  public void aDeletedBranchThatWithdrawsTheRequestIsAnnounced() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMDeleteBranch",
            Instant.now(),
            "{\"branch\":\"work\",\"repoId\":\"" + repoId + "\",\"sha\":\"" + sha() + "\"}",
            null,
            null,
            null));

    assertEquals(List.of("null>PENDING", "PENDING>WITHDRAWN"), announced.movesOf(id));
    assertEquals(List.of("main"), last(id).branches(), "the deleted source is gone");
  }

  // ---- the choke point itself -------------------------------------------------------------------

  @Test
  public void aMoveToTheStateTheRowHasIsNoChange() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    announced.reset();

    boolean moved =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    states.move(
                        ReleaseRequest.<ReleaseRequest>findById(id), ReleaseRequest.State.PENDING));

    assertFalse(moved);
    assertEquals(List.of(), announced.movesOf(id));
  }

  @Test
  public void aTransactionThatRollsBackAnnouncesNothing() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    announced.reset();

    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                states.move(ReleaseRequest.<ReleaseRequest>findById(id), ReleaseRequest.State.READY);
                throw new IllegalStateException("rolled back on purpose");
              });
      fail("the transaction should have thrown");
    } catch (IllegalStateException expected) {
      // the point
    }

    assertEquals(List.of(), announced.movesOf(id));
    assertEquals("PENDING", stateOf(id));
  }

  /** Two moves in one transaction are one change, first state to last — or none at all. */
  @Test
  public void movesInOneTransactionAreOneChangeFromTheFirstStateToTheLast() {
    activeBuilds.answer(Optional.of(1));
    String id = create("work");
    announced.reset();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = ReleaseRequest.findById(id);
              states.move(row, ReleaseRequest.State.READY);
              states.move(row, ReleaseRequest.State.FAILED);
            });
    assertEquals(List.of("PENDING>FAILED"), announced.movesOf(id));

    announced.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = ReleaseRequest.findById(id);
              states.move(row, ReleaseRequest.State.CONFLICTED);
              states.move(row, ReleaseRequest.State.FAILED);
            });
    assertEquals(List.of(), announced.movesOf(id), "FAILED to FAILED is no change");
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a release\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private String stateOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.state");
  }

  private String mergedShaOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.mergedSha");
  }

  private StateChange last(String id) {
    List<StateChange> changes = announced.changesOf(id);
    return changes.get(changes.size() - 1);
  }

  private void verdict(String name, String sha, String runId, String retryOfRunId, String extra) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"" + BackingBranches.atFold(repoId, sha) + "\",\"commitSha\":\"" + sha + "\",\"repoId\":\"" + repoId + "\""
                + (retryOfRunId == null ? "" : ",\"retryOfRunId\":\"" + retryOfRunId + "\"")
                + ",\"runId\":\"" + runId + "\"" + extra + "}",
            null,
            null,
            null));
  }

  private void headMoved(String branch, String sha) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\"" + branch + "\",\"repoId\":\"" + repoId + "\",\"sha\":\"" + sha + "\"}",
            null,
            null,
            null));
  }

  /** The release runs on the request worker, so its announcements are polled for. */
  private void awaitMoves(String id, List<String> expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (announced.movesOf(id).equals(expected)) {
        assertCommitted(id);
        return;
      }
      pause();
    }
    assertEquals(expected, announced.movesOf(id));
  }

  private void awaitLastMove(String id, String state) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      List<StateChange> changes = announced.changesOf(id);
      if (!changes.isEmpty() && state.equals(changes.get(changes.size() - 1).state())) {
        return;
      }
      pause();
    }
    fail("request " + id + " never announced " + state + ": " + announced.movesOf(id));
  }

  /** Each announcement read back a committed state — the last one, the state the request has. */
  private void assertCommitted(String id) {
    List<RecordingReleaseRequestStateAnnouncer.Recorded> mine =
        announced.recorded().stream()
            .filter(r -> r.change().releaseRequestId().equals(id))
            .toList();
    RecordingReleaseRequestStateAnnouncer.Recorded newest = mine.get(mine.size() - 1);
    assertEquals(newest.change().state(), stateOf(id));
  }

  private static void pause() {
    try {
      Thread.sleep(50);
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
