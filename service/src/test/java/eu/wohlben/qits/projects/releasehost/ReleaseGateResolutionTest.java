package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.control.ReleaseGates.GateSet;
import eu.wohlben.qits.projects.control.ReleaseGates.Kind;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Resolving which gates a repository configures, read from its {@code main}.
 *
 * <p>The two halves worth holding in one place are the positive — a file's presence is a gate — and
 * the negative, which is the one that matters: <b>a configuration that could not be read is never an
 * empty set</b>, because an empty set releases at once and "could not ask" must not.
 */
@QuarkusTest
public class ReleaseGateResolutionTest {

  @Inject ReleaseGates gates;

  @Inject RecordingReleaseGitHost gitHost;

  private String repoId;

  @BeforeEach
  void seed() {
    gitHost.reset();
    repoId = "gate-resolution-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = "gate-resolution-project-" + UUID.randomUUID();
              project.name = "gate-resolution";
              project.slug = "gate-resolution-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
            });
  }

  /** Every resolve in this class reads main afresh: the freshness window is per repository id. */
  private GateSet resolve() {
    gates.forget(repoId);
    return gates.resolve(repoId);
  }

  @Test
  void aRepositoryCarryingOnlyARecipeIsGatedByCiAlone() {
    gitHost.tree(
        "refs/heads/main", Map.of(".config/qits/ci-event-release-request.yml", "steps: []\n"));
    GateSet set = resolve();
    assertTrue(set.known());
    assertEquals(Set.of(Kind.CI), set.kinds());
    assertFalse(set.nothingToWaitOn());
  }

  @Test
  void allThreeFilesAreAllThreeGates() {
    gitHost.tree(
        "refs/heads/main",
        Map.of(
            ".config/qits/ci-event-release-request.yml", "steps: []\n",
            ".config/qits/deployments.yml", "resources: []\n",
            ".config/qits/release-requests.yml", "manual-review: true\n"));
    GateSet set = resolve();
    assertEquals(Set.of(Kind.CI, Kind.DEPLOYMENT, Kind.APPROVAL), set.kinds());
  }

  @Test
  void manualReviewFalseIsNoApprovalGate() {
    gitHost.tree("refs/heads/main", Map.of(".config/qits/release-requests.yml", "manual-review: false\n"));
    assertEquals(Set.of(), resolve().kinds());
  }

  @Test
  void aRepositoryConfiguringNothingWaitsOnNothing() {
    gitHost.tree("refs/heads/main", Map.of("README.md", "hello"));
    GateSet set = resolve();
    assertTrue(set.known());
    assertTrue(set.nothingToWaitOn());
  }

  @Test
  void aMainThatCannotBeReadIsUnknownAndNotAnEmptySet() {
    // Nothing staged at refs/heads/main at all: the fake answers a failed listing, which is exactly
    // "could not be asked" and must not read as "configures nothing".
    GateSet set = resolve();
    assertFalse(set.known());
    assertFalse(set.nothingToWaitOn());
    assertFalse(set.requires(Kind.CI));
    assertTrue(set.detail() != null && !set.detail().isBlank());
  }

  @Test
  void aSettingsFileThatDoesNotParseIsUnknownRatherThanNoApprovalGate() {
    gitHost.tree(
        "refs/heads/main",
        Map.of(
            ".config/qits/ci-event-release-request.yml", "steps: []\n",
            ".config/qits/release-requests.yml", "manual-review: maybe\n"));
    GateSet set = resolve();
    assertFalse(set.known());
    assertTrue(set.detail().contains(".config/qits/release-requests.yml"));
  }

  @Test
  void theGateSetIsReadFromMainAndNotFromTheFold() {
    gitHost.tree(
        "refs/heads/main", Map.of(".config/qits/release-requests.yml", "manual-review: true\n"));
    // A fold that deletes the setting. It is not read, so it cannot release itself unreviewed.
    gitHost.tree("some-fold-sha", Map.of("README.md", "the setting is gone on this branch"));
    assertTrue(resolve().requires(Kind.APPROVAL));
  }

  @Test
  void anUnknownAnswerIsNeverHeldWhileAKnownOneIs() {
    // Unknown first: nothing is staged, so the read fails.
    assertFalse(gates.resolve(repoId).known());
    // The very next resolve asks again — retrying is exactly what fixes "could not ask".
    gitHost.tree(
        "refs/heads/main", Map.of(".config/qits/ci-event-release-request.yml", "steps: []\n"));
    assertEquals(Set.of(Kind.CI), gates.resolve(repoId).kinds());
  }
}
