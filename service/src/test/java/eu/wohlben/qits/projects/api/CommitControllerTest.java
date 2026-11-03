package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.projects.control.GitExecutor;
import eu.wohlben.qits.projects.control.GitHostAddress;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class CommitControllerTest {

  @Inject GitHostAddress gitHost;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitExecutor git;

  private final String fixtureUrl;

  public CommitControllerTest() throws Exception {
    fixtureUrl = GitFixtures.path("testing-repo.git");
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRequest(
                    "Repo Project", null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.projects.api.ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null, eu.wohlben.qits.projects.entity.RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  @Test
  public void testCommitsForPlainBranchUseMainBranchAsParent() {
    String repoId = createProjectAndRepository();

    // "feature" is a plain branch (no workspace), so it is compared against the main branch
    // (master). Only the single commit unique to "feature" is returned.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "feature")
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("feature"))
        .body("parent", equalTo("master"))
        .body("commits.size()", equalTo(1))
        .body("commits[0].message", equalTo("Add feature.txt"))
        .body("commits[0].hash", not(emptyOrNullString()))
        .body("commits[0].shortHash", not(emptyOrNullString()))
        .body("commits[0].author", not(emptyOrNullString()))
        .body("commits[0].date", not(emptyOrNullString()))
        // the paths the commit changed, parsed from `git log --name-only`
        .body("commits[0].files", hasItem("feature.txt"));
  }

  @Test
  public void testCommitsForMainBranchFallBackToFullHistory() {
    String repoId = createProjectAndRepository();

    // The main branch's parent resolves to itself, so the range degrades to the full history.
    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "master")
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("master"))
        .body("parent", nullValue())
        .body("commits.size()", equalTo(3));
  }

  // SEAM (migration-plan.md §6): testCommitsForWorkspaceBranchUseWorkspaceParent is not carried over — it POSTed a workspace
  // through the (now qits-workspaces') /repositories/{id}/workspaces route to give the branch a
  // parent, then asserted the commit log was scoped to it. The parent lookup is the optional
  // WorkspaceLookup port now, and no route here can create a workspace. UNOWNED.
  @Test
  public void testCommitsRejectFlagLikeBranchName() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .queryParam("branch", "-D")
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitsRequireBranchParam() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitChangesNoParentListsFilesChangedInCommit() throws Exception {
    String repoId = createProjectAndRepository();

    // Without a parent the changes are computed against the commit's own first parent: the
    // "Add feature.txt" commit only added feature.txt. That parent is answered resolved, as the sha
    // of "Add hello.txt" the fixture forked feature from; the commit is echoed as given.
    String firstParent = revParse(fixtureUrl, "feature^1");
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/feature/changes")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commit", equalTo("feature"))
        .body("parent", equalTo(firstParent))
        .body("files.size()", equalTo(1))
        .body("files[0].path", equalTo("feature.txt"))
        .body("files[0].changeType", equalTo("ADDED"))
        .body("files[0].oldPath", nullValue());
  }

  /**
   * <b>A merge commit read with no parent</b> (qits-1793). {@code git diff-tree --root <merge>}
   * prints nothing, so the endpoint used to answer {@code files: []} for a merge however much it
   * changed. It is diffed against its first parent now, and names that parent.
   */
  @Test
  public void testCommitChangesNoParentOnMergeCommitDiffsAgainstFirstParent() throws Exception {
    String repoId = createProjectAndRepository();
    String[] mergeAndFirstParent = pushMergeOfFeatureIntoMaster(repoId);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/merged/changes")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commit", equalTo("merged"))
        .body("parent", equalTo(mergeAndFirstParent[1]))
        .body("files.size()", equalTo(1))
        .body("files[0].path", equalTo("feature.txt"))
        .body("files[0].changeType", equalTo("ADDED"));
  }

  /** The same merge's patch, against the same first parent rather than an empty {@code diff}. */
  @Test
  public void testCommitFileDiffNoParentOnMergeCommitDiffsAgainstFirstParent() throws Exception {
    String repoId = createProjectAndRepository();
    pushMergeOfFeatureIntoMaster(repoId);

    given()
        .contentType(ContentType.JSON)
        .queryParam("path", "feature.txt")
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/merged/diff")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("path", equalTo("feature.txt"))
        .body("changeType", equalTo("ADDED"))
        .body("diff", containsString("+feature work"));
  }

  /** A root commit has no first parent: the empty tree is the base and {@code parent} is null. */
  @Test
  public void testCommitChangesNoParentOnRootCommitListsAddedFilesAndNullParent() throws Exception {
    String repoId = createProjectAndRepository();
    String root = revParse(fixtureUrl, "master~2");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/" + root + "/changes")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commit", equalTo(root))
        .body("parent", nullValue())
        .body("files.size()", equalTo(1))
        .body("files[0].path", equalTo("README.md"))
        .body("files[0].changeType", equalTo("ADDED"));
  }

  @Test
  public void testCommitChangesWithExplicitParentRebasesDiff() {
    String repoId = createProjectAndRepository();

    // Diffing feature against master surfaces both feature.txt (added) and README.md (modified,
    // since master advanced the README after feature forked).
    given()
        .contentType(ContentType.JSON)
        .queryParam("parent", "master")
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/feature/changes")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("parent", equalTo("master"))
        .body("files.size()", equalTo(2))
        .body("files.path", hasItems("README.md", "feature.txt"));
  }

  @Test
  public void testCommitFileDiffReturnsUnifiedPatch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .queryParam("path", "feature.txt")
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/feature/diff")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("path", equalTo("feature.txt"))
        .body("changeType", equalTo("ADDED"))
        .body("diff", containsString("+feature work"))
        .body("diff", containsString("diff --git"));
  }

  @Test
  public void testCommitChangesRejectFlagLikeCommit() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/-D/changes")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitFileDiffRejectFlagLikePath() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .queryParam("path", "-rf")
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/feature/diff")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitFileDiffRequiresPathParam() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/" + repoId + "/commits/feature/diff")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCommitChangesUnknownRepoReturns404() {
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/projects/api/repositories/does-not-exist/commits/feature/changes")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /** {@code rev} resolved to a full sha in the git directory {@code gitDir}. */
  private String revParse(String gitDir, String rev) throws Exception {
    return git.exec(new File(gitDir), "git", "rev-parse", "--verify", rev + "^{commit}").trim();
  }

  /**
   * Pushes a branch {@code merged} to the repository's bare on the git host: a {@code --no-ff}
   * merge of {@code feature} into {@code master}, whose first parent is master's head and which
   * therefore adds {@code feature.txt} against it. Drops the mirror so the read re-clones it — a
   * mirror fetched inside the freshness window is trusted rather than refreshed. Answers {@code
   * [merge, first parent]}.
   */
  private String[] pushMergeOfFeatureIntoMaster(String repoId) throws Exception {
    Path parent = Files.createTempDirectory("commit-merge");
    parent.toFile().deleteOnExit();
    git.exec(parent.toFile(), "git", "clone", "-q", gitHost.fetchUrl(repoId), "work");
    File work = parent.resolve("work").toFile();
    git.exec(work, "git", "config", "user.email", "fixtures@qits.local");
    git.exec(work, "git", "config", "user.name", "qits fixtures");
    git.exec(work, "git", "checkout", "-q", "-b", "merged", "origin/master");
    git.exec(work, "git", "merge", "-q", "--no-ff", "-m", "Merge feature", "origin/feature");
    git.exec(work, "git", "push", "-q", "origin", "merged");
    String merge = git.exec(work, "git", "rev-parse", "HEAD").trim();
    String firstParent = git.exec(work, "git", "rev-parse", "HEAD^1").trim();

    Path mirror = gitMirrors.of(repoId).gitDir();
    if (Files.exists(mirror)) {
      try (var paths = Files.walk(mirror)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
    return new String[] {merge, firstParent};
  }
}
