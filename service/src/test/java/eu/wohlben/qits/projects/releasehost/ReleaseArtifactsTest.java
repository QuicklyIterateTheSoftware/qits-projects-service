package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What a release published, read out of the released tag's own tree.</b>
 *
 * <p>The tree is the source of every answer here, and that is the claim worth pinning: the
 * declaration is a file in the repository at the tag, so this endpoint answers for a release whose
 * CI announced nothing, for one made before the endpoint existed, and for a repository that
 * publishes nothing at all. Nothing about a build's record is consulted, which is why none of these tests stage one.
 *
 * <p>The second claim is that <b>none of it is an error</b>. Not released, a tag the host cannot
 * read, a declaration that will not parse — each is a 200 carrying a sentence, because the page
 * asking this question is drawing a panel and "we could not ask" is a thing it can say.
 *
 * <p>The third is about <b>which</b> file at the tag is read, and there is one: {@code
 * .config/qits/release.yml}. A tag cut before its repository migrated carries the two retired
 * pipeline files instead and always will, because a tree is immutable — and since 2026-09-18 neither
 * is read, so such a tag answers the empty list rather than what its recipe declared. That is a real
 * loss and it is pinned below rather than left to be discovered, beside the case it was traded for:
 * one reader, of one file, in a repository where the pipeline files no longer exist.
 */
@QuarkusTest
public class ReleaseArtifactsTest {

  @Inject RecordingReleaseGitHost gitHost;

  private static final String VERSION = "2026.904.161524";
  private static final String RELEASED_SHA = "9f1c2b3d4e5f60718293a4b5c6d7e8f901234567";
  private static final String MERGED_SHA = "20c377ee71fabe6f32429d1506989efecec7798b";

  private static final String CONFIG = ".config/qits/release.yml";
  private static final String DEPLOYMENTS = ".config/qits/deployments.yml";

  /**
   * The two retired pipeline files. They are still spelled here, and only here, because a tag cut
   * before the migration carries them for ever and the point of the two tests that stage them is
   * that <b>nothing reads them</b>.
   */
  private static final String RETIRED_RELEASE_RECIPE = ".config/qits/ci-event-release.yml";

  private static final String RETIRED_QA_RECIPE = ".config/qits/ci-event-release-request.yml";

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    gitHost.reset();
    repoId = "artifacts-repo-" + UUID.randomUUID();
    projectId = "artifacts-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "artifacts";
              project.slug = "artifacts-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /**
   * Nothing of this fixture may outlive the class: the finalization sweep walks every ungated row in
   * the database, so a released tag left behind is a git-host call inside somebody else's test.
   */
  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  /**
   * The whole of what tells a service apart from a library here, and it is one file's presence — the
   * same reading {@code ReleaseFinalization} forks the publish phase on, so the two can never
   * disagree about what "deploys" means.
   */
  @Test
  public void aReleasedTreeDeclaringADeploymentIsDeployable() {
    String id = release();
    tree(DEPLOYMENTS, "pom.xml");

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("deployable", equalTo(true))
        .body("version", equalTo(VERSION))
        .body("releasedSha", equalTo(RELEASED_SHA));
  }

  /** And the other side of the same one file: a library declares none, so nothing deploys it. */
  @Test
  public void aReleasedTreeDeclaringNoDeploymentIsNot() {
    String id = release();
    tree("pom.xml", "README.md");

    given().get(artifactsOf(id)).then().statusCode(200).body("deployable", equalTo(false));
  }

  /**
   * <b>A repository with no declaration published nothing, and that is an answer rather than a
   * problem.</b> Every SPA on this platform is in exactly this case, so a sentence on {@code detail}
   * here would turn the ordinary outcome into a warning on half the release pages.
   */
  @Test
  public void aRepositoryThatDeclaresNothingAnswersEmptyWithNothingToExplain() {
    String id = release();
    tree("package.json", "angular.json", "src/main.ts");

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts", hasSize(0))
        .body("detail", nullValue())
        .body("deployable", equalTo(false));
  }

  /**
   * A repository that declares no bundle contributes none, which is most repositories — and {@code
   * userflows: false} is the same answer as an absent key, said out loud.
   */
  @Test
  public void aDeclarationThatPublishesNoBundleAddsNothing() {
    String id = release();
    tree(
        Map.of(
            CONFIG,
            """
            archetype: java-service
            artifacts:
              - { type: docker, name: qits/qits-thing }
            userflows: false
            """));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts", hasSize(1))
        .body("artifacts.name", contains("qits/qits-thing"));
  }

