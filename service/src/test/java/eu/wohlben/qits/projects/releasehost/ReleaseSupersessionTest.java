package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A new release overtakes one that never finalized, and says so.</b>
 *
 * <p>Since a release request stays open until its tag reaches {@code main} (ticket b27384a3), a
 * repository can be asked for a new release while an earlier one is still owed its publish run or
 * its deployment. The new request folds the earlier tag in, so it <em>is</em> the earlier release
 * plus more — and what is left of the earlier one is a request nothing will ever finish and a merge
 * the sweep would go on attempting for a version the platform has moved past.
 *
 * <p>The claim here is that all four halves of ending it happen together: the state, the pointer at
 * the successor, the cancellation, and the abandoned merge. The last is the one only a negative can
 * show — the deployment that would have finalized the old release arrives, and nothing moves.
 */
@QuarkusTest
public class ReleaseSupersessionTest {

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.DeploymentActiveListener deployments;

  @Inject ReleaseFinalization finalization;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingQaRunCancellations cancellations;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    cancellations.reset();
    activeBuilds.answer(Optional.of(0));
    repoId = "supersede-repo-" + UUID.randomUUID();
    projectId = "supersede-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "supersede";
              project.slug = "supersede-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
            });
  }

  /** Both sweeps walk the whole database, so nothing of this fixture may outlive the class. */
  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  @Test
  public void aNewRequestObsoletesTheReleaseItOvertookAndStopsEverythingOwedForIt() {
    String tag = releaseOf("work-a");
    assertEquals("RELEASED", stateOf(firstRequest()), "the premise: released and not finished");
    String earlier = firstRequest();
    cancellations.reset();

    String successor = create("work-b");

    assertEquals("OBSOLETE", stateOf(earlier));
    assertEquals(
        successor,
        given().get(base() + "/" + earlier).then().extract().path("request.supersededBy"),
        "the row names what overtook it, so a reader is never left guessing");
    assertTrue(
        detailOf(earlier).contains(successor),
        "and says so in a sentence too: " + detailOf(earlier));
    assertEquals(
        List.of(earlier),
        cancellations.cancelledRequests(),
        "the overtaken request's runs are cancelled, and only its own");

    ReleasedTagPendingMerge abandoned = rowOf(tag);
    assertNotNull(abandoned.abandonedAt, "and its merge is abandoned rather than left to the sweep");

    // The negative that matters: the gate the old release was waiting for arrives, and nothing
    // moves. Its content is not lost — the successor folds the tag in, one assertion down.
    merger.reset();
    deploymentActive(tag);
    finalization.sweep();

    assertEquals(List.of(), merger.foldsOf("refs/heads/main"));
    assertNull(rowOf(tag).mergedAt, "a superseded release reaches main inside its successor");
  }

  /**
   * The other half, and the reason obsoleting the old release is safe at all: the tag really was
   * cut, so the successor folds it in and every commit it carried ships under the successor's
   * version.
   */
  @Test
  public void theSuccessorFoldsTheSupersededTagIn() {
    String tag = releaseOf("work-a");

    String successor = create("work-b");

    List<String> sources =
        merger.foldsOf("refs/heads/release/" + successor).get(0).sources();
    assertTrue(
        sources.contains("refs/tags/" + tag),
        "the superseded release is an implicit source of what supersedes it: " + sources);
  }

  /**
   * <b>Convergence and obsolescence never fight, because they cannot see the same rows.</b> Every
   * convergence read is over the unreleased states and every obsolescence read is over RELEASED, so
   * asking again about a branch that already participates in an open, untagged request answers that
   * request and obsoletes nothing.
   */
  @Test
  public void aConvergingAskAnswersTheOpenRequestAndObsoletesNothing() {
    String first = create("work-a");

    String again = create("work-a");

    assertEquals(first, again, "the same branch converges, as it always did");
    assertEquals("PENDING", stateOf(first), "and nothing about it was obsoleted");
    assertNull(
        given().get(base() + "/" + first).then().extract().path("request.supersededBy"),
        "there is no successor, because there was no second request to be one");
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  /**
   * Drive one branch all the way to RELEASED and leave it there, waiting on a deployment that never
   * comes — which is exactly the state a later ask overtakes. Answers the version it released as.
   */
  private String releaseOf(String branch) {
    String tag = "2026.915." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree("refs/heads/main", RecordingReleaseGitHost.GATED_MAIN);
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of("pom.xml", "irrelevant", ".config/qits/deployments.yml", "irrelevant"));
    String id = create(branch);
    verdict("BuildSuccessful", mergedShaOf(id));
    awaitState(id, "RELEASED");
    return tag;
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

  /** The oldest request of the fixture repository — the one {@link #releaseOf} made. */
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

  private void verdict(String name, String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"work-a\",\"commitSha\":\""
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

  private void deploymentActive(String version) {
    deployments.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            "{\"deploymentId\":\""
                + UUID.randomUUID()
                + "\",\"applicationName\":\"supersede\",\"environmentName\":\"dev\",\"version\":\""
                + version
                + "\"}",
            null,
            null,
            null));
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
