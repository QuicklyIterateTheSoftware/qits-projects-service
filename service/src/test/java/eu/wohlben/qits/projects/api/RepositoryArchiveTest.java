package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.RepositoryArchive;
import eu.wohlben.qits.projects.control.RepositoryArchivedEdit;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.dto.RepositoryArchiveDto;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.releasehost.FakeActiveBuilds;
import eu.wohlben.qits.projects.releasehost.RecordingBackingBranchMerger;
import eu.wohlben.qits.projects.releasehost.RecordingReleaseExecutor;
import eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The archive action (qits-1784) at its doors: {@code POST /repositories/{repoId}/archive} and
 * {@code …/unarchive}. The git host and the merge door are the suite's recording fakes, the release
 * request is the real {@code ReleaseRequests.ask} over real rows, held open by {@link
 * FakeActiveBuilds} so nothing settles mid-test. The desk's binding reads its token's claims, which
 * a {@code @QuarkusTest} cannot put in front of the service, so those cases drive the controller
 * directly ({@code ReleaseRequestAgentBoundsTest}'s split).
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class RepositoryArchiveTest {

  private static final String CONFIG = RepositoryArchivedEdit.PATH;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseExecutor executor;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RepositoryService repositoryService;

  @Inject RepositoryArchive repositoryArchive;

  private String projectId;
  private String repoId;
  private String name;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    activeBuilds.answer(Optional.of(1));
    String token = UUID.randomUUID().toString().substring(0, 8);
    projectId = "archive-" + token;
    repoId = "archive-repo-" + UUID.randomUUID();
    name = "archive-" + token + "-service";
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
              RepositoryName alias = new RepositoryName();
              alias.project = project;
              alias.repository = repository;
              alias.name = name;
              alias.persist();
            });
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId = ?1", projectId));
  }

  /** main holds {@code content} at the config path (null: no file), beside the gating release.yml. */
  private void mainHolds(String content) {
    Map<String, String> files = new LinkedHashMap<>();
    if (content != null) {
      files.put(CONFIG, content);
    }
    gitHost.gatedTreeFor(repoId, "refs/heads/main", files);
  }

  /** The merge door creates {@code branch} at {@code sha}, whose tree is main's. */
  private String branchCreatedAt(String branch, String content) {
    String sha = RecordingBackingBranchMerger.freshSha();
    merger.answerFor(
        "refs/heads/" + branch, BackingBranchMerger.Outcome.fastForward(sha, List.of(sha)));
    Map<String, String> files = new LinkedHashMap<>(RecordingReleaseGitHost.GATED_MAIN);
    if (content != null) {
      files.put(CONFIG, content);
    }
    gitHost.tree(sha, files);
    return sha;
  }

  private String door(String verb) {
    return "/projects/api/repositories/" + repoId + "/" + verb;
  }

  @Test
  void archiveCommitsOnABranchAndOpensItsReleaseRequest() {
    String original = "version: 1\nrepository:\n  main-branch: main\n";
    mainHolds(original);
    String branch = "archive/" + name;
    String tip = branchCreatedAt(branch, original);

    io.restassured.path.json.JsonPath answer =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post(door("archive"))
            .then()
            .statusCode(202)
            .body("outcome", equalTo("requested"))
            .body("archived", equalTo(true))
            .body("branch", equalTo(branch))
            .body("commit", notNullValue())
            .body("releaseRequestId", notNullValue())
            .body("qualifiedId", equalTo(name + "-rr-1"))
            .extract()
            .jsonPath();
    String commit = answer.getString("commit");

    // The branch was made through the merge door, from main and from nothing else.
    List<RecordingBackingBranchMerger.Fold> created = merger.foldsOf("refs/heads/" + branch);
    assertEquals(1, created.size());
    assertEquals(List.of("refs/heads/main"), created.get(0).sources());
    // One commit, on the branch, pinned to its tip, with the exact subject and the edited file.
    List<RecordingReleaseGitHost.Commit> commits =
        gitHost.commits().stream().filter(c -> c.ref().equals("refs/heads/" + branch)).toList();
    assertEquals(1, commits.size());
    RecordingReleaseGitHost.Commit written = commits.get(0);
    assertEquals(commit, written.sha());
    assertEquals(tip, written.parent());
    assertEquals("chore(platform): archive " + name, written.message());
    assertEquals(
        Map.of(CONFIG, "version: 1\nrepository:\n  archived: true\n  main-branch: main\n"),
        written.files());
    // The branch is a source of the release request the answer names.
    String requestId = answer.getString("releaseRequestId");
    given()
        .when()
        .get("/projects/api/repositories/" + repoId + "/release-requests/" + requestId)
        .then()
        .statusCode(200)
        .body("request.qualifiedId", equalTo(name + "-rr-1"))
        .body("request.sources.name", org.hamcrest.Matchers.hasItem(branch));
  }

  @Test
  void anAbsentFileIsCreatedOnTheBranch() {
    mainHolds(null);
    String branch = "archive/" + name;
    branchCreatedAt(branch, null);

    given().when().post(door("archive")).then().statusCode(202).body("branch", equalTo(branch));

    RecordingReleaseGitHost.Commit written = gitHost.commits().get(gitHost.commits().size() - 1);
    assertTrue(written.files().get(CONFIG).contains("repository:\n  archived: true\n"));
  }

  @Test
  void aBranchAlreadyCarryingTheEditCommitsNothingAndStillAsks() {
    mainHolds("repository:\n  main-branch: main\n");
    String branch = "archive/" + name;
    String tip = branchCreatedAt(branch, "repository:\n  archived: true\n  main-branch: main\n");

    given()
        .when()
        .post(door("archive"))
        .then()
        .statusCode(202)
        .body("commit", equalTo(tip))
        .body("qualifiedId", equalTo(name + "-rr-1"));

    assertTrue(
        gitHost.commits().stream().noneMatch(c -> c.ref().equals("refs/heads/" + branch)));
  }

  @Test
  void unarchiveRemovesTheLineOnItsOwnBranch() {
    String archived = "version: 1\nrepository:\n  archived: true\n  main-branch: main\n";
    mainHolds(archived);
    String branch = "unarchive/" + name;
    branchCreatedAt(branch, archived);

    given()
        .when()
        .post(door("unarchive"))
        .then()
        .statusCode(202)
        .body("outcome", equalTo("requested"))
        .body("archived", equalTo(false))
        .body("branch", equalTo(branch));

    RecordingReleaseGitHost.Commit written = gitHost.commits().get(gitHost.commits().size() - 1);
    assertEquals("chore(platform): unarchive " + name, written.message());
    assertEquals(Map.of(CONFIG, "version: 1\nrepository:\n  main-branch: main\n"), written.files());
  }

  @Test
  void alreadyInTheTargetStateAnswers200AndOpensNothing() {
    mainHolds("repository:\n  archived: true\n");

    given()
        .when()
        .post(door("archive"))
        .then()
        .statusCode(200)
        .body("outcome", equalTo("unchanged"))
        .body("branch", nullValue())
        .body("releaseRequestId", nullValue());
    mainHolds(null);
    given().when().post(door("unarchive")).then().statusCode(200).body("outcome", equalTo("unchanged"));

    assertTrue(merger.folds().isEmpty());
    assertTrue(gitHost.commits().isEmpty());
    assertEquals(
        0L,
        (long)
            QuarkusTransaction.requiringNew()
                .call(() -> ReleaseRequest.count("projectId = ?1", projectId)));
  }

  @Test
  void aFileThatCannotBeEditedSafelyIs422AndWritesNothing() {
    mainHolds("version: 1\nrepository: {main-branch: main}\n");

    given()
        .when()
        .post(door("archive"))
        .then()
        .statusCode(422)
        .body("error", equalTo(RepositoryArchivedEdit.REFUSED));

    assertTrue(merger.folds().isEmpty());
    assertTrue(gitHost.commits().isEmpty());
  }

  @Test
  void aBranchThatConflictsWithMainIs409() {
    mainHolds("repository:\n  main-branch: main\n");
    merger.answerFor(
        "refs/heads/archive/" + name,
        BackingBranchMerger.Outcome.conflict(
            "refs/heads/archive/" + name,
            List.of(new BackingBranchMerger.Conflict(CONFIG, "refs/heads/main", "a".repeat(40), "content"))));

    given().when().post(door("archive")).then().statusCode(409);

    assertTrue(gitHost.commits().isEmpty());
  }

  @Test
  void anUnreachableGitHostIs502() {
    mainHolds("repository:\n  main-branch: main\n");
    merger.answerFor(
        "refs/heads/archive/" + name,
        BackingBranchMerger.Outcome.unreachable("qits-githost answered 500: boom"));

    given().when().post(door("archive")).then().statusCode(502);
  }

  @Test
  void anUnknownRepositoryIs404() {
    given().when().post("/projects/api/repositories/no-such-repo/archive").then().statusCode(404);
  }

  @Test
  void anAgentIsRefused() {
    mainHolds("repository:\n  main-branch: main\n");

    for (String verb : List.of("archive", "unarchive")) {
      given()
          .header("Authorization", ContractBearers.caller("someone", "qits:agent"))
          .contentType(ContentType.JSON)
          .when()
          .post(door(verb))
          .then()
          .statusCode(403);
    }
    assertTrue(merger.folds().isEmpty());
  }

  @Test
  void theDeskActsInItsOwnProjectOnly() {
    mainHolds("repository:\n  main-branch: main\n");
    branchCreatedAt("archive/" + name, "repository:\n  main-branch: main\n");

    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> controller(desk("another-project")).archive(repoId));
    assertEquals(403, refused.statusCode());
    assertTrue(merger.folds().isEmpty());

    jakarta.ws.rs.core.Response own = controller(desk(projectId)).archive(repoId);
    assertEquals(202, own.getStatus());
    RepositoryArchiveDto answer = (RepositoryArchiveDto) own.getEntity();
    assertEquals("archive/" + name, answer.branch());
    assertFalse(answer.qualifiedId() == null);
  }

  private static SecurityIdentity desk(String project) {
    return AgentTokens.token(
        Map.of("project", project, "sub", "desk-delivery-" + project),
        "qits:agent",
        "qits:desk",
        "qits:desk-admin");
  }

  private RepositoryController controller(SecurityIdentity caller) {
    RepositoryController door = new RepositoryController();
    door.repositoryService = repositoryService;
    door.repositoryArchive = repositoryArchive;
    door.identity = caller;
    return door;
  }
}
