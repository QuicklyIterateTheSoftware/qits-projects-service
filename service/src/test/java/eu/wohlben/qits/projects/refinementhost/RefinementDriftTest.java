package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The single-refinement read is not a git operation — the server half of "a freshly started
 * refinement opens a blank page".
 *
 * <p>{@code RefinementService.view()} used to open with {@code mirror.refresh()}, which is a fetch
 * when the mirror is warm and a full clone when it is cold; a refinement that has just cut its
 * branch leaves it cold, so the very first read of the very first row was the slowest read this
 * surface had. These tests pin the three halves of the replacement: the read answers from what is
 * known and never computes, the drift still arrives afterwards, and the arrival is announced on the
 * existing {@code git-status} hint so the browser re-reads the row rather than polling for it.
 */
@QuarkusTest
public class RefinementDriftTest {

  @Inject RefinementService service;
  @Inject RefinementDrift drift;
  @Inject RefinementEventBroadcaster broadcaster;

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(java.util.Map.of("title", title, "description", "A draft."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private long open(String epicId) {
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(java.util.Map.of("epicId", epicId))
            .when()
            .post("/projects/api/refinements")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  private long refinementFor(String projectName, String epicTitle) {
    String projectId = createProject(projectName);
    return open(createEpic(projectId, epicTitle));
  }

  /** Poll until the predicate holds, or fail — drift is computed off the request thread. */
  private void await(String what, java.util.function.BooleanSupplier done) {
    Instant giveUp = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(giveUp)) {
      if (done.getAsBoolean()) {
        return;
      }
      sleep(50);
    }
    throw new AssertionError(what);
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Wait for the drift pool to go quiet, so a pass in flight cannot be mistaken for a new one. */
  private void awaitQuiet() {
    int seen = -1;
    Instant giveUp = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(giveUp)) {
      int now = drift.passes();
      if (now == seen) {
        return;
      }
      seen = now;
      sleep(200);
    }
    throw new AssertionError("the drift pool never went quiet");
  }

  @Test
  public void theReadAnswersWithDriftUnknownRatherThanWaitingForIt() {
    long id = refinementFor("Drift Fresh", "Fresh Drift Epic");

    // Whatever the create's own projection may already have computed, this is the state a brand-new
    // refinement is genuinely in: nothing known, mirror possibly cold.
    drift.forget(id);

    RefinementService.RefinementView view = service.view(service.get(id));

    // Not "eventually null" — the projection reads the cache before it decides to schedule
    // anything, so this is what a caller gets however quick a worker happens to be.
    assertNull(view.ahead(), "a read must not wait for the mirror to be able to count");
    assertNull(view.behind(), "a read must not wait for the mirror to be able to count");
    assertFalse(view.conflictsWithParent(), "an unknown drift is never a conflict warning");
  }

  @Test
  public void driftStillArrives_justNotOnTheReadPath() {
    long id = refinementFor("Drift Arrives", "Arriving Drift Epic");

    drift.forget(id);
    service.view(service.get(id)); // schedules the pass; computes nothing here

    await(
        "drift never arrived for refinement " + id,
        () -> drift.peek(id).map(d -> d.ahead() != null).orElse(false));

    RefinementService.RefinementView view = service.view(service.get(id));
    // The branch was cut at the wrapper's default tip and nobody has pushed to either, so the one
    // honest answer is zero by zero — and it is an answer, not the null of "not known yet".
    assertEquals(0, view.ahead(), "a branch cut at its parent's tip is ahead by nothing");
    assertEquals(0, view.behind(), "a branch cut at its parent's tip is behind by nothing");
    assertFalse(view.conflictsWithParent());
  }

  @Test
  public void repeatedReadsInsideTheWindowDoNoGitWorkAtAll() {
    long id = refinementFor("Drift Cheap", "Cheap Drift Epic");

    drift.forget(id);
    service.view(service.get(id));
    await("drift never arrived for refinement " + id, () -> drift.peek(id).isPresent());
    awaitQuiet();

    // The claim the whole change exists to make: the refining page can re-read its row on every
    // hint and the git host hears nothing.
    int before = drift.passes();
    for (int i = 0; i < 10; i++) {
      service.view(service.get(id));
    }
    assertEquals(before, drift.passes(), "a read inside the staleness window computes nothing");
  }

  @Test
  public void arrivingDriftInvalidatesTheRowOverTheExistingGitStatusHint() {
    long id = refinementFor("Drift Hint", "Hinting Drift Epic");
    awaitQuiet();

    List<String> topics = new CopyOnWriteArrayList<>();
    var subscription = broadcaster.subscribe(id).subscribe().with(topics::add);
    try {
      drift.forget(id);
      service.view(service.get(id));

      // No new channel for the late arrival: `git-status` is the hint the daemon already publishes
      // when the tree flips clean/dirty and the SPA already maps to "re-read this row".
      await(
          "no git-status hint was published when the drift arrived; saw " + topics,
          () -> topics.contains("git-status"));
      assertTrue(drift.peek(id).isPresent());
    } finally {
      subscription.cancel();
    }
  }
}
