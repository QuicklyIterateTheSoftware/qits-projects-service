package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeEstatePins;
import eu.wohlben.qits.projects.testsupport.RecordingReleasedBranchWorkspaces;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A wrapper repository converges per REPOSITORY, not per branch.</b> Two workspaces of one
 * project releasing on the same night are two asks about one estate, so the second ask joins the
 * request the first opened rather than minting a rival — one calver tag, one gating build, one
 * approval, one deployment for one night's work.
 *
 * <p>{@code WrapperEstatePinGateTest} is the template, and the fixture is deliberately its shape: a
 * {@link RepositoryArchetype#PROJECT} wrapper and a plain repository as the <b>control</b>, because
 * the whole claim of this class is that the rule is scoped to the archetype and that everything
 * else keeps the per-branch model untouched. Nothing reaches a git host or qits-maintenance — the
 * wrapper's branches are staged with no submodules at all, so the estate gate finds nothing stale
 * and what holds a request here is only ever the CI gate.
 */
@QuarkusTest
public class WrapperReleaseConvergenceTest {

  @Inject BuildStatusListener listener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeEstatePins estatePins;

  @Inject RecordingReleasedBranchWorkspaces releasedBranchWorkspaces;

  private String projectId;
  private String wrapperRepoId;
  private String plainRepoId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    estatePins.reset();
    releasedBranchWorkspaces.reset();
    // A green build with nothing still in flight, so a verdict is all a request waits for.
    activeBuilds.answer(Optional.of(0));

    String unique = UUID.randomUUID().toString();
    projectId = "converge-project-" + unique;
    wrapperRepoId = "converge-wrapper-" + unique;
    plainRepoId = "converge-plain-" + unique;

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "converge";
              project.slug = "converge-" + unique;
              project.persist();
              alias(project, repository(project, wrapperRepoId, RepositoryArchetype.PROJECT),
                  "converge-wrapper");
              alias(project, repository(project, plainRepoId, RepositoryArchetype.SERVICE),
                  "converge-plain");
            });

    // The wrapper's branches, declaring no submodules: there is no estate to be stale about, so the
    // estate gate passes with nothing asked of qits-maintenance. Every branch this class names.
    for (String branch : List.of("main", "alpha", "beta", "gamma")) {
      gitHost.gatedTree("refs/heads/" + branch, Map.of(".gitmodules", ""));
    }
  }

  private static Repository repository(
      Project project, String repoId, RepositoryArchetype archetype) {
    Repository repository = new Repository();
    repository.id = repoId;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = archetype;
    repository.persist();
    return repository;
  }

  private static void alias(Project project, Repository repository, String name) {
    RepositoryName row = new RepositoryName();
    row.project = project;
    row.repository = repository;
    row.name = name;
    row.persist();
  }

  /** No open request outlives its test: the sweeps walk every open row there is. */
  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId in ?1", List.of(wrapperRepoId, plainRepoId));
            });
  }

  // -----------------------------------------------------------------------------------------
  // The rule
  // -----------------------------------------------------------------------------------------

  /**
   * The defect this class exists for: two workspaces, two branches, <b>one</b> request. The joiner
   * becomes a named source of the request already open, and the fold is redone so the gates judge
   * the estate both branches are asking to ship.
   */
  @Test
  public void asecondBranchOfTheWrapperJoinsTheOpenRequestRatherThanOpeningASecond() {
    String first = create(wrapperRepoId, "alpha", "a release", "ada");
    String firstFold = mergedShaOf(wrapperRepoId, first);

    String second = create(wrapperRepoId, "beta", "another release", "grace");

    assertEquals(first, second, "one estate, one request");
    request(wrapperRepoId, first)
        .body("sources.name", contains("main", "alpha", "beta"))
        .body("sources.implicit", contains(false, false, false));
    assertEquals(
        List.of("refs/heads/main", "refs/heads/alpha", "refs/heads/beta"),
        merger.foldsOf("refs/heads/release/" + first).get(1).sources(),
        "the joiner is content, so the request is folded again with it in");
    assertNotEquals(
        firstFold, mergedShaOf(wrapperRepoId, first), "and the gates are re-armed onto the new fold");
    assertEquals(1, openCountOf(wrapperRepoId));
  }

  /**
   * <b>The control, and the whole reason the rule is written against the archetype.</b> Everything
   * that is not a project's wrapper keeps the per-branch model: two branches are two asks about two
   * independent things, and answering the second with the first's request would fold work nobody
   * asked to ship together.
   */
  @Test
  public void anOrdinaryRepositoryStillOpensOneRequestPerBranch() {
    String first = create(plainRepoId, "alpha", "a release", "ada");
    String second = create(plainRepoId, "beta", "another release", "grace");

    assertNotEquals(first, second);
    assertEquals(2, openCountOf(plainRepoId));
    request(plainRepoId, first).body("sources.name", contains("main", "alpha"));
    request(plainRepoId, second).body("sources.name", contains("main", "beta"));
  }

  /**
   * <b>Re-asking for a branch already on the shared request is still the idempotent converge.</b> It
   * answers the same request and adds nothing to it — the per-branch rule surviving inside the
   * per-repository one, which is what makes a workspace safe to retry its own ask.
   *
   * <p>It does still <em>ask</em> for the fold, exactly as the per-branch converge always has, and
   * that is free rather than an oversight: the same heads folded onto the same target is {@code
   * unchanged} at a real git host, so no ref moves and nothing is re-armed. The recorded merger
   * cannot model that — it mints a fresh sha every time — so what is asserted here is the source
   * set, which is the thing the rule is about.
   */
  @Test
  public void reAskingForAParticipatingBranchAddsNothingToTheSharedRequest() {
    String id = create(wrapperRepoId, "alpha", "a release", "ada");
    create(wrapperRepoId, "beta", "another release", "grace");

    assertEquals(id, create(wrapperRepoId, "beta", "said again", "grace"));

    request(wrapperRepoId, id)
        .body("sources.name", contains("main", "alpha", "beta"))
        .body("sources.find { it.name == 'beta' }.addedBy", equalTo("grace"));
    assertEquals(1, openCountOf(wrapperRepoId));
  }

  // -----------------------------------------------------------------------------------------
  // What a converging ask may overwrite
  // -----------------------------------------------------------------------------------------

  /**
   * <b>The words of the ask that opened the request stand.</b> On a shared request the second
   * workspace's summary would otherwise erase the first's — and that summary is the sentence a
   * person is shown at the approval gate and the message the fold commits under. What the newcomer
   * says reaches its <em>own</em> source row instead: {@code addedBy} records who put the branch
   * on, so nothing is lost, it is simply recorded against the branch it is about.
   */
  @Test
  public void aConvergingAskDoesNotRewriteTheRequestsSummaryOrItsRequester() {
    String id = create(wrapperRepoId, "alpha", "the estate ada opened", "ada");

    create(wrapperRepoId, "beta", "grace has some work too", "grace");

    request(wrapperRepoId, id)
        .body("summary", equalTo("the estate ada opened"))
        .body("requester", equalTo("ada"))
        .body("sources.find { it.name == 'alpha' }.addedBy", equalTo("ada"))
        .body("sources.find { it.name == 'beta' }.addedBy", equalTo("grace"));
  }

  /**
   * The per-branch converge arm is untouched by all of this: on an ordinary repository the caller
   * <b>is</b> the whole of the request's ask, so re-asking restates its summary. That is the
   * behaviour that existed before this rule and the one the rule deliberately leaves alone.
   */
  @Test
  public void anOrdinaryRepositorysReAskStillRestatesTheSummary() {
    String id = create(plainRepoId, "alpha", "first words", "ada");

    assertEquals(id, create(plainRepoId, "alpha", "better words", "ada"));

    request(plainRepoId, id).body("summary", equalTo("better words"));
  }

  /**
   * <b>A converging ask states its own branch's urgency and no other's.</b> The request's effective
   * priority is the max over its sources, so a BLOCKING newcomer escalates the whole fold — which
   * is right, because the fold is what ships — without touching a single sibling's row, and an
   * absent priority still never downgrades an escalation somebody made.
   */
  @Test
  public void aConvergingAskPricesItsOwnBranchAndTheRequestTakesTheMax() {
    String id = create(wrapperRepoId, "alpha", "a release", "ada", "HIGH");
    request(wrapperRepoId, id).body("priority", equalTo("HIGH"));

    create(wrapperRepoId, "beta", "another release", "grace", "BLOCKING");

    request(wrapperRepoId, id)
        .body("priority", equalTo("BLOCKING"))
        .body("sources.find { it.name == 'main' }.priority", equalTo("MEDIUM"))
        .body("sources.find { it.name == 'alpha' }.priority", equalTo("HIGH"))
        .body("sources.find { it.name == 'beta' }.priority", equalTo("BLOCKING"));

    // A third ask that prices nothing leaves every stated urgency exactly where it was.
    create(wrapperRepoId, "gamma", "quietly", "hopper");
    request(wrapperRepoId, id)
        .body("priority", equalTo("BLOCKING"))
        .body("sources.find { it.name == 'alpha' }.priority", equalTo("HIGH"))
        .body("sources.find { it.name == 'beta' }.priority", equalTo("BLOCKING"))
        .body("sources.find { it.name == 'gamma' }.priority", equalTo("MEDIUM"));
  }

  // -----------------------------------------------------------------------------------------
  // What a shared request does to the things that read one
  // -----------------------------------------------------------------------------------------

  /**
   * <b>One release now resolves N workspaces, and it has to report all of them.</b> The release
   * deletes every branch it consumed, so every workspace standing on one is stranded — holding a
   * container, a volume and a commissioned credential for a ref nobody can fetch — and the
   * convergence is exactly what turns "the branch of the person who asked" into "the branches of
   * everybody who did". {@code main} is in no such report: nothing deletes it and no release
   * consumes it.
   */
  @Test
  public void oneReleaseOfTheSharedRequestResolvesEveryParticipatingBranchesWorkspace() {
    String id = create(wrapperRepoId, "alpha", "the estate", "ada");
    create(wrapperRepoId, "beta", "and grace's half of it", "grace");

    verdict(wrapperRepoId, mergedShaOf(wrapperRepoId, id));
    awaitState(wrapperRepoId, id, "RELEASED");

    List<RecordingReleasedBranchWorkspaces.Resolved> resolved = awaitResolutions(2);
    assertEquals(
        List.of("alpha", "beta"),
        resolved.stream().map(RecordingReleasedBranchWorkspaces.Resolved::branch).toList(),
        "every participant, not the first — and never main");
    for (RecordingReleasedBranchWorkspaces.Resolved one : resolved) {
      assertEquals(wrapperRepoId, one.repoId());
    }
    assertEquals(1, executor.calls().size(), "one night's work, one release");
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  private String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String repoId, String branch, String summary, String requester) {
    return create(repoId, branch, summary, requester, null);
  }

  private String create(
      String repoId, String branch, String summary, String requester, String priority) {
    StringBuilder body =
        new StringBuilder("{\"branch\":\"")
            .append(branch)
            .append("\",\"summary\":\"")
            .append(summary)
            .append("\",\"requester\":\"")
            .append(requester)
            .append('"');
    if (priority != null) {
      body.append(",\"priority\":\"").append(priority).append('"');
    }
    return given()
        .contentType(ContentType.JSON)
        .body(body.append('}').toString())
        .post(base(repoId))
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private io.restassured.response.ValidatableResponse request(String repoId, String id) {
    return given().get(base(repoId) + "/" + id).then().statusCode(200).rootPath("request");
  }

  private String mergedShaOf(String repoId, String id) {
    return given().get(base(repoId) + "/" + id).then().extract().path("request.mergedSha");
  }

  private String stateOf(String repoId, String id) {
    return given().get(base(repoId) + "/" + id).then().extract().path("request.state");
  }

  private long openCountOf(String repoId) {
    return given()
        .get(base(repoId))
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("requests.findAll { it.state != 'RELEASED' }")
        .size();
  }

  private void verdict(String repoId, String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\"}",
            null,
            null,
            null));
  }

  private void awaitState(String repoId, String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(repoId, id);
      if (expected.equals(last)) {
        return;
      }
      sleep();
    }
    fail("request " + id + " never reached " + expected + "; last seen " + last);
  }

  /** The resolutions are made on the release worker, after the row settles. */
  private List<RecordingReleasedBranchWorkspaces.Resolved> awaitResolutions(int expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    List<RecordingReleasedBranchWorkspaces.Resolved> last = List.of();
    while (System.currentTimeMillis() < deadline) {
      last = releasedBranchWorkspaces.calls();
      if (last.size() >= expected) {
        return last;
      }
      sleep();
    }
    return fail("expected " + expected + " workspace resolutions; saw " + last);
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      assertTrue(false, "interrupted");
    }
  }
}
