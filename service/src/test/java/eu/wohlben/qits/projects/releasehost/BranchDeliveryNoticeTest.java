package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.BranchDeliveryNotices;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import eu.wohlben.qits.projects.entity.BranchDeliveryNotice;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentLookup;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns.Spoken;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1722: a branch's owning agent — the ACTIVE agent of the request's project whose wrapper
 * branch is the branch or its {@code <wrapper>-} prefix — hears once when the branch fails its
 * build at a fold, and once when it moves to another request. Whoever filed the request or added the
 * source is not what is read: a person's branch, a branch nobody's wrapper covers and an agent no
 * longer ACTIVE hear nothing, and their notices settle {@code NO_AGENT}.
 *
 * <p>qits-1725: a conflict tells only the branches it names as a head, once per fold attempt, and a
 * missing source tells only the missing branch's owner.
 *
 * <p>qits-1751: a conflict or missing-source notice whose fold a push lifted before it was delivered
 * settles {@code LIFTED} and tells nobody.
 *
 * <p>The red verdicts are {@code ReleaseRequestFailureNoticeTest}'s (the qits-1672 test this
 * replaces), the hand-on is {@link BranchDeliveryTest}'s, which is {@link AutoReleaseTest}'s.
 */
@QuarkusTest
public class BranchDeliveryNoticeTest {

  private static final String PERSON = "ab9c11c9-c970-4068-9016-fdba1978eb04";

  @Inject BuildStatusListener listener;

  @Inject ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject eu.wohlben.qits.projects.deploymenthost.FakeDeploymentRequests deploymentRequests;

  @Inject RecordingWorkspaceAgentLookup agents;

  @Inject RecordingWorkspaceAgentTurns turns;

  @Inject BranchDeliveryNotices notices;

  private String projectId;
  private String otherProjectId;

  /** The repository the requests are made in. */
  private String repoId;

  /** The wrapper repository the project's agents work in: what places them in the project. */
  private String wrapperId;

  /** Another project's wrapper repository. */
  private String otherWrapperId;

