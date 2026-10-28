package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseRequestAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
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
 * <b>An automation's commit under a fold in flight is a re-fold OWED, not made</b> (qits-1570).
 * qits-maintenance commits onto a request's own {@code maintenance/automations/<kind>/<request>}
 * branch once per member release, and every such push used to re-fold the request at once —
 * cancelling the QA building its fold and lapsing the approval naming it — so in a release wave a
 * request was re-folded every few minutes and never reached READY. {@code
 * ReleaseGateCorrelationTest} keeps pinning the cancel an ordinary (ticket-branch) move still makes;
 * this class is what the automation branch does differently, with the same fixtures: the merger, the
 * announcer and the cancellations are the package's recording fakes, and a move is the real head
 * listener hearing an {@code SCMPublishCommit}.
 *
 * <p>No automation applies here (the suite's qits-maintenance is not configured) and nothing is
 * upstream, so every fold is announced {@code preRun=DONE} on the evaluation after it lands: "QA is
 * running" is a fold announced DONE with no verdict of its own yet.
 */
@QuarkusTest
public class ReleaseRequestOwedRefoldTest {

  @Inject eu.wohlben.qits.projects.bus.BuildStatusListener buildListener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  @Inject RecordingQaRunCancellations cancellations;

  private String projectId;

  /** A repository whose requests need nothing but a green QA run. */
  private String repoId;

  /** A repository whose main says {@code manual-review: true}: green, then a person's yes. */
  private String reviewedRepoId;

  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    announcer.reset();
    cancellations.reset();
    requestIds.clear();
    // Nothing else in flight: a green verdict is the whole of the CI gate, so what holds a request
    // here is its QA run or its approval and nothing else.
    activeBuilds.answer(Optional.of(0));
    String unique = UUID.randomUUID().toString();
    projectId = "owed-refold-project-" + unique;
    repoId = "owed-refold-repo-" + unique;
    reviewedRepoId = "owed-refold-reviewed-" + unique;
    gitHost.gatedTreeFor(
        reviewedRepoId,
        "refs/heads/main",
        Map.of(
            "README.md", "reviewed",
            ".config/qits/release-requests.yml", "manual-review: true\n"));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "owed-refold";
              project.slug = "owed-refold-" + unique;
              project.persist();
              for (String id : List.of(repoId, reviewedRepoId)) {
                Repository repository = new Repository();
                repository.id = id;
                repository.project = project;
                repository.mainBranch = "main";
                repository.archetype = RepositoryArchetype.SERVICE;
                repository.persist();
              }
            });
  }

  /** Open requests must not outlive this class: {@code sweep()} walks every open row. */
  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId in ?1", List.of(repoId, reviewedRepoId));
              if (!requestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", requestIds);
              }
            });
  }

  // ---------------------------------------------------------------------------------------------
  // While QA runs
  // ---------------------------------------------------------------------------------------------

  /** 1. The automation's commit lands while QA builds the fold: nothing cancelled, nothing moved. */
  @Test
  public void anAutomationMoveWhileQaRunsIsOwedRatherThanMade() {
    String id = inQa(repoId);
    String merged = mergedShaOf(repoId, id);
    int folds = foldsOf(repoId);

    headMoved(repoId, automationBranchOf(id));

    assertEquals(List.of(), cancellations.calls(), "the QA building the fold keeps running");
    assertEquals(merged, mergedShaOf(repoId, id), "and the fold it builds stays the request's");
    assertEquals(folds, foldsOf(repoId), "no fold was even asked for");
    assertNotNull(row(id).refoldOwedAt, "the re-fold is owed instead");
    assertTrue(row(id).refoldOwedReason.contains(automationBranchOf(id)), row(id).refoldOwedReason);
    assertEquals("PENDING", stateOf(repoId, id));
  }

  /**
   * 2. A red verdict at the fold the move was kept waiting for ends its flight: the request rejects
   * and the owed re-fold re-arms it at once, onto the automation branch's new head, and QA is asked
   * for again at the new fold.
   */
  @Test
  public void aRedVerdictAtTheOldFoldPaysTheOwedRefoldOnceAndAsksForQaAgain() {
    String id = inQa(repoId);
    String merged = mergedShaOf(repoId, id);
    headMoved(repoId, automationBranchOf(id));
    int folds = foldsOf(repoId);

    verdict(repoId, "BuildFailed", merged, ",\"outcome\":\"FAILED\"");

    assertEquals(folds + 1, foldsOf(repoId), "exactly one re-fold");
    String refolded = mergedShaOf(repoId, id);
    assertNotEquals(merged, refolded, "onto the automation branch's new head");
    assertTrue(
        merger.lastFold().sources().stream().anyMatch(ref -> ref.endsWith(automationBranchOf(id))),
        merger.lastFold().sources().toString());
    assertEquals("PENDING", stateOf(repoId, id), "re-armed, not left rejected");
    assertNull(row(id).refoldOwedAt, "and nothing is owed any more");
    List<RecordingReleaseRequestAnnouncer.Announced> qa = announcer.qaAnnouncedFor(id);
    assertEquals(refolded, qa.get(qa.size() - 1).mergedSha(), "QA is asked for at the new fold");
    assertEquals(ReleaseRequestAnnouncer.PRE_RUN_DONE, qa.get(qa.size() - 1).preRun());
  }

  /** 3. Several commits during one run coalesce: one re-fold pays them all. */
  @Test
  public void twoAutomationMovesDuringOneRunCoalesceIntoOneRefold() {
    String id = inQa(repoId);
    String merged = mergedShaOf(repoId, id);
    int folds = foldsOf(repoId);

    headMoved(repoId, automationBranchOf(id));
    Instant firstOwed = row(id).refoldOwedAt;
    headMoved(repoId, automationBranchOf(id));

    assertEquals(folds, foldsOf(repoId), "neither move folded");
    assertEquals(List.of(), cancellations.calls());
    assertEquals(firstOwed, row(id).refoldOwedAt, "the first move's instant stands");

    verdict(repoId, "BuildFailed", merged, ",\"outcome\":\"FAILED\"");

    assertEquals(folds + 1, foldsOf(repoId), "one re-fold for both moves");
    assertNull(row(id).refoldOwedAt);
  }

  /**
   * 4. A person's branch moving is not an automation's: it re-folds and cancels at once, exactly as
   * {@code ReleaseGateCorrelationTest} pins it, and pays the owed re-fold with the same fold.
   */
  @Test
  public void aTicketBranchMoveWhileARefoldIsOwedRefoldsAndCancelsAtOnce() {
    String id = inQa(repoId);
    String merged = mergedShaOf(repoId, id);
    headMoved(repoId, automationBranchOf(id));
    assertNotNull(row(id).refoldOwedAt);
    int folds = foldsOf(repoId);

    // "work" is the person's branch this request was asked for (a ticket branch, live).
    headMoved(repoId, "work");

    assertEquals(folds + 1, foldsOf(repoId), "one fold");
    assertNotEquals(merged, mergedShaOf(repoId, id));
    assertEquals(1, cancellations.calls().size(), "the superseded QA is cancelled at once");
    assertEquals(id, cancellations.calls().get(0).releaseRequestId());
    assertNull(row(id).refoldOwedAt, "the fold read the automation branch's head too");
    assertNull(row(id).refoldOwedReason);
  }

  /** A fold whose QA has not been asked for is not in flight: the move re-folds as it always did. */
  @Test
  public void anAutomationMoveUnderAFoldNotInFlightRefoldsAtOnce() {
    String id = inQa(repoId);
    String merged = mergedShaOf(repoId, id);
    // QA not asked for at the fold (as after gate 3's interrupt): nothing is building it.
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(id).qaAnnouncedSha = null);
    int folds = foldsOf(repoId);

    headMoved(repoId, automationBranchOf(id));

    assertEquals(folds + 1, foldsOf(repoId));
    assertNotEquals(merged, mergedShaOf(repoId, id));
    assertNull(row(id).refoldOwedAt);
  }

  // ---------------------------------------------------------------------------------------------
  // Green and waiting on a person
  // ---------------------------------------------------------------------------------------------

  /**
   * 5. Green and waiting on approval: the move is owed, the fold stays, and the approval of THAT fold
   * still counts — the request goes READY on it and releases exactly the fold that was approved,
   * with no re-fold made for the owed move.
   */
  @Test
  public void aGreenFoldAwaitingApprovalKeepsItsFoldAndTheApprovalReleasesIt() {
    String id = inQa(reviewedRepoId);
    String merged = mergedShaOf(reviewedRepoId, id);
    verdict(reviewedRepoId, "BuildSuccessful", merged, "");
    assertEquals("PENDING", stateOf(reviewedRepoId, id), "green, waiting for a person");
    int folds = foldsOf(reviewedRepoId);

    headMoved(reviewedRepoId, automationBranchOf(id));

    assertEquals(List.of(), cancellations.calls());
    assertEquals(merged, mergedShaOf(reviewedRepoId, id));
    assertNotNull(row(id).refoldOwedAt);

    admin()
        .body("{\"mergedSha\":\"" + merged + "\",\"note\":\"ship the fold I read\"}")
        .post(base(reviewedRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200);

    awaitState(reviewedRepoId, id, "RELEASED");
    assertEquals(1, executor.calls().size());
    assertEquals(merged, executor.calls().get(0).expectedSha(), "the approved fold is released");
    assertEquals(folds, foldsOf(reviewedRepoId), "the owed re-fold is dropped, never made");
    assertNull(row(id).refoldOwedAt);
  }

  /** A decline ends the fold's flight as a red verdict does: the owed re-fold re-arms it. */
  @Test
  public void aDeclineOfTheGreenFoldPaysTheOwedRefold() {
    String id = inQa(reviewedRepoId);
    String merged = mergedShaOf(reviewedRepoId, id);
    verdict(reviewedRepoId, "BuildSuccessful", merged, "");
    headMoved(reviewedRepoId, automationBranchOf(id));
    int folds = foldsOf(reviewedRepoId);

    admin()
        .body("{\"mergedSha\":\"" + merged + "\",\"note\":\"not this one\"}")
        .post(base(reviewedRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(200);

    assertEquals(folds + 1, foldsOf(reviewedRepoId));
    assertNotEquals(merged, mergedShaOf(reviewedRepoId, id));
    assertEquals("PENDING", stateOf(reviewedRepoId, id), "re-armed onto the new fold");
    assertNull(row(id).refoldOwedAt);
  }

  // ---------------------------------------------------------------------------------------------
  // The backstop
  // ---------------------------------------------------------------------------------------------

  /** 7. A flight that ended without an evaluation to say so is paid by the sweep, once. */
  @Test
  public void theSweepPaysAnOwedRefoldOnceTheFoldIsNoLongerInFlight() {
    String id = inQa(repoId);
    String merged = mergedShaOf(repoId, id);
    headMoved(repoId, automationBranchOf(id));
    int folds = foldsOf(repoId);

    // Still in flight: the sweep leaves it owed.
    releaseRequests.sweep();
    assertEquals(folds, foldsOf(repoId));
    assertNotNull(row(id).refoldOwedAt);

    // QA un-announced behind the evaluation's back: the flight is over.
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(id).qaAnnouncedSha = null);
    releaseRequests.sweep();

    assertEquals(folds + 1, foldsOf(repoId), "paid");
    assertNotEquals(merged, mergedShaOf(repoId, id));
    assertNull(row(id).refoldOwedAt);
    releaseRequests.sweep();
    assertEquals(folds + 1, foldsOf(repoId), "and paid once");
  }

  // ---------------------------------------------------------------------------------------------
  // Driving it
  // ---------------------------------------------------------------------------------------------

  /**
   * A request on {@code work} with its automation branch joined — the join re-folds, as a join
   * always does — and QA announced at the fold that lands, with no verdict yet.
   */
  private String inQa(String inRepo) {
    String id =
        given()
            .header("X-Qits-User", "ada")
            .header("X-Qits-Roles", "qits:admin")
            .contentType(ContentType.JSON)
            .body("{\"branch\":\"work\",\"summary\":\"a gated release\"}")
            .post(base(inRepo))
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + automationBranchOf(id) + "\"}")
        .post(base(inRepo) + "/" + id + "/sources")
        .then()
        .statusCode(200);
    String merged = mergedShaOf(inRepo, id);
    assertEquals(merged, row(id).qaAnnouncedSha, "QA is asked for at the fold");
    cancellations.reset();
    return id;
  }

  private static String automationBranchOf(String id) {
    return "maintenance/automations/dependency-bump/" + id;
  }

  private RequestSpecification admin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "ada")
        .header("X-Qits-Roles", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada"));
  }

  private static String base(String inRepo) {
    return "/projects/api/repositories/" + inRepo + "/release-requests";
  }

  private String mergedShaOf(String inRepo, String id) {
    return given().get(base(inRepo) + "/" + id).then().extract().path("request.mergedSha");
  }

  private String stateOf(String inRepo, String id) {
    return given().get(base(inRepo) + "/" + id).then().extract().path("request.state");
  }

  private static ReleaseRequest row(String id) {
    return QuarkusTransaction.requiringNew().call(() -> ReleaseRequest.findById(id));
  }

  private int foldsOf(String inRepo) {
    return (int) merger.folds().stream().filter(fold -> inRepo.equals(fold.repoId())).count();
  }

  private void headMoved(String inRepo, String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + branch
                + "\",\"repoId\":\""
                + inRepo
                + "\",\"sha\":\""
                + RecordingBackingBranchMerger.freshSha()
                + "\"}",
            null,
            null,
            null));
  }

  private void verdict(String inRepo, String name, String sha, String extra) {
    buildListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\""
                + BackingBranches.atFold(inRepo, sha)
                + "\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + inRepo
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\""
                + extra
                + "}",
            null,
            null,
            null));
  }

  private void awaitState(String inRepo, String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(inRepo, id);
      if (expected.equals(last)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertEquals(expected, last);
  }
}
