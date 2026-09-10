package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.security.NoDevUserProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
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
 * <b>The doors</b>: the two POSTs a person presses and the read that answers what has been said. The
 * gate itself is {@code ReleaseRequestApprovalGateTest}, which drives it by inserting rows because
 * these routes did not exist yet; this class is the same subject reached the way the product reaches
 * it, and what it pins is everything that lives between the HTTP boundary and the insert.
 *
 * <p><b>It runs under {@link NoDevUserProfile}, and that is what makes the role assertion possible
 * at all.</b> The {@code %test} dev user this platform ships holds every platform role, so inside an
 * ordinary {@code @QuarkusTest} a plain {@code given()} is already an administrator and a route
 * refusing {@code qits:system} could not be observed — the call would be admitted as admin whatever
 * headers it carried. With the fallback blanked, an identity is exactly what the {@code
 * X-Qits-User}/{@code X-Qits-Roles} pair says it is, which is the deployed posture; that is {@code
 * ForwardAuthTest}'s idiom and {@code ProjectRepositoryAdoptTest}'s use of it. Every call here
 * therefore states its caller, and the ones that do not state {@code qits:admin} are making a point.
 *
 * <p><b>The negative in {@link #aMachineMayAskAndWithdrawButMayNotSignOff} is the load-bearing test
 * of this feature.</b> A method-level {@code @RolesAllowed} replaces the class-level one rather than
 * adding to it, and this repository has shipped that defect in both directions before — so the same
 * machine session is driven at four routes in one test, and it has to be refused at exactly two of
 * them. Asserting only the 403s would pass just as well against a class that had lost {@code
 * qits:system} altogether, which would break the bump robot and every peer that opens a request.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
public class ReleaseRequestApprovalDoorTest {

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  private String projectId;
  private String wrapperRepoId;
  private String plainRepoId;

  /** Every request this test opened, so the approval rows behind it can be dropped again. */
  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    requestIds.clear();
    // Nothing in flight, so the build gate is out of the way and what holds a request here is only
    // ever the approval gate and the doors that answer it.
    activeBuilds.answer(Optional.of(0));
    projectId = "approval-door-project-" + UUID.randomUUID();
    wrapperRepoId = "approval-door-wrapper-" + UUID.randomUUID();
    plainRepoId = "approval-door-plain-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "approval-door";
              project.slug = "approval-door-" + UUID.randomUUID();
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
   * {@code ReleaseRequestFlowTest}'s discipline — no open request outlives its test, because {@code
   * sweep()} walks every open row there is — plus the approval table, which has no foreign key to
   * anything and is therefore cascaded away by nothing.
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
  // The verbs
  // -----------------------------------------------------------------------------------------

  /**
   * The whole point of the door: a green wrapper request that was going nowhere releases on the
   * press. The gate is re-asked inside the call — outside its write transaction, but before it
   * answers — so the release is under way by the time the browser has its response, rather than at
   * the sweep's next pass thirty seconds later.
   */
  @Test
  public void anApprovalReleasesAGreenWrapperRequest() {
    String id = create(admin(), wrapperRepoId, "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, merged);
    assertEquals("PENDING", stateOf(wrapperRepoId, id), "green, and waiting for a person");

    admin()
        .body("{\"mergedSha\":\"" + merged + "\",\"note\":\"read the diff, ship it\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalState", org.hamcrest.Matchers.equalTo("APPROVED"))
        .body("request.approvedBy", org.hamcrest.Matchers.equalTo("ada"))
        .body("request.approvalNote", org.hamcrest.Matchers.equalTo("read the diff, ship it"));

    awaitState(wrapperRepoId, id, "RELEASED");
    assertEquals(1, executor.calls().size());
    assertEquals(
        merged,
        executor.calls().get(0).expectedSha(),
        "and the release is pinned to the fold the person approved");
  }

  /**
   * <b>The refusal this parameter exists for, and the assertion that it costs nothing.</b> A page
   * left open while a source was pushed is showing a fold the request has left; approving from it
   * must not transfer the reader's yes to content they never saw. The 409 <b>names the current
   * sha</b> so the SPA can say what changed rather than reporting a conflict — and the table is
   * checked afterwards, because a door that refused and inserted anyway would leave a decision
   * behind that the next re-fold could not distinguish from one somebody made.
   */
  @Test
  public void aStaleShaIsRefusedWithTheCurrentOneAndWritesNothing() {
    String id = create(admin(), wrapperRepoId, "wohlben");
    String firstFold = mergedShaOf(wrapperRepoId, id);

    headMoved(wrapperRepoId, "work");
    awaitFoldToMove(wrapperRepoId, id, firstFold);
    String secondFold = mergedShaOf(wrapperRepoId, id);
    assertNotEquals(firstFold, secondFold, "the push produced new content");

    String message =
        admin()
            .body("{\"mergedSha\":\"" + firstFold + "\"}")
            .post(base(wrapperRepoId) + "/" + id + "/approve")
            .then()
            .statusCode(409)
            .extract()
            .path("message");
    assertTrue(
        message.contains(secondFold),
        "the refusal has to name the fold it is on now, or the SPA cannot say what changed: "
            + message);

    assertEquals(List.of(), approvals(admin(), wrapperRepoId, id), "and nothing was recorded");
  }

  /**
   * <b>Only a person may approve.</b> {@code qits:system} is the maintenance bump, qits-workspaces
   * opening requests on behalf of its callers and the train's scripts, and every one of them is
   * entitled to ask for a release and to say the ask is moot. None of them may sign off the estate:
   * a wrapper release moves the whole platform's version, and a gate a machine could satisfy is not
   * a gate. The create and the withdraw in the same test are what make this a statement about two
   * routes rather than about this caller — a class that had simply lost {@code qits:system} would
   * fail here and pass a test that only asserted the 403s.
   */
  @Test
  public void aMachineMayAskAndWithdrawButMayNotSignOff() {
    String id = create(machine(), wrapperRepoId, "qits-platform-maintenance");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, merged);

    machine()
        .body("{\"mergedSha\":\"" + merged + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403);
    machine()
        .body("{\"mergedSha\":\"" + merged + "\",\"note\":\"the robot disapproves\"}")
        .post(base(wrapperRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(403);

    // Neither refusal wrote anything, and the same session still holds the routes it always did.
    assertEquals(List.of(), approvals(admin(), wrapperRepoId, id));
    assertEquals("PENDING", stateOf(wrapperRepoId, id));
    machine()
        .body("{\"reason\":\"superseded by a later bump\"}")
        .post(base(wrapperRepoId) + "/" + id + "/withdraw")
        .then()
        .statusCode(200)
        .body("request.state", org.hamcrest.Matchers.equalTo("WITHDRAWN"));
  }

  /**
   * The no, through the door: REJECTED at once, carrying the decider's own words rather than a
   * machine's sentence about a run. It rejects <em>at once</em> because the door re-asks the gate
   * like the approval does — a decline that left the request PENDING until a sweep came round would
   * read as a press that did nothing.
   */
  @Test
  public void aDeclineRejectsWithTheActorsSentence() {
    String id = create(admin(), wrapperRepoId, "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, merged);

    admin()
        .body("{\"mergedSha\":\"" + merged + "\",\"note\":\"not before the freeze\"}")
        .post(base(wrapperRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(200)
        .body("request.state", org.hamcrest.Matchers.equalTo("REJECTED"))
        .body(
            "request.detail",
            org.hamcrest.Matchers.equalTo("Declined by ada: not before the freeze"))
        .body("request.approvalState", org.hamcrest.Matchers.equalTo("DECLINED"))
        .body("request.approvedBy", org.hamcrest.Matchers.equalTo("ada"));

    assertEquals(0, executor.calls().size(), "and the release door was never reached");
  }

  /**
   * <b>Approving what has no gate is a caller error, not a no-op.</b> A door that quietly recorded a
   * decision nobody would ever read is a door an operator believes they have used — and it is the
   * exact shape of a UI offering Approve on a repository that never needed one, which is a bug worth
   * hearing about rather than absorbing.
   */
  @Test
  public void aRepositoryWithNoApprovalGateRefusesTheDoorOutright() {
    String id = create(admin(), plainRepoId, "wohlben");
    String merged = mergedShaOf(plainRepoId, id);

    admin()
        .body("{\"mergedSha\":\"" + merged + "\"}")
        .post(base(plainRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(409)
        .body("message", org.hamcrest.Matchers.containsString("needs no approval"));

    assertEquals(List.of(), approvals(admin(), plainRepoId, id));
  }

  /**
   * <b>The trail, and why it is not the request's five fields.</b> Those answer the position at the
   * fold you are looking at, and they drop everything said about a fold the request has left — which
   * is most of what makes a wrapper release worth reading about. Here a decline is answered the
   * ordinary way (a push re-folds the request, the decline stops naming the fold it is on) and the
   * second fold is approved, and both decisions are in the answer with the sha each one judged.
   */
  @Test
  public void theTrailListsDecisionsAcrossFoldsNewestFirst() {
    String id = create(admin(), wrapperRepoId, "wohlben");
    String firstFold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, firstFold);
    admin()
        .body("{\"mergedSha\":\"" + firstFold + "\",\"note\":\"the migration is missing\"}")
        .post(base(wrapperRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(200);
    assertEquals("REJECTED", stateOf(wrapperRepoId, id));

    // The ordinary answer to a decline: push a fix, which re-folds and re-arms.
    headMoved(wrapperRepoId, "work");
    awaitFoldToMove(wrapperRepoId, id, firstFold);
    String secondFold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, secondFold);
    admin()
        .body("{\"mergedSha\":\"" + secondFold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200);
    awaitState(wrapperRepoId, id, "RELEASED");

    JsonPath trail =
        admin()
            .get(base(wrapperRepoId) + "/" + id + "/approvals")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(List.of("APPROVED", "DECLINED"), trail.getList("approvals.decision", String.class));
    assertEquals(
        List.of(secondFold, firstFold),
        trail.getList("approvals.mergedSha", String.class),
        "each entry says which fold it judged, and the superseded one is still here");
    assertEquals(List.of("ada", "ada"), trail.getList("approvals.actor", String.class));
    assertEquals(
        "the migration is missing",
        trail.getString("approvals[1].note"),
        "what was refused is the part of the trail worth keeping");
    assertNotNull(trail.getString("approvals[0].decidedAt"));
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  /** A browser session as the edge forwards it — the only caller these two doors admit. */
  private RequestSpecification admin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "ada")
        .header("X-Qits-Roles", "qits:admin");
  }

  /** A machine peer as its bearer presents it: entitled to ask for a release, never to sign one. */
  private RequestSpecification machine() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "qits-platform-maintenance")
        .header("X-Qits-Roles", "qits:system");
  }

  private String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(RequestSpecification as, String repoId, String requester) {
    String id =
        as.body(
                "{\"branch\":\"work\",\"summary\":\"a gated release\",\"requester\":\""
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

  private JsonPath request(String repoId, String id) {
    return admin()
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
    String merged = request(repoId, id).getString("mergedSha");
    assertNotNull(merged, "the create folds the sources at once");
    return merged;
  }

  private List<String> approvals(RequestSpecification as, String repoId, String id) {
    return as.get(base(repoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("approvals.decision", String.class);
  }

  /** A green gating verdict for one fold, over the real bus listener. */
  private void verdict(String repoId, String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\""
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

  /** The re-fold happens off the consuming thread, so the new sha is polled rather than assumed. */
  private void awaitFoldToMove(String repoId, String id, String from) {
    poll(
        repoId,
        id,
        () -> !from.equals(request(repoId, id).getString("mergedSha")),
        "the fold never moved off " + from);
  }

  /** The execution runs on the request worker, so a terminal state is polled, never assumed. */
  private void awaitState(String repoId, String id, String expected) {
    poll(
        repoId,
        id,
        () -> expected.equals(stateOf(repoId, id)),
        "request " + id + " never reached " + expected);
  }

  private void poll(String repoId, String id, java.util.function.BooleanSupplier done, String why) {
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
    fail(why + "; last seen state " + stateOf(repoId, id));
  }
}
