package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.gate.FoldFailure;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
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
 * <b>A fold that fails because a source does not exist rejects the request</b> (qits-1221).
 *
 * <p>qits-githost answers a fold naming a ref it cannot resolve with {@code 404 no-such-source}.
 * That used to be read as "unreachable" — a fact about the moment — so the request stood PENDING for
 * ever, its gates waiting ("Nothing is folded yet") and the sweep knocking every 30 seconds
 * (qits-projects-service-rr-261). It is a fact about the request: the request is REJECTED with
 * {@code rejecting_gate = fold}, the state and every gate name the missing ref, the sweep leaves it
 * alone, and a push of the branch folds it again.
 */
@QuarkusTest
public class ReleaseRequestFoldFailureTest {

  private static final String MISSING = "refs/heads/ticket/qits-1199";

  @Inject ReleaseRequests releaseRequests;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingReleaseRequestStateAnnouncer states;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    states.reset();
    // A run is "still active", so nothing settles a fixture mid-test: the subject is the fold.
    activeBuilds.answer(Optional.of(1));
    repoId = "fold-failure-repo-" + UUID.randomUUID();
    projectId = "fold-failure-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "fold-failure";
              project.slug = "fold-failure-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /** {@code sweep()} walks every open row in the database, so none outlives its test. */
  @AfterEach
  void dropTheFixturesRequests() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId = ?1", projectId));
    merger.reset();
  }

  @Test
  public void aMissingSourceRejectsTheRequestAndEveryGateNamesTheFold() {
    merger.answer(BackingBranchMerger.Outcome.missingSource(MISSING));
    String id = create("ticket/qits-1199");

    JsonPath rejected = request(id);
    String detail =
        "The sources could not be folded: " + MISSING + " does not exist on the git host";
    assertEquals("REJECTED", rejected.getString("state"), "a terminal-looking state, not PENDING");
    assertEquals(detail, rejected.getString("detail"));
    assertFalse(rejected.getBoolean("retryable"));
    assertNull(rejected.getString("mergedSha"), "nothing was folded");
    assertEquals(FoldFailure.KIND, rejectingGateOf(id));
    assertNull(rejectingRunIdOf(id));

    // Every gate names the fold failure instead of waiting: no "Nothing is folded yet".
    List<Map<String, Object>> gates = rejected.getList("qualityGates");
    assertFalse(gates.isEmpty(), "the CI gate applies: main declares a QA pipeline");
    for (Map<String, Object> gate : gates) {
      assertEquals("FAILED", gate.get("state"), gate.toString());
      assertEquals(detail, gate.get("detail"), gate.toString());
    }
    assertTrue(
        rejected.getList("gates.state").stream().allMatch("FAILED"::equals),
        "the deprecated list agrees: " + rejected.getList("gates"));

    // The rejection is announced like any other — it is what wakes `qits wait release-request`.
    assertTrue(
        states.movesOf(id).contains("PENDING>REJECTED"), "announced: " + states.movesOf(id));
  }

  @Test
  public void theSweepNeitherRefoldsNorReopensAFoldRejection() {
    merger.answer(BackingBranchMerger.Outcome.missingSource(MISSING));
    String id = create("ticket/qits-1199");
    int folds = merger.foldsOf(BackingBranches.ref(id)).size();

    // Even with a git host that would now fold, the sweep does not knock: only a push answers it.
    merger.answerFreshMerges();
    releaseRequests.sweep();

    assertEquals(folds, merger.foldsOf(BackingBranches.ref(id)).size(), "no fold was attempted");
    assertEquals("REJECTED", request(id).getString("state"));
    assertEquals(FoldFailure.KIND, rejectingGateOf(id));
  }

  @Test
  public void aPushOfTheMissingBranchFoldsTheRequestAgain() {
    merger.answer(BackingBranchMerger.Outcome.missingSource(MISSING));
    String id = create("ticket/qits-1199");
    assertEquals("REJECTED", request(id).getString("state"));

    merger.answerFreshMerges();
    headMoved("ticket/qits-1199");

    JsonPath rearmed = request(id);
    assertEquals("PENDING", rearmed.getString("state"));
    assertNotNull(rearmed.getString("mergedSha"), "the fold landed");
    assertNull(rejectingGateOf(id), "the re-arm clears the cause with the rejection");
    assertTrue(
        states.movesOf(id).contains("REJECTED>PENDING"), "announced: " + states.movesOf(id));
  }

  /**
   * A request folded once, whose source then went missing, keeps its last fold; a push that brings
   * the fold back to the very same sha answers {@code unchanged} — and must still lift the rejection,
   * or the request would stay rejected over content that folds.
   */
  @Test
  public void aFoldBackToTheSameShaLiftsTheRejectionAtThatFold() {
    String id = create("ticket/qits-1199");
    String merged = request(id).getString("mergedSha");
    assertNotNull(merged);

    merger.answer(BackingBranchMerger.Outcome.missingSource(MISSING));
    headMoved("ticket/qits-1199");
    JsonPath rejected = request(id);
    assertEquals("REJECTED", rejected.getString("state"));
    assertEquals(merged, rejected.getString("mergedSha"), "the last fold is kept");
    assertEquals(FoldFailure.KIND, rejectingGateOf(id));

    merger.answer(BackingBranchMerger.Outcome.unchanged(merged));
    headMoved("ticket/qits-1199");
    JsonPath reopened = request(id);
    assertEquals("PENDING", reopened.getString("state"));
    assertEquals(merged, reopened.getString("mergedSha"));
    assertNull(rejectingGateOf(id));
  }

  /** Any other failure to fold is still a fact about the moment: the state is left alone. */
  @Test
  public void anUnreachableGitHostStillLeavesTheRequestPending() {
    merger.answer(
        BackingBranchMerger.Outcome.unreachable("qits-githost answered 404: no-such-repository"));
    String id = create("ticket/qits-1199");

    assertEquals("PENDING", request(id).getString("state"));
    assertNull(rejectingGateOf(id));
  }

  // -----------------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a release with a missing source\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
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

  private String rejectingGateOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).rejectingGate);
  }

  private String rejectingRunIdOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).rejectingRunId);
  }

  private void headMoved(String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\"" + branch + "\",\"repoId\":\"" + repoId + "\",\"sha\":\""
                + RecordingBackingBranchMerger.freshSha() + "\"}",
            null,
            null,
            null));
  }
}
