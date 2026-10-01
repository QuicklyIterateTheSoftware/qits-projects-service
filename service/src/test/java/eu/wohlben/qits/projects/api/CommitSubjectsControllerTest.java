package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <b>{@code GET /repositories/{repoId}/commit-subjects}</b> (qits-302) against a real repository: a
 * merge, a machine bump with a free-text subject, a complying {@code feat(qits-1): x} and a
 * person's free-text commit, each landing in its own class — and the guard's opt-in file read off
 * the branch, absent and then present.
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
  public void eachCommitLandsInOneClassAndTheNonComplyingAreListed() throws Exception {
    Repository repo = cloned("Commit Subjects Project");
    Path work = checkout(repo);
    String main = repo.mainBranch;
    // What the fixture already holds: free-text subjects by a person, so every one non-complying.
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
    commit(work, "x.txt", "feat(qits-1): x", "A Person", "person@example.com");
    git.exec(work.toFile(), "git", "push", "-q", "origin", main, "side");
    goCold(repo);

    JsonPath answer = read(repo.id, Map.of(), "qits:agent");

    assertEquals(repo.id, answer.getString("repositoryId"));
    assertEquals(main, answer.getString("branch"), "no branch asked for: the main branch");
    assertEquals(100, answer.getInt("limit"));
    assertFalse(answer.getBoolean("guardEnabled"), "no opt-in file on the branch");
    assertEquals(fixture + 5, answer.getInt("counts.total"));
    assertEquals(2, answer.getInt("counts.complying"), "feat(qits-1) and feat(qits-2)");
    assertEquals(2, answer.getInt("counts.exempt"));
    assertEquals(1, answer.getInt("counts.exemptMerge"), "the merge");
    assertEquals(1, answer.getInt("counts.exemptMachine"), "maintenance's free-text bump");
    assertEquals(fixture + 1, answer.getInt("counts.nonComplying"));

    List<Map<String, Object>> offenders = answer.getList("nonComplying");
    assertEquals(fixture + 1, offenders.size(), "the full non-complying listing");
    Map<String, Object> person = offenders.getFirst();
    assertEquals("Fix the thing quickly", person.get("subject"), "newest first: " + offenders);
    assertEquals("A Person", person.get("authorName"));
    assertEquals("person@example.com", person.get("authorEmail"));
    assertTrue(((String) person.get("hash")).length() == 40, person.toString());
    assertTrue(((String) person.get("hash")).startsWith((String) person.get("shortHash")));
    assertFalse(((String) person.get("date")).isBlank());
    List<String> subjects = offenders.stream().map(o -> (String) o.get("subject")).toList();
    assertFalse(subjects.contains("Merge branch 'side'"), "a merge is exempt: " + subjects);
    assertFalse(
        subjects.contains("bump(dependencies): 2 dependencies"), "a machine bump is exempt");
    assertEquals(List.of("qits-1", "qits-2"), answer.getList("qualifiedIds"));

    // Opting in is an ordinary commit carrying the guard's file; the read follows the branch.
    Files.createDirectories(work.resolve(".config/qits"));
    commit(work, ".config/qits/commit-subjects.yml", "chore(qits-1): opt into the guard",
        "A Person", "person@example.com", "# the receive guard\nenforce: true # on\n");
    git.exec(work.toFile(), "git", "push", "-q", "origin", main);
    goCold(repo);

    JsonPath optedIn = read(repo.id, Map.of("branch", main, "limit", "3"), "qits:system");
    assertTrue(optedIn.getBoolean("guardEnabled"), "enforce: true on the branch read");
    assertEquals(3, optedIn.getInt("limit"));
    assertEquals(3, optedIn.getInt("counts.total"), "limited to the newest three");
    assertEquals(2, optedIn.getInt("counts.complying"));
    assertEquals(1, optedIn.getInt("counts.exemptMachine"));
    assertEquals(0, optedIn.getInt("counts.nonComplying"));

    // A branch without the file reads false, whatever another branch says.
    JsonPath side = read(repo.id, Map.of("branch", "side"), "qits:admin");
    assertFalse(side.getBoolean("guardEnabled"));
    assertEquals("side", side.getString("branch"));
  }

  @Test
  public void aLimitOutsideOneToAThousandAndAnUnknownBranchAreRefused() throws Exception {
    Repository repo = cloned("Commit Subjects Refusals");
    for (String limit : new String[] {"0", "1001"}) {
      given()
          .header("X-Qits-User", "agent-test")
          .header("X-Qits-Roles", "qits:agent")
          .queryParam("limit", limit)
          .when()
          .get("/projects/api/repositories/" + repo.id + "/commit-subjects")
          .then()
          .statusCode(400);
    }
    given()
        .header("X-Qits-User", "agent-test")
        .header("X-Qits-Roles", "qits:agent")
        .queryParam("branch", "no-such-branch")
        .when()
        .get("/projects/api/repositories/" + repo.id + "/commit-subjects")
        .then()
        .statusCode(404);
    given()
        .header("X-Qits-User", "agent-test")
        .header("X-Qits-Roles", "qits:agent")
        .queryParam("branch", "-D")
        .when()
        .get("/projects/api/repositories/" + repo.id + "/commit-subjects")
        .then()
        .statusCode(400);
  }

  private JsonPath read(String repoId, Map<String, String> query, String role) {
    return given()
        .header("X-Qits-User", "subjects-test")
        .header("X-Qits-Roles", role)
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