  /**
   * <b>The release configuration is the same declaration in a file that is no longer a pipeline.</b>
   * Its {@code artifacts:} block is read entry for entry as the recipe's was, and the keys around it
   * — the archetype it names, the two step slots qits-ci composes from, and a per-artifact {@code
   * sbom:} path — are passed over rather than refused. This reader owns one question and must not
   * fail a panel over a key the composer added.
   *
   * <p>{@code userflows: true} is the other half: it says the bundle is published under this
   * repository's own name, which is what a composed pipeline has to publish to, since the archetype
   * has nothing but {@code QITS_CI_REPO_NAME} to interpolate.
   */
  @Test
  public void aReleaseConfigurationDeclaresTheArtifactsAndItsUserflowBundleByFlag() {
    String id = release();
    tree(
        Map.of(
            DEPLOYMENTS,
            "resources: []\n",
            CONFIG,
            """
            archetype: java-service
            release-request:
              - image: qits/build-images/maven-base:latest
                script: ./mvnw verify
            release:
              - image: qits/build-images/node-docker-base:latest
                build: true
                script: buildctl build --opt target=binary
            artifacts:
              - { type: docker, name: qits/qits-thing, sbom: .sbom/sbom.json }
              - { type: maven, name: eu.wohlben.qits:qits-thing-domain }
            userflows: true
            """));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("detail", nullValue())
        .body("deployable", equalTo(true))
        .body("artifacts.type", contains("docker", "maven", "userflows"))
        .body(
            "artifacts.name",
            contains(
                "qits/qits-thing",
                "eu.wohlben.qits:qits-thing-domain",
                "@userflows/qits-thing-service"))
        .body("artifacts.version", contains(VERSION, VERSION, MERGED_SHA));
  }

  /**
   * <b>A named site is why the key is not only a flag.</b> The repository and its bundle genuinely
   * differ — {@code qits-projects-service} publishes {@code @userflows/qits-projects} — and stating
   * the site is what the substring hunt through the QA recipe used to have to discover. Phase 3's
   * archetypes publish to exactly this name.
   */
  @Test
  public void aReleaseConfigurationCanNameTheSiteItsBundleIsPublishedUnder() {
    String id = release();
    tree(
        Map.of(
            CONFIG,
            """
            artifacts:
              - { type: docker, name: qits/qits-thing }
            userflows: qits-thing
            """));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts.type", contains("docker", "userflows"))
        .body("artifacts.name", contains("qits/qits-thing", "@userflows/qits-thing"))
        .body("artifacts.version", contains(VERSION, MERGED_SHA));
  }

  /**
   * <b>The configuration is the whole answer, and a retired recipe beside it is not consulted.</b> A
   * repository migrating in one commit can leave one at a tag — qits-ci skips it with a WARN on the
   * pipeline side — and the two files were never written to agree, so answering out of both would
   * publish an artifact list no release ever produced. That was a precedence rule while both were
   * read; since 2026-09-18 the old file has no reader at all, and this test is what says so,
   * userflow line included.
   */
  @Test
  public void aTagCarryingARetiredRecipeBesideItsConfigurationIgnoresTheRecipe() {
    String id = release();
    tree(
        Map.of(
            CONFIG,
            "artifacts:\n  - { type: docker, name: qits/qits-thing-composed }\n",
            RETIRED_RELEASE_RECIPE,
            "event: SCMRelease\nartifacts:\n  - { type: docker, name: qits/qits-thing-legacy }\n",
            RETIRED_QA_RECIPE,
            """
            event: ReleaseRequestChanged
            steps:
              - script: |
                  curl -X PUT "$QITS_DOCS_URL/@userflows/qits-thing/-/$QITS_CI_SHA"
            """));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("detail", nullValue())
        .body("artifacts", hasSize(1))
        .body("artifacts.name", contains("qits/qits-thing-composed"));
  }

  /**
   * <b>The price of the removal, pinned rather than discovered.</b> A tag cut before its repository
   * migrated carries the two retired pipeline files and no {@code release.yml}, and a tree is
   * immutable, so it is in that state for ever. It used to be answered out of those files — the
   * artifact list from one, the userflow bundle hunted out of a shell line in the other. It is not
   * any more.
   *
   * <p><b>The answer is the empty list with no {@code detail}</b>, which is deliberately the
   * <em>same</em> answer a repository that publishes nothing gets rather than a sentence saying the
   * declaration is unreadable: nothing failed, and the only honest thing to say about a file this
   * service has stopped reading is nothing. What was traded for it is the whole of
   * {@code ReleaseArtifacts} having one reader of one file — and the estate carries no repository
   * whose {@code main} still holds either of these, so only tags cut before the migration are
   * affected.
   */
  @Test
  public void aTagPredatingTheMigrationAnswersEmptyBecauseItsRecipesAreNoLongerRead() {
    String id = release();
    tree(
        Map.of(
            RETIRED_RELEASE_RECIPE,
            "event: SCMRelease\nartifacts:\n  - { type: docker, name: qits/qits-thing-legacy }\n",
            RETIRED_QA_RECIPE,
            """
            event: ReleaseRequestChanged
            steps:
              - script: |
                  curl -X PUT "$QITS_DOCS_URL/@userflows/qits-thing/-/$QITS_CI_SHA"
            """));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts", hasSize(0))
        .body("detail", nullValue());
  }

