package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>An {@code unchanged} fold at a new sha records it and re-arms</b> (qits-1565).
 *
 * <p>qits-githost answers {@code unchanged} when the backing branch's tip already is the fold. That
 * tip is whatever the last fold it made left there — including a fold whose answer was lost, the
 * call failing UNREACHABLE after the ref had already moved. The request's {@code mergedSha} then
 * named a sha the branch had left, and dropping the {@code unchanged} kept it there for ever: no
 * re-arm, and qits-maintenance asked about the old fold while the branch stood at the new one, so
 * every automation run was superseded before it started. An {@code unchanged} at the sha already
 * recorded is still no change at all.
 */
@QuarkusTest
public class ReleaseRequestLostFoldAnswerTest {

  private static final String BRANCH = "ticket/qits-1565";
  private static final String FIRST = "a".repeat(40);
  private static final String LOST = "b".repeat(40);

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingReleaseRequestStateAnnouncer states;

  @Inject RecordingQaRunCancellations cancellations;

  @Inject FakeReleaseRequestAutomations automations;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    states.reset();
    cancellations.reset();
    automations.reset();
    // A run is "still active", so nothing settles a fixture mid-test: the subject is the fold.
    activeBuilds.answer(Optional.of(1));
    repoId = "lost-fold-repo-" + UUID.randomUUID();
    projectId = "lost-fold-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "lost-fold";
              project.slug = "lost-fold-" + UUID.randomUUID();
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
    automations.reset();
  }

  @Test
  public void anUnchangedFoldAtANewShaAfterALostAnswerRecordsTheTipAndRearms() {
    merger.answer(BackingBranchMerger.Outcome.merged(FIRST, List.of()));
    String id = create();
    assertEquals(FIRST, request(id).getString("mergedSha"));

    // The git host folds and moves the branch onto LOST, but its answer never arrives.
    merger.answer(BackingBranchMerger.Outcome.unreachable("qits-githost timed out"));
    headMoved();
    assertEquals(FIRST, request(id).getString("mergedSha"), "nothing came back to record");

    // A request that had got as far as READY on the old fold makes the re-arm observable.
    markReady(id);
    int asksBefore = automations.askedAbout(id).size();
    Instant armedBefore = rowOf(id).armedAt;

    // The next fold finds the branch already at LOST: unchanged, at a sha the request never saw.
    merger.answer(BackingBranchMerger.Outcome.unchanged(LOST));
    headMoved();

    JsonPath rearmed = request(id);
    assertEquals(LOST, rearmed.getString("mergedSha"), "the branch's tip is recorded");
    assertEquals("PENDING", rearmed.getString("state"));
    assertTrue(
        states.movesOf(id).contains("READY>PENDING"), "re-armed: " + states.movesOf(id));
    assertTrue(
        rowOf(id).armedAt.isAfter(armedBefore), "the window restarted on the new fold");

    List<FakeReleaseRequestAutomations.Asked> asks = automations.askedAbout(id);
    assertEquals(asksBefore + 1, asks.size(), "the automations are asked about the new fold");
    FakeReleaseRequestAutomations.Asked ask = asks.get(asks.size() - 1);
    assertEquals(LOST, ask.foldSha());
    assertEquals(FIRST, ask.previousFoldSha(), "the superseded fold travels along");
    assertTrue(
        cancellations.qaOnlyCancelledRequests().contains(id),
        "QA of the superseded fold is cancelled: " + cancellations.calls());
  }

  @Test
  public void anUnchangedFoldAtTheRecordedShaIsStillNoChange() {
    merger.answer(BackingBranchMerger.Outcome.merged(FIRST, List.of()));
    String id = create();
    ReleaseRequest before = rowOf(id);
    assertEquals(FIRST, before.mergedSha);
    int asksBefore = automations.askedAbout(id).size();
    List<String> movesBefore = states.movesOf(id);
    int cancelsBefore = cancellations.calls().size();

    merger.answer(BackingBranchMerger.Outcome.unchanged(FIRST));
    headMoved();

    ReleaseRequest after = rowOf(id);
    assertEquals(FIRST, after.mergedSha);
    assertEquals(before.state, after.state);
    assertEquals(before.detail, after.detail);
    assertEquals(before.armedAt, after.armedAt, "nothing re-armed");
    assertEquals(asksBefore, automations.askedAbout(id).size(), "no automation refresh");
    assertEquals(movesBefore, states.movesOf(id), "no state was announced");
    assertEquals(cancelsBefore, cancellations.calls().size(), "nothing was superseded");
  }

  // -----------------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create() {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + BRANCH + "\",\"summary\":\"a release whose fold answer is lost\"}")
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

  private ReleaseRequest rowOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id));
  }

  private void markReady(String id) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = ReleaseRequest.findById(id);
              row.state = ReleaseRequest.State.READY;
              row.detail = "Ready on the old fold";
            });
  }

  private void headMoved() {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + BRANCH
                + "\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + RecordingBackingBranchMerger.freshSha()
                + "\"}",
            null,
            null,
            null));
  }
}
