package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>An approved release request is closed to new branches</b> (qits-2526). Every join re-folds a
 * request, and a decision names the fold it judged, so a branch joining an approved request voids
 * the approval a person just gave. A PENDING or READY request whose newest decision at its {@code
 * mergedSha} is APPROVED therefore behaves as frozen for joiners: the create door queues a new branch
 * behind it, and the sources door refuses one with a 409 naming where to go — except {@code main}
 * and the request's own automation branches. A re-fold lifts it, a decline is no approval, and a
 * repository that needs no approval never meets it.
 *
 * <p>The repository here declares {@code manual-review: true} on its main, so every fold needs a
 * person; the CI gate is held open ({@link FakeActiveBuilds} answering "a run is still active"), so
 * an approval leaves the request PENDING rather than releasing it mid-test. Branches name no work
 * item, so the feature freeze — at the platform's 3 — never freezes anything here and what queues a
 * branch is only ever the approval.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class ReleaseApprovedJoinLockTest {

  private static final String APPROVER = "wohlben";

  @Inject ReleaseRequests releaseRequests;

  @Inject BuildStatusListener listener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingFoldChanges foldChanges;

  private String projectId;
  private String repoId;
  private String plainRepoId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    foldChanges.reset();
    // Held: nothing settles a request mid-test unless a test lets it.
    activeBuilds.answer(Optional.of(1));
    projectId = "join-lock-" + UUID.randomUUID().toString().substring(0, 8);
    repoId = "join-lock-repo-" + UUID.randomUUID();
    plainRepoId = "join-lock-plain-" + UUID.randomUUID();
    gitHost.gatedTreeFor(
        repoId,
        "refs/heads/main",
        Map.of(".config/qits/release-requests.yml", "manual-review: true\n"));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
              for (String id : List.of(repoId, plainRepoId)) {
                Repository repository = new Repository();
                repository.id = id;
                repository.project = project;
                repository.mainBranch = "main";
                repository.archetype = RepositoryArchetype.SERVICE;
                repository.persist();
              }
            });
  }

  /** The sweeps walk the whole database, so nothing of this fixture may outlive the test. */
  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestApproval.delete(
                  "requestId in (select r.id from ReleaseRequest r where r.projectId = ?1)",
                  projectId);
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId in ?1", List.of(repoId, plainRepoId));
            });
  }

  // -------------------------------------------------------------------------------------------
  // The sources door
  // -------------------------------------------------------------------------------------------

  @Test
  void anExplicitJoinOfAnApprovedRequestIsRefusedAndSaysToAskForAQueuedOne() {
    String held = branch();
    ReleaseRequestDto active = ask(repoId, held).request();
    String sha = approve(active.id());
    List<String> before = namedSources(active.id());

    String branch = branch();
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> releaseRequests.addSource(active.id(), branch, "alice", null));

    assertEquals(409, refused.statusCode());
    assertEquals(
        "Release request "
            + active.qualifiedId()
            + " is approved at "
            + sha.substring(0, 8)
            + ": a new branch would refold it and lapse the approval; ask for a release of the"
            + " branch (qits release-request create) and it opens a request queued behind "
            + active.qualifiedId(),
        refused.getMessage());
    ReleaseRequestDto read = releaseRequests.get(active.id());
    assertEquals(sha, read.mergedSha(), "nothing re-folded it");
    assertEquals("PENDING", read.state());
    assertEquals(before, namedSources(active.id()), "no source was added");
    assertEquals("APPROVED", read.approvalState(), "and the approval stands");

    // A branch already a source still answers PRESENT, re-folding nothing.
    ReleaseRequests.Asked again = releaseRequests.join(active.id(), held, "alice", null);
    assertEquals("PRESENT", again.placement().outcome());
    assertEquals(sha, releaseRequests.get(active.id()).mergedSha());
  }

  @Test
  void aCreateOnAnApprovedRequestQueuesBehindItAndAJoinIsSentThere() {
    ReleaseRequestDto active = ask(repoId, branch()).request();
    String sha = approve(active.id());

    String first = branch();
    ReleaseRequests.Asked opened = ask(repoId, first);

    assertEquals("CREATED", opened.placement().outcome());
    assertTrue(opened.placement().queued());
    ReleaseRequestDto queued = opened.request();
    assertNotEquals(active.id(), queued.id());
    assertEquals(active.id(), queued.queuedBehind());
    assertNull(queued.mergedSha(), "a queued request is not folded");
    assertEquals(
        active.qualifiedId()
            + " is approved; "
            + first
            + " opened "
            + queued.qualifiedId()
            + ", queued behind it",
        opened.placement().detail());

    String second = branch();
    ReleaseRequests.Asked joined = ask(repoId, second);
    assertEquals("JOINED", joined.placement().outcome());
    assertTrue(joined.placement().queued());
    assertEquals(queued.id(), joined.request().id(), "the second create joins the same queue");
    assertEquals(
        active.qualifiedId()
            + " is approved; "
            + second
            + " joined "
            + queued.qualifiedId()
            + ", queued behind it",
        joined.placement().detail());

    String third = branch();
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> releaseRequests.addSource(active.id(), third, "alice", null));
    assertEquals(409, refused.statusCode());
    assertTrue(
        refused
            .getMessage()
            .endsWith(
                ": a new branch would refold it and lapse the approval; join "
                    + queued.qualifiedId()
                    + ", queued behind it"),
        refused.getMessage());
    assertEquals(sha, releaseRequests.get(active.id()).mergedSha(), "the active never re-folded");
    assertFalse(namedSources(active.id()).contains(first));
    assertFalse(namedSources(active.id()).contains(second));
  }

  @Test
  void theRequestsOwnAutomationBranchesStillJoinButAnotherRequestsDoNot() {
    ReleaseRequestDto active = ask(repoId, branch()).request();
    approve(active.id());
    String byQualified = "maintenance/automations/entity-diagram/" + active.qualifiedId();

    ReleaseRequests.Asked joined = releaseRequests.join(active.id(), byQualified, "robot", null);

    assertEquals("JOINED", joined.placement().outcome());
    assertEquals(active.id(), joined.request().id());
    assertTrue(namedSources(active.id()).contains(byQualified));
    // The join re-folded it, so the approval lapsed with the old sha and the lock with it.
    assertNotEquals("APPROVED", releaseRequests.get(active.id()).approvalState());

    approve(active.id());
    String byUuid = "maintenance/automations/cli-tapes/" + active.id();
    assertEquals(
        "JOINED", releaseRequests.join(active.id(), byUuid, "robot", null).placement().outcome());

    approve(active.id());
    String foreign = "maintenance/automations/entity-diagram/" + UUID.randomUUID();
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> releaseRequests.join(active.id(), foreign, "robot", null));
    assertEquals(409, refused.statusCode());
  }

  @Test
  void aJoinIsAdmittedOnceTheApprovedFoldWentRedAndRejected() {
    ReleaseRequestDto active = ask(repoId, branch()).request();
    String sha = approve(active.id());
    activeBuilds.answer(Optional.of(0));

    verdict("BuildFailed", sha, ",\"outcome\":\"FAILED\"");
    await(() -> releaseRequests.get(active.id()).state(), "REJECTED");

    String branch = branch();
    ReleaseRequests.Asked joined = releaseRequests.join(active.id(), branch, "alice", null);
    assertEquals("JOINED", joined.placement().outcome());
    assertTrue(namedSources(active.id()).contains(branch));
    assertNotEquals(sha, releaseRequests.get(active.id()).mergedSha(), "and it re-folded");
  }

  @Test
  void aJoinBeforeTheApprovalIsAdmitted() {
    ReleaseRequestDto active = ask(repoId, branch()).request();
    assertNotNull(active.mergedSha(), "folded, and nobody has decided about it");

    String branch = branch();
    ReleaseRequests.Asked joined = releaseRequests.join(active.id(), branch, "alice", null);

    assertEquals("JOINED", joined.placement().outcome());
    assertFalse(joined.placement().queued());
    assertEquals(active.id(), ask(repoId, branch()).request().id(), "and a create joins too");
  }

  @Test
  void aRequestWhoseNewestDecisionIsDeclinedIsNotLocked() {
    ReleaseRequestDto active = ask(repoId, branch()).request();
    String sha = approve(active.id());
    releaseRequests.decline(active.id(), sha, "not like this", "bob");
    assertEquals("PENDING", releaseRequests.get(active.id()).state(), "no verdict yet");

    ReleaseRequests.Asked asked = ask(repoId, branch());
    assertEquals("JOINED", asked.placement().outcome());
    assertFalse(asked.placement().queued());
    assertEquals(active.id(), asked.request().id());

    String branch = branch();
    assertEquals(
        "JOINED", releaseRequests.join(active.id(), branch, "alice", null).placement().outcome());
  }

  @Test
  void aRepositoryThatNeedsNoApprovalIsUnaffected() {
    ReleaseRequestDto active = ask(plainRepoId, branch()).request();
    assertNotNull(active.mergedSha());
    assertFalse(active.approvalRequired());

    ReleaseRequests.Asked asked = ask(plainRepoId, branch());
    assertEquals(active.id(), asked.request().id());
    assertFalse(asked.placement().queued());
    String branch = branch();
    assertEquals(
        "JOINED", releaseRequests.join(active.id(), branch, "alice", null).placement().outcome());
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  /** Approve {@code id}'s current fold as a person, and answer the sha approved. */
  private String approve(String id) {
    String sha = releaseRequests.get(id).mergedSha();
    assertNotNull(sha, "the premise: folded");
    ReleaseRequestDto read = releaseRequests.approve(id, sha, null, APPROVER);
    assertEquals("PENDING", read.state(), "held by the CI gate: " + read.detail());
    assertEquals("APPROVED", read.approvalState());
    return sha;
  }

  private ReleaseRequests.Asked ask(String repo, String branch) {
    return releaseRequests.ask(repo, branch, "alice", null);
  }

  /** A branch naming no work item, so it never counts toward the feature freeze. */
  private static String branch() {
    return "fix/" + UUID.randomUUID();
  }

  private List<String> namedSources(String requestId) {
    return releaseRequests.get(requestId).sources().stream()
        .filter(source -> !source.implicit())
        .map(source -> source.name())
        .toList();
  }

  private void verdict(String name, String sha, String extra) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\""
                + BackingBranches.atFold(repoId, sha)
                + "\",\"commitSha\":\""
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

  private static void await(Supplier<String> read, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = read.get();
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
    fail("never reached " + expected + "; last seen " + last);
  }
}
