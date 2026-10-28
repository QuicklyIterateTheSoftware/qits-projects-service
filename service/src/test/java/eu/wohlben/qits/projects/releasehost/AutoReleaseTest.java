package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Auto Release: a met gate becomes a tag.</b> The whole executor, driven through the state
 * machine that calls it, against a git host that is a map.
 *
 * <p>The executor is reached by turning {@link RecordingReleaseExecutor#passThrough()} on: the
 * recording fake wins the port's injection in every other test in this package (which is what lets
 * them script an outcome), and delegating is what puts the <em>shipped</em> executor back under the
 * <em>real</em> {@code ReleaseRequests} — so the bookkeeping only that class does (the pending-merge
 * row, RELEASED, the siblings' re-fold) is proved to run behind a real release rather than behind a
 * scripted one.
 *
 * <p>The fixture repository is a two-module maven reactor, because the interesting half of the bump
 * is the {@code <module>} walk and the in-reactor {@code <parent><version>}; {@code
 * ManifestVersionBumpTest} owns the exhaustive manifest cases offline.
 */
@QuarkusTest
public class AutoReleaseTest {

  @Inject eu.wohlben.qits.projects.bus.BuildStatusListener buildListener;

  @Inject ReleaseRequests releaseRequests;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseRequestAnnouncer requestAnnouncer;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingReleaseAnnouncer releases;

  @Inject RecordingQaRunCancellations cancellations;

  @Inject RecordingReleaseRequestStateAnnouncer stateChanges;

  @Inject eu.wohlben.qits.projects.testsupport.RecordingReleasedBranchWorkspaces workspaces;

  /** What {@link eu.wohlben.qits.projects.control.VersionStamp} may produce, and only that. */
  private static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{3,4}\\.\\d{1,6}");

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    requestAnnouncer.reset();
    gitHost.reset();
    releases.reset();
    cancellations.reset();
    stateChanges.reset();
    workspaces.reset();
    activeBuilds.answer(Optional.of(1));
    repoId = "release-exec-repo-" + UUID.randomUUID();
    projectId = "release-exec-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "auto-release";
              project.slug = "auto-release-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /**
   * Open requests must not outlive this class: {@code sweep()} walks every open row. The approval
   * rows go with them by hand — they have no foreign key to the request, deliberately, so nothing
   * cascades them away.
   */
  @AfterEach
  void dropTheFixture() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
              if (!approvedRequestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", approvedRequestIds);
              }
            });
    approvedRequestIds.clear();
  }

  /** The requests this test approved, so their rows can be dropped again. */
  private final List<String> approvedRequestIds = new ArrayList<>();

  // ---------------------------------------------------------------------------------------------
  // The fixture repository
  // ---------------------------------------------------------------------------------------------

  private static final String ROOT_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-thing-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <modules>
          <module>domain</module>
        </modules>
      </project>
      """;

  private static final String MODULE_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <parent>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-thing-parent</artifactId>
          <version>1.0.0-SNAPSHOT</version>
        </parent>
        <artifactId>qits-thing-domain</artifactId>
      </project>
      """;

  private static Map<String, String> reactor() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("pom.xml", ROOT_POM);
    files.put("domain/pom.xml", MODULE_POM);
    files.put("README.md", "nothing to bump here");
    return files;
  }

  // ---------------------------------------------------------------------------------------------
  // Driving one release
  // ---------------------------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\",\"summary\":\"the thing works now\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private String mergedShaOf(String id) {
    return given().get(base() + "/" + id).then().extract().path("request.mergedSha");
  }

  private String stateOf(String id) {
    return given().get(base() + "/" + id).then().extract().path("request.state");
  }

  private void greenVerdict(String sha) {
    buildListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\"" + BackingBranches.atFold(repoId, sha) + "\",\"commitSha\":\""
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

  private void awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(id);
      if (expected.equals(last)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("request " + id + " never reached " + expected + "; last seen " + last);
  }

  /** The consumed branches go after RELEASED is written, so a test reading them waits for them. */
  /** Waits until the released branches' workspaces have been told {@code expected} times. */
  private void awaitWorkspaceCalls(int expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (workspaces.calls().size() < expected && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
  }

  private void awaitDeletions(int expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (gitHost.deletedBranches().size() < expected && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    assertEquals(expected, gitHost.deletedBranches().size(), "deleted: " + gitHost.deletedBranches());
  }

  /**
   * Create a gated request over a staged tree and let the real executor release it. Returns the
   * request's id; the merged sha the tree was staged at is {@link #mergedSha}.
   */
  private String mergedSha;

  private String releaseARequest(Map<String, String> tree) {
    return releaseARequest(tree, fold -> {});
  }

  /**
   * The same walk with a hook at the one moment a test can stage anything keyed by the FOLD: the
   * merged sha is minted by the create and the release runs off the green verdict, so a gitlink pin
   * — which belongs to a revision — has nowhere else to be put.
   */
  private String releaseARequest(Map<String, String> tree, java.util.function.Consumer<String> atFold) {
    String id = create("work");
    mergedSha = mergedShaOf(id);
    assertNotNull(mergedSha, "the create folds the sources at once");
    gitHost.tree(mergedSha, tree);
    atFold.accept(mergedSha);
    executor.passThrough();
    activeBuilds.answer(Optional.of(0));
    approve(id, mergedSha);
    greenVerdict(mergedSha);
    return id;
  }

  /**
   * Clear the <b>approval</b> gate as well as the build one. This class is about the executor and
   * about nothing else, so it clears every gate a request has rather than choosing which ones to
   * stage — and the wrapper cases below need it, because {@code ApprovalPolicy} asks for a person on
   * exactly the archetype {@link #wrapperEstate()} turns the fixture into. For every other test here
   * the row is inert: the policy says no approval is required, so nothing reads it.
   *
   * <p>Inserted directly because the doors that record a decision do not exist yet; {@code
   * ReleaseRequestApprovalGateTest} is where the gate's own behaviour is asserted.
   */
  private void approve(String requestId, String sha) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestApproval approval = new ReleaseRequestApproval();
              approval.id = UUID.randomUUID().toString();
              approval.requestId = requestId;
              approval.mergedSha = sha;
              approval.decision = ReleaseRequestApproval.Decision.APPROVED;
              approval.actor = "ada";
              approval.decidedAt = Instant.now();
              approval.persist();
              approvedRequestIds.add(requestId);
            });
  }

  // ---------------------------------------------------------------------------------------------
  // The happy path, step by step
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aMetGateStampsBumpsCommitsTagsDeletesAndAnnounces() {
    String id = releaseARequest(reactor());
    awaitState(id, "RELEASED");

    // 1. The stamp. One per attempt, and the request wears it.
    String version =
        given().get(base() + "/" + id).then().extract().path("request.version");
    assertTrue(CALVER.matcher(version).matches(), "not a calver: " + version);

    // 2 + 3. The bump, committed onto the BACKING BRANCH — the fold, not any one participant.
    assertEquals(1, gitHost.commits().size(), "one commit, and it is the last one before the tag");
    RecordingReleaseGitHost.Commit commit = gitHost.commits().get(0);
    assertEquals(BackingBranches.ref(id), commit.ref());
    assertEquals(mergedSha, commit.parent(), "the bump sits on the gated fold (qits-1647)");
    // What the release is about is derived from its sources (qits-1673): the one branch, "work".
    assertEquals("release(" + version + "): work", commit.message());
    assertEquals(
        java.util.Set.of("pom.xml", "domain/pom.xml"),
        commit.files().keySet(),
        "every pom of the reactor, and the README is not a manifest");
    assertTrue(
        commit.files().get("pom.xml").contains("<version>" + version + "</version>"),
        commit.files().get("pom.xml"));
    assertTrue(
        commit.files().get("domain/pom.xml").contains("<version>" + version + "</version>"),
        "the in-reactor <parent><version> follows the root");

    // 4. The tag, on the BUMPED commit — the manifests inside it carry the version the tag says.
    assertEquals(List.of(version), gitHost.createdTags());
    assertEquals(commit.sha(), gitHost.tags().get(0).sha(), "what is tagged is the bump commit");

    // 5. The branches the release consumed. NEVER the default branch. Asked for once the row reads
    // RELEASED, so they may trail the state this test awaited by a moment.
    awaitDeletions(2);
    assertEquals(
        List.of("work", BackingBranches.of(id)),
        gitHost.deletedBranches(),
        "the named source and the backing branch, and nothing else");
    assertFalse(gitHost.deletedBranches().contains("main"));

    // 6. SCMRelease, the instant the tag was accepted, with the eight payload fields.
    assertEquals(1, releases.announced().size());
    RecordingReleaseAnnouncer.Announced announced = releases.announced().get(0);
    assertEquals(projectId, announced.projectId());
    assertEquals(repoId, announced.repoId());
    assertEquals(BackingBranches.of(id), announced.branch(), "what was released is the fold's branch");
    assertEquals(version, announced.version());
    assertNotNull(announced.occurredAt());
    // The coordinate that makes this event checkoutable, and it is the BUMP commit rather than the
    // fold: `branch` above names a ref this same operation deleted, and `version` names a tag and
    // not a commit, so before this field a release pipeline could only clone main and go looking.
    assertEquals(
        commit.sha(),
        announced.commitSha(),
        "the event says what the tag points at, and it agrees with the pending row");
    // And what the release was worth: the ask's own value, ridden onto the event unchanged. MEDIUM
    // here because nothing stated one, which is the shape every release before this field had.
    assertEquals("MEDIUM", announced.priority());
    // And the key the publish phase of the release pipeline is recognised by: the id of the request
    // that was just released. Asserted against `id` and NOT against what `branch` spells, which is
    // the whole point — `release/<id>` names a ref step 5 above has already deleted, so the id is
    // the durable half of the pair and travels in its own field rather than being parsed back out.
    assertEquals(
        id,
        announced.releaseRequestId(),
        "the released request's own id reaches the announced event");

    // And this service's own half: the tag joins the repository's implicit source set until
    // something merges it to main, so every other open request is a superset of what is shipping.
    ReleasedTagPendingMerge pending =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    ReleasedTagPendingMerge.<ReleasedTagPendingMerge>find("repoId = ?1", repoId)
                        .firstResult());
    assertNotNull(pending, "a released tag with no pending row would be invisible to the next fold");
    assertEquals(version, pending.tagName);
    assertEquals(commit.sha(), pending.releasedSha, "the row says what the tag points at");
    assertNull(pending.mergedAt, "nothing has merged it to main yet — that is a later arm");
  }

  /**
   * <b>A release asks qits-ci to cancel the request's QA runs, the moment the tag lands.</b> The
   * executor already deleted the branch a stale run would still be building — {@link
   * #aMetGateStampsBumpsCommitsTagsDeletesAndAnnounces} proves that half — so a QA run still in
   * flight is heading for a checkout failure that says nothing about the code, and freeing that
   * build agent is exactly {@code cancel}'s job. {@code ReleaseGateCorrelationTest} owns the same
   * call for supersession and for WITHDRAWN; this is its RELEASED half.
   *
   * <p><b>QA-only, not "everything" (qits-1264).</b> The tag push that just landed may already have
   * started this same request's RELEASE-phase publish run — qits-ci correlates it by this very
   * {@code releaseRequestId} — and a RELEASED cancellation naming "everything" would race that run
   * instead of only cleaning up the stranded QA one.
   */
  @Test
  public void aReleaseCancelsAnyQaRunStillBuildingTheDeletedBranch() {
    String id = releaseARequest(reactor());
    awaitState(id, "RELEASED");

    assertEquals(1, cancellations.calls().size());
    RecordingQaRunCancellations.Cancelled cancelled = cancellations.calls().get(0);
    assertEquals(repoId, cancelled.repoId());
    assertEquals(id, cancelled.releaseRequestId());
    assertTrue(cancelled.qaOnly(), "RELEASED must not cancel the tag's own publish run");
  }

  @Test
  public void aRepositoryThatRendersNoVersionTagsTheFoldItselfAndIsAReleaseLikeAnyOther() {
    String id = releaseARequest(Map.of("README.md", "a docs repository", "docs/index.md", "hi"));
    awaitState(id, "RELEASED");

    assertEquals(List.of(), gitHost.commits(), "nothing renders a version, so nothing is committed");
    assertEquals(1, gitHost.tags().size());
    assertEquals(mergedSha, gitHost.tags().get(0).sha(), "the fold itself is what the tag names");
    assertEquals(1, releases.announced().size());
    assertEquals(
        mergedSha,
        releases.announced().get(0).commitSha(),
        "a stackless release has no commit before its tag, so the fold IS the checkout target —"
            + " the event carries one either way");
    assertEquals(
        id,
        releases.announced().get(0).releaseRequestId(),
        "and the publish phase's key rides out of every release, not only the ones that bump");
  }

  // ---------------------------------------------------------------------------------------------
  // tag-exists: the platform's version-uniqueness guarantee
  // ---------------------------------------------------------------------------------------------

  // ---------------------------------------------------------------------------------------------
  // The wrapper's estate
  // ---------------------------------------------------------------------------------------------

  private static final String LIB_PIN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String SVC_PIN = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  private static final String LIB_PATH = "components/qits-thing/qits-thing-javalib";
  private static final String SVC_PATH = "components/qits-thing/qits-thing-service";

  private String libId;
  private String svcId;

  /**
   * The fixture repository becomes the project's WRAPPER — named, because the pins are read and
   * resolved by project and repository name — with two named siblings and one declared submodule
   * that is not pinned at all, which is a fold somebody is still writing rather than a release to
   * refuse.
   */
  private Map<String, String> wrapperEstate() {
    libId = "estate-lib-" + UUID.randomUUID();
    svcId = "estate-svc-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository wrapper = Repository.findById(repoId);
              wrapper.archetype = RepositoryArchetype.PROJECT;
              Project project = Project.findById(projectId);
              alias(project, wrapper, "auto-release-auto-release");
              alias(project, sibling(project, libId, "main"), "qits-thing-javalib");
              alias(project, sibling(project, svcId, "trunk"), "qits-thing-service");
            });
    Map<String, String> tree = new LinkedHashMap<>();
    tree.put(
        ".gitmodules",
        """
        [submodule "qits-thing-javalib"]
        \tpath = components/qits-thing/qits-thing-javalib
        \turl = ../qits-thing-javalib.git
        [submodule "qits-thing-service"]
        \tpath = components/qits-thing/qits-thing-service
        \turl = ../qits-thing-service.git
        [submodule "qits-thing-stray"]
        \tpath = components/qits-thing/qits-thing-stray
        \turl = ../qits-thing-stray.git
        """);
    tree.put("README.md", "the estate");
    // The ESTATE GATE reads the wrapper's source branches, not its fold: it compares what each
    // branch pins against what the members have released, and holds the request while it cannot.
    // The siblings here have released nothing this service has a record of, so the comparison finds
    // nothing to move — but the branches still have to be readable, or the gate cannot say that.
    gitHost.gatedTree("refs/heads/main", tree);
    gitHost.tree("refs/heads/work", tree);
    return tree;
  }

  private static Repository sibling(Project project, String id, String mainBranch) {
    Repository repository = new Repository();
    repository.id = id;
    repository.project = project;
    repository.mainBranch = mainBranch;
    repository.persist();
    return repository;
  }

  private static void alias(Project project, Repository repository, String name) {
    RepositoryName alias = new RepositoryName();
    alias.project = project;
    alias.repository = repository;
    alias.name = name;
    alias.persist();
  }

  /** The estate as the fold declares it: both pins committed, and both commits really there. */
  private void pinsAt(String fold) {
    gitHost.pin(fold, LIB_PATH, LIB_PIN);
    gitHost.pin(fold, SVC_PATH, SVC_PIN);
    gitHost.holds("qits-thing-javalib", LIB_PIN);
    gitHost.holds("qits-thing-service", SVC_PIN);
  }

  @Test
  public void aWrapperReleaseTagsTheApprovedFoldAndMovesNoPin() {
    String id = releaseARequest(wrapperEstate(), this::pinsAt);
    awaitState(id, "RELEASED");

    // The estate is in the fold, so a release has nothing to write: no version renders here, and
    // the pins are content that was gated and approved rather than something computed after the
    // fact. Before this arm was retired the same walk produced one commit carrying two gitlinks.
    assertEquals(List.of(), gitHost.commits(), "a release rewrites no gitlink and bumps nothing");
    assertEquals(1, gitHost.tags().size());
    assertEquals(
        mergedSha,
        gitHost.tags().get(0).sha(),
        "the tag names the fold the person approved, exactly");
    assertEquals(
        LIB_PIN,
        gitHost.gitlinkAt(projectId, "auto-release-auto-release", mergedSha, LIB_PATH).value(),
        "the pin is byte-identical to what was approved");
    assertEquals(
        SVC_PIN,
        gitHost.gitlinkAt(projectId, "auto-release-auto-release", mergedSha, SVC_PATH).value(),
        "and so is the second one");
    assertEquals(
        mergedSha,
        releases.announced().get(0).commitSha(),
        "a pins-only wrapper has no commit before its tag, so the fold IS the checkout target");
  }

  @Test
  public void aPinNamingACommitNothingHoldsRefusesTheReleaseAndSaysWhichEntry() {
    String id =
        releaseARequest(
            wrapperEstate(),
            fold -> {
              pinsAt(fold);
              // The lib's pin now names a commit no repository on this host holds — a rewritten
              // branch, a restored copy, a hand-edited entry. Git records it without resolving it,
              // so nothing before this point can have noticed.
              gitHost.pin(fold, LIB_PATH, "cccccccccccccccccccccccccccccccccccccccc");
            });
    awaitState(id, "FAILED");

    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("qits-thing-javalib"), detail);
    assertTrue(detail.contains("cannot resolve"), detail);
    given()
        .get(base() + "/" + id)
        .then()
        .body(
            "request.retryable",
            org.hamcrest.Matchers.equalTo(false));
    assertEquals(List.of(), gitHost.createdTags(), "a broken estate must not be released");
    assertEquals(List.of(), gitHost.commits(), "and nothing was committed either");
  }

  @Test
  public void aDeclaredSubmoduleTheFoldDoesNotPinYetIsNotARefusal() {
    // The third section of the fixture's .gitmodules is declared and never pinned. That is an
    // unfinished fold, not a lie about an estate, and the guard is about pins that do not resolve.
    String id = releaseARequest(wrapperEstate(), this::pinsAt);
    awaitState(id, "RELEASED");
    assertEquals(1, gitHost.createdTags().size());
  }

  @Test
  public void aTakenVersionIsRestampedAndTheSecondAttemptRebumpsTheManifests() {
    gitHost.refuseTagsAsExisting(1);
    String id = releaseARequest(reactor());
    awaitState(id, "RELEASED");

    assertEquals(2, gitHost.tags().size(), "one refusal, one attempt more");
    assertEquals(
        ReleaseGitHost.TagResult.ALREADY_EXISTS, gitHost.tags().get(0).result());
    assertEquals(ReleaseGitHost.TagResult.CREATED, gitHost.tags().get(1).result());

    String version =
        given().get(base() + "/" + id).then().extract().path("request.version");
    assertEquals(gitHost.tags().get(1).name(), version, "the request wears the version that landed");

    // THE INVARIANT THE RE-STAMP EXISTS FOR: the tree the tag points at carries the version the tag
    // says. That is why a collision restarts the WHOLE attempt — reading the fold's tree again and
    // bumping it to the new version — rather than re-tagging the same commit under a new name,
    // which would ship artifacts naming a version nothing built.
    String tagged = gitHost.tags().get(1).sha();
    assertEquals(
        1,
        gitHost.treeAt(tagged).get("pom.xml").split(Pattern.quote(version), -1).length - 1,
        "the tagged commit's root pom carries the tag's version exactly once");
    assertTrue(
        gitHost.treeAt(tagged).get("domain/pom.xml").contains("<version>" + version + "</version>"),
        "and so does every other pom of the reactor");

    // Whether the second attempt needed a commit of its own depends on the clock: the stamp has
    // one-second resolution, so a re-stamp inside the same second produces the version the first
    // attempt's commit already wrote, the bump is a no-op and the tag names that commit. Both arms
    // satisfy the invariant above, which is why it is the invariant that is asserted and not a
    // commit count. (A collision across a second boundary writes a second commit.)
    assertTrue(gitHost.commits().size() >= 1 && gitHost.commits().size() <= 2);
    assertEquals(
        gitHost.commits().get(gitHost.commits().size() - 1).sha(),
        tagged,
        "whatever the count, what is tagged is the newest bump commit");
    assertTrue(
        gitHost.commits().stream().allMatch(commit -> mergedSha.equals(commit.parent())),
        "every attempt's bump sits on the gated fold (qits-1647)");
  }

  @Test
  public void aVersionThatStaysTakenGivesUpRetryablyRatherThanForever() {
    gitHost.refuseTagsAsExisting(99);
    String id = releaseARequest(reactor());
    awaitState(id, "FAILED");

    assertEquals(
        GitHostReleaseExecutor.ATTEMPTS,
        gitHost.tags().size(),
        "bounded: a name still taken on the third attempt is not a same-second tie");
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(true));
    assertEquals(List.of(), releases.announced(), "nothing was released, so nothing is announced");
  }

  // ---------------------------------------------------------------------------------------------
  // Failures, and which of them the sweep may knock on again
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aFailureAtTheTagStepIsFailedRetryableAndTheSweepReleasesItAfterwards() {
    gitHost.failTagWith(
        ReleaseGitHost.TagAnswer.failedRetryable("qits-githost answered 503: <html>"));
    String id = releaseARequest(reactor());
    awaitState(id, "FAILED");

    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("could not be tagged"), detail);
    assertTrue(detail.contains("503"), detail);
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(true));
    assertEquals(List.of(), gitHost.deletedBranches(), "a release that did not happen consumed nothing");
    assertEquals(List.of(), releases.announced());

    // The moment passes, and the sweep is what notices. The retry is a whole attempt: a new bump
    // on the gated fold replaces the failed attempt's bump on the backing branch (qits-1647).
    gitHost.failTagWith(null);
    releaseRequests.sweep();
    awaitState(id, "RELEASED");
    assertEquals(1, gitHost.createdTags().size());
    assertEquals(1, releases.announced().size());
    assertTrue(
        gitHost.commits().stream().allMatch(commit -> mergedSha.equals(commit.parent())),
        "every bump sits on the gated fold: " + gitHost.commits());
    assertEquals(
        gitHost.commits().get(gitHost.commits().size() - 1).sha(),
        gitHost.tags().get(gitHost.tags().size() - 1).sha());
  }

  @Test
  public void aManifestThatWillNotParseIsARefusalTheSweepLeavesStanding() {
    Map<String, String> broken = reactor();
    broken.put("pom.xml", "<project><artifactId>oops</artifactId");
    String id = releaseARequest(broken);
    awaitState(id, "FAILED");

    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("could not be stamped"), detail);
    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(false));

    int attempts = gitHost.tags().size();
    releaseRequests.sweep();
    releaseRequests.sweep();
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertEquals("FAILED", stateOf(id), "a malformed pom answers the same on every knock");
    assertEquals(attempts, gitHost.tags().size(), "so the sweep made no further attempt");
  }

  @Test
  public void anUnreachableGitHostAtTheREADIsRetryableRatherThanARefusal() {
    gitHost.failTreeWith(
        ReleaseGitHost.Answer.failedRetryable("qits-githost could not be reached"));
    String id = releaseARequest(reactor());
    awaitState(id, "FAILED");

    given()
        .get(base() + "/" + id)
        .then()
        .body("request.retryable", org.hamcrest.Matchers.equalTo(true));
    String detail = given().get(base() + "/" + id).then().extract().path("request.detail");
    assertTrue(detail.contains("could not be read"), detail);
  }

  // ---------------------------------------------------------------------------------------------
  // A release's own deletions (qits-886)
  // ---------------------------------------------------------------------------------------------

  /**
   * <b>qits-githost announces the branches a release consumes, and the release must not hear them
   * as news about itself.</b> The fake git host hands each delete straight to {@code
   * onBranchDeleted} as it lands — the real announcement consumed with no delay at all, the
   * tightest the race can be drawn. Were the deletes made while the request still read READY, the
   * drop of {@code work} would leave nothing but {@code main} and withdraw it, and RELEASED would
   * then be written over a request whose sources were gone. Asked for only once it is RELEASED, they
   * match no unreleased request: the row is RELEASED at every delete, both sources stand, and
   * nothing folds onto its backing branch again.
   *
   * <p>And each write carried the address pair the real host announces it under.
   */
  @Test
  public void theReleasesOwnBranchDeletionsLeaveTheRequestReleasedWithItsSourcesIntact() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                alias(
                    Project.findById(projectId),
                    Repository.findById(repoId),
                    "qits-thing"));
    List<String> seenAtDelete = new java.util.concurrent.CopyOnWriteArrayList<>();
    gitHost.onDelete(
        (repo, branch) -> {
          seenAtDelete.add(
              QuarkusTransaction.requiringNew()
                  .call(
                      () ->
                          ReleaseRequest.<ReleaseRequest>find("repoId", repo)
                              .firstResult()
                              .state
                              .name()));
          releaseRequests.onBranchDeleted(repo, branch);
        });

    java.util.concurrent.atomic.AtomicInteger foldsAtTheGate =
        new java.util.concurrent.atomic.AtomicInteger();
    String id = releaseARequest(reactor(), fold -> foldsAtTheGate.set(merger.folds().size()));
    awaitState(id, "RELEASED");
    awaitDeletions(2);

    assertEquals(List.of("RELEASED", "RELEASED"), seenAtDelete, "RELEASED before the first delete");
    assertEquals("RELEASED", stateOf(id), "never withdrawn by its own deletions");
    assertEquals(
        List.of("main", "work"),
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    eu.wohlben.qits.projects.entity.ReleaseRequestSource
                        .<eu.wohlben.qits.projects.entity.ReleaseRequestSource>list(
                            "requestId = ?1 order by name", id)
                        .stream()
                        .map(source -> source.name)
                        .toList()),
        "no source was dropped");
    List<RecordingBackingBranchMerger.Fold> folds = merger.folds();
    assertTrue(
        folds.subList(foldsAtTheGate.get(), folds.size()).stream()
            .noneMatch(fold -> fold.target().equals(BackingBranches.ref(id))),
        "nothing re-folded the released request");
    assertTrue(
        gitHost.addressed().stream()
            .allMatch(
                write ->
                    projectId.equals(write.projectId()) && "qits-thing".equals(write.repoName())),
        "every write named the repository: " + gitHost.addressed());
  }

  // ---------------------------------------------------------------------------------------------
  // A source that joined or moved while the tag was being cut (qits-1615)
  // ---------------------------------------------------------------------------------------------

  /**
   * The request a branch is a source of now, other than {@code except} — polled, because the
   * hand-on is made on the worker after the release has settled.
   */
  private ReleaseRequest awaitOpenRequestNaming(String branch, String except) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      ReleaseRequest found =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      eu.wohlben.qits.projects.entity.ReleaseRequestSource
                          .<eu.wohlben.qits.projects.entity.ReleaseRequestSource>list(
                              "name = ?1 and requestId <> ?2", branch, except)
                          .stream()
                          .map(
                              source ->
                                  ReleaseRequest.<ReleaseRequest>findById(source.requestId))
                          .filter(
                              row ->
                                  row != null
                                      && repoId.equals(row.repoId)
                                      && eu.wohlben.qits.projects.persistence
                                          .ReleaseRequestRepository.UNRELEASED
                                          .contains(row.state))
                          .findFirst()
                          .orElse(null));
      if (found != null) {
        return found;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail(branch + " never landed in an open request of " + repoId);
    return null;
  }

  private List<String> sourcesOf(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                eu.wohlben.qits.projects.entity.ReleaseRequestSource
                    .<eu.wohlben.qits.projects.entity.ReleaseRequestSource>list(
                        "requestId = ?1 order by name", requestId)
                    .stream()
                    .map(source -> source.name)
                    .toList());
  }

  /** The RELEASED announcement of one request — exactly one, or the test fails. */
  private eu.wohlben.qits.projects.control.ReleaseRequestStateAnnouncer.StateChange releasedChangeOf(
      String requestId) {
    List<eu.wohlben.qits.projects.control.ReleaseRequestStateAnnouncer.StateChange> released =
        stateChanges.changesOf(requestId).stream()
            .filter(change -> "RELEASED".equals(change.state()))
            .toList();
    assertEquals(1, released.size(), "one RELEASED announcement: " + stateChanges.movesOf(requestId));
    return released.get(0);
  }

  /**
   * <b>A branch that joins while the tag is being cut is handed on, not stranded</b> (qits-1615).
   * The executor snapshots the request once and tags that fold outside every lock; a join in that
   * window folds the request again and re-arms it PENDING on a fold nobody will tag. The tag of the
   * snapshot is legitimate — its release ships the fold that was gated — so the request still ends
   * RELEASED, but naming the fold it tagged, and the late branch leaves it for an open request: it
   * is not deleted, not told to qits-workspaces as released, and not on the RELEASED announcement.
   * qits-qits-rr-200 is the live case: READY at 69b95ccd, re-armed onto 38477f86 by ticket/qits-1596
   * joining, then RELEASED tagging 69b95ccd with ticket/qits-1596 stranded on it.
   */
  @Test
  public void aBranchJoiningWhileTheTagIsCutIsHandedOnToAnOpenRequest() throws InterruptedException {
    RecordingReleaseGitHost.Hold held = gitHost.holdNextTag(repoId);
    String id = releaseARequest(reactor());
    assertTrue(held.awaitEntered(), "the worker reached the executor");
    String tagged = mergedSha;

    String joined = create("late");
    assertEquals(id, joined, "the late branch joins the request whose tag is being cut");
    assertEquals("PENDING", stateOf(id), "the join re-armed it onto a fold nobody will tag");
    assertNotEquals(tagged, mergedShaOf(id));

    held.release();
    awaitState(id, "RELEASED");
    awaitDeletions(2);

    assertEquals(tagged, mergedShaOf(id), "the request names the fold its tag was cut from");
    assertEquals(
        List.of("work", BackingBranches.of(id)),
        gitHost.deletedBranches(),
        "the late branch is not consumed by a release that never folded it");
    assertEquals(List.of("main", "work"), sourcesOf(id), "and it is no source of the release");

    ReleaseRequest handedOn = awaitOpenRequestNaming("late", id);
    assertNotEquals(id, handedOn.id);
    assertTrue(sourcesOf(handedOn.id).contains("late"), "it is a source of an open request now");

    // The workspaces are told last, after the hand-on's ask has folded its request, so the read
    // waits for them rather than racing the worker.
    awaitWorkspaceCalls(1);
    assertEquals(
        List.of("work"),
        workspaces.calls().stream()
            .map(eu.wohlben.qits.projects.testsupport.RecordingReleasedBranchWorkspaces.Resolved::branch)
            .toList(),
        "only the branch the tag released is announced as released");
    var released = releasedChangeOf(id);
    assertEquals(List.of("main", "work"), released.branches());
    assertEquals(tagged, released.mergedSha(), "and the announcement names the tagged fold");
    assertEquals(
        tagged, gitHost.commits().get(0).parent(), "the tagged bump sits on the gated fold");
  }

  /**
   * <b>A source pushed while the tag is being cut is not deleted with its new commits unreleased</b>
   * (qits-1615). The push re-folds the request onto a fold the tag never saw; the release still
   * settles on the snapshot, but the branch's head is no longer in what was tagged, so it is not
   * consumed: it stays on the git host and moves on to an open request, which releases the commits
   * the tag missed.
   */
  @Test
  public void aSourcePushedWhileTheTagIsCutIsNotDeletedButHandedOn() throws InterruptedException {
    RecordingReleaseGitHost.Hold held = gitHost.holdNextTag(repoId);
    String id = releaseARequest(reactor());
    assertTrue(held.awaitEntered(), "the worker reached the executor");
    String tagged = mergedSha;

    // The push: refs/heads/work resolves to a head the tagged fold does not contain any more, and
    // the head listener re-folds the request onto it.
    gitHost.doesNotContain(repoId, "refs/heads/work", tagged);
    releaseRequests.onBranchMoved(repoId, "work", UUID.randomUUID().toString().replace("-", ""));
    assertEquals("PENDING", stateOf(id), "the push re-armed it onto a fold nobody will tag");
    assertNotEquals(tagged, mergedShaOf(id));

    held.release();
    awaitState(id, "RELEASED");
    awaitDeletions(1);

    assertEquals(tagged, mergedShaOf(id), "the request names the fold its tag was cut from");
    assertEquals(
        List.of(BackingBranches.of(id)),
        gitHost.deletedBranches(),
        "the moved branch carries commits nothing released, so it is not deleted");
    assertEquals(List.of("main"), sourcesOf(id));

    ReleaseRequest handedOn = awaitOpenRequestNaming("work", id);
    assertNotEquals(id, handedOn.id);

    assertEquals(List.of(), workspaces.calls(), "the moved branch was not released");
    assertEquals(List.of("main"), releasedChangeOf(id).branches());
  }

  // ---------------------------------------------------------------------------------------------
  // Every ref write is pinned to the sha it was decided on (qits-1647)
  // ---------------------------------------------------------------------------------------------

  /**
   * <b>A join that re-folds the backing branch before the bump commit stops the release</b>
   * (qits-1647). The bump commit is pinned to the gated fold, and the branch must still be there.
   * It is not, so nothing is committed or tagged, and the request re-gates on the new fold. Once that
   * fold passes its gates, it releases with the bump on the new fold.
   */
  @Test
  public void aJoinBeforeTheBumpCommitStopsTheReleaseAndTheRequestRegates()
      throws InterruptedException {
    RecordingReleaseExecutor.Hold held = executor.holdNextRelease();
    String id = releaseARequest(reactor());
    assertTrue(held.awaitEntered(), "the worker reached the executor");
    String gated = mergedSha;

    assertEquals(id, create("late"), "the late branch joins the request");
    String refold = mergedShaOf(id);
    assertNotEquals(gated, refold);
    assertEquals(refold, gitHost.tipOf(repoId, BackingBranches.ref(id)), "the branch moved");

    held.release();
    // The executor asks for the pinned commit, and the git host refuses it.
    long deadline = System.currentTimeMillis() + 10_000;
    while (gitHost.addressed().stream().noneMatch(write -> write.write().equals("commit"))
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    Thread.sleep(200); // Let the worker settle what came back.

    assertEquals(List.of(), gitHost.tags(), "nothing is tagged on a fold that was not gated");
    assertEquals(List.of(), gitHost.commits(), "and no bump commit lands on the new fold");
    assertEquals(List.of(), gitHost.deletedBranches());
    assertEquals("PENDING", stateOf(id), "the request re-gates on the new fold");
    assertEquals(refold, mergedShaOf(id));
    assertEquals(List.of("late", "main", "work"), sourcesOf(id));

    // The new fold passes its gates, and releases with the bump on that fold.
    gitHost.tree(refold, reactor());
    approve(id, refold);
    greenVerdict(refold);
    awaitState(id, "RELEASED");
    assertEquals(1, gitHost.commits().size());
    assertEquals(refold, gitHost.commits().get(0).parent(), "the bump sits on the re-gated fold");
    assertEquals(gitHost.commits().get(0).sha(), gitHost.tags().get(0).sha());
  }

  /**
   * <b>A push between the containment check and the delete keeps the branch</b> (qits-1647). The
   * delete is pinned to the head that was checked; the push moved it, so the git host keeps it, and
   * it is handed on to an open request rather than lost.
   */
  @Test
  public void aPushBetweenTheCheckAndTheDeleteKeepsTheBranchAndHandsItOn() {
    gitHost.pushBeforeDelete(repoId, "work", UUID.randomUUID().toString().replace("-", ""));
    String id = releaseARequest(reactor());
    awaitState(id, "RELEASED");
    awaitDeletions(1);

    assertEquals(
        List.of(BackingBranches.of(id)),
        gitHost.deletedBranches(),
        "the pushed branch is not deleted");
    assertEquals(List.of("main"), sourcesOf(id), "and it is no source of the release any more");
    ReleaseRequest handedOn = awaitOpenRequestNaming("work", id);
    assertNotEquals(id, handedOn.id);
    assertEquals(List.of(), workspaces.calls(), "the pushed branch was not released");
    assertEquals(mergedSha, gitHost.commits().get(0).parent());
  }
}
