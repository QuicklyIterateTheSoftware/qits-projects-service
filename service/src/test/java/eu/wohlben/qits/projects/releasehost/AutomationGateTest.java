package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestAutomationWaiver;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
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
 * <b>Every fold asks for automations, and the request holds until every applicable one is fresh for
 * {@code mergedSha}</b> (epic qits-978) — the estate gate, generalised from the wrapper's gitlink
 * pins to every release-request automation qits-maintenance says applies.
 *
 * <p>The fixture is the estate gate's own: a {@link RepositoryArchetype#PROJECT} wrapper and a plain
 * {@link RepositoryArchetype#SERVICE} repository, both aliased (qits-maintenance addresses a
 * repository by its catalogue name), with nothing in flight so the build gate is a verdict away.
 * Nothing reaches qits-maintenance: {@link FakeReleaseRequestAutomations} beats the HTTP adapter and
 * is scripted per repository name, answering the same states on the trigger and on the read.
 *
 * <p><b>The load-bearing test is {@link #aRedVerdictWhilePendingHoldsThenRejectsOnceFresh}</b> — the
 * one ordering change this gate made. A red build on a fold an automation is about to rewrite holds
 * instead of rejecting, and once the automations are fresh at that same fold the red is read again
 * and rejects exactly as it always did.
 */
@QuarkusTest
public class AutomationGateTest {

  private static final String SCREENSHOTS = "screenshot-baselines";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingFoldChanges foldChanges;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject AutomationLedger ledger;

  @Inject ReleaseRequests releaseRequests;

  private String projectId;
  private String wrapperRepoId;
  private String plainRepoId;
  private String wrapperName;
  private String plainName;

  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    foldChanges.reset();
    automations.reset();
    requestIds.clear();
    activeBuilds.answer(Optional.of(0));

    String unique = UUID.randomUUID().toString();
    projectId = "automations-project-" + unique;
    wrapperRepoId = "automations-wrapper-" + unique;
    plainRepoId = "automations-plain-" + unique;
    // Names unique per test, because the fake is scripted by name and outlives a test.
    wrapperName = "automations-wrapper-" + unique.substring(0, 8);
    plainName = "automations-plain-" + unique.substring(0, 8);
    gitHost.gatedTree("refs/heads/work", Map.of("README.md", "work"));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "automations";
              project.slug = "automations-" + unique;
              project.persist();
              alias(project, repository(project, wrapperRepoId, RepositoryArchetype.PROJECT), wrapperName);
              alias(project, repository(project, plainRepoId, RepositoryArchetype.SERVICE), plainName);
            });
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

  /** No open request outlives its test, the waivers go with it, and the fake is left at rest. */
  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              if (!requestIds.isEmpty()) {
                ReleaseRequestAutomationWaiver.delete("requestId in ?1", requestIds);
              }
            });
    requestIds.forEach(ledger::forget);
    automations.reset();
    foldChanges.reset();
  }

  // -----------------------------------------------------------------------------------------
  // The estate gate's cases, on the port
  // -----------------------------------------------------------------------------------------

  /**
   * <b>No note is a hold</b>, and qits-maintenance not answering is how a wrapper gets none: green,
   * and nothing released. The record is positive, so the path that never wrote one is the one that
   * holds.
   */
  @Test
  public void aWrapperWithNoNoteHolds() {
    automations.answerNothing();

    String id = create(wrapperRepoId, "work");
    String merged = mergedShaOf(wrapperRepoId, id);
    assertFalse(automations.askedAbout(id).isEmpty(), "it did ask");
    verdict(wrapperRepoId, merged);

    JsonPath held = request(wrapperRepoId, id);
    assertEquals("PENDING", held.getString("state"), "green, and deliberately still not released");
    assertEquals(
        "The automations could not be established for "
            + merged.substring(0, 10)
            + ": qits-maintenance could not be asked about them",
        held.getString("detail"));
    assertEquals("UNKNOWN", gateState(held, "AUTOMATIONS"));
    assertEquals(0, executor.calls().size(), "nothing was released, which is the whole claim");
  }

  @Test
  public void freshAtThisFoldReleases() {
    automations.answer(wrapperName, "estate-pins", "FRESH");

    String id = create(wrapperRepoId, "work");
    String merged = mergedShaOf(wrapperRepoId, id);
    JsonPath before = request(wrapperRepoId, id);
    assertEquals("PASSED", gateState(before, "AUTOMATIONS"));
    assertEquals(List.of("estate-pins"), before.getList("automations.kind"));
    assertEquals(List.of("FRESH"), before.getList("automations.state"));
    assertEquals(merged, before.getString("automations[0].foldSha"));

    verdict(wrapperRepoId, merged);
    awaitState(wrapperRepoId, id, "RELEASED");
    assertEquals(1, executor.calls().size());
    assertEquals(merged, executor.calls().get(0).expectedSha());
  }

  /** A FRESH note names the fold it is about; a re-fold leaves it behind and the gate holds again. */
  @Test
  public void freshAtAnOldFoldHolds() {
    automations.answer(wrapperName, "estate-pins", "FRESH");
    String id = create(wrapperRepoId, "work");
    String firstFold = mergedShaOf(wrapperRepoId, id);

    // The far side goes quiet, and then a push re-folds the request.
    automations.answerNothing();
    headMoved(wrapperRepoId, "work");
    awaitFoldToMove(wrapperRepoId, id, firstFold);
    String secondFold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, secondFold);

    JsonPath held = request(wrapperRepoId, id);
    assertEquals("PENDING", held.getString("state"));
    assertTrue(
        held.getString("detail").startsWith("The automations could not be established for "),
        held.getString("detail"));
    assertFalse(ledger.fresh(id, secondFold));
    assertEquals(0, executor.calls().size(), "fresh about the first fold says nothing of the second");
  }

  /**
   * A restart empties the ledger — memory, on purpose — so every open request reads as "no note"
   * and holds; the evaluation that held it asks again, and the next sweep releases it.
   */
  @Test
  public void aRestartEmptiesTheLedgerAndTheNextSweepReAsks() {
    String id = create(wrapperRepoId, "work");
    String merged = mergedShaOf(wrapperRepoId, id);
    assertEquals(1, automations.askedAbout(id).size());

    ledger.forget(id); // the restart
    verdict(wrapperRepoId, merged);

    assertEquals("PENDING", stateOf(wrapperRepoId, id), "no note is a hold, even for a green fold");
    assertEquals(2, automations.askedAbout(id).size(), "and the hold asked again");

    releaseRequests.sweep();
    awaitState(wrapperRepoId, id, "RELEASED");
  }

  // -----------------------------------------------------------------------------------------
  // Every repository
  // -----------------------------------------------------------------------------------------

  /** A repository no automation applies to is asked, answers an empty list, and releases at once. */
  @Test
  public void anOrdinaryRepositoryWithNoAutomationReleasesOnTheSamePass() {
    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);

    JsonPath before = request(plainRepoId, id);
    assertEquals(List.of(), before.getList("automations"), "asked, and nothing applies");
    assertEquals("PASSED", gateState(before, "AUTOMATIONS"));
    FakeReleaseRequestAutomations.Asked ask = automations.askedAbout(id).get(0);
    assertEquals(plainName, ask.repositoryName(), "addressed by the catalogue name");
    assertEquals(merged, ask.foldSha());
    assertEquals(List.of("work"), ask.sourceBranches(), "main is a source and never a target");

    verdict(plainRepoId, merged);
    awaitState(plainRepoId, id, "RELEASED");
    assertEquals(1, executor.calls().size());
  }

  @Test
  public void anOrdinaryRepositoryWithScreenshotsRunningHoldsWithTheSentence() {
    automations.answer(plainName, SCREENSHOTS, "RUNNING");

    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, merged);

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"));
    assertEquals(
        "Waiting for automations at " + merged.substring(0, 10) + ": Screenshot baselines running",
        held.getString("detail"));
    assertEquals("PENDING", gateState(held, "AUTOMATIONS"));
    assertEquals(List.of("RUNNING"), held.getList("automations.state"));
    assertEquals("run-" + SCREENSHOTS, held.getString("automations[0].runId"));
    assertEquals(0, executor.calls().size());
  }

  /**
   * A kind that does not apply is listed on the request with its reason, and holds nothing back:
   * the gate passes on the kinds that apply, and its checks name only those.
   */
  @Test
  public void aNotApplicableKindIsListedAndHoldsNothing() {
    automations.answer(plainName, "entity-diagram", "FRESH");
    automations.answer(plainName, SCREENSHOTS, FakeReleaseRequestAutomations.NOT_APPLICABLE);

    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);
    JsonPath before = request(plainRepoId, id);
    assertEquals(List.of("entity-diagram", SCREENSHOTS), before.getList("automations.kind"));
    assertEquals(List.of("FRESH", "NOT_APPLICABLE"), before.getList("automations.state"));
    assertEquals(
        FakeReleaseRequestAutomations.NOT_APPLICABLE_REASON,
        before.getString("automations[1].detail"));
    assertEquals(null, before.getString("automations[1].runId"));
    assertEquals("PASSED", gateState(before, "AUTOMATIONS"));
    assertEquals(
        List.of("Entity diagram"),
        before.getList("qualityGates.find { it.kind == 'automations' }.checks.name"),
        "a kind that does not apply is no check of the gate");

    verdict(plainRepoId, merged);
    awaitState(plainRepoId, id, "RELEASED");
  }

  /**
   * Beside a kind that does not apply, the request waits for the running kind alone, and a waiver
   * leaves the kind that does not apply as it is.
   */
  @Test
  public void aNotApplicableKindBesideARunningOneWaitsOnlyForTheRunningOne() {
    automations.answer(plainName, SCREENSHOTS, "RUNNING");
    automations.answer(plainName, "entity-diagram", FakeReleaseRequestAutomations.NOT_APPLICABLE);

    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, merged);

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"));
    assertEquals(
        "Waiting for automations at " + merged.substring(0, 10) + ": Screenshot baselines running",
        held.getString("detail"));
    assertEquals("PENDING", gateState(held, "AUTOMATIONS"));

    waive(id, merged);
    JsonPath waived = request(plainRepoId, id);
    assertEquals("PASSED", gateState(waived, "AUTOMATIONS"));
    assertEquals(List.of("WAIVED", "NOT_APPLICABLE"), waived.getList("automations.state"));
  }

  /**
   * A red automation run holds: a person's turn (push, re-run or waive), never a rejection — beside
   * a red verdict too, see {@link #aRedVerdictBesideFailedAutomationsHoldsUntilAPush}.
   */
  @Test
  public void aFailedAutomationHoldsAndIsNotRejected() {
    automations.answer(plainName, SCREENSHOTS, "FAILED");

    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, merged);

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"), "held, not rejected");
    assertEquals(
        "Screenshot baselines failed at " + merged.substring(0, 10) + "; push, re-run, or waive this fold",
        held.getString("detail"));
    assertEquals("FAILED", gateState(held, "AUTOMATIONS"));
    assertEquals(0, executor.calls().size());
  }

  /**
   * <b>The ordering change.</b> A red CI verdict on a fold whose automations are not yet fresh holds
   * — no rejection, no rejecting run, no unattended-gate ticket — because that fold is about to be
   * superseded by the automation's commit. Then the run ends having moved nothing, the sweep
   * <em>re-reads</em> it (a status read, not a second trigger), the automations are fresh at this
   * same fold, and the red verdict that was always there rejects exactly as it did before.
   */
  @Test
  public void aRedVerdictWhilePendingHoldsThenRejectsOnceFresh() {
    automations.answer(plainName, SCREENSHOTS, "RUNNING");
    String id = create(plainRepoId, "work", "dev-qits-maintenance");
    String merged = mergedShaOf(plainRepoId, id);

    redVerdict(plainRepoId, merged);

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"), "a red build on a fold about to be rewritten");
    assertNull(held.getString("gateTicketId"), "no unattended-gate ticket for a hold");
    assertTrue(held.getString("detail").startsWith("Waiting for automations at "));
    assertNull(rejectingRunIdOf(id));

    // The run ends with nothing to commit: fresh at this very fold.
    automations.answer(plainName, SCREENSHOTS, "FRESH");
    releaseRequests.sweep(); // held, and the tail re-reads where the automations stand
    assertTrue(automations.reads().contains(id), "the hold re-read the outcome");
    assertEquals(1, automations.askedAbout(id).size(), "re-read, never re-triggered");
    assertTrue(ledger.fresh(id, merged));
    releaseRequests.sweep();

    awaitState(plainRepoId, id, "REJECTED");
    assertEquals(merged, mergedShaOf(plainRepoId, id), "the same fold, rejected as it always was");
    assertTrue(rejectingRunIdOf(id) != null, "and now the red run is the rejecting one");
    assertEquals(0, executor.calls().size());
  }

  /**
   * A FAILED note can still carry a kind in flight — FAILED outranks RUNNING when the states are
   * read together — and a kind in flight may yet commit and re-fold the request, so red still holds.
   */
  @Test
  public void aRedVerdictBesideAFailedAndARunningAutomationHolds() {
    automations.answer(plainName, SCREENSHOTS, "FAILED");
    automations.answer(plainName, "estate-pins", "RUNNING");
    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);

    redVerdict(plainRepoId, merged);

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"), "something is still moving at this fold");
    assertNull(rejectingRunIdOf(id));
    assertEquals(0, executor.calls().size());
  }

  /** Automations that could not be read are an outage, and an outage never rejects. */
  @Test
  public void aRedVerdictWithUnreadableAutomationsHolds() {
    automations.answerNothing();
    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);

    redVerdict(plainRepoId, merged);
    releaseRequests.sweep();

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"), "held, not rejected on an outage");
    assertTrue(
        held.getString("detail").startsWith("The automations could not be established for "),
        held.getString("detail"));
    assertNull(rejectingRunIdOf(id));
  }

  /**
   * <b>qits-760, superseded by qits-1133.</b> A red verdict beside automations that have already
   * FAILED used to reject, because nothing was in flight. Now QA is not even asked for until the
   * pre-run passes, so a verdict at a fold whose automations failed answers no run this request
   * started: the request HOLDS, never rejected, saying the automation failed — and a push (an
   * automation re-run's own branch joining included) re-folds it, which is one of the three ways on.
   */
  @Test
  public void aRedVerdictBesideFailedAutomationsHoldsUntilAPush() {
    automations.answer(plainName, SCREENSHOTS, "FAILED");
    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);

    String redRun = "run-" + UUID.randomUUID();
    frame(plainRepoId, "BuildFailed", merged, redRun, null, ",\"outcome\":\"FAILED\"");
    releaseRequests.sweep();

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"), "the pre-run holds; the verdict is not read");
    assertNull(rejectingRunIdOf(id));
    assertEquals(
        "Screenshot baselines failed at " + merged.substring(0, 10) + "; push, re-run, or waive this fold",
        held.getString("detail"));
    assertEquals(0, executor.calls().size());

    // The automation's re-run commits: its branch joins the request, which re-folds it.
    automations.answer(plainName, SCREENSHOTS, "RUNNING");
    releaseRequests.addSource(id, "maintenance/automations/screenshot-baselines/x", "maint", null);
    awaitFoldToMove(plainRepoId, id, merged);
    awaitState(plainRepoId, id, "PENDING");
    assertTrue(
        request(plainRepoId, id).getString("detail").startsWith("Waiting for automations at "),
        "the pre-run restarted on the new fold");
  }

  /** A push to a source branch re-folds a request held by a failed automation. */
  @Test
  public void aPushMovesARequestHeldByAFailedAutomation() {
    automations.answer(plainName, SCREENSHOTS, "FAILED");
    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);
    redVerdict(plainRepoId, merged);
    assertEquals("PENDING", stateOf(plainRepoId, id), "held, not rejected");

    headMoved(plainRepoId, "work");
    awaitFoldToMove(plainRepoId, id, merged);
    awaitState(plainRepoId, id, "PENDING");
    assertNull(rejectingRunIdOf(id));
  }

  // -----------------------------------------------------------------------------------------
  // The waiver
  // -----------------------------------------------------------------------------------------

  /** The far side's failure rides the row to the API, field for field; a running row has none. */
  @Test
  public void aFailedAutomationCarriesWhyItFailed() {
    automations.answer(plainName, SCREENSHOTS, "FAILED");
    automations.failWith(
        plainName, SCREENSHOTS, new AutomationLedger.Failure(3, "node:22", 1, "2 screenshots differ"));
    automations.answer(plainName, "estate-pins", "RUNNING");

    String id = create(plainRepoId, "work");
    verdict(plainRepoId, mergedShaOf(plainRepoId, id));

    JsonPath held = request(plainRepoId, id);
    assertEquals("FAILED", held.getString("automations[0].state"));
    assertEquals(3, held.getInt("automations[0].failure.stepIndex"));
    assertEquals("node:22", held.getString("automations[0].failure.image"));
    assertEquals(1, held.getInt("automations[0].failure.exitCode"));
    assertEquals("2 screenshots differ", held.getString("automations[0].failure.excerpt"));
    assertNull(held.get("automations[1].failure"), "a running automation has no failure");
  }

  @Test
  public void aWaiverAtThisFoldReleases() {
    automations.answer(plainName, SCREENSHOTS, "FAILED");
    automations.failWith(
        plainName, SCREENSHOTS, new AutomationLedger.Failure(0, "node:22", null, null));
    String id = create(plainRepoId, "work");
    String merged = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, merged);
    assertEquals("PENDING", stateOf(plainRepoId, id));

    waive(id, merged);
    JsonPath waived = request(plainRepoId, id);
    assertEquals("PASSED", gateState(waived, "AUTOMATIONS"));
    assertEquals(List.of("WAIVED"), waived.getList("automations.state"));
    assertEquals(
        "node:22",
        waived.getString("automations[0].failure.image"),
        "a waiver changes the gate, not why the run failed");

    releaseRequests.sweep();
    awaitState(plainRepoId, id, "RELEASED");
  }

  @Test
  public void aWaiverAtAnOldFoldDoesNot() {
    automations.answer(plainName, SCREENSHOTS, "FAILED");
    String id = create(plainRepoId, "work");
    String firstFold = mergedShaOf(plainRepoId, id);
    waive(id, firstFold);

    headMoved(plainRepoId, "work");
    awaitFoldToMove(plainRepoId, id, firstFold);
    String secondFold = mergedShaOf(plainRepoId, id);
    verdict(plainRepoId, secondFold);
    releaseRequests.sweep();

    JsonPath held = request(plainRepoId, id);
    assertEquals("PENDING", held.getString("state"), "the waiver named a fold this one is not");
    assertEquals("FAILED", gateState(held, "AUTOMATIONS"));
    assertEquals(0, executor.calls().size());
  }

  // -----------------------------------------------------------------------------------------
  // Configuration
  // -----------------------------------------------------------------------------------------

  /**
   * With no port configured the gate is not configured: an ordinary repository releases exactly as
   * it did before automations existed, and the wrapper holds, as the estate gate always did with
   * nothing to ask.
   */
  @Test
  public void withNoPortAnOrdinaryRepositoryReleasesAndAWrapperHolds() {
    automations.unconfigure();

    String plain = create(plainRepoId, "work");
    String plainFold = mergedShaOf(plainRepoId, plain);
    JsonPath before = request(plainRepoId, plain);
    assertFalse(
        before.getList("gates.kind").contains("AUTOMATIONS"),
        "a gate that is not configured is not reported");
    verdict(plainRepoId, plainFold);
    awaitState(plainRepoId, plain, "RELEASED");

    String wrapper = create(wrapperRepoId, "work");
    String wrapperFold = mergedShaOf(wrapperRepoId, wrapper);
    verdict(wrapperRepoId, wrapperFold);
    JsonPath held = request(wrapperRepoId, wrapper);
    assertEquals("PENDING", held.getString("state"));
    assertEquals(
        "The automations could not be established for "
            + wrapperFold.substring(0, 10)
            + ": no qits-maintenance is configured to run the automations",
        held.getString("detail"));

    assertEquals(List.of(), automations.asked(), "nothing asks a port that is not there");
    assertEquals(1, executor.calls().size(), "only the ordinary repository released");
  }

  /**
   * The far side carries an outcome over a re-fold only when it is told what the re-fold changed:
   * the first fold has no previous one and says null, the re-fold names its predecessor and the
   * paths between them.
   */
  @Test
  public void changedSincePreviousIsPassedOnAReFoldAndNullOnTheFirst() {
    String id = create(plainRepoId, "work");
    String firstFold = mergedShaOf(plainRepoId, id);
    FakeReleaseRequestAutomations.Asked first = automations.askedAbout(id).get(0);
    assertNull(first.previousFoldSha());
    assertNull(first.changedSincePrevious());

    foldChanges.between(plainRepoId, List.of("src/app/page.ts", "README.md"));
    headMoved(plainRepoId, "work");
    awaitFoldToMove(plainRepoId, id, firstFold);
    String secondFold = mergedShaOf(plainRepoId, id);

    List<FakeReleaseRequestAutomations.Asked> asks = automations.askedAbout(id);
    FakeReleaseRequestAutomations.Asked refold = asks.get(asks.size() - 1);
    assertEquals(secondFold, refold.foldSha());
    assertEquals(firstFold, refold.previousFoldSha());
    assertEquals(List.of("src/app/page.ts", "README.md"), refold.changedSincePrevious());
    assertNotEquals(firstFold, secondFold);
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  private String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String repoId, String branch) {
    return create(repoId, branch, "wohlben");
  }

  private String create(String repoId, String branch, String requester) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"branch\":\""
                    + branch
                    + "\",\"summary\":\"an automated release\",\"requester\":\""
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

  private JsonPath request(String repoId, String id) {
    return given()
        .get(base(repoId) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private static String gateState(JsonPath request, String kind) {
    List<String> kinds = request.getList("gates.kind");
    int at = kinds.indexOf(kind);
    if (at < 0) {
      fail("no " + kind + " gate in " + kinds);
    }
    return request.getString("gates[" + at + "].state");
  }

  private String stateOf(String repoId, String id) {
    return request(repoId, id).getString("state");
  }

  private String mergedShaOf(String repoId, String id) {
    return request(repoId, id).getString("mergedSha");
  }

  private String rejectingRunIdOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).rejectingRunId);
  }

  /** One waiver, straight into the table — the door's own suite is where the door is tested. */
  private void waive(String requestId, String mergedSha) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestAutomationWaiver waiver = new ReleaseRequestAutomationWaiver();
              waiver.id = UUID.randomUUID().toString();
              waiver.requestId = requestId;
              waiver.mergedSha = mergedSha;
              waiver.actor = "ada";
              waiver.reason = "qits-maintenance is down and this is its fix";
              waiver.waivedAt = Instant.now();
              waiver.persist();
            });
  }

  private void verdict(String repoId, String sha) {
    frame(repoId, "BuildSuccessful", sha, "");
  }

  private void redVerdict(String repoId, String sha) {
    frame(repoId, "BuildFailed", sha, ",\"outcome\":\"FAILED\"");
  }

  private void frame(String repoId, String name, String sha, String extra) {
    frame(repoId, name, sha, "run-" + UUID.randomUUID(), null, extra);
  }

  /** The same with the run pinned and, for {@code qits ci retry}'s shape, the run it re-fires. */
  private void frame(
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

  private void awaitFoldToMove(String repoId, String id, String previousFold) {
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
}
