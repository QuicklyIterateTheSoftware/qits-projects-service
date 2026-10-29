package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.ReleaseRequestSourceMoveAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceMoveDto;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import eu.wohlben.qits.projects.entity.BranchDeliveryNotice;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentLookup;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns.Spoken;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * <b>Moving one source to the next request</b> (qits-1665): a source that alone holds a request
 * back leaves it for the request queued after it — the existing queue, or one minted queued behind
 * it — keeping its priority, author and feature stamp as an ordinary member; the request it left
 * refolds without it and goes back to PENDING; the branch's ledger takes MOVED and its owner is told;
 * the move is recorded on both requests and announced after its commit; and the queue folds the
 * branch once the request it waited behind has ended. Every refusal answers its own status.
 *
 * <p>{@link ReleaseFeatureFreezeTest}'s fixture: counted branches name real ticket rows, and the CI
 * gate is held open ({@link FakeActiveBuilds} answering "a run is still active") so nothing settles
 * mid-test.
 */
@QuarkusTest
public class ReleaseRequestSourceMoveTest {

  @Inject ReleaseRequests releaseRequests;

  @Inject WorkEntityService entities;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  @Inject RecordingReleaseRequestSourceMoveAnnouncer moves;

  @Inject RecordingBranchDeliveryAnnouncer deliveries;

  @Inject RecordingWorkspaceAgentLookup agents;

  @Inject RecordingWorkspaceAgentTurns turns;