  private String unique;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    automations.reset();
    deploymentRequests.reset();
    agents.reset();
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "queued");
    unique = UUID.randomUUID().toString().substring(0, 8);
    projectId = "notice-" + unique;
    otherProjectId = "notice-other-" + unique;
    repoId = "notice-repo-" + UUID.randomUUID();
    wrapperId = "notice-wrapper-" + UUID.randomUUID();
    otherWrapperId = "notice-other-wrapper-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = project(projectId);
              alias(project, repository(project, repoId), "notice-repo-" + unique);
              repository(project, wrapperId);
              repository(project(otherProjectId), otherWrapperId);
            });
  }

  private static Project project(String id) {
    Project project = new Project();
    project.id = id;
    project.name = id;
    project.slug = id;
    project.persist();
    return project;
  }

  private static Repository repository(Project project, String id) {
    Repository repository = new Repository();
    repository.id = id;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = RepositoryArchetype.SERVICE;
    repository.persist();
    return repository;
  }

  private static void alias(Project project, Repository repository, String name) {
    RepositoryName row = new RepositoryName();
    row.project = project;
    row.repository = repository;
    row.name = name;
    row.persist();
  }

  /** The sweeps walk the whole database, so nothing of this fixture may outlive the test. */
  @AfterEach
  void drop() {
    agents.reset();
    turns.reset();
    automations.reset();
    deploymentRequests.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
              BranchDelivery.delete("projectId = ?1", projectId);
              BranchDeliveryNotice.delete("projectId = ?1", projectId);
            });
  }

  // ---- FAILED ----------------------------------------------------------------------------------

  @Test
  public void aRedRunTellsEachBranchsOwnerOnceAndARetryAtTheSameFoldTellsNobody() {
    String a = ticket("a");
    String b = ticket("b");
    agents.stage(agent(), "work-a", wrapperId, a);
    agents.stage(agent(), "work-b", wrapperId, b);
    String id = create(a, PERSON);
    join(id, b, PERSON);
    join(id, ticket("person"), PERSON);
    join(id, "maintenance/dependencies", "dev-qits-maintenance");
    String merged = mergedShaOf(id);
    String red = run();

    verdict(merged, red, null);
    awaitState(id, "REJECTED");
    awaitSettled(id, 3);

    String qid = qualifiedIdOf(id);
    assertEquals(
        Set.of(
            new Spoken("work-a", "fyi: your " + a + " failed its build in " + qid),
            new Spoken("work-b", "fyi: your " + b + " failed its build in " + qid)),
        Set.copyOf(turns.calls()),
        "each branch's owner hears of its own branch once; the person's branch is told nobody");
    List<BranchDeliveryNotice> recorded = recordedOf(id);
    assertEquals(3, recorded.size(), "one notice per named branch; maintenance/* is not followed");
    for (BranchDeliveryNotice notice : recorded) {
      assertEquals("FAILED", notice.step);
      assertEquals(merged, notice.foldSha);
      assertEquals(BranchDeliveryNotices.failedKey(id, merged, repoId, notice.branch), notice.noticeKey);
    }
    BranchDeliveryNotice told = byBranch(recorded, a);
    assertEquals("DELIVERED", told.outcome);
    assertEquals("work-a", told.workId);
    assertNotNull(told.agentId, "the agent the branch resolved to is recorded at delivery");
    assertNotNull(told.deliveredAt);
    BranchDeliveryNotice nobody = byBranch(recorded, ticket("person"));
    assertEquals("NO_AGENT", nobody.outcome);
    assertNull(nobody.agentId);
    assertNull(nobody.deliveredAt);

    // The retry of the run fails the same fold again: nothing new is owed or said.
    String retry = run();
    verdict(merged, retry, red);
    awaitDetail(id, retry);
    notices.deliverOwed();
    assertEquals(2, turns.calls().size(), "the same fold is told once");
    assertEquals(3, recordedOf(id).size());
  }

  @Test
  public void aNewFoldThatFailsTellsTheOwnerAgain() {
    String a = ticket("a");
    agents.stage(agent(), "work-a", wrapperId, a);
    String id = create(a, PERSON);
    verdict(mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitTold(1);

    headMoved(a);
    awaitState(id, "PENDING");
    verdict(mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitTold(2);
    assertEquals(2, recordedOf(id).size(), "one notice per fold");
  }

  /**
   * Only an ACTIVE agent of the request's project is told: a REMOVED agent is not listed, and an
   * ACTIVE agent of another project on the same wrapper branch does not own this one.
   */
  @Test
  public void aRemovedAgentAndAnotherProjectsAgentAreSkipped() {
    String gone = ticket("gone");
    String removed = agent();
    agents.stage(removed, "work-removed", wrapperId, gone);
    agents.unstage(removed);
    agents.stage(agent(), "work-elsewhere", otherWrapperId, gone);
    String id = create(gone, PERSON);
    verdict(mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitSettled(id, 1);

    assertEquals(List.of(), turns.calls(), "nobody is told");
    BranchDeliveryNotice notice = recordedOf(id).get(0);
    assertEquals("NO_AGENT", notice.outcome);
    assertNull(notice.agentId);
  }

  @Test
  public void anUnreachableDeliveryIsKeptOwedAndTheSweepDeliversIt() {
    String a = ticket("a");
    agents.stage(agent(), "work-a", wrapperId, a);
    turns.willAnswer(WorkspaceAgentTurns.Outcome.COULD_NOT, "unreachable");
    String id = create(a, PERSON);
    verdict(mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitTold(1);
    awaitAttempted(id);
    assertNull(recordedOf(id).get(0).outcome, "still owed");

    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "queued");
    QuarkusTransaction.requiringNew()
        .run(() -> BranchDeliveryNotice.update("claimedAt = null where requestId = ?1", id));
    notices.deliverOwed();
    assertEquals(2, turns.calls().size());
    assertEquals("DELIVERED", recordedOf(id).get(0).outcome);
  }

  // ---- CONFLICTED and FAILED FOLD (qits-1725) -------------------------------------------------

  /**
   * Only the branch a conflict names as its head is told, once per attempt; the same conflict hit
   * again says nothing, and a changed one (a new tip of the head) is a new attempt and is said
   * again.
   */
  @Test
  public void aConflictTellsOnlyItsHeadOncePerAttemptAndAChangedConflictAgain() {
    String a = ticket("a");
    String b = ticket("b");
    agents.stage(agent(), "work-a", wrapperId, a);
    agents.stage(agent(), "work-b", wrapperId, b);
    String id = create(a, PERSON);
    join(id, b, PERSON);
    String qid = qualifiedIdOf(id);

    merger.answer(conflict(id, "refs/heads/" + a, "abc1234", "pom.xml"));
    headMoved(a);
    awaitState(id, "CONFLICTED");
    awaitSettled(id, 1);
    String text = "fyi: your " + a + " conflicts in " + qid + " on pom.xml";
    assertEquals(List.of(new Spoken("work-a", text)), turns.calls(), "b is no head: not told");
    BranchDeliveryNotice notice = recordedOf(id).get(0);
    assertEquals("CONFLICTED", notice.step);
    String attempt = ledgerRow(a).foldAttempt;
    assertEquals(BranchDeliveryNotices.conflictedKey(id, attempt, repoId, a), notice.noticeKey);
    assertEquals("CONFLICTED", ledgerRow(b).step, "every source still takes the step");

    headMoved(b); // the same conflict again: the same attempt
    notices.deliverOwed();
    assertEquals(1, turns.calls().size(), "the same conflict is told once");

    merger.answer(conflict(id, "refs/heads/" + a, "def5678", "pom.xml"));
    headMoved(a);
    awaitSettled(id, 2);
    assertEquals(
        List.of(new Spoken("work-a", text), new Spoken("work-a", text)),
        turns.calls(),
        "a changed conflict is a new attempt, told again");
  }

  /** A conflict whose only head is {@code main} names no source branch, and tells nobody. */
  @Test
  public void aConflictWhoseOnlyHeadIsMainTellsNobody() {
    String a = ticket("a");
    agents.stage(agent(), "work-a", wrapperId, a);
    String id = create(a, PERSON);
    merger.answer(conflict(id, "refs/heads/main", "abc1234", "pom.xml"));

    headMoved(a);
    awaitState(id, "CONFLICTED");

    assertEquals("CONFLICTED", ledgerRow(a).step);
    notices.deliverOwed();
    assertEquals(List.of(), recordedOf(id), "nothing owed");
    assertEquals(List.of(), turns.calls());
  }

  /**
   * A head spelled bare names the branch as much as {@code refs/heads/} does; its distinct paths
   * are listed in conflict order, five at most, then {@code , and <n> more}.
   */
  @Test
  public void aBareHeadIsToldItsPathsCappedAtFive() {
    String a = ticket("a");
    agents.stage(agent(), "work-a", wrapperId, a);
    String id = create(a, PERSON);
    String qid = qualifiedIdOf(id);
    List<BackingBranchMerger.Conflict> conflicts =
        List.of("p1", "p2", "p2", "p3", "p4", "p5", "p6", "p7").stream()
            .map(path -> new BackingBranchMerger.Conflict(path, a, "abc1234", "content"))
            .toList();
    merger.answer(BackingBranchMerger.Outcome.conflict(BackingBranches.ref(id), conflicts));

    headMoved(a);
    awaitState(id, "CONFLICTED");
    awaitSettled(id, 1);

    assertEquals(
        List.of(
            new Spoken(
                "work-a",
                "fyi: your " + a + " conflicts in " + qid + " on p1, p2, p3, p4, p5, and 2 more")),
        turns.calls());
  }

  /** A missing source tells the missing branch's owner; the request's other sources do not hear. */
  @Test
  public void aMissingSourceTellsOnlyTheMissingBranchsOwner() {
    String a = ticket("a");
    String b = ticket("b");
    agents.stage(agent(), "work-a", wrapperId, a);
    agents.stage(agent(), "work-b", wrapperId, b);
    String id = create(a, PERSON);
    join(id, b, PERSON);
    String qid = qualifiedIdOf(id);
    merger.answer(BackingBranchMerger.Outcome.missingSource("refs/heads/" + b));

    headMoved(a);
    awaitState(id, "REJECTED");
    awaitSettled(id, 1);

    assertEquals(
        List.of(
            new Spoken(
                "work-b",
                "fyi: your " + b + " is missing on the git host; " + qid + " cannot fold")),
        turns.calls());
    BranchDeliveryNotice notice = recordedOf(id).get(0);
    assertEquals("FAILED", notice.step);
    assertEquals(
        BranchDeliveryNotices.missingKey(id, "missing:refs/heads/" + b, repoId, b),
        notice.noticeKey);
    assertEquals("FAILED", ledgerRow(a).step, "the other source still takes the step");

    headMoved(a); // still missing: the same attempt
    notices.deliverOwed();
    assertEquals(1, turns.calls().size());
  }

  /**
   * qits-1751: a missing-source notice owed, then the missing branch is pushed and the rejection
   * lifted before the notice could be delivered — it settles LIFTED and nobody is told.
   */
  @Test
  public void aMissingSourceNoticeWhoseRejectionAPushLiftedIsNotSaid() {
    String a = ticket("a");
    String b = ticket("b");
    agents.stage(agent(), "work-a", wrapperId, a);
    agents.stage(agent(), "work-b", wrapperId, b);
    String id = create(a, PERSON);
    join(id, b, PERSON);
    agents.willThrow(new IllegalStateException("qits-workspaces is unreachable"));
    merger.answer(BackingBranchMerger.Outcome.missingSource("refs/heads/" + b));

    headMoved(a);
    awaitState(id, "REJECTED");
    awaitOwedAndTried(id);

    merger.answerFreshMerges();
    headMoved(b); // the push lands: the fold succeeds and the rejection is lifted
    awaitState(id, "PENDING");
    agents.willThrow(null);
    releaseClaims(id);
    notices.deliverOwed();

    BranchDeliveryNotice notice = recordedOf(id).get(0);
    assertEquals(
        BranchDeliveryNotices.missingKey(id, "missing:refs/heads/" + b, repoId, b),
        notice.noticeKey);
    assertEquals("LIFTED", notice.outcome);
    assertNull(notice.agentId);
    assertNull(notice.deliveredAt);
    assertEquals(List.of(), turns.calls(), "nobody is told of a failure a push already answered");
  }

  /** qits-1751: the same for a conflict notice whose conflict was pushed away before delivery. */
  @Test
  public void aConflictNoticeWhoseConflictClearedIsNotSaid() {
    String a = ticket("a");
    agents.stage(agent(), "work-a", wrapperId, a);
    String id = create(a, PERSON);
    agents.willThrow(new IllegalStateException("qits-workspaces is unreachable"));
    merger.answer(conflict(id, "refs/heads/" + a, "abc1234", "pom.xml"));

    headMoved(a);
    awaitState(id, "CONFLICTED");
    awaitOwedAndTried(id);

    merger.answerFreshMerges();
    headMoved(a); // a new tip that folds: the conflict is gone
    awaitState(id, "PENDING");
    agents.willThrow(null);
    releaseClaims(id);
    notices.deliverOwed();

    BranchDeliveryNotice notice = recordedOf(id).get(0);
    assertEquals("CONFLICTED", notice.step);
    assertEquals("LIFTED", notice.outcome);
    assertEquals(List.of(), turns.calls());
  }

  // ---- addressing ------------------------------------------------------------------------------

  /**
   * The git_refs rule: {@code <wrapper>} and {@code <wrapper>-*}, never a bare prefix, and the
   * longest wrapper branch wins where two agents' rules both cover the branch.
   */
  @Test
  public void aBranchIsOwnedByTheLongestWrapperItExtendsWithADash() {
    String epic = "epic/" + projectId + "-1";
    String ticket = epic + "-t";
    agents.stage(agent(), "work-epic", wrapperId, epic);
    agents.stage(agent(), "work-ticket", wrapperId, ticket);
    String ticketsOwn = ticket + "-fix";
    String epicsOwn = epic + "-other";
    String nobodys = epic + "0-x";
    String id = create(ticketsOwn, PERSON);
    join(id, epicsOwn, PERSON);
    join(id, nobodys, PERSON);
    verdict(mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitSettled(id, 3);

    String qid = qualifiedIdOf(id);
    assertEquals(
        Set.of(
            new Spoken("work-ticket", "fyi: your " + ticketsOwn + " failed its build in " + qid),
            new Spoken("work-epic", "fyi: your " + epicsOwn + " failed its build in " + qid)),
        Set.copyOf(turns.calls()));
    assertEquals("NO_AGENT", byBranch(recordedOf(id), nobodys).outcome, "a bare prefix is no claim");
  }

  /**
   * An agent that adds a branch it does not own to a request hears nothing of that branch's
   * failure; the branch's owner does, though it never touched the request.
   */
  @Test
  public void aSourceAddedByAnotherAgentIsToldToItsOwnerNotToTheAdder() {
    String adder = agent();
    String owner = agent();
    String adders = ticket("adder");
    String owned = ticket("owned");
    agents.stage(adder, "work-adder", wrapperId, adders);
    agents.stage(owner, "work-owner", wrapperId, owned);
    String id = create(adders, subjectOf(adder));
    join(id, owned + "-fix", subjectOf(adder));
    verdict(mergedShaOf(id), run(), null);
    awaitState(id, "REJECTED");
    awaitSettled(id, 2);

    String qid = qualifiedIdOf(id);
    assertEquals(
        Set.of(
            new Spoken("work-adder", "fyi: your " + adders + " failed its build in " + qid),
            new Spoken("work-owner", "fyi: your " + owned + "-fix failed its build in " + qid)),
        Set.copyOf(turns.calls()),
        "the adder hears of its own branch only");
  }

  // ---- MOVED -----------------------------------------------------------------------------------

  /**
   * {@link BranchDeliveryTest}'s hand-on: the branch that joined while the tag was cut is handed on
   * to the next request, and its owner hears that it moved, once. The branch the tag carried is
   * RELEASED, which is no notice.
   */
  @Test
  public void aHandedOnBranchTellsItsOwnerItMoved() throws InterruptedException {
    String carried = ticket("carried");
    String late = ticket("late");
    agents.stage(agent(), "work-carried", wrapperId, carried);
    agents.stage(agent(), "work-late", wrapperId, late);
    activeBuilds.answer(Optional.of(0));
    stageRelease();
    RecordingReleaseExecutor.Hold held = executor.holdNextRelease();
    String id = create(carried, PERSON);
    String qid = qualifiedIdOf(id);
    verdict("BuildSuccessful", mergedShaOf(id), run(), null);
    assertTrue(held.awaitEntered(), "the worker reached the executor");

    assertEquals(id, create(late, PERSON), "the late branch joins the request being released");
    held.release();
    awaitState(id, "RELEASED");
    await(() -> !turns.calls().isEmpty(), "the late branch's owner was never told: " + turns.calls());

    BranchDelivery moved = ledgerRow(late);
    assertEquals("MOVED", moved.step);
    assertNotEquals(id, moved.requestId);
    assertEquals(
        List.of(
            new Spoken(
                "work-late", "fyi: your " + late + " moved from " + qid + " to " + moved.qualifiedId)),
        turns.calls(),
        "the move is told once, to the late branch's owner; a release is no notice");
    BranchDeliveryNotice notice = recordedOf(moved.requestId).get(0);
    assertEquals(BranchDeliveryNotices.movedKey(repoId, late, id, moved.requestId), notice.noticeKey);
    assertEquals("MOVED", notice.step);

    notices.deliverOwed();
    assertEquals(1, turns.calls().size(), "a settled move is not said again");
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private String ticket(String what) {
    return "ticket/" + projectId + "-" + what;
  }

  private static String agent() {
    return UUID.randomUUID().toString();
  }

  /** A workspace agent's token subject, as qits-idp mints it. */
  private static String subjectOf(String agentId) {
    return "tok-workspace-agent-" + agentId.substring(0, 18) + "-zpwOo-qhaQ_FX7nkWaYARQ";
  }

  private static BranchDeliveryNotice byBranch(List<BranchDeliveryNotice> notices, String branch) {
    return notices.stream()
        .filter(n -> n.branch.equals(branch))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no notice for " + branch + ": " + notices));
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

  private List<BranchDeliveryNotice> recordedOf(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                BranchDeliveryNotice.<BranchDeliveryNotice>list("requestId", requestId).stream()
                    .sorted(Comparator.comparing((BranchDeliveryNotice n) -> n.createdAt))
                    .toList());
  }

  private BranchDelivery ledgerRow(String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                BranchDelivery.<BranchDelivery>find(
                        "projectId = ?1 and repoId = ?2 and branch = ?3", projectId, repoId, branch)
                    .firstResult());
  }

  /** The next release's version, whose tree declares a deployment and no release pipeline. */
  private String stageRelease() {
    String tag = "2026.1028." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of("pom.xml", "irrelevant", ".config/qits/deployments.yml", "irrelevant"));
    return tag;
  }

  private void verdict(String sha, String runId, String retryOfRunId) {
    verdict("BuildFailed", sha, runId, retryOfRunId);
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
                + ",\"runId\":\"" + runId + "\""
                + ("BuildFailed".equals(name) ? ",\"outcome\":\"FAILED\"" : "")
                + "}",
            null,
            null,
            null));
  }

  /** A conflict of the request's fold naming {@code head} at {@code headSha} on {@code path}. */
  private static BackingBranchMerger.Outcome conflict(
      String id, String head, String headSha, String path) {
    return BackingBranchMerger.Outcome.conflict(
        BackingBranches.ref(id),
        List.of(new BackingBranchMerger.Conflict(path, head, headSha, "content")));
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

  private void awaitSettled(String id, int count) {
    await(
        () -> {
          List<BranchDeliveryNotice> recorded = recordedOf(id);
          return recorded.size() == count && recorded.stream().allMatch(n -> n.outcome != null);
        },
        "the notices of " + id + " never settled: " + recordedOf(id));
  }

  /** At least one notice owed, every one tried once and still owed (the lookup failed). */
  private void awaitOwedAndTried(String id) {
    await(
        () -> {
          List<BranchDeliveryNotice> recorded = recordedOf(id);
          return !recorded.isEmpty()
              && recorded.stream().allMatch(n -> n.attempts > 0 && n.outcome == null);
        },
        "the notices of " + id + " were never tried: " + recordedOf(id));
  }

  /** Frees every claim on the request's notices, so the next sweep may take them at once. */
  private void releaseClaims(String id) {
    QuarkusTransaction.requiringNew()
        .run(() -> BranchDeliveryNotice.update("claimedAt = null where requestId = ?1", id));
  }

  private void awaitAttempted(String id) {
    await(
        () -> recordedOf(id).stream().allMatch(n -> n.attempts > 0),
        "the notices of " + id + " were never tried");
  }

  private static void await(BooleanSupplier done, String failure) {
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
