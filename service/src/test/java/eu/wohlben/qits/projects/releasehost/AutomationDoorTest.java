package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestAutomationWaiver;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.NoDevUserProfile;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The two automation doors</b> (epic qits-978): the waiver a person presses and the re-run the
 * release request page forwards to qits-maintenance. The gate behind them is {@link
 * AutomationGateTest}'s; this class is everything between the HTTP boundary and the gate.
 *
 * <p>It runs under {@link NoDevUserProfile} for {@code ReleaseRequestApprovalDoorTest}'s reason: the
 * {@code %test} dev user holds every role, so a door refusing {@code qits:agent} could not be
 * observed with a plain {@code given()}. Every call here states its caller.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
public class AutomationDoorTest {

  private static final String SCREENSHOTS = "screenshot-baselines";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject AutomationLedger ledger;

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
    automations.reset();
    requestIds.clear();
    activeBuilds.answer(Optional.of(0));
    String unique = UUID.randomUUID().toString();
    projectId = "automation-door-project-" + unique;
    repoId = "automation-door-repo-" + unique;
    repoName = "automation-door-" + unique.substring(0, 8);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "automation-door";
              project.slug = "automation-door-" + unique;
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
    // A red automation, so every request here is held by the gate the doors answer.
    automations.answer(repoName, SCREENSHOTS, "FAILED");
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
  }

  // -----------------------------------------------------------------------------------------
  // The waiver
  // -----------------------------------------------------------------------------------------

  /** A person waives the fold they are looking at, and the request releases on the press. */
  @Test
  public void aPersonsWaiverReleasesTheHeldFold() {
    String id = create();
    String merged = held(id);

    admin()
        .body("{\"foldSha\":\"" + merged + "\",\"reason\":\"qits-maintenance is down\"}")
        .post(base() + "/" + id + "/automations/waivers")
        .then()
        .statusCode(200)
        .body("request.id", equalTo(id));

    awaitState(id, "RELEASED");
    assertEquals(1, waivers(id).size());
    assertEquals("ada", waivers(id).get(0).actor, "the verified person's name");
  }

  /** A waiver is a sign-off: an agent — or asserted headers alone — may not give one. */
  @Test
  public void anAgentMayNotWaive() {
    String id = create();
    String merged = held(id);

    as("an-agent", "qits:agent")
        .body("{\"foldSha\":\"" + merged + "\",\"reason\":\"let me through\"}")
        .post(base() + "/" + id + "/automations/waivers")
        .then()
        .statusCode(403);
    as("mallory", "qits:admin")
        .body("{\"foldSha\":\"" + merged + "\",\"reason\":\"let me through\"}")
        .post(base() + "/" + id + "/automations/waivers")
        .then()
        .statusCode(403);

    assertEquals(List.of(), waivers(id));
    assertEquals("PENDING", stateOf(id));
  }

  /** A push landed while the page was open: the waiver names a fold the request has left. */
  @Test
  public void aWaiverOfAMovedFoldIsRefusedWithTheCurrentOne() {
    String id = create();
    String firstFold = held(id);
    headMoved();
    awaitFoldToMove(id, firstFold);
    String secondFold = mergedShaOf(id);

    admin()
        .body("{\"foldSha\":\"" + firstFold + "\",\"reason\":\"qits-maintenance is down\"}")
        .post(base() + "/" + id + "/automations/waivers")
        .then()
        .statusCode(409)
        .body("message", containsString(secondFold));

    assertEquals(List.of(), waivers(id), "and nothing was recorded");
  }

  // -----------------------------------------------------------------------------------------
  // The forwarded re-run
  // -----------------------------------------------------------------------------------------

  @Test
  public void theRerunIsForwardedAndAnswers202WithTheRunsId() {
    String id = create();
    held(id);

    String runId =
        as("an-agent", "qits:agent")
            .body("{}")
            .post(base() + "/" + id + "/automations/" + SCREENSHOTS + "/runs")
            .then()
            .statusCode(202)
            .extract()
            .path("id");

    assertTrue(runId.startsWith("bump-"), runId);
    List<FakeReleaseRequestAutomations.Rerun> asked = automations.reruns();
    assertEquals(1, asked.size());
    assertEquals(id, asked.get(0).requestId());
    assertEquals(SCREENSHOTS, asked.get(0).kind());
    assertEquals(repoName, asked.get(0).repositoryName(), "the repository travels with it");
  }

  /** The far side's refusal is the fact the person pressing has not got: status and sentence. */
  @Test
  public void aRerunRefusalPassesThrough() {
    String id = create();
    held(id);
    automations.refuseReruns(409, "screenshot-baselines is already running for this request");

    as("ada", "qits:admin")
        .body("{}")
        .post(base() + "/" + id + "/automations/" + SCREENSHOTS + "/runs")
        .then()
        .statusCode(409)
        .body("message", equalTo("screenshot-baselines is already running for this request"));
  }

  @Test
  public void aRerunWithNoMaintenanceIs503() {
    String id = create();
    automations.unconfigure();

    as("ada", "qits:admin")
        .body("{}")
        .post(base() + "/" + id + "/automations/" + SCREENSHOTS + "/runs")
        .then()
        .statusCode(503);
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  private RequestSpecification admin() {
    return as("ada", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada"));
  }

  private RequestSpecification as(String user, String roles) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", roles);
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create() {
    String id =
        as("ada", "qits:admin")
            .body("{\"branch\":\"work\",\"summary\":\"an automated release\"}")
            .post(base())
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    return id;
  }

  /** A green verdict, after which only the red automation holds the request. */
  private String held(String id) {
    String merged = mergedShaOf(id);
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\""
                + merged
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\"}",
            null,
            null,
            null));
    assertEquals("PENDING", stateOf(id));
    return merged;
  }

  private void headMoved() {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\"work\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\"}",
            null,
            null,
            null));
  }

  private String stateOf(String id) {
    return as("ada", "qits:admin").get(base() + "/" + id).then().statusCode(200).extract().path("request.state");
  }

  private String mergedShaOf(String id) {
    return as("ada", "qits:admin")
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .path("request.mergedSha");
  }

  private List<ReleaseRequestAutomationWaiver> waivers(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequestAutomationWaiver.<ReleaseRequestAutomationWaiver>list("requestId", id));
  }

  private void awaitFoldToMove(String id, String from) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      String now = mergedShaOf(id);
      if (now != null && !now.equals(from)) {
        return;
      }
      sleep();
    }
    fail("the fold never moved off " + from);
  }

  private void awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(id);
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
