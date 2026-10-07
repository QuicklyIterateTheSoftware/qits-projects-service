package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The <b>second</b> gate: a green build is no longer the whole of what releases a wrapper. Where
 * {@code ApprovalPolicy} says a person has to be asked — today the {@link
 * RepositoryArchetype#PROJECT} archetype — a request that has passed every build-gate arm waits for
 * a decision about its <em>current</em> fold, and releases, or is rejected, on what that decision
 * says.
 *
 * <p><b>Every case here is driven by inserting approval rows directly</b>, because the doors that
 * record a decision do not exist yet. The re-evaluation is then triggered the way the platform
 * already triggers one — a verdict arriving over the real bus listener for the request's
 * merged sha — which is exactly the path an approval door will take when it calls the gate after its
 * own write. Nothing here reaches a git host or qits-ci: the merger, the executor and the active-run
 * probe are the package's recording fakes.
 *
 * <p><b>Both archetypes are seeded, and the plain one is the control.</b> The rule this feature had
 * to keep is that a repository nobody has to ask about behaves byte for byte as it did, so the last
 * test releases one on a green verdict alone — with an approval row sitting in the table, which must
 * change nothing at all.
 */
@QuarkusTest
public class ReleaseRequestApprovalGateTest {

  /** The default of {@code qits.projects.release-requests.unattended-requesters}. */
  private static final String ROBOT = "dev-qits-maintenance";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  /** Injected for one test only: the sweep is the belt under the retry path and has to be asked. */
  @Inject eu.wohlben.qits.projects.control.ReleaseRequests releaseRequests;

  /**
   * Not a subject here, and injected precisely so that it is not one. The fixture's wrapper is
   * staged as a real one — an alias to be addressed by, and two source branches that declare no
   * submodules. The <b>automations gate</b> sits in front of the approval gate, and the suite's fake
   * qits-maintenance answers that no automation applies, so every request here passes it on its
   * first look and what holds it is the gate this class is about.
   */
  @Inject RecordingReleaseGitHost gitHost;

  /** What each fold changed — "nothing" unless a test of the content rule scripts otherwise. */
  @Inject RecordingFoldChanges foldChanges;

  private String projectId;
  private String wrapperRepoId;
  private String plainRepoId;

  /** Every request this test opened, so the approval rows it inserted can be dropped again. */
  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    foldChanges.reset();
    requestIds.clear();
    // A wrapper whose branches declare no submodules. See the field's javadoc.
    gitHost.gatedTree("refs/heads/main", java.util.Map.of("README.md", "no estate here"));
    gitHost.tree("refs/heads/work", java.util.Map.of("README.md", "no estate here"));
    // A green build with nothing still in flight, so the build gate is out of the way in every test
    // here and what holds a request is only ever the approval gate.
    activeBuilds.answer(Optional.of(0));
    projectId = "approval-gate-project-" + UUID.randomUUID();
    wrapperRepoId = "approval-gate-wrapper-" + UUID.randomUUID();
    // THE WRAPPER'S OWN MAIN SAYS IT REQUIRES A PERSON. Approval stopped being "is a wrapper" and
    // became "says manual-review: true", so the fixture declares it where the platform's own wrapper
    // declares it — in the repository's .config/qits/release-requests.yml. The plain repository
    // beside it carries no such file and is the control, exactly as it was when the archetype
    // decided this.
    gitHost.gatedTreeFor(
        wrapperRepoId,
        "refs/heads/main",
        java.util.Map.of(
            "README.md", "no estate here",
            ".config/qits/release-requests.yml", "manual-review: true\n"));
    plainRepoId = "approval-gate-plain-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "approval-gate";
              project.slug = "approval-gate-" + UUID.randomUUID();
              project.persist();
              // The wrapper is ALIASED: a wrapper is a name-addressed thing, and qits-maintenance
              // addresses the automations it asks about by name. The plain one needs no name here.
              alias(project, persistRepository(project, wrapperRepoId, RepositoryArchetype.PROJECT));
              persistRepository(project, plainRepoId, RepositoryArchetype.SERVICE);
            });
  }

  private static eu.wohlben.qits.projects.entity.Repository persistRepository(
      Project project, String repoId, RepositoryArchetype archetype) {
    Repository repository = new Repository();
    repository.id = repoId;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = archetype;
    repository.persist();
    return repository;
  }

  private static void alias(Project project, Repository repository) {
    eu.wohlben.qits.projects.entity.RepositoryName name =
        new eu.wohlben.qits.projects.entity.RepositoryName();
    name.project = project;
    name.repository = repository;
    name.name = "approval-gate-approval-gate";
    name.persist();
  }

  /**
   * The discipline {@code ReleaseRequestFlowTest} states — no open request outlives its test,
   * because {@code sweep()} walks every open row there is — plus this class's own table: approval
   * rows have no foreign key to anything (that is the point of them, they outlive the request), so
   * nothing cascades them away and the test that made them deletes them.
   */
  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", wrapperRepoId);
              ReleasedTagPendingMerge.delete("repoId = ?1", plainRepoId);
              if (!requestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", requestIds);
              }
            });
  }

  // -----------------------------------------------------------------------------------------
  // The gate
  // -----------------------------------------------------------------------------------------

  /**
   * The waiting arm, which is the one that costs a release: CI vouched for the fold and the request
   * still does not move, because on a wrapper a green build is only the first of two answers. The
   * sentence names the fold, so the person being waited on can see which one they are deciding
   * about.
   */
  @Test
  public void aGreenBuildOnAWrapperWaitsForAPersonAndSaysSo() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    assertNotNull(merged, "the create folds the sources at once");

    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"), "green, and still not released");
    assertEquals(
        "Waiting for a person to approve " + merged.substring(0, 10),
        request.getString("detail"));
    assertEquals(true, request.getBoolean("approvalRequired"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"), "nobody has decided about this fold");
    assertNull(request.getString("approvedAt"));
    assertNull(request.getString("approvalNote"));
    assertEquals(0, executor.calls().size(), "and the door was never reached");
  }

  /** The yes: a decision about the fold that is current, and the request goes the way it always did. */
  @Test
  public void anApprovalAtTheCurrentFoldReleasesIt() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");
    assertEquals("PENDING", stateOf(wrapperRepoId, id));

    record(id, merged, ReleaseRequestApproval.Decision.APPROVED, "ada", "ship it");
    reEvaluate(wrapperRepoId, merged);
    awaitState(wrapperRepoId, id, "RELEASED");

    assertEquals(1, executor.calls().size());
    assertEquals(
        merged,
        executor.calls().get(0).expectedSha(),
        "and the door is pinned to the fold the person approved");

    var request = request(wrapperRepoId, id);
    assertEquals("APPROVED", request.getString("approvalState"));
    assertEquals("ada", request.getString("approvedBy"));
    assertNotNull(request.getString("approvedAt"));
    assertEquals("ship it", request.getString("approvalNote"));
  }

  /**
   * The no, and the part of it that is a decision rather than a mechanism: a decline is <b>not</b> an
   * unattended-gate ticket. That belt exists for a red build nobody is watching; a person saying no
   * is somebody watching, by definition, and filing them a bug about their own answer is noise on
   * the one repository where a human is already engaged. The requester here is the platform's bump
   * robot precisely so that a ticket <em>would</em> be filed if this went through the red-build arm.
   */
  @Test
  public void aDeclineRejectsWithThePersonsSentenceAndFilesNoTicket() {
    String id = create(wrapperRepoId, "work", ROBOT);
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    record(id, merged, ReleaseRequestApproval.Decision.DECLINED, "ada", "not before the freeze");
    reEvaluate(wrapperRepoId, merged);

    var request = request(wrapperRepoId, id);
    assertEquals("REJECTED", request.getString("state"));
    assertEquals("Declined by ada: not before the freeze", request.getString("detail"));
    assertEquals("DECLINED", request.getString("approvalState"));
    assertEquals("ada", request.getString("approvedBy"), "the fields carry whichever answer is current");
    assertEquals("not before the freeze", request.getString("approvalNote"));

    // The filing is synchronous with the gate's own transaction on this path, so an absent ticket
    // here is an absent ticket for good.
    assertNull(request.getString("gateTicketId"), "a person decided; nobody needs telling");
    assertEquals(List.of(), ticketsOnProject());
    assertEquals(0, executor.calls().size());
  }

  /**
   * <b>A CI verdict never takes a person's no back</b> (ticket qits-309). A green retry re-opens a
   * rejection a red build made, at the same fold and with nothing pushed — which is exactly the
   * shape that could undo a decline if the path were admitted by <em>state</em>. It is admitted by
   * RUN: the request records which run rejected it, a decline records none, and a verdict that
   * superseded some other run therefore matches nothing here.
   *
   * <p>The retry below re-fires the very run whose green verdict put this request in front of a
   * person, which is the most dangerous shape available — a genuine supersession of a genuine run of
   * this very fold. Both paths are exercised: the verdict's own, and the sweep that is the belt
   * under it.
   */
  @Test
  public void aGreenRetryNeverReOpensARequestAPersonDeclined() {
    String id = create(wrapperRepoId, "work", ROBOT);
    String merged = mergedShaOf(wrapperRepoId, id);
    // Unique ids: commit_build_status is keyed on the run and nothing empties it between tests.
    String qa = "run-qa-" + UUID.randomUUID();
    String retry = "run-qa-retry-" + UUID.randomUUID();
    verdict(wrapperRepoId, "BuildSuccessful", merged, qa, null, "");

    record(id, merged, ReleaseRequestApproval.Decision.DECLINED, "ada", "not before the freeze");
    reEvaluate(wrapperRepoId, merged);
    assertEquals("REJECTED", stateOf(wrapperRepoId, id));

    verdict(wrapperRepoId, "BuildSuccessful", merged, retry, qa, "");
    releaseRequests.sweep();

    var request = request(wrapperRepoId, id);
    assertEquals("REJECTED", request.getString("state"), "a run was retried; a person still said no");
    assertEquals("Declined by ada: not before the freeze", request.getString("detail"));
    assertEquals("DECLINED", request.getString("approvalState"));
    assertEquals(0, executor.calls().size());
  }

  /** A decline with nothing said is the bare fact, not a sentence with an empty tail. */
  @Test
  public void aDeclineWithNoNoteIsJustTheName() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    record(id, merged, ReleaseRequestApproval.Decision.DECLINED, "ada", null);
    reEvaluate(wrapperRepoId, merged);

    assertEquals("Declined by ada", request(wrapperRepoId, id).getString("detail"));
  }

  /**
   * <b>The re-arm carries the invalidation, and it carries it for the approval gate too.</b> A push
   * to a participating branch re-folds the request onto a new merged sha, and every decision made
   * about the old one stops matching — with no column cleared and no path that had to remember to
   * clear one. Pushing a fix onto a declined request is the ordinary way to answer it, exactly as it
   * is for a red build.
   */
  @Test
  public void aNewFoldReArmsADeclinedRequestAndTheOldDecisionStopsCounting() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String firstFold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", firstFold, "");
    record(id, firstFold, ReleaseRequestApproval.Decision.DECLINED, "ada", "no");
    reEvaluate(wrapperRepoId, firstFold);
    assertEquals("REJECTED", stateOf(wrapperRepoId, id));

    headMoved(wrapperRepoId, "work");
    awaitState(wrapperRepoId, id, "PENDING");
    String secondFold = mergedShaOf(wrapperRepoId, id);
    assertNotEquals(firstFold, secondFold, "the push produced new content");

    // And the fresh fold is a fold nobody has looked at, however much history the request carries.
    verdict(wrapperRepoId, "BuildSuccessful", secondFold, "");
    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"), "the decline judged a fold this request has left");
    assertEquals(
        "Waiting for a person to approve " + secondFold.substring(0, 10),
        request.getString("detail"));
  }

  /**
   * The same rule from the other side, and the one that would be a security hole if it went the
   * other way: an approval names the fold it was made about, so it can never authorise different
   * content. A decision recorded against a sha this request is not on opens nothing.
   */
  @Test
  public void anApprovalOfASupersededFoldOpensNothing() {
    String id = create(wrapperRepoId, "work", "wohlben");
    String merged = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, "BuildSuccessful", merged, "");

    record(
        id,
        "sha-somebody-else-" + UUID.randomUUID(),
        ReleaseRequestApproval.Decision.APPROVED,
        "ada",
        "approved, but not this");
    reEvaluate(wrapperRepoId, merged);

    var request = request(wrapperRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"));
    assertEquals(0, executor.calls().size());
  }

  /**
   * <b>The control.</b> A repository the policy does not ask about releases on its gates alone, and
   * the approval row sitting in the table for it changes nothing: {@code NOT_REQUIRED} is not
   * {@code APPROVED} and the decision fields stay empty, because nobody was asked.
   */
  @Test
  public void aRepositoryThatNeedsNoApprovalIsUntouched() {
    String id = create(plainRepoId, "work", "wohlben");
    String merged = mergedShaOf(plainRepoId, id);
    record(id, merged, ReleaseRequestApproval.Decision.DECLINED, "ada", "ignored");

    verdict(plainRepoId, "BuildSuccessful", merged, "");
    awaitState(plainRepoId, id, "RELEASED");

    var request = request(plainRepoId, id);
    assertEquals(false, request.getBoolean("approvalRequired"));
    assertEquals("NOT_REQUIRED", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"), "nobody was asked, so nobody answered");
    assertNull(request.getString("approvedAt"));
    assertNull(request.getString("approvalNote"));
    assertEquals(1, executor.calls().size());
  }

  /** The list read answers the same derived facts as the single read, and in the same words. */
  @Test
  public void theProjectListAnswersTheGateToo() {
    String wrapper = create(wrapperRepoId, "work", "wohlben");
    verdict(wrapperRepoId, "BuildSuccessful", mergedShaOf(wrapperRepoId, wrapper), "");
    String plain = create(plainRepoId, "work", "wohlben");

    var entries =
        given()
            .get("/projects/api/projects/" + projectId + "/release-requests")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        "WAITING",
        entries.getString("requests.find { it.id == '" + wrapper + "' }.approvalState"));
    assertEquals(
        "NOT_REQUIRED",
        entries.getString("requests.find { it.id == '" + plain + "' }.approvalState"));
  }


  // -----------------------------------------------------------------------------------------
  // The content rule: a fold that changes .config/qits/ asks a person, whatever main configures
  // -----------------------------------------------------------------------------------------

  /**
   * <b>A request may not rewrite its own rules unseen.</b> The plain repository configures no
   * approval, and its fold adds, modifies, deletes and renames under {@code .config/qits/} — a rename
   * out of the directory counting by its old path. Every one of those holds the request at the
   * approval gate, and the gate says which paths did it. The requester is the bump robot on purpose:
   * a machine requester is not exempt.
   */
  @Test
  public void aFoldChangingConfigQitsIsHeldForAPersonAndNamesThePaths() {
    foldChanges.changes(
        plainRepoId,
        List.of(
            RecordingFoldChanges.file("MODIFIED", "README.md", null),
            RecordingFoldChanges.file("ADDED", ".config/qits/deployments.yml", null),
            RecordingFoldChanges.file("MODIFIED", ".config/qits/release.yml", null),
            RecordingFoldChanges.file("DELETED", ".config/qits/release-requests.yml", null),
            RecordingFoldChanges.file("RENAMED", ".config/qits/b.yml", ".config/qits/a.yml"),
            RecordingFoldChanges.file("RENAMED", "docs/moved.yml", ".config/qits/moved.yml")));
    String id = create(plainRepoId, "work", ROBOT);
    String merged = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, "BuildSuccessful", merged, "");

    var request = request(plainRepoId, id);
    assertEquals("PENDING", request.getString("state"), "green, and a person has not seen it");
    assertEquals(
        "Waiting for a person to approve " + merged.substring(0, 10),
        request.getString("detail"));
    assertEquals(true, request.getBoolean("approvalRequired"));
    assertEquals("WAITING", request.getString("approvalState"));
    String expected =
        "changes .config/qits/: .config/qits/deployments.yml, .config/qits/release.yml,"
            + " .config/qits/release-requests.yml, .config/qits/a.yml, .config/qits/b.yml,"
            + " .config/qits/moved.yml";
    assertEquals("PENDING", request.getString("gates.find { it.kind == 'APPROVAL' }.state"));
    assertEquals(expected, request.getString("gates.find { it.kind == 'APPROVAL' }.detail"));
    assertNull(
        request.getString("gates.find { it.kind == 'CI' }.detail"), "only the approval gate says why");
    assertEquals(0, executor.calls().size());

    // And the list read answers the same reason, asked per request rather than per repository.
    var entries =
        given()
            .get("/projects/api/projects/" + projectId + "/release-requests")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        expected,
        entries.getString(
            "requests.find { it.id == '" + id + "' }.gates.find { it.kind == 'APPROVAL' }.detail"));
  }

  /**
   * Gitlinks are somebody else's tree: a fold that only moves pins — even one mounted under {@code
   * .config/qits/} — is this repository changing none of its own rules.
   */
  @Test
  public void aGitlinkOnlyFoldIsNotHeld() {
    foldChanges.changes(
        plainRepoId,
        List.of(
            RecordingFoldChanges.gitlink("components/qits-thing/qits-thing-service"),
            RecordingFoldChanges.gitlink(".config/qits/vendored")));
    String id = create(plainRepoId, "work", "wohlben");
    verdict(plainRepoId, "BuildSuccessful", mergedShaOf(plainRepoId, id), "");
    awaitState(plainRepoId, id, "RELEASED");

    var request = request(plainRepoId, id);
    assertEquals(false, request.getBoolean("approvalRequired"));
    assertNull(request.get("gates.find { it.kind == 'APPROVAL' }"), "no approval gate at all");
  }

  /** An unreadable fold is held, and says so: "could not look" is not the cheap way past a person. */
  @Test
  public void aFoldWhoseChangesCannotBeReadIsHeld() {
    foldChanges.unreadable(plainRepoId, "the mirror is on fire");
    String id = create(plainRepoId, "work", "wohlben");
    verdict(plainRepoId, "BuildSuccessful", mergedShaOf(plainRepoId, id), "");

    var request = request(plainRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertEquals(
        "the changes to .config/qits/ could not be read: the mirror is on fire",
        request.getString("gates.find { it.kind == 'APPROVAL' }.detail"));
    assertEquals(0, executor.calls().size());
  }

  /** The requirement is the current fold's: a refold that drops the change releases on green. */
  @Test
  public void aRefoldThatDropsTheConfigChangeClearsTheRequirement() {
    foldChanges.changes(
        plainRepoId, List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/release.yml", null)));
    String id = create(plainRepoId, "work", "wohlben");
    String firstFold = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, "BuildSuccessful", firstFold, "");
    assertEquals("WAITING", request(plainRepoId, id).getString("approvalState"));

    foldChanges.changes(plainRepoId, List.of(RecordingFoldChanges.file("MODIFIED", "README.md", null)));
    headMoved(plainRepoId, "work");
    String secondFold = awaitNewFold(plainRepoId, id, firstFold);
    verdict(plainRepoId, "BuildSuccessful", secondFold, "");
    awaitState(plainRepoId, id, "RELEASED");

    var request = request(plainRepoId, id);
    assertEquals(false, request.getBoolean("approvalRequired"));
    assertEquals("NOT_REQUIRED", request.getString("approvalState"));
  }

  /**
   * An approval names the fold it read. A refold that changes {@code .config/qits/} again is content
   * nobody approved, so the request asks again — the yes at the older sha counts for nothing.
   */
  @Test
  public void aRefoldThatChangesConfigQitsAgainAfterAnApprovalAsksAgain() {
    foldChanges.changes(
        plainRepoId, List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/release.yml", null)));
    String id = create(plainRepoId, "work", "wohlben");
    String firstFold = mergedShaOf(plainRepoId, id);
    record(id, firstFold, ReleaseRequestApproval.Decision.APPROVED, "ada", "fine at this fold");
    assertEquals("APPROVED", request(plainRepoId, id).getString("approvalState"));

    foldChanges.changes(
        plainRepoId,
        List.of(RecordingFoldChanges.file("ADDED", ".config/qits/deployments.yml", null)));
    headMoved(plainRepoId, "work");
    String secondFold = awaitNewFold(plainRepoId, id, firstFold);
    verdict(plainRepoId, "BuildSuccessful", secondFold, "");

    var request = request(plainRepoId, id);
    assertEquals("PENDING", request.getString("state"));
    assertEquals("WAITING", request.getString("approvalState"));
    assertNull(request.getString("approvedBy"), "the yes judged a fold this request has left");
    assertEquals(
        "changes .config/qits/: .config/qits/deployments.yml",
        request.getString("gates.find { it.kind == 'APPROVAL' }.detail"));
    assertEquals(0, executor.calls().size());
  }

  /**
   * <b>A settled request reads no git.</b> Its {@code release/<id>} ref is gone, so a read would
   * fetch the mirror per row and then fail closed into a WAITING nobody can answer. Without a
   * recorded approval it simply was not asked about, whatever its fold changed; with one, it was —
   * and the history still says who approved. Both the single read and the list read are asked.
   */
  @Test
  public void aSettledRequestNeverReadsItsFoldAndAnswersFromTheRecord() {
    foldChanges.changes(
        plainRepoId, List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/release.yml", null)));
    String silent = settled(plainRepoId, ReleaseRequest.State.FINALIZED);
    String approved = settled(plainRepoId, ReleaseRequest.State.RELEASED);
    record(approved, "settled-fold-" + approved, ReleaseRequestApproval.Decision.APPROVED, "ada", "fine");

    var nobody = request(plainRepoId, silent);
    assertEquals(false, nobody.getBoolean("approvalRequired"));
    assertEquals("NOT_REQUIRED", nobody.getString("approvalState"));
    assertNull(nobody.get("gates.find { it.kind == 'APPROVAL' }"));

    var somebody = request(plainRepoId, approved);
    assertEquals(true, somebody.getBoolean("approvalRequired"));
    assertEquals("APPROVED", somebody.getString("approvalState"));
    assertEquals("ada", somebody.getString("approvedBy"));
    assertEquals("PASSED", somebody.getString("gates.find { it.kind == 'APPROVAL' }.state"));
    assertNull(
        somebody.getString("gates.find { it.kind == 'APPROVAL' }.detail"),
        "no manual-review configured, and a recorded approval speaks for itself");

    var entries =
        given()
            .get("/projects/api/projects/" + projectId + "/release-requests?state=all")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        false, entries.getBoolean("requests.find { it.id == '" + silent + "' }.approvalRequired"));
    assertEquals(
        "APPROVED", entries.getString("requests.find { it.id == '" + approved + "' }.approvalState"));
    assertEquals("ada", entries.getString("requests.find { it.id == '" + approved + "' }.approvedBy"));

    assertEquals(0, foldChanges.reads(plainRepoId), "not one git read for history");
  }

  /** On a settled request, manual-review still says so in its own words — and still reads no git. */
  @Test
  public void aSettledRequestOfAManualReviewRepositoryKeepsTheConfigurationsSentence() {
    String id = settled(wrapperRepoId, ReleaseRequest.State.FINALIZED);

    var request = request(wrapperRepoId, id);
    assertEquals(true, request.getBoolean("approvalRequired"));
    assertEquals(
        "configured by manual-review",
        request.getString("gates.find { it.kind == 'APPROVAL' }.detail"));
    assertEquals(0, foldChanges.reads(wrapperRepoId));
  }

  /** A request row written straight into the table at a settled state, its fold named after it. */
  private String settled(String repoId, ReleaseRequest.State state) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = new ReleaseRequest();
              row.id = id;
              row.repoId = repoId;
              row.projectId = projectId;
              row.summary = "settled history";
              row.state = state;
              row.mergedSha = "settled-fold-" + id;
              row.createdAt = Instant.now();
              row.armedAt = row.createdAt;
              row.updatedAt = row.createdAt;
              row.persist();
            });
    requestIds.add(id);
    return id;
  }

  /** Both rules at once: the configuration's reason first, the content's after it. */
  @Test
  public void manualReviewAndAConfigChangeAreBothNamed() {
    foldChanges.changes(
        wrapperRepoId,
        List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/release-requests.yml", null)));
    String id = create(wrapperRepoId, "work", "wohlben");
    verdict(wrapperRepoId, "BuildSuccessful", mergedShaOf(wrapperRepoId, id), "");

    var request = request(wrapperRepoId, id);
    assertEquals(
        "configured by manual-review; changes .config/qits/: .config/qits/release-requests.yml",
        request.getString("gates.find { it.kind == 'APPROVAL' }.detail"));
  }

  /** The configuration alone says so in its own words. */
  @Test
  public void manualReviewAloneSaysSo() {
    String id = create(wrapperRepoId, "work", "wohlben");
    verdict(wrapperRepoId, "BuildSuccessful", mergedShaOf(wrapperRepoId, id), "");

    assertEquals(
        "configured by manual-review",
        request(wrapperRepoId, id).getString("gates.find { it.kind == 'APPROVAL' }.detail"));
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  /** The refold runs off the head event; poll until the request is on a fold other than {@code old}. */
  private String awaitNewFold(String repoId, String id, String old) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      String now = mergedShaOf(repoId, id);
      if (now != null && !now.equals(old)) {
        return now;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("request " + id + " never refolded off " + old);
    return null;
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
                    + "\",\"summary\":\"a gated release\",\"requester\":\""
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

  /** One decision, inserted straight into the table — the doors that will write these do not exist yet. */
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

  /**
   * Ask the gate again, which is what an approval door will do after its own write. A further
   * verdict for the same fold is the trigger the platform already has, and it settles the request in
   * the same consumption — so it stands in for the door until there is one.
   */
  private void reEvaluate(String repoId, String mergedSha) {
    verdict(repoId, "BuildSuccessful", mergedSha, "");
  }

  private void verdict(String repoId, String name, String sha, String extra) {
    verdict(repoId, name, sha, "run-" + UUID.randomUUID(), null, extra);
  }

  /**
   * The same with the run pinned, and optionally saying which run it re-fires — {@code qits ci
   * retry}'s shape, needed here only to prove that no lineage reaches a rejection a person made.
   */
  private void verdict(
      String repoId, String name, String sha, String runId, String retryOfRunId, String extra) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"work\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\""
                + (retryOfRunId == null ? "" : ",\"retryOfRunId\":\"" + retryOfRunId + "\"")
                + ",\"runId\":\""
                + runId
                + "\""
                + extra
                + "}",
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

  private List<String> ticketsOnProject() {
    return given()
        .get("/projects/api/projects/" + projectId + "/work?archetype=TICKET")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("entities.id", String.class);
  }

  /** The execution runs on the request worker, so a terminal state is polled, never assumed. */
  private void awaitState(String repoId, String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = stateOf(repoId, id);
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
}
