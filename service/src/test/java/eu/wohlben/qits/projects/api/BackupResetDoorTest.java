package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryBackupReset;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The backup twin reset at its door</b> (qits-1885): {@code POST
 * /projects/api/repositories/{repoId}/backup-reset} and its read, {@code GET …/backup-resets}.
 *
 * <p>With the dev user blanked, as {@code UpstreamOverrideDoorTest} is and for its reason: every
 * call states its caller. A person is a browser session (the cookie this service introspects) or a
 * person's CLI bearer; the desk, an agent and another project's desk are their bearers ({@link
 * ContractBearers}). The repository is a real blank on the suite's git host, and its twin a real
 * bare whose main is an unrelated root commit — the live case — so a 200 here really reset a twin.
 */
@QuarkusTest
public class BackupResetDoorTest {

  private static final String PERSON_CLI_BEARER = "Bearer backup-reset-person-cli";
  private static final String AGENT_BEARER = "Bearer backup-reset-agent";
  private static final String DESK_BEARER = "Bearer backup-reset-desk";
  private static final String OTHER_DESK_BEARER = "Bearer backup-reset-other-desk";
  private static final String SERVICE_CLIENT_BEARER = "Bearer backup-reset-service-client";

  private static final Map<String, String> IDENTITY =
      Map.of(
          "GIT_AUTHOR_NAME", "Forge",
          "GIT_AUTHOR_EMAIL", "forge@example.test",
          "GIT_COMMITTER_NAME", "Forge",
          "GIT_COMMITTER_EMAIL", "forge@example.test");

  @Inject ContractBearers bearers;
  @Inject ProjectService projectService;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;

  private String projectId;
  private String repoId;
  private String mainBranch;
  private Path twin;
  private String seed;

