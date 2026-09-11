package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The regression, and it is the one that matters.</b> Almost every repository on this platform
 * carries {@code .config/qits/ci-event-release-request.yml} on its {@code main} and no {@code
 * release-requests.yml} at all, so almost every repository has to behave byte-identically before and
 * after the gate set landed: one CI gate, unconditional, and nobody ever asked to approve anything.
 *
 * <p>It is written before the reporting and the cutover, and a green run here is what licenses the
 * rest. Where {@code ReleaseRequestFlowTest} asserts these rules as the machine's behaviour, this
 * class asserts them as a statement about <b>a configuration</b> — it stages the repository's {@code
 * main} explicitly, so what it proves survives a change to whatever the fake happens to default to.
 */
@QuarkusTest
public class CiRepositoryUnchangedTest {

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    activeBuilds.answer(Optional.of(0));
    repoId = "ci-unchanged-repo-" + UUID.randomUUID();
    projectId = "ci-unchanged-project-" + UUID.randomUUID();
    // THE POPULATION THIS CLASS IS ABOUT: a release-request recipe, and nothing else. No
    // deployments.yml, no release-requests.yml — the state of almost every repository here.
    gitHost.tree(
        "refs/heads/main", Map.of(RecordingReleaseGitHost.CI_RECIPE, "steps:\n  - name: verify\n"));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "ci-unchanged";
              project.slug = "ci-unchanged-" + UUID.randomUUID();
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
  void dropTheFixturesRequests() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  @Test
  public void nothingReleasesUntilAGatingRunSaysSuccess() {
    String id = create("work");
    String merged = mergedShaOf(id);
    assertNotNull(merged, "the create folds the sources at once");

    releaseRequests.sweep();
    releaseRequests.sweep();
    assertEquals("PENDING", stateOf(id), "no verdict, no release");
    assertEquals(0, executor.calls().size());
    assertTrue(
        detailOf(id).contains("Waiting for a gating CI verdict"),
        "and it says which gate it is waiting on: " + detailOf(id));

    verdict("BuildSuccessful", merged, "");
    awaitState(id, "RELEASED");
  }

  @Test
  public void oneRedGatingVerdictRejectsImmediately() {
    String id = create("work");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    assertTrue(detailOf(id).contains("FAILED"), detailOf(id));
    assertEquals(0, executor.calls().size(), "a rejected request must never reach the door");
  }

  @Test
  public void aNonGatingVerdictChangesNothing() {
    String id = create("work");
    String merged = mergedShaOf(id);
    verdict("BuildFailed", merged, ",\"outcome\":\"FAILED\",\"gating\":false");
    assertEquals("PENDING", stateOf(id), "a userflow verdict is read and ignored");
    verdict("BuildSuccessful", merged, ",\"gating\":false");
    assertEquals("PENDING", stateOf(id), "and a green one is no vouch either");
    assertEquals(0, executor.calls().size());
  }

  @Test
  public void aVerdictForAStaleShaIsIgnored() {
    String id = create("work");
    String superseded = mergedShaOf(id);
    headMoved("work");
    String current = mergedShaOf(id);
    assertNotEquals(superseded, current, "the push re-folded the request");

    verdict("BuildSuccessful", superseded, "");
    releaseRequests.sweep();
    assertEquals("PENDING", stateOf(id), "the vouch is for content nobody will accept any more");
    assertEquals(0, executor.calls().size());

    verdict("BuildSuccessful", current, "");
    awaitState(id, "RELEASED");
  }

  @Test
  public void noApprovalIsEverAskedFor() {
    String id = create("work");
    boolean approvalRequired =
        given()
            .get(base() + "/" + id)
            .then()
            .statusCode(200)
            .extract()
            .path("request.approvalRequired");
    assertFalse(
        approvalRequired, "a repository with no release-requests.yml has no approval gate");
    assertEquals(
        "NOT_REQUIRED",
        given().get(base() + "/" + id).then().extract().path("request.approvalState"));

    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");
    assertEquals(1, executor.calls().size(), "a green build alone released it, as it always did");
  }

  // -----------------------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"an ordinary release\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private void verdict(String name, String sha, String extra) {
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

  /** A push to a participating branch, over the real listener — what re-folds and re-arms. */
  private void headMoved(String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"repoId\":\""
                + repoId
                + "\",\"branch\":\""
                + branch
                + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\"}",
            null,
            null,
            null));
  }

  private String stateOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.state");
  }

  private String detailOf(String id) {
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    return detail == null ? "" : detail;
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
