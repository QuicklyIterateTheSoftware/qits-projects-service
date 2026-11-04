package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code GET /repositories/{repoId}/commit-subjects}</b> (qits-302, qits-706) against a real
 * repository: a merge, a maintenance bump, a release stamp, a platform chore, a complying {@code
 * feat(qits-1): x} and a person's free-text commit, each counted under its own variant — or under
 * none — whoever authored it.
 *
 * <p>Driven through real git the way {@code MergeRangeCommitsTest} is: the fixture bare is cloned
 * as a repository, the history is made in a working clone and pushed to the git host, and the
 * mirror is dropped so the read re-clones it.
 */
@QuarkusTest
public class CommitSubjectsControllerTest {

  @Inject RepositoryService repositoryService;

  @Inject ProjectService projectService;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitHostAddress gitHost;

  @Inject GitExecutor git;

  @Test
  public void eachCommitLandsUnderOneVariantAndTheNonComplyingAreListed() throws Exception {
    Repository repo = cloned("Commit Subjects Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;
    // What the fixture already holds: free-text subjects from before the rule, so every one fits
    // no variant — the expected reading of an old history.
    int fixture =
        Integer.parseInt(git.exec(work.toFile(), "git", "rev-list", "--count", "HEAD").trim());

    // A side branch with a complying commit, merged back with git's own merge message.
    git.exec(work.toFile(), "git", "checkout", "-q", "-b", "side", main);
    commit(work, "side.txt", "feat(qits-2): side work", "A Person", "person@example.com");
    git.exec(work.toFile(), "git", "checkout", "-q", main);
    commit(work, "free.txt", "Fix the thing quickly", "A Person", "person@example.com");
    git.exec(work.toFile(), "git", "merge", "-q", "--no-ff", "-m", "Merge branch 'side'", "side");
    commit(work, "bump.txt", "bump(dependencies): 2 dependencies", "qits maintenance",
        "maintenance@qits.local");
    commit(work, "release.txt", "release(2026.1104.1): qits-1 the export", "qits-projects",
        "qits-projects@qits.internal");
    commit(work, "chore.txt", "chore(platform): add the export to the project", "qits-projects",
        "qits-projects@qits.internal");
    // Only the first line counts: git's %s would join these two lines into a complying subject.
    commit(work, "split.txt", "feat(qits-3):\nthe message on the second line", "A Person",
        "person@example.com");
    commit(work, "x.txt", "feat(qits-1): x", "A Person", "person@example.com");
    git.exec(work.toFile(), "git", "push", "-q", "origin", main, "side");
    goCold(repo);

    JsonPath answer = read(repo.id, Map.of(), "qits:agent");

    assertEquals(repo.id, answer.getString("repositoryId"));
    assertEquals(main, answer.getString("branch"), "no branch asked for: the main branch");
    assertEquals(100, answer.getInt("limit"));
    assertEquals(fixture + 8, answer.getInt("counts.total"));
    assertEquals(1, answer.getInt("counts.merge"), "the merge");
    assertEquals(1, answer.getInt("counts.bump"), "maintenance's bump");
    assertEquals(1, answer.getInt("counts.release"), "the release stamp");
    assertEquals(1, answer.getInt("counts.platform"), "the platform's chore");
    assertEquals(2, answer.getInt("counts.subject"), "feat(qits-1) and feat(qits-2)");
    assertEquals(fixture + 2, answer.getInt("counts.nonComplying"));

    List<Map<String, Object>> offenders = answer.getList("nonComplying");
    assertEquals(fixture + 2, offenders.size(), "the full non-complying listing");
    Map<String, Object> split = offenders.getFirst();
    assertEquals("feat(qits-3):", split.get("subject"), "newest first, first line: " + offenders);
    Map<String, Object> person = offenders.get(1);
    assertEquals("Fix the thing quickly", person.get("subject"));
    assertEquals("A Person", person.get("authorName"));
    assertEquals("person@example.com", person.get("authorEmail"));
    assertTrue(((String) person.get("hash")).length() == 40, person.toString());
    assertTrue(((String) person.get("hash")).startsWith((String) person.get("shortHash")));
    assertFalse(((String) person.get("date")).isBlank());
    List<String> subjects = offenders.stream().map(o -> (String) o.get("subject")).toList();
    assertFalse(subjects.contains("Merge branch 'side'"), "a merge fits MERGE: " + subjects);
    assertEquals(List.of("qits-1", "qits-2"), answer.getList("qualifiedIds"),
        "the SUBJECT commits' ids only — a release's summary names none");
    assertNull(answer.get("guardEnabled"), "there is no opt-in any more");

    JsonPath limited = read(repo.id, Map.of("branch", main, "limit", "3"), "qits:system");
    assertEquals(3, limited.getInt("limit"));
    assertEquals(3, limited.getInt("counts.total"), "limited to the newest three");
    assertEquals(1, limited.getInt("counts.subject"));
    assertEquals(1, limited.getInt("counts.platform"));
    assertEquals(1, limited.getInt("counts.nonComplying"));

    JsonPath side = read(repo.id, Map.of("branch", "side"), "qits:admin");
    assertEquals("side", side.getString("branch"));
    assertEquals(1, side.getInt("counts.subject"));
    assertEquals(0, side.getInt("counts.merge"));
  }

  /**
   * A scope naming several ids ({@code CommitSubjectEntities#references}, qits-1140) is one
   * COMPLYING commit, and both ids land in the distinct {@code qualifiedIds} list.
   */
  @Test
  public void aCommitNamingSeveralIdsIsOneComplyingCommitListingBothIds() throws Exception {
    Repository repo = cloned("Commit Subjects Multi Id");
    Path work = checkout(repo);
    String main = repo.mainBranch;
    int fixture =
        Integer.parseInt(git.exec(work.toFile(), "git", "rev-list", "--count", "HEAD").trim());

    commit(
        work,
        "multi.txt",
        "chore(qits-1, qits-2): bump(dependencies): 2 dependencies",
        "A Person",
        "person@example.com");
    git.exec(work.toFile(), "git", "push", "-q", "origin", main);
    goCold(repo);

    JsonPath answer = read(repo.id, Map.of(), "qits:agent");

    assertEquals(fixture + 1, answer.getInt("counts.total"));
    assertEquals(1, answer.getInt("counts.subject"), "one commit, naming two ids");
    assertEquals(fixture, answer.getInt("counts.nonComplying"), "the fixture's free-text history");
    assertEquals(List.of("qits-1", "qits-2"), answer.getList("qualifiedIds"));
  }

  @Test
  public void aLimitOutsideOneToAThousandAndAnUnknownBranchAreRefused() throws Exception {
    Repository repo = cloned("Commit Subjects Refusals");
    for (String limit : new String[] {"0", "1001"}) {
      given()
          .header("Authorization", ContractBearers.caller("agent-test", "qits:agent"))
          .queryParam("limit", limit)
          .when()
          .get("/projects/api/repositories/" + repo.id + "/commit-subjects")
          .then()
          .statusCode(400);
    }
    given()
        .header("Authorization", ContractBearers.caller("agent-test", "qits:agent"))
        .queryParam("branch", "no-such-branch")
        .when()
        .get("/projects/api/repositories/" + repo.id + "/commit-subjects")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", ContractBearers.caller("agent-test", "qits:agent"))
        .queryParam("branch", "-D")
        .when()
        .get("/projects/api/repositories/" + repo.id + "/commit-subjects")
        .then()
        .statusCode(400);
  }

  private JsonPath read(String repoId, Map<String, String> query, String role) {
    return given()
        .header("Authorization", ContractBearers.caller("subjects-test", role))
        .queryParams(query)
        .when()
        .get("/projects/api/repositories/" + repoId + "/commit-subjects")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private Repository cloned(String projectName) throws Exception {
    var project = projectService.create(projectName + " " + UUID.randomUUID(), null);
    return repositoryService.cloneRepository(GitFixtures.path("testing-repo.git"), null, project);
  }

  private Path checkout(Repository repo) throws Exception {
    Path parent = Files.createTempDirectory("commit-subjects");
    parent.toFile().deleteOnExit();
    git.exec(parent.toFile(), "git", "clone", "-q", gitHost.fetchUrl(repo.id), "work");
    Path work = parent.resolve("work");
    git.exec(work.toFile(), "git", "config", "user.email", "fixtures@qits.local");
    git.exec(work.toFile(), "git", "config", "user.name", "qits fixtures");
    return work;
  }

  private void commit(Path work, String file, String message, String name, String email)
      throws Exception {
    commit(work, file, message, name, email, message + "\n");
  }

  private void commit(
      Path work, String file, String message, String name, String email, String content)
      throws Exception {
    Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    git.exec(work.toFile(), "git", "add", "-A");
    git.exec(
        work.toFile(),
        "git",
        "commit",
        "-q",
        "--author",
        name + " <" + email + ">",
        "-m",
        message);
  }

  /** Drop the mirror, so the next read clones it afresh (see {@code MergeRangeCommitsTest}). */
  private void goCold(Repository repo) throws IOException {
    Path mirror = gitMirrors.of(repo.id).gitDir();
    if (!Files.exists(mirror)) {
      return;
    }
    try (var paths = Files.walk(mirror)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