  private String repoId;
  private String wrapperId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    announcer.reset();
    moves.reset();
    deliveries.reset();
    agents.reset();
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "queued");
    activeBuilds.answer(Optional.of(1));
    projectId = "move-" + UUID.randomUUID().toString().substring(0, 8);
    repoId = "move-repo-" + UUID.randomUUID();
    wrapperId = "move-wrapper-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
              repository(project, repoId);
              // The repository the project's agents work in: what places them in the project.
              repository(project, wrapperId);
            });
  }

  private static void repository(Project project, String id) {
    Repository repository = new Repository();
    repository.id = id;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = RepositoryArchetype.SERVICE;
    repository.persist();
  }

  /** The sweeps walk the whole database, so nothing of this fixture may outlive the test. */
  @AfterEach
  void drop() {
    agents.reset();
    turns.reset();
    merger.answerFreshMerges();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
              BranchDelivery.delete("projectId = ?1", projectId);
              BranchDeliveryNotice.delete("projectId = ?1", projectId);
            });
  }

  // -------------------------------------------------------------------------------------------
  // Where the source lands
  // -------------------------------------------------------------------------------------------

  @Test
  void aMoveLandsOnTheExistingQueueAndKeepsTheSourcesOwnFields() {
    String first = feature();
    String active = ask(first, "alice").request().id();
    String moved = feature();
    releaseRequests.ask(repoId, moved, "bob", "HIGH");
    ask(feature(), "alice");
    String queued = ask(feature(), "alice").request().id();
    assertEquals(active, releaseRequests.get(queued).queuedBehind(), "the premise: a queue");
    ReleaseRequestSource before = sourceRow(active, moved);

    ReleaseRequests.Moved answer =
        releaseRequests.moveSource(active, moved, "it conflicts with " + first, "carol");

    assertEquals(active, answer.from().id());
    assertEquals(queued, answer.to().id(), "the existing queue takes it");
    assertFalse(names(answer.from()).contains(moved), "it left the request");
    assertTrue(names(answer.to()).contains(moved), "and is on the queue");
    assertEquals("main", names(answer.to()).get(0), "main still folds first");
    ReleaseRequestSourceDto carried = source(answer.to(), moved);
    assertEquals("HIGH", carried.priority(), "its priority is kept");
    assertEquals("bob", carried.addedBy(), "and its author, not the mover");
    assertTrue(carried.feature(), "and its feature stamp");
    assertFalse(carried.explicitJoin(), "an ordinary member of the queue");
    assertEquals(2, answer.to().featureFreeze().features(), "it counts toward the queue's freeze");
    assertEquals(2, answer.from().featureFreeze().features());
    ReleaseRequestSource after = sourceRow(queued, moved);
    assertEquals(
        before.addedAt.truncatedTo(ChronoUnit.MILLIS),
        after.addedAt.truncatedTo(ChronoUnit.MILLIS),
        "when it was added is kept");
    assertEquals(before.causationId, after.causationId, "and the causation it was added under");
    assertEquals(1, queuedRequestsOf(), "no second queue");
    assertTrue(
        releaseRequests.get(queued).participants().contains("bob"),
        "its author takes part in the queue");
    assertFalse(
        releaseRequests.get(queued).participants().contains("carol"), "the mover does not");
  }

  @Test
  void aMoveMintsAQueueBehindTheRequestWhenThereIsNone() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    ask(moved, "bob");
    String activeQid = releaseRequests.get(active).qualifiedId();

    ReleaseRequests.Moved answer = releaseRequests.moveSource(active, moved, "not ready", "carol");

    ReleaseRequestDto queue = answer.to();
    assertNotEquals(active, queue.id(), "a queue was minted");
    assertEquals(active, queue.queuedBehind());
    assertEquals(activeQid, queue.queuedBehindQualifiedId());
    assertEquals("PENDING", queue.state());
    assertEquals(ReleaseRequests.queuedDetail(activeQid), queue.detail());
    assertNull(queue.mergedSha(), "a queued request is not folded");
    assertEquals(List.of("main", moved), names(queue), "main and the moved branch");
    assertEquals("bob", source(queue, moved).addedBy());
    assertEquals(queue.id(), answer.from().queuedNext(), "the request names its queue");
    assertTrue(
        merger.foldsOf("refs/heads/" + queue.backingBranch()).isEmpty(),
        "the queue folds nothing");
    assertTrue(announcer.announcedFor(queue.id()).isEmpty(), "and announces nothing");
    assertEquals(1, queuedRequestsOf());
  }

  @Test
  void bothRequestsListTheMove() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    ask(moved, "bob");

    ReleaseRequests.Moved answer =
        releaseRequests.moveSource(active, moved, "  its check is red  ", "carol");

    String from = answer.from().qualifiedId();
    String to = answer.to().qualifiedId();
    for (ReleaseRequestDto read :
        List.of(
            answer.from(),
            answer.to(),
            releaseRequests.get(active),
            releaseRequests.listByRepo(repoId, null).stream()
                .filter(row -> row.id().equals(active))
                .findFirst()
                .orElseThrow(),
            releaseRequests.listByProject(projectId, null).stream()
                .filter(row -> row.id().equals(answer.to().id()))
                .findFirst()
                .orElseThrow())) {
      assertEquals(1, read.sourceMoves().size(), read.qualifiedId());
      ReleaseRequestSourceMoveDto move = read.sourceMoves().get(0);
      assertEquals(moved, move.branch());
      assertEquals(from, move.fromQualifiedId());
      assertEquals(to, move.toQualifiedId());
      assertEquals("carol", move.movedBy());
      assertEquals("its check is red", move.reason(), "the reason, trimmed");
      assertNotNull(move.movedAt());
    }
  }

  // -------------------------------------------------------------------------------------------
  // What follows the move
  // -------------------------------------------------------------------------------------------

  @Test
  void theRequestItLeftRefoldsWithoutItAndGoesBackToPending() {
    String a = feature();
    String id = ask(a, "alice").request().id();
    String b = feature();
    ask(b, "bob");
    merger.answer(
        BackingBranchMerger.Outcome.conflict(
            BackingBranches.ref(id),
            List.of(
                new BackingBranchMerger.Conflict("pom.xml", "refs/heads/" + b, "abc1234", "content"))));
    releaseRequests.onBranchMoved(repoId, b, RecordingBackingBranchMerger.freshSha());
    assertEquals("CONFLICTED", releaseRequests.get(id).state(), "the premise: b conflicts");
    String before = releaseRequests.get(id).mergedSha();
    String backing = "refs/heads/" + releaseRequests.get(id).backingBranch();
    merger.answerFreshMerges();
    announcer.reset();

    ReleaseRequests.Moved answer = releaseRequests.moveSource(id, b, "it conflicts", "carol");

    assertEquals("PENDING", answer.from().state(), "the refold re-armed it");
    assertNotEquals(before, answer.from().mergedSha(), "onto a new fold");
    RecordingBackingBranchMerger.Fold last =
        merger.foldsOf(backing).get(merger.foldsOf(backing).size() - 1);
    assertFalse(last.sources().contains("refs/heads/" + b), "without the moved branch: " + last);
    assertTrue(last.sources().contains("refs/heads/" + a), "and with the rest: " + last);
    assertFalse(announcer.announcedFor(id).isEmpty(), "the new fold is announced");
  }

  @Test
  void theLedgerTakesMovedAndTheBranchsOwnerIsTold() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    agents.stage(UUID.randomUUID().toString(), "work-moved", wrapperId, moved);
    ask(moved, "bob");
    String from = releaseRequests.get(active).qualifiedId();

    ReleaseRequests.Moved answer = releaseRequests.moveSource(active, moved, "not ready", "carol");

    String to = answer.to().qualifiedId();
    BranchDelivery row = ledgerRow(moved);
    assertEquals("MOVED", row.step);
    assertEquals(answer.to().id(), row.requestId);
    assertTrue(
        deliveries.movesOf(moved).contains("MOVED@" + to),
        "BranchDeliveryChanged MOVED is announced: " + deliveries.movesOf(moved));
    var step =
        deliveries.stepsOf(moved).stream()
            .filter(announced -> "MOVED".equals(announced.step()))
            .findFirst()
            .orElseThrow();
    assertEquals(active, step.previousRequestId());
    assertEquals(from, step.previousQualifiedId());
    await(() -> !turns.calls().isEmpty(), "the owner was never told");
    assertEquals(
        List.of(new Spoken("work-moved", "fyi: your " + moved + " moved from " + from + " to " + to)),
        turns.calls());
  }

  @Test
  void theMoveIsAnnouncedAfterItsCommit() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    ask(moved, "bob");

    ReleaseRequests.Moved answer = releaseRequests.moveSource(active, moved, "not ready", "carol");

    List<RecordingReleaseRequestSourceMoveAnnouncer.Recorded> recorded = moves.recorded();
    assertEquals(1, recorded.size());
    assertTrue(recorded.get(0).committed(), "the source was on its target when announced");
    ReleaseRequestSourceMoveAnnouncer.SourceMove move = recorded.get(0).move();
    assertEquals(projectId, move.projectId());
    assertEquals(repoId, move.repoId());
    assertEquals(moved, move.branch());
    assertEquals(active, move.fromRequestId());
    assertEquals(answer.from().qualifiedId(), move.fromQualifiedId());
    assertEquals(answer.to().id(), move.toRequestId());
    assertEquals(answer.to().qualifiedId(), move.toQualifiedId());
    assertTrue(move.minted());
    assertEquals("carol", move.movedBy());
    assertEquals("not ready", move.reason());
  }

  @Test
  void theQueueFoldsTheMovedBranchOnceTheRequestItWaitedBehindEnds() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    ask(moved, "bob");
    String queued = releaseRequests.moveSource(active, moved, "not ready", "carol").to().id();

    releaseRequests.withdraw(active, "not this release", "alice");

    ReleaseRequestDto read = releaseRequests.get(queued);
    assertNull(read.queuedBehind(), "activated");
    assertNotNull(read.mergedSha(), "and folded");
    String backing = "refs/heads/" + read.backingBranch();
    assertTrue(
        merger.foldsOf(backing).stream()
            .anyMatch(fold -> fold.sources().contains("refs/heads/" + moved)),
        "the moved branch is in its fold: " + merger.foldsOf(backing));
  }

  // -------------------------------------------------------------------------------------------
  // Refusals
  // -------------------------------------------------------------------------------------------

  @Test
  void aBlankReasonIs400() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    ask(moved, "bob");

    assertStatus(400, () -> releaseRequests.moveSource(active, moved, "  ", "carol"));
    assertStatus(400, () -> releaseRequests.moveSource(active, moved, null, "carol"));
    assertTrue(names(releaseRequests.get(active)).contains(moved), "nothing moved");
  }

  @Test
  void aBranchTheRequestDoesNotNameIs404() {
    String active = ask(feature(), "alice").request().id();
    ask(feature(), "bob");

    assertStatus(404, () -> releaseRequests.moveSource(active, feature(), "why", "carol"));
    assertStatus(
        404, () -> releaseRequests.moveSource(UUID.randomUUID().toString(), "x", "why", "carol"));
  }

  @Test
  void theDefaultBranchIs409() {
    String active = ask(feature(), "alice").request().id();
    ask(feature(), "bob");

    String message = assertStatus(409, () -> releaseRequests.moveSource(active, "main", "why", "c"));
    assertTrue(message.contains("default branch"), message);
  }

  @Test
  void aReleasedTagIs409() {
    String active = ask(feature(), "alice").request().id();
    ask(feature(), "bob");
    String tag = "2026.1027." + (100000 + (int) (Math.random() * 800000));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleasedTagPendingMerge pending = new ReleasedTagPendingMerge();
              pending.id = UUID.randomUUID().toString();
              pending.repoId = repoId;
              pending.tagName = tag;
              pending.releasedSha = RecordingBackingBranchMerger.freshSha();
              pending.releasedAt = Instant.now();
              pending.persist();
            });
    assertTrue(
        releaseRequests.get(active).sources().stream()
            .anyMatch(source -> source.implicit() && source.name().equals(tag)),
        "the premise: an implicit tag source");

    for (String named : List.of(tag, "refs/tags/" + tag)) {
      String message = assertStatus(409, () -> releaseRequests.moveSource(active, named, "why", "c"));
      assertTrue(message.contains("released tag"), message);
    }
  }

  @Test
  void theOnlyBranchBesidesMainIs409AndPointsAtWithdraw() {
    String only = feature();
    String active = ask(only, "alice").request().id();

    String message = assertStatus(409, () -> releaseRequests.moveSource(active, only, "why", "c"));
    assertTrue(message.contains("withdraw"), message);
    assertEquals(0, queuedRequestsOf(), "nothing was minted");
  }

  @Test
  void aQueuedRequestIs409() {
    String active = ask(feature(), "alice").request().id();
    String first = feature();
    ask(first, "bob");
    ask(feature(), "bob");
    String queued = releaseRequests.moveSource(active, first, "not ready", "carol").to().id();
    String second = feature();
    releaseRequests.addSource(queued, second, "bob", null);

    String message =
        assertStatus(409, () -> releaseRequests.moveSource(queued, second, "why", "carol"));
    assertTrue(message.contains("is queued behind"), message);
  }

  @Test
  void aRequestPastTheUnreleasedStatesIs409() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    ask(moved, "bob");
    for (ReleaseRequest.State state :
        List.of(
            ReleaseRequest.State.RELEASED,
            ReleaseRequest.State.FINALIZED,
            ReleaseRequest.State.OBSOLETE,
            ReleaseRequest.State.WITHDRAWN)) {
      QuarkusTransaction.requiringNew()
          .run(() -> ReleaseRequest.update("state = ?1 where id = ?2", state, active));

      String message =
          assertStatus(409, () -> releaseRequests.moveSource(active, moved, "why", "carol"));
      assertTrue(message.contains("is already " + state), message);
    }
    assertTrue(names(releaseRequests.get(active)).contains(moved), "nothing moved");
  }

  // -------------------------------------------------------------------------------------------
  // The door
  // -------------------------------------------------------------------------------------------

  @Test
  void theDoorAnswersBothRequests() {
    String active = ask(feature(), "alice").request().id();
    String moved = feature();
    ask(moved, "bob");
    String qid = releaseRequests.get(active).qualifiedId();

    ExtractableResponse<Response> answer =
        move(qid, "{\"branch\":\"" + moved + "\",\"reason\":\"not ready\"}", 200);

    assertEquals(active, answer.path("from.id"), "the qualified id resolves");
    String to = answer.path("to.id");
    assertEquals(active, answer.path("to.queuedBehind"));
    assertEquals(moved, answer.path("to.sourceMoves[0].branch"));
    assertEquals(qid, answer.path("from.sourceMoves[0].fromQualifiedId"));
    assertTrue(names(releaseRequests.get(to)).contains(moved));
    move(active, "{\"branch\":\"" + feature() + "\",\"reason\":\"\"}", 400);
    move(active, "{\"branch\":\"main\",\"reason\":\"why\"}", 409);
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  private ReleaseRequests.Asked ask(String branch, String requester) {
    return releaseRequests.ask(repoId, branch, requester, null);
  }

  private String feature() {
    long number =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("A change", "because", null, "IMPROVEMENT", null),
                "seed")
            .entity()
            .number;
    return "ticket/" + projectId + "-" + number;
  }

  private static List<String> names(ReleaseRequestDto read) {
    return read.sources().stream()
        .filter(source -> !source.implicit())
        .map(ReleaseRequestSourceDto::name)
        .toList();
  }

  private static ReleaseRequestSourceDto source(ReleaseRequestDto read, String branch) {
    return read.sources().stream()
        .filter(source -> source.name().equals(branch))
        .findFirst()
        .orElseThrow(() -> new AssertionError(branch + " is not on " + read.qualifiedId()));
  }

  private ReleaseRequestSource sourceRow(String requestId, String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequestSource.<ReleaseRequestSource>find(
                        "requestId = ?1 and name = ?2", requestId, branch)
                    .firstResult());
  }

  private BranchDelivery ledgerRow(String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                BranchDelivery.<BranchDelivery>find(
                        "projectId = ?1 and repoId = ?2 and branch = ?3", projectId, repoId, branch)
                    .firstResult());
  }

  private long queuedRequestsOf() {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.count("repoId = ?1 and queuedBehind is not null", repoId));
  }

  private ExtractableResponse<Response> move(String requestId, String body, int status) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .post(
            "/projects/api/repositories/"
                + repoId
                + "/release-requests/"
                + requestId
                + "/sources/move")
        .then()
        .statusCode(status)
        .extract();
  }

  private static String assertStatus(int status, Executable call) {
    DomainException refused = assertThrows(DomainException.class, call);
    assertEquals(status, refused.statusCode(), refused.getMessage());
    return refused.getMessage();
  }

  private static void await(Supplier<Boolean> condition, String what) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("never true: " + what);
  }
}
