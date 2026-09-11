package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
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
 * What a request <b>says</b> about the gates it is held by. With one unconditional gate there was
 * nothing to report; with a set there are three states a person can now see that did not exist —
 * waiting on a build, waiting on a person, and waiting on nothing.
 *
 * <p>The load-bearing assertion is the last one: <b>{@code UNKNOWN} is not {@code PENDING}</b>, and a
 * set that could not be read is not an empty one.
 */
@QuarkusTest
public class ReleaseGateReportingTest {

  @Inject BuildStatusListener listener;

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
    repoId = "gate-report-repo-" + UUID.randomUUID();
    projectId = "gate-report-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "gate-report";
              project.slug = "gate-report-" + UUID.randomUUID();
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
  public void aCiRepositoryWaitsOnItsBuildAndSaysSo() {
    gitHost.tree("refs/heads/main", Map.of(RecordingReleaseGitHost.CI_RECIPE, "steps: []\n"));
    String id = create("work");
    assertEquals(List.of("CI"), kinds(id));
    assertEquals(List.of("PENDING"), states(id), "configured, and nothing has answered yet");

    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");
    assertEquals(List.of("PASSED"), states(id));
  }

  @Test
  public void aRedGatingVerdictReportsTheCiGateFailed() {
    gitHost.tree("refs/heads/main", Map.of(RecordingReleaseGitHost.CI_RECIPE, "steps: []\n"));
    String id = create("work");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    assertEquals(List.of("CI"), kinds(id));
    assertEquals(List.of("FAILED"), states(id));
  }

  @Test
  public void aRepositoryWaitingOnAPersonSaysThatInsteadOfSayingBuild() {
    gitHost.tree("refs/heads/main", Map.of(".config/qits/release-requests.yml", "manual-review: true\n"));
    String id = create("work");
    assertEquals(List.of("APPROVAL"), kinds(id), "approval stands alone with no build in front");
    assertEquals(List.of("PENDING"), states(id));
    assertTrue(
        detailOf(id).contains("approve"),
        "and the sentence names the gate rather than a verdict: " + detailOf(id));
  }

  @Test
  public void aRepositoryConfiguringNothingWaitsOnNothingAndReleasesAtOnce() {
    gitHost.tree("refs/heads/main", Map.of("README.md", "a repository that configures no gate"));
    String id = create("work");
    awaitState(id, "RELEASED");
    assertEquals(List.of(), kinds(id), "an empty set, and it reads as done rather than unfinished");
  }

  @Test
  public void aDeploymentGateIsReportedOnARequestThatHasAlreadyReleased() {
    gitHost.tree(
        "refs/heads/main",
        Map.of(
            RecordingReleaseGitHost.CI_RECIPE, "steps: []\n",
            ".config/qits/deployments.yml", "resources: []\n"));
    String id = create("work");
    assertEquals(List.of("CI", "DEPLOYMENT"), kinds(id));

    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");
    // Released, and main has not been finalized: "waiting on its deployment" is a real state today
    // and this is the first thing that says it.
    assertEquals(List.of("PASSED", "PENDING"), states(id));

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleasedTagPendingMerge.update(
                    "mergedAt = ?1 where repoId = ?2", Instant.now(), repoId));
    assertEquals(List.of("PASSED", "PASSED"), states(id), "the deployment reached main");
  }

  @Test
  public void aConfigurationThatCannotBeReadIsUnknownAndNeverPending() {
    gitHost.mainUnreadable();
    String id = create("work");
    assertEquals(List.of("CI", "APPROVAL", "DEPLOYMENT"), kinds(id), "never an empty list");
    assertEquals(List.of("UNKNOWN", "UNKNOWN", "UNKNOWN"), states(id));
    assertEquals("PENDING", stateOf(id), "held, and not rejected either: nothing refused it");
    assertEquals(0, executor.calls().size());
  }

  // -----------------------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a reported release\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private List<String> kinds(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.gates.kind");
  }

  private List<String> states(String id) {
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.gates.state");
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