  /**
   * A configuration that will not parse is said out loud for the reason the recipe's is: the
   * difference between "this published nothing" and "we cannot read what it published" is the whole
   * reason {@code detail} exists. Still a 200 — a repository's file is not this service's error.
   */
  @Test
  public void aReleaseConfigurationThatWillNotParseIsASentenceAndNeverAShorterList() {
    String id = release();
    tree(Map.of(CONFIG, "archetype: java-service\nartifacts: a-string-is-not-a-list\n"));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("artifacts", hasSize(0))
        .body("detail", containsString("release declaration"));
  }

  /**
   * The question is asked of every request a page draws, so a request that has not released has to
   * have an answer — and "nothing yet" is one. A 404 or a 409 here would make the panel's ordinary
   * state an error.
   */
  @Test
  public void anUnreleasedRequestIsToldSoRatherThanRefused() {
    String id = pending();

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("version", nullValue())
        .body("releasedSha", nullValue())
        .body("deployable", equalTo(false))
        .body("artifacts", hasSize(0))
        .body("detail", equalTo("Not released yet"));
  }

  /**
   * A tag the git host cannot read is a fact about the moment and not about the release, so the
   * version still travels and the reason is on the answer. Never a 500: this read sits behind a
   * panel, not behind a decision.
   */
  @Test
  public void aTagTheGitHostCannotReadAnswersWithItsOwnWords() {
    String id = release();
    gitHost.failTreeWith(ReleaseGitHost.Answer.failedRetryable("qits-githost answered 503"));

    given()
        .get(artifactsOf(id))
        .then()
        .statusCode(200)
        .body("version", equalTo(VERSION))
        .body("artifacts", hasSize(0))
        .body("detail", containsString("503"));
  }

  /** The scope is part of the address: another repository's route does not answer for this one. */
  @Test
  public void aRequestReadThroughTheWrongRepositoryIsNotFound() {
    String id = release();

    given()
        .get("/projects/api/repositories/somebody-else/release-requests/" + id + "/artifacts")
        .then()
        .statusCode(404);
  }

  // -----------------------------------------------------------------------------------------------
  // The fixture
  // -----------------------------------------------------------------------------------------------

  private String artifactsOf(String requestId) {
    return "/projects/api/repositories/" + repoId + "/release-requests/" + requestId + "/artifacts";
  }

  /** A landed release of the fixture repository, with the tag row the release recorded. */
  private String release() {
    String id = row(ReleaseRequest.State.RELEASED, VERSION);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleasedTagPendingMerge tag = new ReleasedTagPendingMerge();
              tag.id = UUID.randomUUID().toString();
              tag.repoId = repoId;
              tag.tagName = VERSION;
              tag.releasedSha = RELEASED_SHA;
              tag.releaseRequestId = id;
              tag.releasedAt = Instant.now();
              tag.persist();
            });
    return id;
  }

  private String pending() {
    return row(ReleaseRequest.State.PENDING, null);
  }

  private String row(ReleaseRequest.State state, String version) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest request = new ReleaseRequest();
              request.id = UUID.randomUUID().toString();
              request.repoId = repoId;
              request.projectId = projectId;
              request.repoName = "qits-thing-service";
              request.summary = "a release worth reading";
              request.state = state;
              request.version = version;
              request.mergedSha = MERGED_SHA;
              request.createdAt = Instant.now();
              request.armedAt = request.createdAt;
              request.updatedAt = request.createdAt;
              request.persist();
              return request.id;
            });
  }

  /** The released tree as paths alone — what the deployability read is the whole of. */
  private void tree(String... paths) {
    Map<String, String> files = new LinkedHashMap<>();
    for (String path : paths) {
      files.put(path, "irrelevant");
    }
    gitHost.tree("refs/tags/" + VERSION, files);
  }

  /** The released tree with content, for the paths that are read rather than only listed. */
  private void tree(Map<String, String> files) {
    gitHost.tree("refs/tags/" + VERSION, files);
  }
}
