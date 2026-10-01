package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /projects/api/gc/tags} at the door: who may call it, what a body missing a pin source
 * gets, and that a dry run leaves the git host as it found it.
 *
 * <p>The judgement itself is {@code TagKeepRuleTest}'s and the deleting {@code TagCollectorTest}'s;
 * nothing here deletes, because a real run judges every repository in this suite's shared database.
 */
@QuarkusTest
public class GcApiTest {

  private static final String DOOR = "/projects/api/gc/tags";

  /** Six empty answers — well-formed and pinning nothing. */
  private static final String PINS =
      """
      {"deployments": {"pins": []}, "ciDaemon": {}, "dependencies": {"pins": []},
       "configuredImages": {"pins": []}, "workspaceLaunches": {"pins": []},
       "projectLaunches": {"pins": []}}
      """;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;

  private static RequestSpecification as(String user, String role) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", role);
  }

  private static String body(boolean dryRun, String pins) {
    return "{\"dryRun\": " + dryRun + ", \"pins\": " + pins + "}";
  }

  @Test
  public void anAdminAndAMachineGetInAndNobodyElse() {
    as("alice", "qits:admin")
        .body(body(true, PINS))
        .when()
        .post(DOOR)
        .then()
        .statusCode(200)
        .body("dryRun", is(true));
    as("dev-qits-orchestrator", "qits:system")
        .body(body(true, PINS))
        .when()
        .post(DOOR)
        .then()
        .statusCode(200);

    as("agent-qits-591", "qits:agent").body(body(true, PINS)).when().post(DOOR).then().statusCode(403);
    as("mallory", "qits:user").body(body(true, PINS)).when().post(DOOR).then().statusCode(403);
  }

  /** An absent source is not "it pins nothing" — reading it so would delete what it pins. */
  @Test
  public void aMissingOrMalformedPinSourceIsRefusedWhole() {
    as("alice", "qits:admin")
        .body("{\"dryRun\": false}")
        .when()
        .post(DOOR)
        .then()
        .statusCode(400)
        .body(containsString("pins is required"));

    as("alice", "qits:admin")
        .body(body(false, PINS.replace("\"ciDaemon\": {},", "")))
        .when()
        .post(DOOR)
        .then()
        .statusCode(400)
        .body(containsString("pins.ciDaemon"));

    as("alice", "qits:admin")
        .body(body(false, PINS.replace("\"ciDaemon\": {}", "\"ciDaemon\": null")))
        .when()
        .post(DOOR)
        .then()
        .statusCode(400)
        .body(containsString("pins.ciDaemon"));

    as("alice", "qits:admin")
        .body(body(false, PINS.replace("\"ciDaemon\": {}", "\"ciDaemon\": \"2026.930.1\"")))
        .when()
        .post(DOOR)
        .then()
        .statusCode(400)
        .body(containsString("pins.ciDaemon"));
  }

  @Test
  public void aDryRunDeletesNothing() throws Exception {
    Project project = projectService.create("Gc Dry Run", "gc-dry-run", null);
    Repository repo =
        repositoryService.cloneRepository(
            GitFixtures.path("testing-repo.git"), RepositoryArchetype.SERVICE, project);
    Path host = Path.of(gitHost.fetchUrl(repo.id));
    git.exec(
        host.toFile(),
        Map.of(
            "GIT_COMMITTER_DATE", "2025-10-01T00:00:00Z",
            "GIT_COMMITTER_NAME", "qits-test",
            "GIT_COMMITTER_EMAIL", "qits-test@local"),
        "git",
        "tag",
        "-a",
        "-m",
        "old",
        "2025.101.1",
        "HEAD");
    // Five younger releases, so 2025.101.1 is not among the newest and a real run would take it.
    for (int i = 1; i <= 5; i++) {
      git.exec(host.toFile(), "git", "tag", "2026.930." + i, "HEAD");
    }

    as("alice", "qits:admin")
        .body(body(true, PINS))
        .when()
        .post(DOOR)
        .then()
        .statusCode(200)
        .body("dryRun", is(true));

    assertFalse(
        git.exec(host.toFile(), "git", "tag", "--list", "2025.101.1").isBlank(),
        "a dry run leaves the tag where it was");
  }
}
