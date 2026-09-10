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
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.EstatePins;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeEstatePins;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A wrapper release must never ship a stale estate</b>, and this is the gate that makes that
 * true. A wrapper's tree pins each member at a commit; its members release on their own; so a fold of
 * the wrapper can name versions nobody is running. Before such a request may pass, this service holds
 * a <em>positive</em> record that, as of that exact fold, the pins name what the members released —
 * and where they do not, it asks qits-maintenance to write them and waits for the commit to re-arm
 * the request.
 *
 * <p>{@code ReleaseRequestApprovalGateTest} is the template and the seeding is deliberately its own:
 * a {@link RepositoryArchetype#PROJECT} wrapper and a plain repository as the control, with the build
 * gate put out of the way so that what holds a request here is only ever the estate gate. Nothing
 * reaches a git host or qits-maintenance — {@link RecordingReleaseGitHost} is the estate and {@link
 * FakeEstatePins} is the bump door, both ordinary beans beating their {@code @DefaultBean} adapters.
 *
 * <p><b>The load-bearing test is {@link #maintenanceBeingDownHoldsTheRequestAndReleasesNothing}.</b>
 * Everything else here describes the happy shape; that one is the reason the record is positive
 * rather than negative, and it asserts the thing a negative record would get wrong — that the
 * executor is never reached, with a green gating verdict sitting there and a person's approval on
 * top of it.
 */
@QuarkusTest
public class WrapperEstatePinGateTest {

  /** The member's directory in the wrapper, under the component grammar. */
  private static final String MEMBER_PATH = "components/member-a/member-a";

  /** The version the member has released — what a current pin has to name. */
  private static final String MEMBER_VERSION = "2026.910.120000";

  /** The commit that version resolves to. */
  private static final String MEMBER_RELEASED_SHA = "member-a-released-sha";

  /** What an out-of-date wrapper tree holds there instead. */
  private static final String STALE_PIN = "member-a-stale-sha";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeEstatePins estatePins;

  private String projectId;
  private String wrapperRepoId;
  private String wrapperName;
  private String memberRepoId;
  private String unreleasedRepoId;
  private String plainRepoId;

  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    estatePins.reset();
    requestIds.clear();
    // A green build with nothing still in flight, so the build gate is out of the way and what holds
    // a request here is only ever the estate gate (and, behind it, the approval gate).
    activeBuilds.answer(Optional.of(0));

    String unique = UUID.randomUUID().toString();
    projectId = "estate-project-" + unique;
    wrapperRepoId = "estate-wrapper-" + unique;
    memberRepoId = "estate-member-" + unique;
    unreleasedRepoId = "estate-unreleased-" + unique;
    plainRepoId = "estate-plain-" + unique;
    wrapperName = "estate-wrapper";

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "estate";
              project.slug = "estate-" + unique;
              project.persist();
              alias(project, wrapper(project), wrapperName);
              alias(project, member(project, memberRepoId), "member-a");
              alias(project, member(project, unreleasedRepoId), "member-b");
              alias(project, member(project, plainRepoId), "plain");
              // The member's one release. member-b deliberately has none: a submodule that has
              // never been released has no version to pin at, and inventing one is the single thing
              // an estate pin must never be.
              ReleasedTagPendingMerge released = new ReleasedTagPendingMerge();
              released.id = UUID.randomUUID().toString();
              released.repoId = memberRepoId;
              released.tagName = MEMBER_VERSION;
              released.releasedSha = MEMBER_RELEASED_SHA;
              released.releasedAt = Instant.now();
              released.persist();
            });
  }

  private Repository wrapper(Project project) {
    return persistRepository(project, wrapperRepoId, RepositoryArchetype.PROJECT);
  }

  private static Repository member(Project project, String repoId) {
    return persistRepository(project, repoId, RepositoryArchetype.SERVICE);
  }

  private static Repository persistRepository(
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

  /**
   * {@code ReleaseRequestFlowTest}'s discipline — no open request outlives its test, because the
   * sweeps walk every open row there is — plus this class's two unscoped tables: the released-tag
   * rows it seeded and the approval rows it inserted, neither of which is foreign-keyed to anything
   * that would cascade them.
   */
  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete(
                  "repoId in ?1",
                  List.of(wrapperRepoId, memberRepoId, unreleasedRepoId, plainRepoId));
              if (!requestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", requestIds);
              }
            });
  }

  // -----------------------------------------------------------------------------------------
  // The gate
  // -----------------------------------------------------------------------------------------

  /**
   * Arming is where the ask is made, and it is made <b>once per branch this request releases</b>. A
   * request folds N branches and each of them has its own tree with its own pins, so each needs its
   * own commit — one bump carrying every stale pin of that branch, and no bump at all for a branch
   * with none. The member with no release is in the wrapper's declaration throughout and appears in
   * no change: it is not part of the estate this release pins.
   *
   * <p><b>The assertion names the branches rather than counting them</b>, and that is the point of
   * the shape: {@code main} is a named source of every request, so a count is satisfied by asking
   * for the wrong branch — which is exactly the defect this pair of tests was written for. The
   * named branches are the whole list, in the order they were put on the request.
   */
  @Test
  public void armingAWrapperRequestAsksMaintenanceOncePerBranchItReleases() {
    stageWrapperAt("main", STALE_PIN);
    stageWrapperAt("work", STALE_PIN);
    stageWrapperAt("also", STALE_PIN);

    String id = create(wrapperRepoId, "work", "wohlben");
    assertNotNull(mergedShaOf(wrapperRepoId, id), "the create folds the sources at once");
    assertEquals(
        List.of("work"),
        estatePins.branchesAsked(),
        "the fold's other source is main, and main is never a target");

    // A second branch put on the request re-folds it, and the new fold's ask names both branches.
    estatePins.reset();
    addSource(wrapperRepoId, id, "also");
    assertEquals(
        List.of("work", "also"),
        estatePins.branchesAsked(),
        "one bump per branch this request releases, in source order, and still never main");

    List<FakeEstatePins.Asked> asked = estatePins.asked();
    for (FakeEstatePins.Asked ask : asked) {
      assertEquals(wrapperName, ask.repositoryName(), "addressed by the catalogue name");
      assertEquals(
          1,
          ask.changes().size(),
          "the member with no release yet is not in the estate: " + ask.changes());
      EstatePins.GitlinkChange change = ask.changes().get(0);
      assertEquals(MEMBER_PATH, change.manifestPath());
      assertEquals("member-a", change.name(), "the sibling's name is what resolves the clone url");
      assertEquals(STALE_PIN, change.from(), "what the tree holds now, for the commit message");
      assertEquals(MEMBER_VERSION, change.to(), "the member's latest released version");
    }
  }

  /**
   * <b>The repository's default branch is a source of the fold and never a target of a bump</b>, and
   * a stale {@code main} is where that had to be said. Every request folds {@code main} first, so it
   * is a BRANCH source like any other — but it is the one source no release consumes, and it is
   * protected: a bump asked for it fails on the push, and the request then waits for ever on a
   * commit that is never coming ("the estate pins are being written; <sha> re-arms when they land").
   * Observed live on {@code qits-qits}, two TARGETED bumps for one request — {@code epic/…}
   * succeeded with four pins, {@code main} failed.
   *
   * <p>So: {@code main}'s pins are stale here and the branch being released carries current ones.
   * Nothing is asked, and the estate gate opens rather than holding on somebody else's branch.
   */
  @Test
  public void aStaleDefaultBranchIsNeverAskedAboutBecauseItIsASourceAndNotATarget() {
    stageWrapperAt("main", STALE_PIN);
    stageWrapperAt("work", MEMBER_RELEASED_SHA);

    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);

    assertEquals(
        List.of(),
        estatePins.branchesAsked(),
        "main is stale and is still not asked about: a bump onto a protected branch cannot land");

    verdict(wrapperRepoId, merged);

    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals(
        "Waiting for a person to approve " + merged.substring(0, 10),
        request.getString("detail"),
        "the estate gate passed, rather than holding for a commit nobody was ever going to make");
  }

  /**
   * <b>The terminating case, and the one it would be easiest to get wrong.</b> A branch whose pins
   * already name what its members released asks for nothing — not an empty bump, which
   * qits-maintenance would accept and answer "nothing to do", and which would leave this request
   * waiting for a commit that is never coming. The estate gate then passes and the request goes on to
   * the next one, which is what the approval sentence here proves.
   */
  @Test
  public void aSourceAlreadyCarryingItsPinsAsksForNothing() {
    stageWrapperAt("main", MEMBER_RELEASED_SHA);
    stageWrapperAt("work", MEMBER_RELEASED_SHA);

    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);

    assertEquals(List.of(), estatePins.asked(), "nothing was stale, so nothing was asked");

    verdict(wrapperRepoId, merged);

    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals(
        "Waiting for a person to approve " + merged.substring(0, 10),
        request.getString("detail"),
        "the estate gate let it through, so the approval gate is what holds it now");
  }

  /**
   * <b>The bump's commit is what re-arms the request, and nothing here polls for it.</b> The commit
   * lands on the source branch, {@code SCMPublishCommit} arrives, the request re-folds onto a new
   * merged sha — and that re-arm invalidates an approval given for the previous fold, which is
   * exactly the point of the gate sitting in front of the approval one. A person must not have their
   * yes about one estate transferred to another.
   */
  @Test
  public void theBumpsCommitReArmsTheFoldAndTheOldApprovalStopsCounting() {
    stageWrapperAt("main", MEMBER_RELEASED_SHA);
    stageWrapperAt("work", STALE_PIN);

    String id = create(wrapperRepoId, "work", "wohlben");
    String firstFold = mergedShaOf(wrapperRepoId, id);
    assertEquals(
        List.of("work"),
        estatePins.asked().stream().map(FakeEstatePins.Asked::branch).toList(),
        "only the branch that was actually stale");

    verdict(wrapperRepoId, firstFold);
    assertEquals(
        "The estate pins are being written; "
            + firstFold.substring(0, 10)
            + " re-arms when they land",
        request(wrapperRepoId, id).getString("detail"));

    // A person signs off the fold they are looking at, while the bump is still in flight.
    record(id, firstFold, ReleaseRequestApproval.Decision.APPROVED, "ada", "ship it");
    assertEquals("APPROVED", request(wrapperRepoId, id).getString("approvalState"));

    // The bump lands: the branch now carries the released sha, and the push re-folds the request.
    stageWrapperAt("work", MEMBER_RELEASED_SHA);
    headMoved(wrapperRepoId, "work");
    awaitDetailChange(wrapperRepoId, id, firstFold);

    String secondFold = mergedShaOf(wrapperRepoId, id);
    assertNotEquals(firstFold, secondFold, "the bump's commit produced new content");
    verdict(wrapperRepoId, secondFold);

    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals(
        "WAITING",
        request.getString("approvalState"),
        "the approval judged an estate this request has left");
    assertNull(request.getString("approvedBy"));
    assertEquals(
        "Waiting for a person to approve " + secondFold.substring(0, 10),
        request.getString("detail"),
        "and the pins are current now, so it is the approval gate that holds it");
    assertEquals(0, executor.calls().size());
  }

  /**
   * <b>The load-bearing test.</b> qits-maintenance cannot be asked — no address, unreachable, refusing,
   * or a bump already in flight; from the domain they are one answer — and the request holds, says
   * why, and <em>releases nothing</em>, with a green gating verdict and a person's approval both
   * sitting on the fold.
   *
   * <p>This is why the ledger's record is positive. A negative record — a flag written when a refresh
   * fails — is fail-open: this path would then have to remember to write one, and every path that did
   * not (an unconfigured port, a restart, a throw before the write) would release the stale estate
   * silently. Absence is the hold, so the assertion that matters is the one about the executor never
   * being reached.
   */
  @Test
  public void maintenanceBeingDownHoldsTheRequestAndReleasesNothing() {
    stageWrapperAt("main", STALE_PIN);
    stageWrapperAt("work", STALE_PIN);
    estatePins.answerNothing();

    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    assertFalse(estatePins.asked().isEmpty(), "it did try");

    verdict(wrapperRepoId, merged);
    var held = request(wrapperRepoId, id);
    assertEquals("PENDING", held.getString("state"), "green, and deliberately still not released");
    assertEquals(
        "The estate pins could not be refreshed for " + merged.substring(0, 10),
        held.getString("detail"),
        "and the sentence says which of the two holds it, not merely that something does");

    // Even a person saying yes cannot open it: the estate is unknown, so what they would be
    // approving is unknown too.
    record(id, merged, ReleaseRequestApproval.Decision.APPROVED, "ada", "ship it");
    verdict(wrapperRepoId, merged);

    assertEquals("PENDING", stateOf(wrapperRepoId, id));
    assertEquals(0, executor.calls().size(), "nothing was released, which is the whole claim");
  }

  /**
   * <b>The control.</b> An ordinary repository pins no estate, so nothing about it is asked of
   * qits-maintenance and it releases on its gates exactly as it did before this feature existed —
   * including on a platform where the bump door is unreachable, which is the shipped configuration.
   */
  @Test
  public void anOrdinaryRepositoryAsksNothingAndReleasesAsBefore() {
    estatePins.answerNothing();

    String id = create(plainRepoId, "work", "wohlben");
    String merged = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, merged);
    awaitState(plainRepoId, id, "RELEASED");

    assertEquals(List.of(), estatePins.asked(), "nobody asked about an estate it does not have");
    assertEquals(1, executor.calls().size());
    assertEquals(merged, executor.calls().get(0).expectedSha());
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  /** The wrapper's declaration and its tree at one branch — one member released, one never. */
  private void stageWrapperAt(String branch, String pin) {
    String rev = "refs/heads/" + branch;
    gitHost.tree(
        rev,
        Map.of(
            ".gitmodules",
            """
            [submodule "member-a"]
            \tpath = %s
            \turl = ../member-a.git
            [submodule "member-b"]
            \tpath = components/member-b/member-b
            \turl = ../member-b.git
            """
                .formatted(MEMBER_PATH)));
    gitHost.pin(rev, MEMBER_PATH, pin);
    gitHost.pin(rev, "components/member-b/member-b", "member-b-whatever");
  }

  private String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String repoId, String branch, String requester) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"branch\":\""
                    + branch
                    + "\",\"summary\":\"an estate release\",\"requester\":\""
                    + requester
                    + "\"}")
            .post(base(repoId))
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    return id;
  }

  /** A second branch on an open request — the door, because the re-fold is what re-arms the gate. */
  private void addSource(String repoId, String id, String branch) {
    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\"}")
        .post(base(repoId) + "/" + id + "/sources")
        .then()
        .statusCode(200);
  }

  private io.restassured.path.json.JsonPath request(String repoId, String id) {
    return given()
        .get(base(repoId) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private String stateOf(String repoId, String id) {
    return request(repoId, id).getString("state");
  }

  private String mergedShaOf(String repoId, String id) {
    return request(repoId, id).getString("mergedSha");
  }

  /** One decision, straight into the table — the door's own suite is where the door is tested. */
  private void record(
      String requestId,
      String mergedSha,
      ReleaseRequestApproval.Decision decision,
      String actor,
      String note) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestApproval approval = new ReleaseRequestApproval();
              approval.id = UUID.randomUUID().toString();
              approval.requestId = requestId;
              approval.mergedSha = mergedSha;
              approval.decision = decision;
              approval.actor = actor;
              approval.note = note;
              approval.decidedAt = Instant.now();
              approval.persist();
            });
  }

  private void verdict(String repoId, String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\""
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

  /** A push to a participating branch: the re-fold, which is the re-arm. */
  private void headMoved(String repoId, String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + branch
                + "\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\"}",
            null,
            null,
            null));
  }

  /** The re-fold runs on the consuming thread, so the new sha is polled rather than assumed. */
  private void awaitDetailChange(String repoId, String id, String previousFold) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = mergedShaOf(repoId, id);
      if (last != null && !last.equals(previousFold)) {
        return;
      }
      sleep();
    }
    fail("request " + id + " never re-folded past " + previousFold + "; last seen " + last);
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

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted");
    }
  }

  /** Kept honest: the fixture really does declare a member this service has no release for. */
  @Test
  public void aMemberWithNoReleaseIsSimplyNotInTheEstate() {
    stageWrapperAt("main", MEMBER_RELEASED_SHA);
    stageWrapperAt("work", MEMBER_RELEASED_SHA);

    create(wrapperRepoId, "work", "wohlben");

    assertTrue(
        estatePins.asked().isEmpty(),
        "member-b is declared and unreleased, and asking for it would invent a version");
  }
}