  @BeforeEach
  void seed() throws Exception {
    bearers.reset();
    String token = UUID.randomUUID().toString().substring(0, 8);
    Project project = projectService.create("Backup Reset", "backup-reset-" + token, null);
    projectId = project.id;
    Repository created =
        projectService
            .createRepository(projectId, null, "reset-service", RepositoryArchetype.SERVICE)
            .repository();
    repoId = created.id;
    twin = Files.createTempDirectory("backup-reset-door").resolve("reset-service.git");
    mainBranch =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository row = Repository.findById(repoId);
                  row.url = twin.toString();
                  return row.mainBranch == null ? "main" : row.mainBranch;
                });
    seed = seedUnrelatedTwin();

    bearers.grantPerson(PERSON_CLI_BEARER, "wohlben");
    bearers.grant(
        AGENT_BEARER,
        "agent-client",
        Map.of("context_kind", "agent", "project", projectId, "groups", List.of("qits:agent")),
        "qits:agent");
    bearers.grant(
        SERVICE_CLIENT_BEARER,
        "dev-qits-maintenance",
        Map.of("groups", List.of("clients/dev-qits-maintenance", "qits:system")),
        "qits:system");
    grantDesk(DESK_BEARER, projectId);
    grantDesk(OTHER_DESK_BEARER, "another-project-" + token);
  }

  @AfterEach
  void drop() {
    bearers.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> RepositoryBackupReset.delete("repositoryId = ?1", repoId));
  }

  /** A forge's README-only first commit, unrelated to anything the git host holds. */
  private String seedUnrelatedTwin() throws Exception {
    Path work = Files.createTempDirectory("backup-reset-door-work");
    git.exec(twin.getParent().toFile(), "git", "init", "-q", "--bare", twin.toString());
    git.exec(work.toFile(), "git", "init", "-q", "-b", mainBranch);
    Files.writeString(work.resolve("README.md"), "# reset-service\n", StandardCharsets.UTF_8);
    git.exec(work.toFile(), "git", "add", "README.md");
    git.exec(work.toFile(), IDENTITY, "git", "commit", "-q", "-m", "Initial commit");
    git.exec(work.toFile(), "git", "push", "-q", twin.toString(), mainBranch + ":" + mainBranch);
    return twinMain();
  }

  private String twinMain() throws Exception {
    return git.exec(twin.toFile(), "git", "rev-parse", "refs/heads/" + mainBranch).trim();
  }

  private String hostMain() throws Exception {
    return git.exec(
            Path.of(gitHost.fetchUrl(repoId)).toFile(),
            "git",
            "rev-parse",
            "refs/heads/" + mainBranch)
        .trim();
  }

  private void grantDesk(String bearer, String project) {
    String subject = "desk-of-" + project;
    bearers.grant(
        bearer,
        subject,
        Map.of(
            "context_kind",
            "desk-delivery",
            "project",
            project,
            "groups",
            List.of("qits:agent", "qits:desk", "clients/" + subject)),
        "qits:agent",
        "qits:desk");
  }

  private static RequestSpecification as(String bearer) {
    return given().contentType(ContentType.JSON).header("Authorization", bearer);
  }

  private static RequestSpecification person(String name) {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller(name, "qits:admin"))
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(name));
  }

  private String door() {
    return "/projects/api/repositories/" + repoId + "/backup-reset";
  }

  private static final String BODY = "{\"reason\":\"GitHub seeded the twin with a README\"}";

  @Test
  public void aPersonResetsTheUnrelatedTwinAndTheResetIsListed() throws Exception {
    String ours = hostMain();

    JsonPath answer =
        person("wohlben")
            .body(BODY)
            .post(door())
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals(repoId, answer.getString("repositoryId"));
    assertEquals("UNRELATED_RESET", answer.getString("kind"));
    assertEquals(seed, answer.getString("twinShaBefore"));
    assertEquals(ours, answer.getString("pushedSha"));
    assertEquals("wohlben", answer.getString("decidedBy"));
    assertNotEquals(null, answer.getString("decidedAt"));
    assertEquals(ours, twinMain(), "the twin's main is ours now");

    JsonPath listed =
        as(AGENT_BEARER)
            .get("/projects/api/repositories/" + repoId + "/backup-resets")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(List.of("UNRELATED_RESET"), listed.getList("resets.kind"));
    assertEquals(List.of("GitHub seeded the twin with a README"), listed.getList("resets.reason"));
  }

  @Test
  public void aPersonsCliTokenCountsToo() throws Exception {
    as(PERSON_CLI_BEARER).body(BODY).post(door()).then().statusCode(200);
    assertEquals(hostMain(), twinMain());
  }

  @Test
  public void theProjectsOwnDeskResetsAsItself() throws Exception {
    JsonPath answer =
        as(DESK_BEARER).body(BODY).post(door()).then().statusCode(200).extract().jsonPath();

    assertEquals("desk-of-" + projectId, answer.getString("decidedBy"));
    assertEquals(hostMain(), twinMain());
  }

  @Test
  public void anotherProjectsDeskAnAgentAndMachinesAreRefused() throws Exception {
    for (String bearer : List.of(OTHER_DESK_BEARER, AGENT_BEARER, SERVICE_CLIENT_BEARER)) {
      as(bearer).body(BODY).post(door()).then().statusCode(403);
    }
    // An administrator asserted by headers alone, with no session or token behind it, is nobody.
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("mallory", "qits:admin"))
        .body(BODY)
        .post(door())
        .then()
        .statusCode(403);

    assertEquals(seed, twinMain(), "the twin is untouched");
    assertEquals(0L, resets(), "nothing recorded");
  }

  @Test
  public void aBlankOrMissingReasonIsABadRequest() throws Exception {
    person("wohlben").body("{\"reason\":\"   \"}").post(door()).then().statusCode(400);
    person("wohlben").body("{}").post(door()).then().statusCode(400);
    assertEquals(seed, twinMain());
  }

  @Test
  public void anUnknownRepositoryIsNotFound() {
    person("wohlben")
        .body(BODY)
        .post("/projects/api/repositories/no-such-repository/backup-reset")
        .then()
        .statusCode(404);
    as(AGENT_BEARER)
        .get("/projects/api/repositories/no-such-repository/backup-resets")
        .then()
        .statusCode(404);
  }

  @Test
  public void aRepositoryWithNoTwinIsAConflict() {
    QuarkusTransaction.requiringNew()
        .run(() -> Repository.<Repository>findById(repoId).url = null);

    person("wohlben").body(BODY).post(door()).then().statusCode(409);
    assertEquals(0L, resets());
  }

  @Test
  public void aTwinAheadOfOursIsAConflictAndUntouched() throws Exception {
    // The twin takes our main and then one commit of its own on top: it holds work we do not.
    String ours = hostMain();
    git.exec(
        twin.toFile(),
        "git",
        "fetch",
        "-q",
        gitHost.fetchUrl(repoId),
        "+refs/heads/" + mainBranch + ":refs/heads/" + mainBranch);
    String tree = git.exec(twin.toFile(), "git", "rev-parse", ours + "^{tree}").trim();
    String ahead =
        git.exec(twin.toFile(), IDENTITY, "git", "commit-tree", tree, "-p", ours, "-m", "forge-only")
            .trim();
    git.exec(twin.toFile(), "git", "update-ref", "refs/heads/" + mainBranch, ahead);

    String message =
        person("wohlben").body(BODY).post(door()).then().statusCode(409).extract().asString();

    assertEquals(true, message.contains(ahead) && message.contains(ours), message);
    assertEquals(ahead, twinMain());
    assertNull(
        QuarkusTransaction.requiringNew()
            .call(() -> Repository.<Repository>findById(repoId).lastBackupOutcome));
  }

  private long resets() {
    return QuarkusTransaction.requiringNew()
        .call(() -> RepositoryBackupReset.count("repositoryId = ?1", repoId));
  }
}
