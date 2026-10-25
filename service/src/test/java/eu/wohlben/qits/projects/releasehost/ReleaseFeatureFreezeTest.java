package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The soft feature freeze</b> (epic qits-1580, task qits-1585): a repository's active release
 * request is frozen once it holds as many counted sources — branches naming a work item, of any type
 * — as the threshold in force (the repository's own {@code feature_freeze}, else its project's, else
 * the platform's 3; 0 turns it off), and from then on every branch asked for through the create
 * door lands in the one request queued behind it: features, bug fixes and maintenance bumps alike.
 * Only a join naming the frozen request by id is admitted past the freeze, and says so on its
 * source row. A queued request folds nothing, announces nothing and runs no gate; when the active
 * one leaves the unreleased states — RELEASED or WITHDRAWN — it is activated and folds. From RELEASED
 * on, no join is accepted at all. {@code main} and a re-ask answer PRESENT where they are.
 *
 * <p>Counted branches name real ticket rows of the entities module, as {@link
 * ReleaseRequestFeatureSourcesTest} does: IMPROVEMENT, BUG and MAINTENANCE tickets all count; a
 * {@code maintenance/*} branch names no work item and never does.
 * Most tests hold the CI gate open ({@link FakeActiveBuilds} answering "a run is still active"), so
 * nothing settles mid-test; the activation-on-release test drives the real release flow, as {@link
 * ReleaseSupersessionTest} does.
 */
@QuarkusTest
public class ReleaseFeatureFreezeTest {

  @Inject ReleaseRequests releaseRequests;

  @Inject WorkEntityService entities;

  @Inject BuildStatusListener listener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    announcer.reset();
    // Held: nothing settles a request mid-test unless a test lets it.
    activeBuilds.answer(Optional.of(1));
    projectId = "freeze-" + UUID.randomUUID().toString().substring(0, 8);
    repoId = "freeze-repo-" + UUID.randomUUID();
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
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
            });
  }

  /** The sweeps walk the whole database, so nothing of this fixture may outlive the test. */
  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  // -------------------------------------------------------------------------------------------
  // Placement
  // -------------------------------------------------------------------------------------------

  @Test
  void threeFeatureAsksShareOneRequestAndTheFourthLandsQueuedBehindIt() {
    ReleaseRequests.Asked first = ask(feature());
    assertEquals("CREATED", first.placement().outcome());
    assertFalse(first.placement().queued());
    String active = first.request().id();
    ReleaseRequests.Asked second = ask(feature());
    String thirdBranch = feature();
    ReleaseRequests.Asked third = ask(thirdBranch);
    assertEquals(active, second.request().id());
    assertEquals(active, third.request().id());
    assertEquals("JOINED", third.placement().outcome());
    assertFalse(third.placement().queued());
    assertEquals(
        thirdBranch
            + " joined "
            + first.request().qualifiedId()
            + ", the active release request (3 of 3 counted branches)",
        third.placement().detail());

    String fourthBranch = feature();
    ReleaseRequests.Asked fourth = ask(fourthBranch);

    String activeQid = first.request().qualifiedId();
    assertEquals("CREATED", fourth.placement().outcome());
    assertTrue(fourth.placement().queued());
    assertEquals(
        activeQid
            + " is frozen at 3 of 3 counted branches; "
            + fourthBranch
            + " opened "
            + fourth.request().qualifiedId()
            + ", queued behind it",
        fourth.placement().detail());
    ReleaseRequestDto queued = fourth.request();
    assertNotEquals(active, queued.id());
    assertEquals(active, queued.queuedBehind());
    assertEquals(activeQid, queued.queuedBehindQualifiedId());
    assertEquals("PENDING", queued.state());
    assertEquals(
        "Queued behind " + activeQid + ": it folds once that request has released",
        queued.detail());
    assertNull(queued.mergedSha(), "a queued request is not folded");
    assertEquals(1, queued.featureFreeze().features());
    assertFalse(queued.featureFreeze().frozen(), "a queued request is never frozen");

    ReleaseRequestDto frozen = releaseRequests.get(active);
    assertEquals(3, frozen.featureFreeze().features());
    assertEquals(3, frozen.featureFreeze().threshold());
    assertEquals("PLATFORM", frozen.featureFreeze().source(), "neither level declares a freeze");
    assertTrue(frozen.featureFreeze().frozen());
    assertEquals(queued.id(), frozen.queuedNext());
    assertEquals(queued.qualifiedId(), frozen.queuedNextQualifiedId());
    assertNull(frozen.queuedBehind());
  }

  @Test
  void aFifthAndASixthFeatureJoinTheSameQueuedRequest() {
    String active = freezeActive();
    String queued = ask(feature()).request().id();

    ReleaseRequests.Asked fifth = ask(feature());
    ReleaseRequests.Asked sixth = ask(feature());

    assertEquals(queued, fifth.request().id());
    assertEquals(queued, sixth.request().id());
    assertEquals("JOINED", sixth.placement().outcome());
    assertTrue(sixth.placement().queued());
    assertEquals(3, sixth.request().featureFreeze().features(), "a queued request has no limit");
    assertEquals(active, sixth.request().queuedBehind());
    assertEquals(1, queuedRequestsOf(), "at most one queued request per active request");
  }

  @Test
  void aBugFixAndAMaintenanceBumpAskedForAFrozenRequestGoToTheQueue() {
    String active = freezeActive();
    String activeQid = releaseRequests.get(active).qualifiedId();
    String bugBranch = ticket("BUG");
    String bumpBranch = "maintenance/java-deps/" + UUID.randomUUID();

    ReleaseRequests.Asked bug = ask(bugBranch);
    ReleaseRequests.Asked bump = ask(bumpBranch);

    assertEquals("CREATED", bug.placement().outcome());
    assertTrue(bug.placement().queued());
    String queued = bug.request().id();
    assertNotEquals(active, queued);
    assertEquals(active, bug.request().queuedBehind());
    assertEquals(
        activeQid
            + " is frozen at 3 of 3 counted branches; "
            + bugBranch
            + " opened "
            + bug.request().qualifiedId()
            + ", queued behind it",
        bug.placement().detail());
    assertEquals(queued, bump.request().id(), "the bump joins the same queue");
    assertEquals("JOINED", bump.placement().outcome());
    assertTrue(bump.placement().queued());
    assertEquals(
        activeQid
            + " is frozen at 3 of 3 counted branches; "
            + bumpBranch
            + " joined "
            + bug.request().qualifiedId()
            + ", queued behind it",
        bump.placement().detail());
    assertFalse(branchesOf(active).names.contains(bugBranch), "nothing joined the frozen request");
    assertFalse(branchesOf(active).names.contains(bumpBranch));
    assertEquals(3, releaseRequests.get(active).featureFreeze().features());
    // The bug counts, the maintenance bump does not.
    assertEquals(1, releaseRequests.get(queued).featureFreeze().features());
    assertEquals(1, queuedRequestsOf());
  }

  @Test
  void mainAndAReAskAnswerPresentOnTheFrozenRequest() {
    String active = freezeActive();
    String participant = branchesOf(active).feature;

    ReleaseRequests.Asked main = ask("main");
    ReleaseRequests.Asked again = ask(participant);

    for (ReleaseRequests.Asked asked : new ReleaseRequests.Asked[] {main, again}) {
      assertEquals(active, asked.request().id(), asked.placement().detail());
      assertEquals("PRESENT", asked.placement().outcome());
      assertFalse(asked.placement().queued(), asked.placement().detail());
    }
    assertEquals(0, queuedRequestsOf(), "nothing was queued");
  }

  @Test
  void maintenanceBranchesDoNotCountWhileBugTicketsDo() {
    String active = ask(ticket("BUG")).request().id();
    ask("maintenance/java-deps/" + UUID.randomUUID());
    ask("maintenance/gitlinks/" + UUID.randomUUID());
    ask(ticket("MAINTENANCE"));
    ReleaseRequestDto read = releaseRequests.get(active);
    assertEquals(2, read.featureFreeze().features(), "the two tickets count, the bumps do not");
    assertFalse(read.featureFreeze().frozen());

    ReleaseRequests.Asked third = ask(ticket("BUG"));
    assertEquals(active, third.request().id(), "the third counted branch still joins");
    assertTrue(releaseRequests.get(active).featureFreeze().frozen(), "and freezes the request");
    assertTrue(ask("maintenance/java-deps/" + UUID.randomUUID()).placement().queued());
  }

  @Test
  void aPushToAParticipantOfAFrozenRequestStillRefoldsIt() {
    String active = freezeActive();
    ask(feature());
    String backing = "refs/heads/" + releaseRequests.get(active).backingBranch();
    int before = merger.foldsOf(backing).size();
    String sha = mergedShaOf(active);

    releaseRequests.onBranchMoved(repoId, branchesOf(active).feature, RecordingBackingBranchMerger.freshSha());

    assertEquals(before + 1, merger.foldsOf(backing).size());
    assertNotEquals(sha, mergedShaOf(active));
  }

  @Test
  void aQueuedRequestIsNotFoldedAnnouncesNothingAndAPushToItsBranchDoesNotFoldIt() {
    freezeActive();
    String branch = feature();
    String queued = ask(branch).request().id();
    String backing = "refs/heads/" + releaseRequests.get(queued).backingBranch();

    releaseRequests.onBranchMoved(repoId, branch, RecordingBackingBranchMerger.freshSha());
    releaseRequests.sweep();

    assertTrue(merger.foldsOf(backing).isEmpty(), "nothing was folded: " + merger.foldsOf(backing));
    assertTrue(announcer.announcedFor(queued).isEmpty(), "and nothing announced");
    assertNull(mergedShaOf(queued));
    ReleaseRequestDto read = releaseRequests.get(queued);
    assertEquals("PENDING", read.state());
    assertTrue(read.detail().startsWith("Queued behind "), read.detail());
  }

  // -------------------------------------------------------------------------------------------
  // Activation
  // -------------------------------------------------------------------------------------------

  @Test
  void theQueueIsActivatedWhenTheActiveRequestReleasesAndFoldsWithItsTag() {
    activeBuilds.answer(Optional.of(0));
    String active = freezeActive();
    String queued = ask(feature()).request().id();
    String tag = stageRelease();

    verdict(mergedShaOf(active));
    await(() -> releaseRequests.get(active).state(), "RELEASED");

    await(() -> String.valueOf(releaseRequests.get(queued).queuedBehind()), "null");
    awaitTrue(() -> mergedShaOf(queued) != null, "the activated request folds");
    String backing = "refs/heads/" + releaseRequests.get(queued).backingBranch();
    assertTrue(
        merger.foldsOf(backing).stream()
            .anyMatch(fold -> fold.sources().contains("refs/tags/" + tag)),
        "it folds the release it waited for in: " + merger.foldsOf(backing));
    assertNull(releaseRequests.get(active).queuedNext());
  }

  @Test
  void theQueueIsActivatedWhenTheActiveRequestIsWithdrawnAndFolds() {
    String active = freezeActive();
    String queued = ask(feature()).request().id();
    String activeQid = releaseRequests.get(active).qualifiedId();

    releaseRequests.withdraw(active, "not this release", "alice");

    ReleaseRequestDto read = releaseRequests.get(queued);
    assertNull(read.queuedBehind());
    assertNotNull(read.mergedSha(), "it folds once it is active");
    assertFalse(read.detail().contains(activeQid), "and no longer says it waits: " + read.detail());
    assertFalse(announcer.announcedFor(queued).isEmpty(), "and its fold is announced");
  }

  @Test
  void aQueueActivatedWithThreeFeaturesIsFrozenAndTheNextFeatureQueuesBehindIt() {
    String active = freezeActive();
    String queued = ask(feature()).request().id();
    ask(feature());
    ask(feature());

    releaseRequests.withdraw(active, "not this release", "alice");

    ReleaseRequestDto activated = releaseRequests.get(queued);
    assertNull(activated.queuedBehind());
    assertTrue(activated.featureFreeze().frozen(), "frozen the moment it is active");

    ReleaseRequests.Asked next = ask(feature());
    assertEquals("CREATED", next.placement().outcome());
    assertTrue(next.placement().queued());
    assertEquals(queued, next.request().queuedBehind());
  }

  @Test
  void theSweepActivatesAQueueWhoseActiveRequestIsGone() {
    String active = freezeActive();
    String queued = ask(feature()).request().id();
    // An ending nothing activated: the row the queue names is simply gone.
    QuarkusTransaction.requiringNew().run(() -> ReleaseRequest.delete("id", active));

    releaseRequests.sweep();

    ReleaseRequestDto read = releaseRequests.get(queued);
    assertNull(read.queuedBehind());
    assertNotNull(read.mergedSha(), "and the sweep folded it");
  }

  // -------------------------------------------------------------------------------------------
  // The sources door, and the threshold
  // -------------------------------------------------------------------------------------------

  @Test
  void anExplicitJoinOfAFrozenRequestAdmitsAFeatureABugAndAMaintenanceBranch() {
    String active = freezeActive();
    String activeQid = releaseRequests.get(active).qualifiedId();
    java.util.List<String> held = branchesOf(active).names;
    String feature = feature();
    String bug = ticket("BUG");
    String bump = "maintenance/java-deps/" + UUID.randomUUID();
    String automation = "maintenance/automations/entity-diagram/" + activeQid;

    for (String branch : new String[] {feature, bug, bump, automation}) {
      ExtractableResponse<Response> joined = addSource(active, branch);
      assertEquals(active, joined.path("request.id"), branch + " joined the request it named");
      assertEquals("JOINED", joined.path("placement.outcome"));
      assertEquals(Boolean.FALSE, joined.path("placement.queued"));
      String detail = joined.path("placement.detail");
      assertTrue(
          detail.startsWith(branch + " joined " + activeQid + " explicitly, past its feature freeze"),
          detail);
      assertEquals(
          Boolean.TRUE,
          joined.path("request.sources.find { it.name == '" + branch + "' }.explicitJoin"),
          branch);
    }
    ReleaseRequestDto read = releaseRequests.get(active);
    assertTrue(read.featureFreeze().frozen(), "still frozen");
    assertEquals(5, read.featureFreeze().features(), "the feature and the bug count");
    assertEquals(0, queuedRequestsOf(), "nothing was redirected");
    assertTrue(
        read.sources().stream()
            .filter(source -> held.contains(source.name()))
            .noneMatch(source -> source.explicitJoin()),
        "the sources it held when it froze joined past no freeze");
    assertTrue(
        merger.foldsOf("refs/heads/" + read.backingBranch()).stream()
            .anyMatch(fold -> fold.sources().contains("refs/heads/" + automation)),
        "the automation's branch is folded into the frozen request: "
            + merger.foldsOf("refs/heads/" + read.backingBranch()));
  }

  @Test
  void aJoinBelowTheFreezeIsNoExplicitJoin() {
    String active = ask(feature()).request().id();
    String branch = feature();

    ExtractableResponse<Response> joined = addSource(active, branch);

    assertEquals(active, joined.path("request.id"));
    assertEquals(
        Boolean.FALSE,
        joined.path("request.sources.find { it.name == '" + branch + "' }.explicitJoin"));
    String detail = joined.path("placement.detail");
    assertTrue(detail.endsWith(" as asked (2 of 3 counted branches)"), detail);
  }

  @Test
  void aReleasedRequestRefusesEveryJoinAndNamesTheNextRequest() {
    activeBuilds.answer(Optional.of(0));
    String active = freezeActive();
    ReleaseRequestDto queued = ask(feature()).request();
    stageRelease();
    verdict(mergedShaOf(active));
    await(() -> releaseRequests.get(active).state(), "RELEASED");
    String activeQid = releaseRequests.get(active).qualifiedId();
    java.util.List<String> held = branchesOf(active).names;

    for (String branch :
        new String[] {feature(), ticket("BUG"), "maintenance/java-deps/" + UUID.randomUUID()}) {
      DomainException refused =
          assertThrows(
              DomainException.class, () -> releaseRequests.addSource(active, branch, "alice", null));
      assertEquals(409, refused.statusCode());
      assertTrue(
          refused.getMessage().startsWith("Release request " + activeQid + " is released"),
          refused.getMessage());
      assertTrue(
          refused.getMessage().contains("; the next release request of "), refused.getMessage());
      assertTrue(
          refused.getMessage().endsWith(" is " + queued.qualifiedId()), refused.getMessage());
    }
    assertEquals(held, branchesOf(active).names, "nothing joined the released request");
  }

  @Test
  void aWithdrawnRequestWithNothingOpenSaysSoOnARefusedJoin() {
    String active = ask(feature()).request().id();
    releaseRequests.withdraw(active, "not this release", "alice");
    String branch = feature();

    DomainException refused =
        assertThrows(
            DomainException.class, () -> releaseRequests.addSource(active, branch, "alice", null));

    assertEquals(409, refused.statusCode());
    assertTrue(refused.getMessage().contains("is already WITHDRAWN"), refused.getMessage());
    assertTrue(
        refused.getMessage().endsWith("ask for a release of the branch to open the next one"),
        refused.getMessage());
  }

  @Test
  void aProjectThresholdOfTwoFreezesAtTwo() {
    projectFreeze(2);
    String active = ask(feature()).request().id();
    assertEquals(active, ask(feature()).request().id());

    ReleaseRequests.Asked third = ask(feature());

    assertTrue(third.placement().queued());
    assertEquals(active, third.request().queuedBehind());
    assertTrue(third.placement().detail().contains("frozen at 2 of 2"), third.placement().detail());
    ReleaseRequestDto read = releaseRequests.get(active);
    assertEquals(2, read.featureFreeze().threshold());
    assertEquals("PROJECT", read.featureFreeze().source(), "the project beats the platform");
    assertTrue(read.featureFreeze().frozen());
  }

  @Test
  void aThresholdOfZeroTurnsTheFreezeOff() {
    projectFreeze(0);
    String active = ask(feature()).request().id();
    for (int i = 0; i < 5; i++) {
      ReleaseRequests.Asked asked = ask(i % 2 == 0 ? feature() : ticket("BUG"));
      assertEquals(active, asked.request().id(), asked.placement().detail());
      assertFalse(asked.placement().queued());
    }
    ReleaseRequestDto read = releaseRequests.get(active);
    assertEquals(6, read.featureFreeze().features());
    assertEquals(0, read.featureFreeze().threshold());
    assertEquals("PROJECT", read.featureFreeze().source());
    assertFalse(read.featureFreeze().frozen(), "nothing is frozen at 0");
    assertEquals(0, queuedRequestsOf());
    String detail = ask(feature()).placement().detail();
    assertTrue(detail.endsWith("(the feature freeze is off)"), detail);
  }

  @Test
  void aRepositoryThresholdBeatsTheProjectsAndIsStoredForReads() {
    projectFreeze(2);
    repositoryFreeze("manual-review: false\nfeature_freeze: 4\n");

    String active = ask(feature()).request().id();
    ask(feature());
    ask(feature());
    assertEquals(active, ask(feature()).request().id(), "the fourth still joins at 4");
    ReleaseRequests.Asked fifth = ask(feature());

    assertTrue(fifth.placement().queued());
    assertTrue(fifth.placement().detail().contains("frozen at 4 of 4"), fifth.placement().detail());
    ReleaseRequestDto read = releaseRequests.get(active);
    assertEquals(4, read.featureFreeze().threshold());
    assertEquals("REPOSITORY", read.featureFreeze().source());
    assertEquals(
        4,
        QuarkusTransaction.requiringNew()
            .call(() -> Repository.<Repository>findById(repoId).releaseFeatureFreeze),
        "stored on the repository row, so a read never asks the git host");
  }

  @Test
  void aRepositoryThresholdOfZeroTurnsTheFreezeOffOverTheProjects() {
    projectFreeze(1);
    repositoryFreeze("feature_freeze: 0\n");

    String active = ask(feature()).request().id();
    assertEquals(active, ask(feature()).request().id());

    ReleaseRequestDto read = releaseRequests.get(active);
    assertEquals(0, read.featureFreeze().threshold());
    assertEquals("REPOSITORY", read.featureFreeze().source());
    assertFalse(read.featureFreeze().frozen());
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  /** The project's {@code release_requests.feature_freeze}, as the wrapper reconcile stores it. */
  private void projectFreeze(Integer value) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = Project.findById(projectId);
              project.releaseFeatureFreeze = value;
            });
  }

  /** The repository's own {@code .config/qits/release-requests.yml} at main, CI gate kept. */
  private void repositoryFreeze(String settings) {
    gitHost.gatedTreeFor(
        repoId, "refs/heads/main", Map.of(".config/qits/release-requests.yml", settings));
  }

  /** Three feature asks: the repository's active request, frozen. Answers its id. */
  private String freezeActive() {
    String active = ask(feature()).request().id();
    ask(feature());
    ask(feature());
    assertTrue(releaseRequests.get(active).featureFreeze().frozen(), "the premise: frozen");
    return active;
  }

  private ReleaseRequests.Asked ask(String branch) {
    return releaseRequests.ask(repoId, branch, "a release of " + branch, "alice", null);
  }

  private String feature() {
    return ticket("IMPROVEMENT");
  }

  private String ticket(String type) {
    long number =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("A " + type, "because", null, type, null),
                "seed")
            .entity()
            .number;
    return "ticket/" + projectId + "-" + number;
  }

  private record Branches(java.util.List<String> names, String feature) {}

  private Branches branchesOf(String requestId) {
    ReleaseRequestDto read = releaseRequests.get(requestId);
    java.util.List<String> names =
        read.sources().stream().filter(source -> !source.implicit()).map(s -> s.name()).toList();
    String feature =
        read.sources().stream()
            .filter(source -> source.feature())
            .map(s -> s.name())
            .findFirst()
            .orElse(null);
    return new Branches(names, feature);
  }

  private long queuedRequestsOf() {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.count("repoId = ?1 and queuedBehind is not null", repoId));
  }

  private String mergedShaOf(String id) {
    return releaseRequests.get(id).mergedSha();
  }

  private ExtractableResponse<Response> addSource(String requestId, String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"" + branch + "\"}")
        .post("/projects/api/repositories/" + repoId + "/release-requests/" + requestId + "/sources")
        .then()
        .statusCode(200)
        .extract();
  }

  /** The next release answers a fresh version whose tree declares a deployment. */
  private String stageRelease() {
    String tag = "2026.1024." + (100000 + (int) (Math.random() * 800000));
    executor.answer(ReleaseExecutor.Outcome.released(tag, RecordingBackingBranchMerger.freshSha()));
    gitHost.tree(
        "refs/tags/" + tag,
        Map.of("pom.xml", "irrelevant", ".config/qits/deployments.yml", "irrelevant"));
    return tag;
  }

  private void verdict(String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\""
                + BackingBranches.atFold(repoId, sha)
                + "\",\"commitSha\":\""
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

  private static void await(Supplier<String> read, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = read.get();
      if (expected.equals(last)) {
        return;
      }
      sleep();
    }
    fail("never reached " + expected + "; last seen " + last);
  }

  private static void awaitTrue(Supplier<Boolean> condition, String what) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      sleep();
    }
    fail("never true: " + what);
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted");
    }
  }
}
