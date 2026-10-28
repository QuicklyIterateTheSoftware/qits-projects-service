package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener;
import eu.wohlben.qits.projects.control.ReleaseRequestFailureNotices;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestFailureNotice;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentLookup;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1672: when a fold fails its build, every agent that filed the request or added a source to it
 * is told once, through the work item's delivery. Persons and machines are told nothing.
 */
@QuarkusTest
public class ReleaseRequestFailureNoticeTest {

  private static final String PERSON = "ab9c11c9-c970-4068-9016-fdba1978eb04";

  @Inject BuildStatusListener listener;

  @Inject ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingWorkspaceAgentLookup agents;

  @Inject RecordingWorkspaceAgentTurns turns;

  @Inject ReleaseRequestFailureNotices notices;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    agents.reset();
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "queued");
    repoId = "failure-notice-repo-" + UUID.randomUUID();
    projectId = "failure-notice-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "failure-notices";
              project.slug = "failure-notices-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  @AfterEach
  void dropTheFixture() {
    agents.reset();
    turns.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  @Test
  public void aRedRunTellsEachAgentBehindTheRequestOnceAndARetryAtTheSameFoldTellsNobody() {
    String agentA = UUID.randomUUID().toString();
    String agentB = UUID.randomUUID().toString();
    agents.stage(agentA, "work-a");
    agents.stage(agentB, "work-b");
    String id = create("ticket/a", subjectOf(agentA));
    join(id, "ticket/b", subjectOf(agentB));
    join(id, "ticket/person", PERSON);
    join(id, "maintenance/dependencies", "dev-qits-maintenance");
    String merged = mergedShaOf(id);
    String red = run();

    verdict("BuildFailed", merged, red, null);
    awaitState(id, "REJECTED");
    awaitTold(2);

    String message = ReleaseRequestFailureNotices.message(qualifiedIdOf(id));
    assertEquals(
        Set.of(
            new RecordingWorkspaceAgentTurns.Spoken("work-a", message),
            new RecordingWorkspaceAgentTurns.Spoken("work-b", message)),
        Set.copyOf(turns.calls()),
        "each agent hears it once; the person and qits-maintenance hear nothing");
    assertEquals("fyi: your " + qualifiedIdOf(id) + " failed its build", message);
    List<ReleaseRequestFailureNotice> recorded = recordedOf(id);
    assertEquals(2, recorded.size());
    for (ReleaseRequestFailureNotice notice : recorded) {
      assertEquals(merged, notice.mergedSha);
      assertEquals("DELIVERED", notice.outcome);
      assertNotNull(notice.toldAt, "the request records when it was told");
    }
    assertEquals(
        Set.of(agentA, agentB),
        recorded.stream().map(n -> n.agentId).collect(Collectors.toSet()));

    // The retry of the run fails the same fold again: nothing new is said.
    String retry = run();
    verdict("BuildFailed", merged, retry, red);
    awaitDetail(id, retry);
    notices.deliverOwed();
    assertEquals(2, turns.calls().size(), "the same fold is told once");
    assertEquals(2, recordedOf(id).size());
  }

  @Test
  public void aNewFoldThatFailsTellsTheAgentAgain() {
    String agent = UUID.randomUUID().toString();
    agents.stage(agent, "work-a");
    String id = create("ticket/a", subjectOf(agent));
    verdict("BuildFailed", mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitTold(1);

    headMoved("ticket/a");
    awaitState(id, "PENDING");
    verdict("BuildFailed", mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitTold(2);
    assertEquals(2, recordedOf(id).size(), "one record per fold");
  }

  @Test
  public void aPersonsRequestTellsNobodyAndAnAgentNotActiveIsSkipped() {
    String gone = UUID.randomUUID().toString();
    String id = create("ticket/person", PERSON);
    join(id, "ticket/gone", subjectOf(gone));
    verdict("BuildFailed", mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitSettled(id);

    assertEquals(List.of(), turns.calls(), "nobody is told");
    List<ReleaseRequestFailureNotice> recorded = recordedOf(id);
    assertEquals(1, recorded.size(), "only the agent is owed a notice");
    assertEquals("NOT_ACTIVE", recorded.get(0).outcome);
    assertNull(recorded.get(0).toldAt);
  }

  @Test
  public void anUnreachableDeliveryIsKeptOwedAndTheSweepDeliversIt() {
    String agent = UUID.randomUUID().toString();
    agents.stage(agent, "work-a");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.COULD_NOT, "unreachable");
    String id = create("ticket/a", subjectOf(agent));
    verdict("BuildFailed", mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitTold(1);
    awaitAttempted(id);
    assertNull(recordedOf(id).get(0).outcome, "still owed");

    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "queued");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleaseRequestFailureNotice.update(
                    "claimedAt = null where requestId = ?1", id));
    notices.deliverOwed();
    assertEquals(2, turns.calls().size());
    assertEquals("DELIVERED", recordedOf(id).get(0).outcome);
  }

  // ---- helpers ----------------------------------------------------------------------------------

  /** A workspace agent's token subject, as qits-idp mints it. */
  private static String subjectOf(String agentId) {
    return "tok-workspace-agent-" + agentId.substring(0, 18) + "-zpwOo-qhaQ_FX7nkWaYARQ";
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  /** A create by {@code requester} — the forwarded caller, which is what attribution reads. */
  private String create(String branch, String requester) {
    return given()
        .header("X-Qits-User", requester)
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"a release\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private void join(String id, String branch, String requester) {
    given()
        .header("X-Qits-User", requester)
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\"}")
        .post(base() + "/" + id + "/sources")
        .then()
        .statusCode(200);
  }

  private String mergedShaOf(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().path("request.mergedSha");
  }

  private String qualifiedIdOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).qualifiedId);
  }

  private List<ReleaseRequestFailureNotice> recordedOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequestFailureNotice.<ReleaseRequestFailureNotice>list("requestId", id)
                    .stream()
                    .sorted(Comparator.comparing(n -> n.owedAt))
                    .toList());
  }

  private void verdict(String name, String sha, String runId, String retryOfRunId) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"" + BackingBranches.atFold(repoId, sha) + "\",\"commitSha\":\"" + sha
                + "\",\"repoId\":\"" + repoId + "\""
                + (retryOfRunId == null ? "" : ",\"retryOfRunId\":\"" + retryOfRunId + "\"")
                + ",\"runId\":\"" + runId + "\",\"outcome\":\"FAILED\"}",
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
            "{\"branch\":\"" + branch + "\",\"repoId\":\"" + repoId + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "") + "\"}",
            null,
            null,
            null));
  }

  private void awaitState(String id, String expected) {
    await(
        () -> expected.equals(given().get(base() + "/" + id).then().extract().path("request.state")),
        "request " + id + " never reached " + expected);
  }

  private void awaitDetail(String id, String part) {
    await(
        () -> {
          String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
          String state = given().get(base() + "/" + id).then().extract().path("request.state");
          return "REJECTED".equals(state) && detail != null && detail.contains(part);
        },
        "request " + id + " was never rejected again");
  }

  private void awaitTold(int calls) {
    await(() -> turns.calls().size() >= calls, "never told " + calls + " times: " + turns.calls());
  }

  private void awaitSettled(String id) {
    await(
        () -> {
          List<ReleaseRequestFailureNotice> recorded = recordedOf(id);
          return !recorded.isEmpty() && recorded.stream().allMatch(n -> n.outcome != null);
        },
        "the notices of " + id + " never settled");
  }

  private void awaitAttempted(String id) {
    await(
        () -> recordedOf(id).stream().allMatch(n -> n.attempts > 0),
        "the notices of " + id + " were never tried");
  }

  private static void await(java.util.function.BooleanSupplier done, String failure) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (done.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail(failure);
  }

  private static String run() {
    return "run-red-" + UUID.randomUUID();
  }
}
