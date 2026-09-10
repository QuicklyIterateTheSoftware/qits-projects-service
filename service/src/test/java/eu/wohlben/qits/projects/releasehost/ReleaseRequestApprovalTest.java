package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ApprovalPolicy;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.ReleaseRequestApprovalRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The approval record and the policy seam, which is everything that exists of the approval gate so
 * far: a row per decision and one question about whether a repository needs one. Nothing reads
 * either yet, so what is asserted here is the two properties the gate will be built on top of.
 *
 * <p><b>The read is "the newest row at the request's CURRENT merged sha"</b>, and both halves earn a
 * test. A second decision about the same fold wins over the first — that is how a change of mind
 * works in an insert-only table. A decision about a fold the request has since moved past matches
 * nothing at all — that is the re-arm carrying the invalidation for free, which is the reason the
 * sha is on the row rather than a flag on the request.
 *
 * <p>A {@code @QuarkusTest} rather than a domain unit test because both subjects need a datasource:
 * one is a Panache repository and the other reads {@code repository.archetype} out of the database.
 */
@QuarkusTest
public class ReleaseRequestApprovalTest {

  @Inject ReleaseRequestApprovalRepository approvals;

  @Inject ApprovalPolicy policy;

  private String projectId;
  private String requestId;

  @BeforeEach
  void seed() {
    projectId = "approval-project-" + UUID.randomUUID();
    requestId = "approval-request-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "approval-policy";
              project.slug = "approval-policy-" + UUID.randomUUID();
              project.persist();
              // One repository per archetype, so the policy is asked about the whole enum rather
              // than about the two cases that happen to be interesting.
              for (RepositoryArchetype archetype : RepositoryArchetype.values()) {
                Repository repository = new Repository();
                repository.id = repoIdOf(archetype);
                repository.project = project;
                repository.mainBranch = "main";
                repository.archetype = archetype;
                repository.persist();
              }
            });
  }

  /**
   * The approval rows have no foreign key to anything — that is the point of them, they outlive the
   * request — so nothing sweeps them and this class deletes its own. The fixture project and its
   * repositories are per-run unique and left where they are, the discipline the sibling flow tests
   * follow.
   */
  @AfterEach
  void dropTheFixturesApprovals() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequestApproval.delete("requestId = ?1", requestId));
  }

  private String repoIdOf(RepositoryArchetype archetype) {
    return "approval-repo-" + archetype.name() + "-" + projectId;
  }

  private void record(
      String mergedSha, ReleaseRequestApproval.Decision decision, String actor, Instant decidedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestApproval approval = new ReleaseRequestApproval();
              approval.id = UUID.randomUUID().toString();
              approval.requestId = requestId;
              approval.mergedSha = mergedSha;
              approval.decision = decision;
              approval.actor = actor;
              approval.decidedAt = decidedAt;
              approvals.persist(approval);
            });
  }

  /**
   * A decline, then the approval that answers it, about the same fold: the read is the second one.
   * Both rows stay — the trail is the corpus, and the older row is what says a person refused first.
   */
  @Test
  void theNewestDecisionAtAShaIsTheOneThatCounts() {
    Instant now = Instant.now();
    String sha = "sha-" + UUID.randomUUID();
    record(sha, ReleaseRequestApproval.Decision.DECLINED, "ada", now.minus(2, ChronoUnit.HOURS));
    record(sha, ReleaseRequestApproval.Decision.APPROVED, "ada", now.minus(1, ChronoUnit.HOURS));

    Optional<ReleaseRequestApproval> current = approvals.latestFor(requestId, sha);
    assertTrue(current.isPresent(), "a decision was made about this fold");
    assertEquals(ReleaseRequestApproval.Decision.APPROVED, current.get().decision);

    List<ReleaseRequestApproval> trail = approvals.listByRequest(requestId);
    assertEquals(2, trail.size(), "nothing updates or deletes a row; both decisions are kept");
    assertEquals(ReleaseRequestApproval.Decision.APPROVED, trail.get(0).decision);
    assertEquals(ReleaseRequestApproval.Decision.DECLINED, trail.get(1).decision);
  }

  /**
   * The re-arm doing the invalidating: the request folded again and its merged sha moved, so the
   * approval made against the old fold answers nothing about the new one — the same empty a request
   * nobody has decided on gives.
   */
  @Test
  void aDecisionAboutASupersededFoldMatchesNothingAtTheCurrentOne() {
    String supersededSha = "sha-old-" + UUID.randomUUID();
    String currentSha = "sha-new-" + UUID.randomUUID();
    record(
        supersededSha, ReleaseRequestApproval.Decision.APPROVED, "ada", Instant.now());

    assertTrue(
        approvals.latestFor(requestId, supersededSha).isPresent(),
        "the decision is still there, and still says which fold it judged");
    assertTrue(
        approvals.latestFor(requestId, currentSha).isEmpty(),
        "but the request has moved past that fold, so nothing is approved now");
    assertEquals(
        1, approvals.listByRequest(requestId).size(), "the history read still shows it");
  }

  /** The seam's rule today: the wrapper needs a person, and nothing else does. */
  @Test
  void onlyTheWrapperRequiresApproval() {
    for (RepositoryArchetype archetype : RepositoryArchetype.values()) {
      boolean required = policy.requiresApproval(repoIdOf(archetype));
      if (archetype == RepositoryArchetype.PROJECT) {
        assertTrue(required, "a wrapper release is approved by a person");
      } else {
        assertFalse(required, archetype + " releases on its gates alone");
      }
    }
  }

  /**
   * A request outlives the repository it named, so this is asked for ids that no longer resolve —
   * and the answer must let settled history be read rather than hold it forever.
   */
  @Test
  void aRepositoryWithNoRowRequiresNoApproval() {
    assertFalse(policy.requiresApproval("approval-vanished-" + UUID.randomUUID()));
  }
}
