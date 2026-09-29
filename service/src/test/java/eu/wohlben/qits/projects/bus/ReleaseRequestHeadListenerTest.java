package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A push to main is news about the implicit source set.</b> A released tag carried onto {@code
 * main} by hand leaves the set the moment the push's head is found to contain it — the same
 * question finalization asks after its own merge, which announces nothing and so cannot be relied
 * on to come back through here.
 *
 * <p>A {@code @QuarkusTest} on the default profile rather than plain JUnit, because the claim is
 * the stamp on the row, not that a method was called; the frame is built through the real {@code
 * CanonicalJson}, so the payload is byte-for-byte what qits-events stores.
 */
@QuarkusTest
class ReleaseRequestHeadListenerTest {

  private static final String RELEASED_SHA = "a".repeat(40);
  private static final String PUSHED_SHA = "b".repeat(40);

  @Inject ReleaseRequestHeadListener listener;

  @Inject RecordingReleaseGitHost gitHost;

  private String projectId;
  private String repoId;
  private String rowId;

  @BeforeEach
  void seed() {
    gitHost.reset();
    projectId = "head-project-" + UUID.randomUUID();
    repoId = "head-repo-" + UUID.randomUUID();
    rowId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "head";
              project.slug = "head-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
              ReleasedTagPendingMerge row = new ReleasedTagPendingMerge();
              row.id = rowId;
              row.repoId = repoId;
              row.tagName = "2026.929." + (100000 + (int) (Math.random() * 800000));
              row.releasedSha = RELEASED_SHA;
              row.releasedAt = Instant.now();
              row.persist();
            });
    gitHost.containsCommit(repoId, RELEASED_SHA, PUSHED_SHA);
  }

  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleasedTagPendingMerge.delete("repoId = ?1", repoId));
  }

  @Test
  void aPushToAnotherBranchLeavesThePendingTagAlone() {
    listener.onFrame(frameOf(commit("work-x", PUSHED_SHA)));

    assertNull(mergedAt(), "a branch that is not main says nothing about main");
  }

  @Test
  void aPushToMainWhoseHeadContainsAPendingTagStampsIt() {
    listener.onFrame(frameOf(commit("main", PUSHED_SHA)));

    assertNotNull(mergedAt(), "main now holds the tag, so it leaves the implicit source set");
  }

  // -------------------------------------------------------------------------------------------

  private Instant mergedAt() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleasedTagPendingMerge.<ReleasedTagPendingMerge>findById(rowId).mergedAt);
  }

  private SCMPublishCommit commit(String branch, String sha) {
    Instant now = Instant.now();
    return new SCMPublishCommit(
        repoId,
        projectId,
        "head-repo",
        branch,
        "1".repeat(40),
        sha,
        // A parent that is NOT the head: the head is the question, never its parents.
        List.of("1".repeat(40)),
        "qits",
        "qits@local",
        now,
        now,
        "a hand push",
        false,
        now);
  }

  /** The event as it really arrives: canonicalized into an envelope, then read back as a frame. */
  private static EventFrame frameOf(SCMPublishCommit event) {
    EventEnvelope envelope = EventEnvelope.of(event);
    return new EventFrame(
        UUID.randomUUID().toString(),
        envelope.name(),
        envelope.occurredAt(),
        envelope.payload(),
        null,
        envelope.parentId(),
        null);
  }
}
