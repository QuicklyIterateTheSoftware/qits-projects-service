package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.FakeGitHostRepositories.NamesPush;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The git host's name index is fed from the alias table (qits-1215): a committed change to a
 * repository's names bumps its {@code names_revision} and pushes the whole set after commit; a
 * change that rolls back, or a write that changes nothing, pushes nothing; and a git host that fails
 * never fails the write that triggered it.
 */
@QuarkusTest
public class GitHostNameSyncTest {

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject RepositoryRepository repositoryRepository;
  @Inject RepositoryNameRepository repositoryNameRepository;
  @Inject FakeGitHostRepositories host;
  @Inject GitHostNameSync sync;

  @AfterEach
  void allowNames() {
    host.allowNames();
  }

  private Repository cloned(String projectName) throws Exception {
    Project project = projectService.create(projectName, null);
    return repositoryService.cloneRepository(
        GitFixtures.path("testing-repo.git"), null, project);
  }

  private long revisionOf(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> repositoryRepository.findById(repoId).namesRevision);
  }

  private NamesPush lastPush(String repoId) {
    List<NamesPush> pushes = host.namesPushesOf(repoId);
    assertFalse(pushes.isEmpty(), "no names push was made for " + repoId);
    return pushes.get(pushes.size() - 1);
  }

  @Test
  public void aCommittedAliasPushesTheWholeSetWithTheBumpedRevision() throws Exception {
    Repository repo = cloned("Names Push Alias");
    NamesPush created = lastPush(repo.id);
    assertEquals(List.of("testing-repo"), created.names(), "the self-name was pushed at create");
    assertEquals(revisionOf(repo.id), created.revision());

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                repositoryNameRepository.ensureAlias(
                    repositoryRepository.findById(repo.id).project,
                    "another-name",
                    repositoryRepository.findById(repo.id)));

    NamesPush pushed = lastPush(repo.id);
    assertEquals(List.of("another-name", "testing-repo"), pushed.names(), "every name, sorted");
    assertEquals(created.revision() + 1, pushed.revision(), "one insert, one bump");
    assertEquals(revisionOf(repo.id), pushed.revision(), "the revision the row holds");
    assertEquals(repo.project.id, pushed.projectId());
    assertEquals(repo.project.slug, pushed.projectSlug());
  }

  @Test
  public void aWriteThatChangesNothingNeitherBumpsNorPushes() throws Exception {
    Repository repo = cloned("Names Push No-op");
    long revision = revisionOf(repo.id);
    int pushes = host.namesPushesOf(repo.id).size();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository loaded = repositoryRepository.findById(repo.id);
              repositoryNameRepository.ensureAlias(loaded.project, "testing-repo", loaded);
              repositoryNameRepository.registerSelfName(loaded, "testing-repo");
            });

    assertEquals(revision, revisionOf(repo.id));
    assertEquals(pushes, host.namesPushesOf(repo.id).size());
  }

  @Test
  public void aRolledBackChangePushesNothing() throws Exception {
    Repository repo = cloned("Names Push Rollback");
    long revision = revisionOf(repo.id);
    int pushes = host.namesPushesOf(repo.id).size();

    assertThrows(
        IllegalStateException.class,
        () ->
            QuarkusTransaction.requiringNew()
                .run(
                    () -> {
                      Repository loaded = repositoryRepository.findById(repo.id);
                      repositoryNameRepository.ensureAlias(loaded.project, "never-was", loaded);
                      throw new IllegalStateException("roll it back");
                    }));

    assertEquals(revision, revisionOf(repo.id), "the bump rolled back with the row");
    assertEquals(pushes, host.namesPushesOf(repo.id).size(), "and nothing was pushed");
  }

  /** The rename case the reorganisation is ordered after: one statement, one name set, pushed. */
  @Test
  public void aRenamePushesExactlyTheNewNameWithAHigherRevision() throws Exception {
    Repository repo = cloned("Names Push Rename");
    long before = lastPush(repo.id).revision();

    repositoryService.rename(repo.id, "renamed-repo");

    NamesPush pushed = lastPush(repo.id);
    assertEquals(List.of("renamed-repo"), pushed.names());
    assertTrue(pushed.revision() > before, "a newer revision than the old set's");
  }

  @Test
  public void aFailingGitHostNeverFailsTheWriteThatTriggeredIt() throws Exception {
    Repository repo = cloned("Names Push Fails");
    host.failNamesOf(repo.id);
    int pushes = host.namesPushesOf(repo.id).size();

    repositoryService.rename(repo.id, "renamed-anyway");

    assertEquals(
        repo.id,
        repositoryService.findByProjectAndName(repo.project.id, "renamed-anyway").orElseThrow().id,
        "the rename committed");
    assertTrue(host.namesPushesOf(repo.id).size() > pushes, "the push was attempted");
    assertEquals(GitHostNameSync.Result.FAILED, sync.push(repo.id), "and is reported, not thrown");

    host.allowNames();
    assertEquals(GitHostNameSync.Result.APPLIED, sync.push(repo.id), "the retry heals it");
    assertEquals(List.of("renamed-anyway"), lastPush(repo.id).names());
  }

  @Test
  public void aNameTheGitHostWouldRefuseIsLeftOutRatherThanFailingTheSet() throws Exception {
    Repository repo = cloned("Names Push Filter");

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository loaded = repositoryRepository.findById(repo.id);
              repositoryNameRepository.ensureAlias(loaded.project, "has space", loaded);
              repositoryNameRepository.ensureAlias(loaded.project, "suffixed.git", loaded);
            });

    assertEquals(List.of("testing-repo"), lastPush(repo.id).names());
  }

  @Test
  public void aVanishedRepositoryIsSkipped() {
    int before = host.namesPushesOf("no-such-repository").size();
    assertEquals(GitHostNameSync.Result.SKIPPED, sync.push("no-such-repository"));
    assertEquals(before, host.namesPushesOf("no-such-repository").size());
  }

  @Test
  public void theGitHostsNameRuleIsTheOneItEnforces() {
    assertTrue(GitHostNameSync.isAcceptedName("qits-ci-service"));
    assertFalse(GitHostNameSync.isAcceptedName("a/b"));
    assertFalse(GitHostNameSync.isAcceptedName("a b"));
    assertFalse(GitHostNameSync.isAcceptedName("repo.git"));
    assertFalse(GitHostNameSync.isAcceptedName(" "));
    assertFalse(GitHostNameSync.isAcceptedName("x".repeat(256)));
    assertTrue(GitHostNameSync.isAcceptedName("x".repeat(255)));
  }
}
