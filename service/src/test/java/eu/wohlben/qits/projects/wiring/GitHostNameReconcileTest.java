package eu.wohlben.qits.projects.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.FakeGitHostRepositories;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The git host's name index reconcile (qits-1215): one pass pushes every repository this service
 * holds, whatever the after-commit push did, and its counts say what the host made of each — the
 * line an operator reads to know the backfill ran and nothing is failing.
 *
 * <p>The suite's database is shared with every other class in the run, so the counts are checked
 * as a partition of every repository plus at least the cases this test staged, never as exact
 * totals.
 */
@QuarkusTest
public class GitHostNameReconcileTest {

  @Inject GitHostNameReconcile reconcile;
  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject FakeGitHostRepositories host;

  @AfterEach
  void allowNames() {
    host.allowNames();
  }

  @Test
  public void onePassPushesEveryRepositoryAndCountsWhatTheHostSaid() throws Exception {
    Project project = projectService.create("Names Reconcile", null);
    Repository stored =
        repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
    Repository failing =
        repositoryService.cloneRepository(
            GitFixtures.path("submodule-shared.git"), null, project);
    Repository unstored =
        repositoryService.cloneRepository(
            GitFixtures.path("submodule-grandchild.git"), null, project);
    host.failNamesOf(failing.id);
    host.delete(unstored.id);
    int storedBefore = host.namesPushesOf(stored.id).size();

    GitHostNameReconcile.Counts counts = reconcile.reconcile();

    long total = QuarkusTransaction.requiringNew().call(() -> repositoryRepository.count());
    assertEquals(
        total,
        counts.pushed() + counts.failed() + counts.skipped(),
        "every repository is pushed, failed or skipped — none is missed: " + counts);
    assertTrue(counts.failed() >= 1, "the failing one is counted, not thrown: " + counts);
    assertTrue(counts.notStored() >= 1, "the one the host does not store: " + counts);
    assertTrue(counts.pushed() >= counts.applied() + counts.notStored(), counts.toString());

    List<FakeGitHostRepositories.NamesPush> pushes = host.namesPushesOf(stored.id);
    assertEquals(
        storedBefore + 1,
        pushes.size(),
        "pushed again although the after-commit push already landed — the reconcile never trusts"
            + " it");
    assertEquals(List.of("testing-repo"), pushes.get(pushes.size() - 1).names());
    assertEquals(project.slug, pushes.get(pushes.size() - 1).projectSlug());
  }
}
