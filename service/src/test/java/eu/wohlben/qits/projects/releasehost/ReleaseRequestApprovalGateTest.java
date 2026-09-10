package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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
 * The <b>second</b> gate: a green build is no longer the whole of what releases a wrapper. Where
 * {@code ApprovalPolicy} says a person has to be asked — today the {@link
 * RepositoryArchetype#PROJECT} archetype — a request that has passed every build-gate arm waits for
 * a decision about its <em>current</em> fold, and releases, or is rejected, on what that decision
 * says.
 *
 * <p><b>Every case here is driven by inserting approval rows directly</b>, because the doors that
 * record a decision do not exist yet. The re-evaluation is then triggered the way the platform
 * already triggers one — a gating verdict arriving over the real bus listener for the request's
 * merged sha — which is exactly the path an approval door will take when it calls the gate after its
 * own write. Nothing here reaches a git host or qits-ci: the merger, the executor and the active-run
 * probe are the package's recording fakes.
 *
 * <p><b>Both archetypes are seeded, and the plain one is the control.</b> The rule this feature had
 * to keep is that a repository nobody has to ask about behaves byte for byte as it did, so the last
 * test releases one on a green verdict alone — with an approval row sitting in the table, which must
 * change nothing at all.
 */
@QuarkusTest
public class ReleaseRequestApprovalGateTest {

  /** The default of {@code qits.projects.release-requests.unattended-requesters}. */
  private static final String ROBOT = "qits-platform-maintenance";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  private String projectId;
  private String wrapperRepoId;
  private String plainRepoId;

  /** Every request this test opened, so the approval rows it inserted can be dropped again. */
  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    requestIds.clear();
    // A green build with nothing still in flight, so the build gate is out of the way in every test
    // here and what holds a request is only ever the approval gate.
    activeBuilds.answer(Optional.of(0));
    projectId = "approval-gate-project-" + UUID.randomUUID();
    wrapperRepoId = "approval-gate-wrapper-" + UUID.randomUUID();
    plainRepoId = "approval-gate-plain-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "approval-gate";
              project.slug = "approval-gate-" + UUID.randomUUID();
              project.persist();
              persistRepository(project, wrapperRepoId, RepositoryArchetype.PROJECT);
              persistRepository(project, plainRepoId, RepositoryArchetype.SERVICE);
            });
  }

  private static void persistRepository(
      Project project, String repoId, RepositoryArchetype archetype) {
    Repository repository = new Repository();
    repository.id = repoId;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = archetype;
    repository.persist();
  }

  /**
   * The discipline {@code ReleaseRequestFlowTest} states — no open request outlives its test,
   * because {@code sweep()} walks every open row there is — plus this class's own table: approval
   * rows have no foreign key to anything (that is the point of them, they outlive the request), so
   * nothing cascades them away and the test that made them deletes them.
   */
  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", wrapperRepoId);
              ReleasedTagPendingMerge.delete("repoId = ?1", plainRepoId);
              if (!requestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", requestIds);
              }
            });
  }

  // -----------------------------------------------------------------------------------------
  // The gate
  // -----------------------------------------------------------------------------------------

  /**
   * The waiting arm, which is the one that costs a release: CI vouched for the fold and the request
   * still does not move, because on a wrapper a green build is only the first of two answers. The
   * sentence names the fold, so the person being waited on can see which one they are deciding
   * about.
   */
  @Test
  public void aGreenBuildOnAWrapperWaitsForAPersonAndSaysSo() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    assertNotNull(merged, "the create folds the sources at once");

    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"), "green, and still not released");
    assertEquals(
        "Waiting for a person to approve " + merged.substring(0, 10),
        request.getString("detail"));
    assertEquals(true, request.getBoolean("approvalRequired"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"), "nobody has decided about this fold");
    assertNull(request.getString("approvedAt"));
    assertNull(request.getString("approvalNote"));
    assertEquals(0, executor.calls().size(), "and the door was never reached");
  }

  /** The yes: a decision about the fold that is current, and the request goes the way it always did. */
  @Test
  public void anApprovalAtTheCurrentFoldReleasesIt() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");
    assertEquals("PENDING", stateOf(wrapperRepoId, id));

    record(id, merged, ReleaseRequestApproval.Decision.APPROVED, "ada", "ship it");
    reEvaluate(wrapperRepoId, merged);
    awaitState(wrapperRepoId, id, "RELEASED");

    assertEquals(1, executor.calls().size());
    assertEquals(
        merged,
        executor.calls().get(0).expectedSha(),
        "and the door is pinned to the fold the person approved");

    var request = request(wrapperRepoId, id);
    assertEquals("APPROVED", request.getString("approvalState"));
    assertEquals("ada", request.getString("approvedBy"));
    assertNotNull(request.getString("approvedAt"));
    assertEquals("ship it", request.getString("approvalNote"));
  }

  /**
   * The no, and the part of it that is a decision rather than a mechanism: a decline is <b>not</b> an
   * unattended-gate ticket. That belt exists for a red build nobody is watching; a person saying no
   * is somebody watching, by definition, and filing them a bug about their own answer is noise on
   * the one repository where a human is already engaged. The requester here is the platform's bump
   * robot precisely so that a ticket <em>would</em> be filed if this went through the red-build arm.
   */
  @Test
  public void aDeclineRejectsWithThePersonsSentenceAndFilesNoTicket() {
    String id = create(wrapperRepoId, "work", ROBOT);
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    record(id, merged, ReleaseRequestApproval.Decision.DECLINED, "ada", "not before the freeze");
    reEvaluate(wrapperRepoId, merged);

    var request = request(wrapperRepoId, id);
    assertEquals("REJECTED", request.getString("state"));
    assertEquals("Declined by ada: not before the freeze", request.getString("detail"));
    assertEquals("DECLINED", request.getString("approvalState"));
    assertEquals("ada", request.getString("approvedBy"), "the fields carry whichever answer is current");
    assertEquals("not before the freeze", request.getString("approvalNote"));

    // The filing is synchronous with the gate's own transaction on this path, so an absent ticket
    // here is an absent ticket for good.
    assertNull(request.getString("gateTicketId"), "a person decided; nobody needs telling");
    assertEquals(List.of(), ticketsOnProject());
    assertEquals(0, executor.calls().size());
  }

  /** A decline with nothing said is the bare fact, not a sentence with an empty tail. */
  @Test
  public void aDeclineWithNoNoteIsJustTheName() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    record(id, merged, ReleaseRequestApproval.Decision.DECLINED, "ada", null);
    reEvaluate(wrapperRepoId, merged);

    assertEquals("Declined by ada", request(wrapperRepoId, id).getString("detail"));
  }

  /**
   * <b>The re-arm carries the invalidation, and it carries it for the approval gate too.</b> A push
   * to a participating branch re-folds the request onto a new merged sha, and every decision made
   * about the old one stops matching — with no column cleared and no path that had to remember to
   * clear one. Pushing a fix onto a declined request is the ordinary way to answer it, exactly as it
   * is for a red build.
   */
  @Test
  public void aNewFoldReArmsADeclinedRequestAndTheOldDecisionStopsCounting() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String firstFold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", firstFold, "");
    record(id, firstFold, ReleaseRequestApproval.Decision.DECLINED, "ada", "no");
    reEvaluate(wrapperRepoId, firstFold);
    assertEquals("REJECTED", stateOf(wrapperRepoId, id));

    headMoved(wrapperRepoId, "work");
    awaitState(wrapperRepoId, id, "PENDING");
    String secondFold = mergedShaOf(wrapperRepoId, id);
    assertNotEquals(firstFold, secondFold, "the push produced new content");

    // And the fresh fold is a fold nobody has looked at, however much history the request carries.
    verdict(wrapperRepoId, "BuildSuccessful", secondFold, "");
    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"), "the decline judged a fold this request has left");
    assertEquals(
        "Waiting for a person to approve " + secondFold.substring(0, 10),
        request.getString("detail"));
  }

  /**
   * The same rule from the other side, and the one that would be a security hole if it went the
   * other way: an approval names the fold it was made about, so it can never authorise different
   * content. A decision recorded against a sha this request is not on opens nothing.
   */
  @Test
  public void anApprovalOfASupersededFoldOpensNothing() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    record(
        id,
        "sha-somebody-else-" + UUID.randomUUID(),
        ReleaseRequestApproval.Decision.APPROVED,
        "ada",
        "approved, but not this");
    reEvaluate(wrapperRepoId, merged);

    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"));
    assertEquals(0, executor.calls().size());
  }

  /**
   * <b>The control.</b> A repository the policy does not ask about releases on its gates alone, and
   * the approval row sitting in the table for it changes nothing: {@code NOT_REQUIRED} is not
   * {@code APPROVED} and the decision fields stay empty, because nobody was asked.
   */
  @Test
  public void aRepositoryThatNeedsNoApprovalIsUntouched() {
    String id = create(plainRepoId, "work", "wohlben");
    String merged = mergedShaOf(plainRepoId, id);
    record(id, merged, ReleaseRequestApproval.Decision.DECLINED, "ada", "ignored");

    verdict(plainRepoId, "BuildSuccessful", merged, "");
    awaitState(plainRepoId, id, "RELEASED");

    var request = request(plainRepoId, id);
    assertEquals(false, request.getBoolean("approvalRequired"));
    assertEquals("NOT_REQUIRED", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"), "nobody was asked, so nobody answered");
    assertNull(request.getString("approvedAt"));
    assertNull(request.getString("approvalNote"));
    assertEquals(1, executor.calls().size());
  }

  /** The list read answers the same derived facts as the single read, and in the same words. */
  @Test
  public void theProjectListAnswersTheGateToo() {
    String wrapper = create(wrapperRepoId, "work", "wohlben");
    verdict(wrapperRepoId, "BuildSuccessful", mergedShaOf(wrapperRepoId, wrapper), "");
    String plain = create(plainRepoId, "work", "wohlben");

    var entries =
        given()
            .get("/projects/api/projects/" + projectId + "/release-requests")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        "WAITING",
        entries.getString("requests.find { it.id == '" + wrapper + "' }.approvalState"));
    assertEquals(
        "NOT_REQUIRED",
        entries.getString("requests.find { it.id == '" + plain + "' }.approvalState"));
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  private String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String repoId, String branch, String requester) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"branch\":\""
                    + branch
                    + "\",\"summary\":\"a gated release\",\"requester\":\""
                    + requester
                    + "\"}")
            .post(base(repoId))
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    return id;
  }

  private io.restassured.path.json.JsonPath request(String repoId, String id) {
    return given()
        .get(base(repoId) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private String stateOf(String repoId, String id) {
    return request(repoId, id).getString("state");
  }

  private String mergedShaOf(String repoId, String id) {
    return request(repoId, id).getString("mergedSha");
  }

  /** One decision, inserted straight into the table — the doors that will write these do not exist yet. */
  private void record(
      String requestId,
      String mergedSha,
      ReleaseRequestApproval.Decision decision,
      String actor,
      String note) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestApproval approval = new ReleaseRequestApproval();
              approval.id = UUID.randomUUID().toString();
              approval.requestId = requestId;
              approval.mergedSha = mergedSha;
              approval.decision = decision;
              approval.actor = actor;
              approval.note = note;
              approval.decidedAt = Instant.now();
              approval.persist();
            });
  }

  /**
   * Ask the gate again, which is what an approval door will do after its own write. A further gating
   * verdict for the same fold is the trigger the platform already has, and it settles the request in
   * the same consumption — so it stands in for the door until there is one.
   */
  private void reEvaluate(String repoId, String mergedSha) {
    verdict(repoId, "BuildSuccessful", mergedSha, "");
  }

  private void verdict(String repoId, String name, String sha, String extra) {
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

  /** A push to a participating branch: the re-fold, which is the re-arm. */
  private void headMoved(String repoId, String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + branch
                + "\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\"}",
            null,
            null,
            null));
  }

  private List<String> ticketsOnProject() {
    return given()
        .get("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("entries.ticket.id", String.class);
  }

  /** The execution runs on the request worker, so a terminal state is polled, never assumed. */
  private void awaitState(String repoId, String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(repoId, id);
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
