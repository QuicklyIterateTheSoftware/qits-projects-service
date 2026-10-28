package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.bus.EntityTransitioned;
import eu.wohlben.qits.projects.bus.RecordingEntityTransitionAnnouncer;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.releasehost.RecordingBackingBranchMerger;
import eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost;
import eu.wohlben.qits.projects.testsupport.RecordingBranchListing;
import eu.wohlben.qits.projects.testsupport.RecordingReleasedBranchWorkspaces;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The phase hand-off: a transition delivers the turn its <b>new</b> status starts into the workspace
 * standing on the ticket's branch, and the thread says what became of it.
 *
 * <p>What is pinned here is what this service owns — <b>which</b> prompt is delivered, <b>where</b>,
 * <b>when</b>, and what the ticket is left saying. The delivery itself is qits-workspaces' and is not
 * simulated; the three templates' own sentences are {@link PhasePromptsTest}'s.
 *
 * <p>The caller is named with the real {@code X-Qits-*} pair rather than {@code @TestSecurity}, for
 * {@link TicketFlowDispatchTest}'s reason: the comment's {@code author} is one of the
 * assertions and the header is what produces it in a deployment.
 */
@QuarkusTest
public class PhaseAdvanceTest {

  @Inject RecordingWorkspaceAgentTurns turns;

  /** The read half of the dispatch port: what is standing on the ticket's branch, if anything. */
  @Inject RecordingWorkspaceAgentDispatch workspaces;

  @Inject eu.wohlben.qits.projects.control.ProjectService projects;

  /** Where the wrapper branch is derived, so a sentence's branch can be asserted. */
  @Inject EntityWorkspaces addresses;

  @Inject eu.wohlben.qits.entities.control.WorkEntityService entities;

  /**
   * The bean itself, for the one case that cannot be reached through the transition door — see
   * {@link #aTicketThatWasDroppedDeliversNoTurnAndAsksAboutNoBranch}.
   */
  @Inject PhaseAdvance advance;

  @Inject RecordingEntityTransitionAnnouncer transitions;

  @Inject eu.wohlben.qits.entities.control.EntityDispatchService dispatchEntities;

  /**
   * The git host a VERIFIED ask puts each pushed branch to first (qits-1220): a branch it was not
   * told about answers {@code no-such-branch}, so every agent here that pushed something has its
   * branch staged as there ({@link #onTheGitHost}).
   */
  @Inject RecordingReleaseGitHost gitHost;

  /**
   * The branches each repository lists on the git host (qits-1218): the first source of what a move
   * into VERIFIED asks for. A repository nothing staged lists no branch, so a test that is not about
   * the listing reads the agents' push logs alone.
   */
  @Inject RecordingBranchListing listing;

  /** Where a VERIFIED item with nothing left to release hands its workspace to cleanup. */
  @Inject RecordingReleasedBranchWorkspaces resolutions;

  /**
   * The git host's merge door, through which a wrapper branch the git host does not have is created
   * at the wrapper's default branch before it is asked for (qits-1224). Every release request's own
   * fold lands here too, so a test reads the folds of one target ref ({@link #creationsOf}).
   */
  @Inject RecordingBackingBranchMerger merger;

  /** Every project this class made, so the requests its releases opened can be taken away again. */
  private final List<String> projectIds = new ArrayList<>();

  @BeforeEach
  void resetThePort() {
    turns.reset();
    workspaces.reset();
    transitions.reset();
    listing.reset();
    resolutions.reset();
    merger.reset();
  }

  /** Every fold that targeted {@code ticketId}'s wrapper branch: the creations of it (qits-1224). */
  private List<RecordingBackingBranchMerger.Fold> creationsOf(String ticketId) {
    return merger.foldsOf("refs/heads/" + branchOf(ticketId));
  }

  /** The creation fold qits-1224 asks for: the wrapper branch, at the head of {@code main}. */
  private void assertCreatedAtMain(String wrapperId, String ticketId) {
    List<RecordingBackingBranchMerger.Fold> creations = creationsOf(ticketId);
    assertEquals(1, creations.size(), "the wrapper branch was created once: " + creations);
    assertEquals(wrapperId, creations.get(0).repoId());
    assertEquals(List.of("refs/heads/main"), creations.get(0).sources(), "at the head of main");
  }

  /** The sentence a wrapper branch created for the release adds to the thread (qits-1224). */
  private String createdAtMain(String ticketId) {
    return " `"
        + branchOf(ticketId)
        + "` was not on the git host, so it was created at `main` to carry the estate pins.";
  }

  /** What was handed to cleanup for {@code ticketId}'s wrapper branch. */
  private List<RecordingReleasedBranchWorkspaces.Resolved> handedToCleanup(String ticketId) {
    return resolutions.calls().stream()
        .filter(call -> branchOf(ticketId).equals(call.branch()))
        .toList();
  }

  /**
   * <b>Open requests must not outlive this class</b>, the discipline {@code ReleaseRequestFlowTest}
   * states: the release sweep walks every open row in the database, so a request a ticket opened
   * here is a door call inside whichever test sweeps next.
   */
  @AfterEach
  void dropTheRequestsTheTicketsAskedFor() {
    QuarkusTransaction.requiringNew()
        .run(() -> projectIds.forEach(id -> ReleaseRequest.delete("projectId = ?1", id)));
    projectIds.clear();
  }

  private RequestSpecification asAdmin(String user) {
    return given()
        // A person behind the press: the session the edge keeps, as a browser sends it (qits-887).
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(user))
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", "qits:admin");
  }

  private String createProject(String name) {
    String id = createdProject(name);
    projectIds.add(id);
    return id;
  }

  private String createdProject(String name) {
    return asAdmin("setup")
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createTicket(String projectId, String title) {
    return TestCriteria.give(
        WorkRequests.ticket(
            () -> asAdmin("setup"), projectId, title, "BUG", "something occurs in this project"));
  }

  /** One step along the lifecycle, through the door a person presses. */
  private void transition(String ticketId, String target) {
    asAdmin("dana")
        .body(new Transition(target))
        .when()
        .post("/projects/api/work/" + ticketId + "/status")
        .then()
        .statusCode(200);
  }

  /** Refined and scheduled by a person (qits-887): the ticket stands at READY_FOR_DEV. */
  private void scheduled(String ticketId) {
    transition(ticketId, "REFINED");
    transition(ticketId, "READY_FOR_DEV");
  }

  /** The status move's body, {@code POST /work/{qualifiedId}/status}'s {@code {"target"}}. */
  private record Transition(String target) {}

  private String wrapperIdOf(String projectId) {
    return projects
        .findWrapper(projectId)
        .orElseThrow(() -> new AssertionError("the fixture project has no wrapper"))
        .id;
  }

  /** The ticket's whole thread, oldest first, as the reader of it sees it. */
  private java.util.List<String> thread(String ticketId) {
    return asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .extract()
        .path("entries.comment.body");
  }

  /**
   * The ticket's wrapper branch, {@code ticket/<qualified id>} (qits-1152), read as the dispatch and
   * this flow derive it.
   */
  private String branchOf(String ticketId) {
    return "ticket/" + addresses.qualifiedIdOf(entities.find(ticketId));
  }

  /** The ticket's ACTIVE agent over at qits-workspaces, having pushed {@code branch} there. */
  private WorkspaceAgentDispatch.Agent standingOn(
      String repositoryId, String branch, String ticketId) {
    onTheGitHost(repositoryId, branch);
    return RecordingWorkspaceAgentDispatch.active(
        "agent-7",
        7L,
        repositoryId,
        ticketId,
        branch,
        new WorkspaceAgentDispatch.Branch(repositoryId, branch));
  }

  /**
   * Stage {@code branch} as present on the git host, at a head of its own that nothing contains —
   * a pushed branch that is not released yet, which is what every ask here is about (qits-1220).
   */
  private String onTheGitHost(String repositoryId, String branch) {
    String sha = "head-of-" + repositoryId + "-" + branch;
    gitHost.headOf(repositoryId, branch, sha);
    return sha;
  }

  /** Stage {@code branch} as released and deleted: the git host answers {@code no-such-branch}. */
  private void goneFromTheGitHost(String repositoryId, String branch) {
    gitHost.headUnreadable(
        repositoryId,
        branch,
        ReleaseGitHost.Answer.failed(
            "qits-githost answered 404: {\"error\":\"no-such-branch\",\"detail\":\"refs/heads/"
                + branch
                + "\"}"));
  }

  /** Stage {@code branch} as released: its head is in the repository's default branch already. */
  private void releasedOnTheGitHost(String repositoryId, String branch) {
    String head = onTheGitHost(repositoryId, branch);
    gitHost.headOf(repositoryId, "main", "main-of-" + repositoryId);
    gitHost.containsCommit(repositoryId, head, "main-of-" + repositoryId);
  }

  /**
   * An agent that <b>was</b> on this ticket and is OBSOLETE or REMOVED now. The port answers these —
   * so a ticket keeps a link to where its work happened — and this class is what proves nothing
   * here acts on one.
   */
  private WorkspaceAgentDispatch.Agent resolvedOn(
      String repositoryId, String branch, String ticketId, String state) {
    return new WorkspaceAgentDispatch.Agent(
        "agent-9",
        9L,
        repositoryId,
        ticketId,
        null,
        branch,
        List.of(new WorkspaceAgentDispatch.Branch(repositoryId, branch)),
        state,
        null,
        null,
        java.time.Instant.parse("2026-09-20T09:30:00Z"));
  }

  /** The repository's release requests, as the release door answers them: the open ones. */
  private java.util.List<Map<String, Object>> releaseRequestsOf(String repoId) {
    return asAdmin("dana")
        .when()
        .get("/projects/api/repositories/" + repoId + "/release-requests")
        .then()
        .statusCode(200)
        .extract()
        .path("requests");
  }

  /** The branches named on one request, as a reader of it sees them. */
  @SuppressWarnings("unchecked")
  private java.util.List<String> sourceNamesOf(Map<String, Object> request) {
    return ((java.util.List<Map<String, Object>>) request.get("sources"))
        .stream().map(source -> (String) source.get("name")).toList();
  }

  // --- the rule: the prompt for a status is the work that starts from it ----------------------

  /**
   * <b>The whole rule, once per status.</b> Each move delivers the phase its new status begins, on
   * the ticket's own branch at the project's wrapper — and the statuses that start nothing deliver
   * nothing at all, which is where the human decisions live: REFINED waits for a person to schedule
   * it, the scheduling itself starts nothing (qits-887), and VERIFIED and DONE are a person's to
   * close. This walk covers the pipeline; DROPPED is off it and has a case of its own.
   */
  @Test
  public void eachStatusDeliversThePromptThePhaseItStartsNeeds() {
    String projectId = createProject("Advance Walk");
    String ticketId = createTicket(projectId, "Walk me through");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    // REPORTED → REFINED: nothing runs while REFINED holds — a person schedules it.
    transition(ticketId, "REFINED");
    assertEquals(List.of(), turns.calls(), "REFINED starts no phase");
    // REFINED → READY_FOR_DEV is that person's approval, and not a hand-off.
    transition(ticketId, "READY_FOR_DEV");
    assertEquals(List.of(), turns.calls(), "scheduling starts nothing on its own");
    assertEquals("READY_FOR_DEV", statusOf(ticketId));

    // READY_FOR_DEV → IMPLEMENTED (the skip): verify is what runs while IMPLEMENTED holds.
    transition(ticketId, "IMPLEMENTED");
    assertEquals(1, turns.calls().size(), "one transition, one turn");
    RecordingWorkspaceAgentTurns.Spoken verify = turns.lastCall();
    assertEquals(ticketId, verify.workId(), "the turn finds the agent by the work item");
    assertTrue(
        verify.text().contains("Verify ticket \""),
        "IMPLEMENTED starts verification: " + verify.text());
    // The turn was spoken, so the verification started and the ticket says so (qits-749).
    assertEquals("VERIFYING", statusOf(ticketId));

    // VERIFIED and DONE start no phase: the work is over and closing is a person's move. VERIFIED
    // does ask for the release of the branch the work was done on — which is not a turn, and is
    // what the tests below are about.
    transition(ticketId, "VERIFIED");
    assertEquals(
        1, turns.calls().size(), "a move into VERIFIED starts no phase, so it delivers no turn");
    transition(ticketId, "DONE");
    assertEquals(1, turns.calls().size(), "and neither does a move into DONE");
  }

  /**
   * <b>A ticket that was dropped starts nothing and says nothing.</b> DROPPED is not a phase that
   * ended, it is the phases stopping, so there is no turn to deliver, no branch to ask about and
   * nothing worth putting on the thread — a comment there would be a comment about having done
   * nothing, on a thread somebody has just finished with.
   *
   * <p>It hands the bean the ticket rather than pressing the transition door, which every other
   * case here does, and the reason is the status column's check constraint: a persisted DROPPED row
   * needs the migration that widens it. What this class owns is the rule that a status starting no
   * phase delivers no turn, and that rule reads the row it is given.
   */
  @Test
  public void aTicketThatWasDroppedDeliversNoTurnAndAsksAboutNoBranch() {
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    WorkEntity dropped = new WorkEntity();
    dropped.id = "tkt-dropped";
    dropped.projectId = "prj-dropped";
    dropped.archetype = Archetype.TICKET;
    dropped.title = "Never mind";
    dropped.slug = "never-mind";
    dropped.ticketType = TicketType.BUG;
    dropped.status = EntityStatus.DROPPED.name();

    advance.afterTransition(dropped, null, "dana");

    assertEquals(
        List.of(), turns.calls(), "a dropped ticket has no phase to start, so no turn is delivered");
    assertEquals(
        List.of(),
        workspaces.lookups(),
        "and it is answered before the workspace lookup, so no release is asked for either — the"
            + " one switch that says a status starts no phase is the whole of the decision");
  }

  /**
   * <b>A campaign's move delivers nothing and releases nothing</b> (qits-411), at every status —
   * VERIFIED included, where an epic or a ticket would ask for its branch's release. A campaign
   * stands in no workspace; its moves belong to its own door. Handed to the bean directly, the
   * idiom above, because no transition door reaches this bean with a campaign today.
   */
  @Test
  public void aCampaignsMoveDeliversNoTurnAndAsksForNoRelease() {
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    for (EntityStatus status : EntityStatus.values()) {
      WorkEntity campaign = new WorkEntity();
      campaign.id = "cmp-" + status;
      campaign.projectId = "prj-campaign";
      campaign.archetype = Archetype.CAMPAIGN;
      campaign.title = "Spring";
      campaign.slug = "spring";
      campaign.status = status.name();
      campaign.dispatchContinues = true;

      advance.afterTransition(campaign, null, "dana");
    }

    assertEquals(List.of(), turns.calls(), "a campaign is never delivered a turn");
    assertEquals(
        List.of(), workspaces.lookups(), "and no workspace is looked up, so no release is asked");
  }

  /**
   * <b>A feature's and a task's move delivers nothing and releases nothing</b> (qits-763), at every
   * status — VERIFIED included, where an epic or a ticket would ask for its branch's release. They
   * hold a status of their own now, but no phase runs on a piece of a plan: the bean returns before
   * it reads a phase, so neither the prompt renderer (which has no template for them and throws) nor
   * the workspace lookup is reached. Handed to the bean directly, the campaign case's idiom, and
   * asserted not to throw — a throw here is what the doors would log as a WARN on every move.
   */
  @Test
  public void aFeaturesAndATasksMoveDeliversNoTurnAndAsksForNoRelease() {
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    for (Archetype piece : new Archetype[] {Archetype.FEATURE, Archetype.TASK}) {
      for (EntityStatus status : EntityStatus.values()) {
        WorkEntity row = new WorkEntity();
        row.id = piece + "-" + status;
        row.projectId = "prj-piece";
        row.archetype = piece;
        row.title = "A piece";
        row.slug = "a-piece";
        row.status = status.name();
        row.dispatchContinues = true;

        assertDoesNotThrow(
            () -> advance.afterTransition(row, EntityStatus.IMPLEMENTED.name(), "dana"),
            piece + " at " + status);
      }
    }

    assertEquals(List.of(), turns.calls(), "a piece of a plan is never delivered a turn");
    assertEquals(
        List.of(), workspaces.lookups(), "and no workspace is looked up, so no release is asked");
  }

  /**
   * The same through the door a person or an agent presses: a task moved to VERIFIED through {@code
   * POST /work/{qualifiedId}/status} is announced as a TASK and starts nothing.
   */
  @Test
  public void aTaskVerifiedThroughTheStatusDoorIsAnnouncedAndStartsNothing() {
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    String projectId = createProject("Phase Advance Task");
    String epic =
        TestCriteria.give(WorkRequests.epic(() -> asAdmin("setup"), projectId, "The plan"));
    String feature =
        WorkRequests.feature(() -> asAdmin("setup"), epic, "The part");
    for (String target : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED")) {
      asAdmin("dana")
          .body(eu.wohlben.qits.entities.api.WorkRequests.statusBody(target))
          .post("/projects/api/work/" + epic + "/status")
          .then()
          .statusCode(200);
    }
    turns.reset();
    workspaces.reset();
    transitions.reset();

    asAdmin("dana")
        .body(Map.of("target", "VERIFIED"))
        .post("/projects/api/work/" + feature + "/status")
        .then()
        .statusCode(200);

    assertEquals(List.of(), turns.calls(), "a verified feature delivers no turn");
    assertEquals(List.of(), workspaces.lookups(), "and asks for no release of any branch");
    assertTrue(
        transitions.published().stream()
            .flatMap(event -> event.entities().stream())
            .anyMatch(
                entity -> "FEATURE".equals(entity.archetype()) && "VERIFIED".equals(entity.status())),
        "the move is announced as the feature's own: " + transitions.published());
  }

  /**
   * <b>A blocked ticket delivers no turn</b>, and the ticket here is READY_FOR_DEV — a status that
   * plainly does start a phase — so what is being asserted is the flag and nothing about the
   * status. A block says the phase the ticket is <em>already standing in</em> cannot finish, so
   * telling the agent in that workspace to start it is telling it to walk into the obstacle
   * somebody has just written down on the thread.
   *
   * <p>It hands the bean the row directly, the idiom {@link
   * #aTicketThatWasDroppedDeliversNoTurnAndAsksAboutNoBranch} uses, and here that is not merely the
   * cheaper route but the <b>only</b> one: {@code WorkEntityService.transition} clears the flag on
   * every move, so a ticket can never arrive at this bean through the transition door with it set.
   * A test that pressed the door would be asserting that an unblocked ticket starts its phase,
   * which is the opposite arm and is covered by every other case in this class.
   */
  @Test
  public void aTicketBlockedInThePhaseItStandsInDeliversNoTurn() {
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    WorkEntity blocked = new WorkEntity();
    blocked.id = "tkt-blocked";
    blocked.projectId = "prj-blocked";
    blocked.archetype = Archetype.TICKET;
    blocked.title = "Waiting on something";
    blocked.slug = "waiting-on-something";
    blocked.ticketType = TicketType.BUG;
    blocked.status = EntityStatus.READY_FOR_DEV.name();
    blocked.blocked = true;

    advance.afterTransition(blocked, null, "dana");

    assertEquals(
        List.of(),
        turns.calls(),
        "READY_FOR_DEV starts the implement phase, so the empty list here is the block and nothing"
            + " else");
    assertEquals(
        List.of(),
        workspaces.lookups(),
        "and the block is answered before the workspace lookup, so nothing was even asked about the"
            + " branch the obstacle is standing on");
  }

  /**
   * REPORTED is reached by <b>moving back</b> into it, since a ticket is created there rather than
   * transitioned into it — and the refine phase is what runs while it holds, whichever way the
   * ticket arrived.
   */
  @Test
  public void movingBackToReportedDeliversTheRefinePrompt() {
    String projectId = createProject("Advance Reopen");
    String ticketId = createTicket(projectId, "Back to the start");

    transition(ticketId, "REFINED"); // nobody to tell yet, so it stays REFINED
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    transition(ticketId, "REPORTED");

    assertTrue(
        turns.lastCall().text().contains("Refine ticket \""),
        "REPORTED starts refinement: " + turns.lastCall().text());
  }

  /**
   * <b>Direction is not consulted, and this is the case that proves it.</b> A move back from
   * IMPLEMENTED to IMPLEMENTING corrects a claim that turned out wrong — it is not how a failed
   * verification reports, which is a block (qits-592) — and what has to start from it is
   * <em>implementation</em> again, rework. A rule that asked "forward or back?" would need a second
   * table to answer from, and that table is the thing that goes wrong.
   */
  @Test
  public void aMoveBackToImplementingStartsTheImplementPhase() {
    String projectId = createProject("Advance Backward");
    String ticketId = createTicket(projectId, "Still broken after all");
    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED"); // the skip
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "IMPLEMENTING");

    assertEquals(1, turns.calls().size());
    assertTrue(
        turns.lastCall().text().contains("Implement ticket \""),
        "a move BACK to IMPLEMENTING starts implementation again: " + turns.lastCall().text());
  }

  /**
   * <b>The one exception to direction not being consulted</b> (qits-749): READY_FOR_DEV →
   * IMPLEMENTING says an implementation was started — by a press, or by the agent at work — so the
   * implement prompt is already out and a second one would restart it.
   */
  @Test
  public void aMoveFromReadyForDevIntoImplementingPushesNothing() {
    String projectId = createProject("Advance Started By Hand");
    String ticketId = createTicket(projectId, "Started by hand");
    scheduled(ticketId);
    assertEquals("READY_FOR_DEV", statusOf(ticketId));
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "IMPLEMENTING");

    assertEquals(List.of(), turns.calls(), "no second implement prompt");
  }

  /**
   * <b>A FLOW refine that ends in REFINED ends there</b> (qits-887): REFINED starts no phase, so no
   * implement turn is spoken and the ticket is not moved on. With the refine agent still standing
   * on the branch, the thread says the run now waits for a person, so the flow does not end silently.
   */
  @Test
  public void aFlowRefineThatLandsRefinedDeliversNoTurnAndSaysItWaitsForAPerson() {
    String projectId = createProject("Advance Into Refined");
    String ticketId = createTicket(projectId, "Waits for a person");
    workspaces.willFind(
        standingOn(wrapperIdOf(projectId), branchOf(ticketId), ticketId));
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "REFINED");

    assertEquals(List.of(), turns.calls(), "no implement turn");
    assertEquals("REFINED", statusOf(ticketId));
    List<EntityTransitioned.Entity> moved =
        transitions.published().stream().flatMap(event -> event.entities().stream()).toList();
    assertEquals(
        List.of("REPORTED->REFINED"),
        moved.stream().map(entity -> entity.statusBefore() + "->" + entity.status()).toList());
    assertEquals(
        List.of("Refined; waiting for a person to schedule it (READY_FOR_DEV)."), thread(ticketId));
  }

  /**
   * <b>The pre-approved twin</b> (qits-1075): the person who pressed Dispatch at REPORTED left their
   * name on the row, so a FLOW refine that lands REFINED is scheduled as that person — the audit
   * row and the {@code EntityTransitioned} name them — the pre-approval is spent, the implement turn
   * is delivered into the same session, and the ticket ends IMPLEMENTING. No "waiting" sentence.
   */
  @Test
  public void aPreApprovedFlowRefineThatLandsRefinedIsScheduledAsThePersonAndHandedOn() {
    String projectId = createProject("Advance Pre-approved");
    String ticketId = createTicket(projectId, "Goes all the way");
    dispatchEntities.setPreApprovedBy(ticketId, "mallory", "mallory");
    workspaces.willFind(
        standingOn(wrapperIdOf(projectId), branchOf(ticketId), ticketId));
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "REFINED"); // the refine agent's claim, made here by dana

    assertEquals(1, turns.calls().size(), "one implement turn");
    RecordingWorkspaceAgentTurns.Spoken implement = turns.lastCall();
    assertEquals(ticketId, implement.workId());
    assertTrue(implement.text().contains("Implement ticket \""), implement.text());
    assertEquals("IMPLEMENTING", statusOf(ticketId));
    assertEquals(null, dispatchEntities.fresh(ticketId).preApprovedBy, "spent");
    assertEquals(
        List.of(
            "REPORTED->REFINED by dana",
            "REFINED->READY_FOR_DEV by mallory",
            "READY_FOR_DEV->IMPLEMENTING by dana"),
        transitions.published().stream()
            .flatMap(event -> event.entities().stream())
            .map(e -> e.statusBefore() + "->" + e.status() + " by " + e.changedBy())
            .toList());
    List<Map<String, Object>> audit =
        asAdmin("dana")
            .when()
            .get("/projects/api/work/" + ticketId + "/audit")
            .then()
            .statusCode(200)
            .extract()
            .path("entries");
    assertTrue(
        audit.stream()
            .anyMatch(
                entry ->
                    "mallory".equals(entry.get("changedBy"))
                        && String.valueOf(entry.get("snapshot")).contains("READY_FOR_DEV")),
        "the scheduling is audited as mallory's: " + audit);
    assertEquals(
        List.of(
            "Started the implement phase: the agent working in the workspace on"
                + " `" + branchOf(ticketId) + "` was told."),
        thread(ticketId));
  }

  /**
   * <b>A gate refuses the pre-approved scheduling</b> (qits-1075): with no acceptance criteria the
   * move is refused, nothing moves and no turn is delivered, the pre-approval stays for a later
   * press, and the thread says once which gate refused and what a person can do. Handed to the
   * bean directly, because the claim to REFINED is itself gated on criteria — so the row reaches
   * REFINED with them and loses them afterwards, as a person editing it at REFINED may.
   */
  @Test
  public void aPreApprovedSchedulingAGateRefusesMovesNothingAndSaysWhyOnce() {
    String projectId = createProject("Advance Pre-approved Gate");
    String ticketId = createTicket(projectId, "Lost its criteria");
    transition(ticketId, "REFINED");
    given()
        .contentType("application/merge-patch+json")
        .body(Map.of("acceptanceCriteria", List.of()))
        .when()
        .patch("/projects/api/work/" + ticketId)
        .then()
        .statusCode(200);
    dispatchEntities.setPreApprovedBy(ticketId, "mallory", "mallory");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    transitions.reset();

    advance.afterTransition(dispatchEntities.fresh(ticketId), "REPORTED", "agent");

    assertEquals("REFINED", statusOf(ticketId));
    assertEquals(List.of(), turns.calls(), "no implement turn");
    assertEquals(List.of(), transitions.published(), "nothing moved");
    assertEquals("mallory", dispatchEntities.fresh(ticketId).preApprovedBy, "the flag stays");
    List<String> said = thread(ticketId);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.get(0).startsWith("mallory pre-approved scheduling this ticket"), said.get(0));
    assertTrue(said.get(0).contains("ACCEPTANCE_CRITERIA"), said.get(0));
    assertTrue(
        said.get(0).endsWith("a person can schedule it (READY_FOR_DEV), or press Dispatch."),
        said.get(0));

    // Fixed, a person's Dispatch at REFINED schedules it and spends the pending pre-approval.
    TestCriteria.give(ticketId);
    asAdmin("dana")
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + ticketId + "/dispatch")
        .then()
        .statusCode(200)
        .body("dispatch.phase", equalTo("implement"));
    assertEquals("IMPLEMENTING", statusOf(ticketId));
    assertEquals(null, dispatchEntities.fresh(ticketId).preApprovedBy, "spent by the press");
  }

  /** A one-phase run that lands REFINED says nothing: the person who pressed knows it stops. */
  @Test
  public void aOnePhaseRefineThatLandsRefinedSaysNothing() {
    String projectId = createProject("Advance Into Refined Quietly");
    String ticketId = createTicket(projectId, "Stops quietly");
    dispatchEntities.setDispatchContinues(ticketId, false, "dana");
    workspaces.willFind(standingOn(wrapperIdOf(projectId), branchOf(ticketId), ticketId));

    transition(ticketId, "REFINED");

    assertEquals(List.of(), turns.calls());
    assertEquals(List.of(), thread(ticketId));
  }

  /**
   * <b>Scheduling pushes nothing</b> (qits-887), even for an entity whose last press was FLOW and
   * whose refine workspace is still standing: REFINED → READY_FOR_DEV is a person's approval, not a
   * phase hand-off, and starting the work is the next press.
   */
  @Test
  public void schedulingDeliversNoTurnEvenWithAFlowWorkspaceStanding() {
    String projectId = createProject("Advance Scheduled");
    String ticketId = createTicket(projectId, "Scheduled by a person");
    workspaces.willFind(
        standingOn(wrapperIdOf(projectId), branchOf(ticketId), ticketId));
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    transition(ticketId, "REFINED");
    transitions.reset();

    transition(ticketId, "READY_FOR_DEV");

    assertTrue(dispatchEntities.fresh(ticketId).dispatchContinues, "the run was a flow");
    assertEquals(List.of(), turns.calls(), "the scheduling delivered no implement turn");
    assertEquals("READY_FOR_DEV", statusOf(ticketId), "and nothing moved it on to IMPLEMENTING");
    assertEquals(
        List.of("REFINED->READY_FOR_DEV"),
        transitions.published().stream()
            .flatMap(event -> event.entities().stream())
            .map(entity -> entity.statusBefore() + "->" + entity.status())
            .toList());
  }

  /**
   * <b>VERIFYING mirrors IMPLEMENTING</b> (qits-749): a FLOW hand-off into IMPLEMENTED whose verify
   * turn is spoken moves on to VERIFYING with one turn; an explicit IMPLEMENTED → VERIFYING pushes
   * nothing; VERIFIED → VERIFYING (back) pushes the verify prompt again.
   */
  @Test
  public void aFlowHandOffIntoImplementedMovesOnToVerifyingWithOneTurn() {
    String projectId = createProject("Advance Into Verifying");
    String ticketId = createTicket(projectId, "Verify me next");
    scheduled(ticketId); // nobody to tell, and scheduling starts nothing anyway
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");
    transitions.reset();

    transition(ticketId, "IMPLEMENTED"); // the skip

    assertEquals(1, turns.calls().size(), "one verify turn and no second");
    assertTrue(turns.lastCall().text().contains("Verify ticket \""), turns.lastCall().text());
    assertEquals("VERIFYING", statusOf(ticketId));
    assertEquals(
        List.of("READY_FOR_DEV->IMPLEMENTED", "IMPLEMENTED->VERIFYING"),
        transitions.published().stream()
            .flatMap(event -> event.entities().stream())
            .map(entity -> entity.statusBefore() + "->" + entity.status())
            .toList());
  }

  @Test
  public void aMoveFromImplementedIntoVerifyingPushesNothing() {
    String projectId = createProject("Advance Verifying By Hand");
    String ticketId = createTicket(projectId, "Verifying by hand");
    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED"); // nobody to tell, so it stays IMPLEMENTED
    assertEquals("IMPLEMENTED", statusOf(ticketId));
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "VERIFYING");

    assertEquals(List.of(), turns.calls(), "no second verify prompt");
  }

  @Test
  public void aMoveBackToVerifyingStartsTheVerifyPhase() {
    String projectId = createProject("Advance Reverify");
    String ticketId = createTicket(projectId, "Check it again");
    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED"); // the skip
    turns.reset();
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "VERIFYING");

    assertEquals(1, turns.calls().size());
    assertTrue(turns.lastCall().text().contains("Verify ticket \""), turns.lastCall().text());
    assertEquals("VERIFYING", statusOf(ticketId));
  }

  private String statusOf(String id) {
    return dispatchEntities.fresh(id).status;
  }

  // --- what lands on the thread ---------------------------------------------------------------

  @Test
  public void aDeliveredTurnSaysWhichPhaseStartedAndWhereTheAgentIs() {
    String projectId = createProject("Advance Delivered");
    String ticketId = createTicket(projectId, "Tell the agent");
    scheduled(ticketId);
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "IMPLEMENTED");

    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(1))
        .body("entries[0].comment.author", equalTo("dana"))
        .body(
            "entries[0].comment.body",
            equalTo(
                "Started the verify phase: the agent working in the workspace on"
                    + " `" + branchOf(ticketId) + "` was told."));
  }

  /** A launched agent is a different fact from a told one, and the thread carries which. */
  @Test
  public void aLaunchedAgentIsSaidToBeALaunchAndNotAHandOff() {
    String projectId = createProject("Advance Launched");
    String ticketId = createTicket(projectId, "Nobody was home");
    scheduled(ticketId);
    turns.willAnswer(WorkspaceAgentTurns.Outcome.LAUNCHED, "started one");

    transition(ticketId, "IMPLEMENTED");

    assertEquals(
        java.util.List.of(
            "Started the verify phase: no agent was running in the workspace on"
                + " `"
                + branchOf(ticketId)
                + "`, so one was launched to take it."),
        thread(ticketId));
  }

  /**
   * <b>No workspace is not a failure and gets no comment at all.</b> A person walking a ticket
   * through its statuses by hand must not have their thread filled with "there was nobody to tell",
   * once per move — which is also the resting state of every other ticket suite in this repository.
   */
  @Test
  public void aTicketWithNoWorkspaceIsAskedAndThenLeftAlone() {
    String projectId = createProject("Advance Nowhere");
    String ticketId = createTicket(projectId, "Nobody is on this");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.NO_AGENT, "the work item has no agent");

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");

    assertEquals(1, turns.calls().size(), "the far side is still asked — it is the only thing that knows");
    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));

    // And the transition itself stands, which is the half that must never depend on any of this.
    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId)
        .then()
        .statusCode(200)
        .body("status", equalTo("IMPLEMENTED"));
  }

  /**
   * A far side that refused: the move stands, and the thread gets the honest sentence — the reason,
   * the status the ticket now holds, and <b>nothing claiming an agent is on it</b>.
   */
  @Test
  public void aRefusedDeliveryLeavesTheTransitionAndSaysSoPlainly() {
    String projectId = createProject("Advance Refused");
    String ticketId = createTicket(projectId, "Nothing answers here");
    scheduled(ticketId);
    turns.willAnswer(WorkspaceAgentTurns.Outcome.COULD_NOT, "qits-workspaces answered 503");

    transition(ticketId, "IMPLEMENTED");

    assertEquals(
        java.util.List.of(
            "Could not start the verify phase: qits-workspaces answered 503. The ticket is"
                + " IMPLEMENTED and nothing is running on it."),
        thread(ticketId));
    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId)
        .then()
        .statusCode(200)
        .body("status", equalTo("IMPLEMENTED"));
  }

  /**
   * <b>A port that throws although its contract forbids it.</b> The transition has already been
   * recorded by the time the delivery runs, so a port bug may not undo it, may not reach the caller
   * as an error, and still owes the thread an answer.
   */
  @Test
  public void aPortThatThrowsCannotUndoTheTransitionAndStillSaysSomething() {
    String projectId = createProject("Advance Thrown");
    String ticketId = createTicket(projectId, "The port misbehaves");
    scheduled(ticketId);
    turns.willThrow(new IllegalStateException("the adapter is broken"));

    transition(ticketId, "IMPLEMENTED"); // a 200, or this line fails

    assertEquals(
        java.util.List.of(
            "Could not start the verify phase: the delivery failed unexpectedly. The ticket is"
                + " IMPLEMENTED and nothing is running on it."),
        thread(ticketId));
    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId)
        .then()
        .statusCode(200)
        .body("status", equalTo("IMPLEMENTED"));
  }

  // --- when it runs ---------------------------------------------------------------------------

  /**
   * <b>The delivery happens after the transition is committed, so a transition that never happened
   * speaks to nobody.</b> A non-adjacent target is refused by the lifecycle, and what this pins is
   * that the refusal costs nothing outward: the port is not asked, and no thread is stamped about a
   * phase that was never entered.
   */
  @Test
  public void aRefusedTransitionSpeaksToNobody() {
    String projectId = createProject("Advance Refused Move");
    String ticketId = createTicket(projectId, "Too far in one step");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    asAdmin("dana")
        .body(new Transition("IMPLEMENTED")) // REPORTED → IMPLEMENTED is two steps
        .when()
        .post("/projects/api/work/" + ticketId + "/status")
        .then()
        .statusCode(409);

    assertTrue(turns.calls().isEmpty(), "a move that was refused started no phase");
    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));
  }

  /** A ticket that does not exist is a 404 and asks nothing of anybody. */
  @Test
  public void anUnknownTicketSpeaksToNobodyEither() {
    asAdmin("dana")
        .body(new Transition("REFINED"))
        .when()
        .post("/projects/api/work/no-such-ticket/status")
        .then()
        .statusCode(404);

    assertTrue(turns.calls().isEmpty(), "there was no transition, so there is no phase to start");
  }

  /**
   * The dispatch door and this flow have to arrive at the <b>same</b> agent (qits-1152: the work
   * item's id keys it), or the hand-off talks to nobody. This asserts the answer the far side sees.
   */
  @Test
  public void theTurnGoesToTheSameWorkItemTheDispatchDoorStandsUp() {
    String projectId = createProject("Advance Same Address");
    String ticketId = createTicket(projectId, "One address");
    scheduled(ticketId);
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "IMPLEMENTED");

    assertEquals(ticketId, turns.lastCall().workId());
    assertTrue(
        turns.lastCall().text().contains(ticketId),
        "and the turn tells the agent where to read the ticket itself");
  }

  /** The comment is the dispatch comment's twin, so it is stamped from the caller like any other. */
  @Test
  public void theCommentIsStampedFromTheCallerThatMovedTheTicket() {
    String projectId = createProject("Advance Stamped");
    String ticketId = createTicket(projectId, "Who said that");
    scheduled(ticketId);
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    asAdmin("mallory")
        .body(new Transition("IMPLEMENTED"))
        .when()
        .post("/projects/api/work/" + ticketId + "/status")
        .then()
        .statusCode(200);

    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries[0].comment.author", equalTo("mallory"))
        .body("entries[0].comment.body", containsString("Started the verify phase"));
  }

  // --- VERIFIED asks for a release ------------------------------------------------------------

  /**
   * <b>The whole of the new arm.</b> Verification succeeded, so the branch the work was done on is
   * asked to be released — at the project's wrapper, on exactly the reference's own branch, and the
   * thread names the request the ticket now waits on.
   */
  @Test
  public void aMoveIntoVerifiedAsksForTheReleaseOfTheBranchTheAgentPushed() {
    String projectId = createProject("Advance Release");
    String ticketId = createTicket(projectId, "Release me");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(standingOn(wrapperId, branchOf(ticketId), ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    java.util.List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "one ask, one request");
    Map<String, Object> request = requests.get(0);
    assertTrue(
        sourceNamesOf(request).contains(branchOf(ticketId)),
        "the ticket's own branch is what was put on the request: " + sourceNamesOf(request));
    // Derived from the sources (qits-1673): the ticket's branch names it, so its id and title.
    assertEquals(
        branchOf(ticketId).substring("ticket/".length()) + " Release me", request.get("summary"));
    assertTrue(
        thread(ticketId).get(thread(ticketId).size() - 1).contains((String) request.get("qualifiedId")),
        "the thread names the request the ticket waits on: " + thread(ticketId));
    assertTrue(
        handedToCleanup(ticketId).isEmpty(),
        "a release was asked for, and it resolves the workspace when it lands");
  }

  /** The release is not a phase, so nothing is said to any agent about it. */
  @Test
  public void aMoveIntoVerifiedDeliversNoAgentTurn() {
    String projectId = createProject("Advance Release No Turn");
    String ticketId = createTicket(projectId, "Nothing to say");
    workspaces.willFind(
        standingOn(wrapperIdOf(projectId), branchOf(ticketId), ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    int beforeVerification = turns.calls().size();
    transition(ticketId, "VERIFIED");

    assertEquals(
        beforeVerification,
        turns.calls().size(),
        "VERIFIED starts no phase, so it delivers no turn");
  }

  /**
   * <b>An agent that pushed nothing, on a git host with nothing of the ticket's, still asks for the
   * wrapper</b> (qits-1224): the wrapper's release is what moves the estate pins, so its branch is
   * created at {@code main} first — {@code ReleaseRequests.request} checks a branch name's syntax and
   * nothing else, and a request naming a branch that is not there cannot fold. A release was asked
   * for, so nothing is handed to cleanup (qits-1218): that release resolves the workspace.
   */
  @Test
  public void anAgentThatPushedNothingStillAsksForTheWrapperBranchCreatedAtMain() {
    String projectId = createProject("Advance Release Nothing Pushed");
    String ticketId = createTicket(projectId, "Nothing pushed");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, "ticket/x"));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "the wrapper is asked for, pushed or not: " + requests);
    assertEquals(List.of("main", branchOf(ticketId)), sourceNamesOf(requests.get(0)));
    assertCreatedAtMain(wrapperId, ticketId);
    assertEquals(
        "Asked for the release of `"
            + branchOf(ticketId)
            + "` (release request "
            + requests.get(0).get("qualifiedId")
            + "): the ticket now waits on that request."
            + createdAtMain(ticketId),
        thread(ticketId).get(thread(ticketId).size() - 1));
    assertTrue(handedToCleanup(ticketId).isEmpty(), "the wrapper's release resolves the workspace");
  }

  /**
   * <b>Each pushed branch is asked for in its own repository</b> (qits-1152): the agent named its
   * submodule branch {@code <wrapper branch>-<its choice>}, and the wrapper's branch goes last.
   */
  @Test
  public void aMoveIntoVerifiedAsksForEveryBranchTheAgentPushedInItsOwnRepository() {
    String projectId = createProject("Advance Release Two Repositories");
    String ticketId = createTicket(projectId, "Two repositories");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-component-service", null).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(wrapperId, branch),
            new WorkspaceAgentDispatch.Branch(componentId, branch + "-totals")));
    onTheGitHost(wrapperId, branch);
    onTheGitHost(componentId, branch + "-totals");

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> component = releaseRequestsOf(componentId);
    List<Map<String, Object>> wrapper = releaseRequestsOf(wrapperId);
    assertEquals(1, component.size(), "one request in the component");
    assertEquals(1, wrapper.size(), "and one in the wrapper");
    assertTrue(sourceNamesOf(component.get(0)).contains(branch + "-totals"));
    assertTrue(sourceNamesOf(wrapper.get(0)).contains(branch));
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(
        said.indexOf((String) component.get(0).get("qualifiedId"))
            < said.indexOf((String) wrapper.get(0).get("qualifiedId")),
        "the wrapper is asked for last: " + said);
  }

  /**
   * <b>The project's {@code qits-agent-config} is released like any other repository of the
   * project</b> (qits-1382): the workspace daemon writes an agent's memories and skills on a branch
   * named exactly like its wrapper branch, the agent reports it, and VERIFIED asks for it in its own
   * repository — before the wrapper, which always goes last. Nothing here knows the archetype: it
   * is in scope because it is the work item's project's repository and the branch is in the agent's
   * scope.
   */
  @Test
  public void aMoveIntoVerifiedAsksForTheAgentConfigBranchBeforeTheWrapper() {
    String projectId = createProject("Advance Release Agent Config");
    String ticketId = createTicket(projectId, "Remember this");
    String wrapperId = wrapperIdOf(projectId);
    String agentConfigId = projects.ensureAgentConfigRepository(projectId).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(wrapperId, branch),
            new WorkspaceAgentDispatch.Branch(agentConfigId, branch)));
    onTheGitHost(wrapperId, branch);
    onTheGitHost(agentConfigId, branch);

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> agentConfig = releaseRequestsOf(agentConfigId);
    List<Map<String, Object>> wrapper = releaseRequestsOf(wrapperId);
    assertEquals(1, agentConfig.size(), "one request in qits-agent-config: " + agentConfig);
    assertEquals(1, wrapper.size(), "and one in the wrapper: " + wrapper);
    assertTrue(
        sourceNamesOf(agentConfig.get(0)).contains(branch),
        "the agent's own branch, in its own repository: " + sourceNamesOf(agentConfig.get(0)));
    assertTrue(sourceNamesOf(wrapper.get(0)).contains(branch), sourceNamesOf(wrapper.get(0)).toString());
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(
        said.indexOf((String) agentConfig.get(0).get("qualifiedId"))
            < said.indexOf((String) wrapper.get(0).get("qualifiedId")),
        "the agent configuration is asked for before the wrapper: " + said);
  }

  /**
   * <b>The agent's list is its word, and only what lies in its scope is released</b> (qits-1152).
   * Another work item's branch, {@code main}, a name that only looks like the wrapper branch and the
   * agent's own branch name in another project's repository are each left off (D28: the repository
   * check is what keeps another project out), and the thread says which.
   */
  @Test
  void aBranchOutsideTheAgentsScopeIsNeverReleased() {
    String projectId = createProject("Advance Release Scope");
    String ticketId = createTicket(projectId, "Stay in scope");
    String otherTicket = createTicket(projectId, "Somebody else's");
    String wrapperId = wrapperIdOf(projectId);
    String foreignProject = createProject("Advance Release Scope Elsewhere");
    String foreignWrapper = wrapperIdOf(foreignProject);
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(wrapperId, branchOf(otherTicket)),
            new WorkspaceAgentDispatch.Branch(wrapperId, "main"),
            new WorkspaceAgentDispatch.Branch(wrapperId, branch + "0-lookalike"),
            new WorkspaceAgentDispatch.Branch(foreignWrapper, branch + "-elsewhere"),
            new WorkspaceAgentDispatch.Branch(wrapperId, branch + "-fine")));
    onTheGitHost(wrapperId, branch + "-fine");

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertTrue(releaseRequestsOf(foreignWrapper).isEmpty(), "nothing in another project");
    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), requests.toString());
    List<String> sources = sourceNamesOf(requests.get(0));
    assertTrue(sources.contains(branch + "-fine"), sources.toString());
    assertFalse(sources.contains(branchOf(otherTicket)), sources.toString());
    assertFalse(sources.contains(branch + "0-lookalike"), sources.toString());
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(said.contains("outside the agent's scope"), said);
    assertTrue(said.contains(branchOf(otherTicket)), said);
    assertTrue(said.contains(branch + "-elsewhere"), said);
  }

  /**
   * An agent whose every branch lies outside its scope asks for none of them — and the wrapper is
   * asked for all the same (qits-1224), on its own branch created at {@code main}.
   */
  @Test
  void anAgentWithOnlyForeignBranchesAsksForTheWrapperAlone() {
    String projectId = createProject("Advance Release Foreign Only");
    String ticketId = createTicket(projectId, "Only foreign");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branchOf(ticketId),
            new WorkspaceAgentDispatch.Branch(wrapperId, "release/hijack")));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), requests.toString());
    assertEquals(List.of("main", branchOf(ticketId)), sourceNamesOf(requests.get(0)));
    assertCreatedAtMain(wrapperId, ticketId);
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(said.startsWith("Asked for the release of `" + branchOf(ticketId) + "`"), said);
    assertTrue(said.contains("`release/hijack`"), said);
  }

  // --- and a branch that is gone or already released is not asked for (qits-1220) -----------

  /**
   * <b>A pushed branch the git host no longer has is not asked for</b> (qits-1220). The agent's
   * list still names a branch an earlier release shipped and deleted; asking for it opened
   * qits-projects-service-rr-261, whose fold died on {@code 404 no-such-source}. The branch that is
   * still there is asked for as ever, and the thread names the one passed over.
   */
  @Test
  void aBranchTheGitHostNoLongerHasIsNotAskedFor() {
    String projectId = createProject("Advance Release Gone");
    String ticketId = createTicket(projectId, "Shipped before");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-gone-service", null).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(componentId, branch + "-shipped"),
            new WorkspaceAgentDispatch.Branch(wrapperId, branch)));
    goneFromTheGitHost(componentId, branch + "-shipped");
    onTheGitHost(wrapperId, branch);

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertTrue(releaseRequestsOf(componentId).isEmpty(), "the deleted branch was not asked for");
    List<Map<String, Object>> wrapper = releaseRequestsOf(wrapperId);
    assertEquals(1, wrapper.size(), wrapper.toString());
    assertTrue(sourceNamesOf(wrapper.get(0)).contains(branch));
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(said.startsWith("Asked for the release of `" + branch + "`"), said);
    assertTrue(
        said.endsWith(
            " Not asked for, because they no longer exist on the git host: `"
                + branch
                + "-shipped`."),
        said);
  }

  /**
   * <b>A pushed branch whose head is already released is not asked for</b> (qits-1220): its head
   * is contained in the repository's default branch, so a request would release nothing new.
   */
  @Test
  void aBranchWhoseHeadIsAlreadyReleasedIsNotAskedFor() {
    String projectId = createProject("Advance Release Already");
    String ticketId = createTicket(projectId, "Released already");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-already-service", null).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(componentId, branch + "-done"),
            new WorkspaceAgentDispatch.Branch(wrapperId, branch)));
    releasedOnTheGitHost(componentId, branch + "-done");
    onTheGitHost(wrapperId, branch);

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertTrue(releaseRequestsOf(componentId).isEmpty(), "the released branch was not asked for");
    assertEquals(1, releaseRequestsOf(wrapperId).size());
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(
        said.endsWith(" Not asked for, because they are already released: `" + branch + "-done`."),
        said);
  }

  /**
   * <b>Nothing else left to release still asks for the wrapper</b> (qits-1224): every other in-scope
   * branch is gone or released and is passed over and named (qits-1220), and the wrapper branch,
   * released too, is asked for anyway — it exists, so nothing is created, and it is what the estate
   * pins are bumped onto. A release was asked for, so nothing is handed to cleanup.
   */
  @Test
  void anItemWithNothingElseLeftAsksForItsReleasedWrapperBranchAnyway() {
    String projectId = createProject("Advance Release Nothing Left");
    String ticketId = createTicket(projectId, "All shipped");
    String wrapperId = wrapperIdOf(projectId);
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(wrapperId, branch + "-first"),
            new WorkspaceAgentDispatch.Branch(wrapperId, branch)));
    goneFromTheGitHost(wrapperId, branch + "-first");
    releasedOnTheGitHost(wrapperId, branch);

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), requests.toString());
    assertEquals(List.of("main", branch), sourceNamesOf(requests.get(0)), "the gone branch is not on it");
    assertTrue(creationsOf(ticketId).isEmpty(), "a branch that exists is not created");
    assertEquals(
        "Asked for the release of `"
            + branch
            + "` (release request "
            + requests.get(0).get("qualifiedId")
            + "): the ticket now waits on that request. `"
            + branch
            + "` is already released, and was asked for anyway to carry the estate pins. Not asked"
            + " for, because they no longer exist on the git host: `"
            + branch
            + "-first`.",
        thread(ticketId).get(thread(ticketId).size() - 1));
    assertTrue(handedToCleanup(ticketId).isEmpty(), "the wrapper's release resolves the workspace");
  }

  /**
   * <b>A git host that cannot answer is "cannot tell", and the branch is asked for</b> — the
   * regression guard on qits-1220's other side: a release is never dropped on a flaky moment, so an
   * unreachable host asks exactly as before the check existed.
   */
  @Test
  void aGitHostThatCannotAnswerStillAsks() {
    String projectId = createProject("Advance Release Host Down");
    String ticketId = createTicket(projectId, "Host is down");
    String wrapperId = wrapperIdOf(projectId);
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(wrapperId, branch)));
    gitHost.headUnreadable(
        wrapperId,
        branch,
        ReleaseGitHost.Answer.failedRetryable("qits-githost could not be reached: timeout"));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "could not ask is not gone: " + requests);
    assertTrue(sourceNamesOf(requests.get(0)).contains(branch));
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertFalse(said.contains("Not asked for"), said);
  }

  // --- every agent, in any state, and the git host (qits-1218) --------------------------------

  /**
   * <b>An OBSOLETE agent beside the ACTIVE one is read too, and the branch they share is asked for
   * once.</b> {@code agentsFor} answers every agent of the ticket, OBSOLETE and REMOVED ones
   * included, and an OBSOLETE agent is not one whose branches are all gone — so its push log is a
   * source like the live one's, and only the git host's verdict decides. Here the obsolete agent
   * names the ticket's branch in a repository that is not the project's, which is out of scope and
   * named as such, and the live agent names it in the wrapper, where it is asked for — once.
   */
  @Test
  public void anObsoleteAgentIsReadBesideTheActiveOneAndTheSharedBranchIsAskedForOnce() {
    String projectId = createProject("Advance Release Resolved And Live");
    String ticketId = createTicket(projectId, "Twice around");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(
        resolvedOn("a-repository-that-is-not-the-wrapper", branchOf(ticketId), ticketId,
            "OBSOLETE"),
        standingOn(wrapperId, branchOf(ticketId), ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    java.util.List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "the wrapper is where the ask went");
    assertEquals(
        1,
        sourceNamesOf(requests.get(0)).stream().filter(branchOf(ticketId)::equals).count(),
        "the ticket's own branch is on it once: " + sourceNamesOf(requests.get(0)));
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(
        said.contains((String) requests.get(0).get("qualifiedId")),
        "the thread names the request the ticket waits on: " + said);
    assertTrue(said.contains("outside the agent's scope"), said);
    assertTrue(handedToCleanup(ticketId).isEmpty(), "a release was asked for");
  }

  /**
   * <b>The qits-168 shape.</b> The agent that did the work is OBSOLETE — one of its branches was
   * released, which is all it takes — and the ACTIVE agent that verified pushed nothing. The
   * wrapper branch is still on the git host, unreleased: reading the ACTIVE agent's push log alone
   * said "pushed no branches" and asked for nothing, and the work was never released. Read every
   * agent and the branch is asked for; its released sibling is passed over and named.
   */
  @Test
  void anObsoleteAgentsUnreleasedWrapperBranchIsAskedFor() {
    String projectId = createProject("Advance Release Obsolete Wrapper");
    String ticketId = createTicket(projectId, "Done by the first agent");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-obsolete-service", null).repository().id;
    String branch = branchOf(ticketId);
    onTheGitHost(wrapperId, branch);
    goneFromTheGitHost(componentId, branch + "-a");
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branch),
        new WorkspaceAgentDispatch.Agent(
            "agent-9",
            9L,
            wrapperId,
            ticketId,
            null,
            branch,
            List.of(
                new WorkspaceAgentDispatch.Branch(wrapperId, branch),
                new WorkspaceAgentDispatch.Branch(componentId, branch + "-a")),
            WorkspaceAgentDispatch.Agent.OBSOLETE,
            null,
            null,
            java.time.Instant.parse("2026-10-12T09:30:00Z")));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> wrapper = releaseRequestsOf(wrapperId);
    assertEquals(1, wrapper.size(), "exactly one request, in the wrapper: " + wrapper);
    assertTrue(sourceNamesOf(wrapper.get(0)).contains(branch), sourceNamesOf(wrapper.get(0)).toString());
    assertTrue(releaseRequestsOf(componentId).isEmpty(), "the released branch was not asked for");
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertFalse(said.contains("pushed no branches"), said);
    assertTrue(said.startsWith("Asked for the release of `" + branch + "`"), said);
    assertTrue(
        said.endsWith(
            " Not asked for, because they no longer exist on the git host: `" + branch + "-a`."),
        said);
    assertTrue(handedToCleanup(ticketId).isEmpty(), "a release was asked for");
  }

  /**
   * <b>The plain-push shape.</b> An agent that pushed with a plain {@code git push} has an empty
   * push log, but its branches are on the git host — and the git host is read first. A component
   * branch the listing names and the host answers unreleased is asked for, and the wrapper last.
   */
  @Test
  void aBranchOnlyTheGitHostKnowsIsAskedForWrapperLast() {
    String projectId = createProject("Advance Release Plain Push");
    String ticketId = createTicket(projectId, "Pushed by hand");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-plain-push-service", null).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branch));
    // The wrapper is listed first on purpose, so "wrapper last" is the sort and not the listing.
    listing.willList(wrapperId, "main", branch);
    String foreign = branchOf(createTicket(projectId, "Somebody else's"));
    listing.willList(componentId, "main", branch + "-totals", foreign);
    onTheGitHost(wrapperId, branch);
    onTheGitHost(componentId, branch + "-totals");

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> component = releaseRequestsOf(componentId);
    List<Map<String, Object>> wrapper = releaseRequestsOf(wrapperId);
    assertEquals(1, component.size(), component.toString());
    assertEquals(1, wrapper.size(), wrapper.toString());
    assertTrue(sourceNamesOf(component.get(0)).contains(branch + "-totals"));
    assertFalse(
        sourceNamesOf(component.get(0)).contains(foreign),
        "another ticket's branch is out of scope: " + sourceNamesOf(component.get(0)));
    assertTrue(sourceNamesOf(wrapper.get(0)).contains(branch));
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(
        said.indexOf((String) component.get(0).get("qualifiedId"))
            < said.indexOf((String) wrapper.get(0).get("qualifiedId")),
        "the wrapper is asked for last: " + said);
    assertTrue(handedToCleanup(ticketId).isEmpty(), "a release was asked for");
    assertTrue(creationsOf(ticketId).isEmpty(), "a wrapper branch that is there is not created");
    assertFalse(said.contains("estate pins"), said);
  }

  /**
   * <b>A repository whose listing cannot be read is skipped, and the others are still read.</b>
   */
  @Test
  void aListingThatFailsSkipsThatRepositoryAlone() {
    String projectId = createProject("Advance Release Listing Fails");
    String ticketId = createTicket(projectId, "One host is down");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-unlisted-service", null).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branch));
    listing.failFor(componentId, "ls-remote failed");
    listing.willList(wrapperId, branch);
    onTheGitHost(wrapperId, branch);

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertTrue(listing.listed().contains(componentId), "the component was asked");
    assertEquals(1, releaseRequestsOf(wrapperId).size(), "and the wrapper was still asked for");
  }

  /**
   * <b>A listing that does not answer within the deadline is skipped, and the others still
   * count.</b> The listings run concurrently under one deadline because this runs inside the
   * transition request; a hung git host costs the deadline, never the move. The deadline is shrunk
   * here so the test does not wait the shipped ten seconds.
   */
  @Test
  void aListingThatHangsPastTheDeadlineIsSkippedWhileTheOthersCount() {
    String projectId = createProject("Advance Release Listing Hangs");
    String ticketId = createTicket(projectId, "One host hangs");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-hung-service", null).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branch));
    listing.willList(componentId, branch + "-late");
    listing.hang(componentId);
    onTheGitHost(componentId, branch + "-late");
    listing.willList(wrapperId, branch);
    onTheGitHost(wrapperId, branch);
    PhaseAdvance bean = io.quarkus.arc.ClientProxy.unwrap(advance);
    java.time.Duration shipped = bean.listingDeadline;
    assertEquals(java.time.Duration.ofSeconds(10), PhaseAdvance.LISTING_DEADLINE);

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    bean.listingDeadline = java.time.Duration.ofMillis(300);
    long started = System.nanoTime();
    try {
      transition(ticketId, "VERIFIED");
    } finally {
      bean.listingDeadline = shipped;
    }
    long tookMillis = (System.nanoTime() - started) / 1_000_000;

    assertTrue(listing.listed().contains(componentId), "the hung repository was asked");
    assertTrue(
        releaseRequestsOf(componentId).isEmpty(), "and its listing, never answered, counts for nothing");
    List<Map<String, Object>> wrapper = releaseRequestsOf(wrapperId);
    assertEquals(1, wrapper.size(), "the wrapper's listing still counted: " + wrapper);
    assertTrue(sourceNamesOf(wrapper.get(0)).contains(branch));
    assertTrue(tookMillis < 5_000, "the move waited the deadline, not the hang: " + tookMillis);
  }

  /**
   * <b>The qits-1223 shape, since qits-1224.</b> The ACTIVE agent pushed nothing, and nothing of the
   * ticket's is on the git host — only {@code main} and another ticket's branch. The wrapper branch
   * is created at {@code main} and asked for, carrying nothing of anybody else's; that release is
   * what marks the agent OBSOLETE, so nothing is handed to cleanup here.
   */
  @Test
  void nothingInScopeOnTheGitHostStillAsksForTheWrapperBranchCreatedAtMain() {
    String projectId = createProject("Advance Release Nothing On The Host");
    String ticketId = createTicket(projectId, "Nothing to show");
    String other = createTicket(projectId, "Somebody else's work");
    String wrapperId = wrapperIdOf(projectId);
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branch));
    listing.willList(wrapperId, "main", branchOf(other), branch + "0-lookalike");
    onTheGitHost(wrapperId, branchOf(other));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertTrue(listing.listed().contains(wrapperId), "the git host was read");
    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), requests.toString());
    assertEquals(List.of("main", branch), sourceNamesOf(requests.get(0)), "nothing of anybody else's");
    assertCreatedAtMain(wrapperId, ticketId);
    assertTrue(handedToCleanup(ticketId).isEmpty(), "the wrapper's release resolves the workspace");
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertFalse(said.contains("nothing is running on it"), said);
    assertTrue(said.endsWith(createdAtMain(ticketId)), said);
  }

  /**
   * <b>A refused ask resolves nothing</b>: work may still be unreleased, so the workspace stays for
   * a person, and the thread says the ask failed without claiming anything about an agent.
   */
  @Test
  void aRefusedAskDoesNotHandTheWorkspaceToCleanup() {
    String projectId = createProject("Advance Release Refused Ask");
    String ticketId = createTicket(projectId, "Cannot be asked");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(standingOn(wrapperId, branchOf(ticketId), ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    QuarkusMock.installMockForType(new RefusingReleaseRequests(), ReleaseRequests.class);
    transition(ticketId, "VERIFIED");

    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(
        said.startsWith("Could not ask for the release of `" + branchOf(ticketId) + "`: refused here"),
        said);
    assertFalse(said.contains("nothing is running on it"), said);
    assertTrue(handedToCleanup(ticketId).isEmpty(), "a refused ask resolves nothing");
  }

  /**
   * A {@link ReleaseRequests} that refuses every ask, for the one test above. Its sweeps do nothing,
   * so the scheduler that ticks while it is installed meets no unwired field.
   */
  static class RefusingReleaseRequests extends ReleaseRequests {
    @Override
    public ReleaseRequestDto request(
        String repoId, String branch, String requester, String priority) {
      throw new IllegalStateException("refused here");
    }

    @Override
    public void sweep() {}

    @Override
    public void sweepGateTickets() {}
  }

  // --- the wrapper is always asked for, last (qits-1224) --------------------------------------

  /**
   * <b>The everyday shape.</b> The agent pushed a component branch and never pushed the wrapper
   * branch. The component is asked for; then the wrapper branch is created at {@code main} on the
   * git host — before its ask, since a fold naming a source the git host does not have rejects — and
   * asked for last, so the wrapper's estate-pins automation has a branch to bump onto.
   */
  @Test
  void aComponentBranchIsAskedForAndTheWrapperBranchIsCreatedAndAskedForLast() {
    String projectId = createProject("Advance Release Wrapper Created");
    String ticketId = createTicket(projectId, "Component only");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-created-service", null).repository().id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(componentId, branch + "-totals")));
    onTheGitHost(componentId, branch + "-totals");

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> component = releaseRequestsOf(componentId);
    List<Map<String, Object>> wrapper = releaseRequestsOf(wrapperId);
    assertEquals(1, component.size(), component.toString());
    assertEquals(1, wrapper.size(), wrapper.toString());
    assertEquals(List.of("main", branch + "-totals"), sourceNamesOf(component.get(0)));
    assertEquals(List.of("main", branch), sourceNamesOf(wrapper.get(0)));
    assertCreatedAtMain(wrapperId, ticketId);
    List<RecordingBackingBranchMerger.Fold> folds = merger.folds();
    int created = folds.indexOf(creationsOf(ticketId).get(0));
    int foldedIn =
        java.util.stream.IntStream.range(0, folds.size())
            .filter(i -> folds.get(i).sources().contains("refs/heads/" + branch))
            .findFirst()
            .orElse(-1);
    assertTrue(
        created >= 0 && (foldedIn < 0 || created < foldedIn),
        "created before anything folded it in: " + folds);
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertEquals(
        "Asked for the release of `"
            + branch
            + "-totals` (release request "
            + component.get(0).get("qualifiedId")
            + "), `"
            + branch
            + "` (release request "
            + wrapper.get(0).get("qualifiedId")
            + "): the ticket now waits on those requests."
            + createdAtMain(ticketId),
        said);
    assertTrue(handedToCleanup(ticketId).isEmpty(), "a release was asked for");
  }

  /**
   * <b>Nothing at all, anywhere</b> — no push, no listing, no candidate — and the wrapper is still
   * asked for, on its branch created at {@code main}. The one ask is the whole of the outcome.
   */
  @Test
  void noCandidateAtAllStillAsksForTheWrapperBranchCreatedAtMain() {
    String projectId = createProject("Advance Release No Candidate");
    String ticketId = createTicket(projectId, "Nothing anywhere");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branchOf(ticketId)));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), requests.toString());
    assertEquals(List.of("main", branchOf(ticketId)), sourceNamesOf(requests.get(0)));
    assertCreatedAtMain(wrapperId, ticketId);
    assertTrue(handedToCleanup(ticketId).isEmpty());
  }

  /**
   * <b>A wrapper branch that cannot be created is not asked for</b>: a request naming a source the
   * git host does not have rejects its fold (qits-1221). The thread says why, and with nothing else
   * asked for, the qits-1218 rule decides as before — the workspace is handed to cleanup.
   */
  @Test
  void aWrapperBranchThatCannotBeCreatedIsNotAskedForAndTheWorkspaceIsHandedToCleanup() {
    String projectId = createProject("Advance Release Not Created");
    String ticketId = createTicket(projectId, "Host refuses");
    String wrapperId = wrapperIdOf(projectId);
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branch));
    merger.answerFor(
        "refs/heads/" + branch,
        BackingBranchMerger.Outcome.unreachable("qits-githost could not be reached"));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertEquals(1, creationsOf(ticketId).size(), "the creation was tried");
    assertTrue(releaseRequestsOf(wrapperId).isEmpty(), "and the wrapper was not asked for");
    assertEquals(
        "No branch of this ticket was found on the git host or among its agents' pushes, so no"
            + " release was asked for. `"
            + branch
            + "` is not on the git host and could not be created at `main` to carry the estate"
            + " pins (qits-githost could not be reached), so the wrapper's release was not asked"
            + " for. Its workspace was handed to cleanup.",
        thread(ticketId).get(thread(ticketId).size() - 1));
    assertEquals(
        List.of(new RecordingReleasedBranchWorkspaces.Resolved(wrapperId, branch, "verified", null)),
        handedToCleanup(ticketId));
  }

  /**
   * <b>A wrapper branch that cannot be created beside a component that was asked for</b>: the
   * component's release stands, the wrapper is not asked for and the thread says why — and since a
   * release was asked for, it is that release which resolves the workspace, not a hand-off here.
   */
  @Test
  void aWrapperBranchThatCannotBeCreatedLeavesTheComponentsAskStanding() {
    String projectId = createProject("Advance Release Not Created Beside");
    String ticketId = createTicket(projectId, "Half refused");
    String wrapperId = wrapperIdOf(projectId);
    String componentId =
        projects.createRepository(projectId, null, "advance-not-created-service", null)
            .repository()
            .id;
    String branch = branchOf(ticketId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active(
            "agent-7",
            7L,
            wrapperId,
            ticketId,
            branch,
            new WorkspaceAgentDispatch.Branch(componentId, branch + "-totals")));
    onTheGitHost(componentId, branch + "-totals");
    merger.answerFor(
        "refs/heads/" + branch, BackingBranchMerger.Outcome.missingSource("refs/heads/main"));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> component = releaseRequestsOf(componentId);
    assertEquals(1, component.size(), component.toString());
    assertTrue(releaseRequestsOf(wrapperId).isEmpty(), "the wrapper was not asked for");
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(said.startsWith("Asked for the release of `" + branch + "-totals`"), said);
    assertTrue(
        said.endsWith(
            " `"
                + branch
                + "` is not on the git host and could not be created at `main` to carry the estate"
                + " pins (refs/heads/main), so the wrapper's release was not asked for."),
        said);
    assertTrue(handedToCleanup(ticketId).isEmpty(), "a release was asked for");
  }

  /**
   * <b>A second VERIFIED of a ticket whose wrapper branch had to be created joins the request the
   * first opened</b>, with the branch on it once — the convergence rule, unchanged by qits-1224.
   */
  @Test
  void aSecondMoveIntoVerifiedJoinsTheWrapperRequestItsCreatedBranchOpened() {
    String projectId = createProject("Advance Release Created Twice");
    String ticketId = createTicket(projectId, "Created and verified again");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-7", 7L, wrapperId, ticketId, branchOf(ticketId)));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");
    String first = (String) releaseRequestsOf(wrapperId).get(0).get("id");
    transition(ticketId, "VERIFYING");
    transition(ticketId, "VERIFIED");

    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), requests.toString());
    assertEquals(first, requests.get(0).get("id"));
    assertEquals(List.of("main", branchOf(ticketId)), sourceNamesOf(requests.get(0)));
  }

  /**
   * <b>A ticket whose only agent is REMOVED still has its branches read</b> (qits-1218), and does
   * not fail doing it. Here the branch it pushed is gone from the git host — and it is the wrapper
   * branch, so it is created again at {@code main} and asked for (qits-1224), the transition
   * untouched.
   */
  @Test
  public void aTicketWhoseOnlyAgentIsRemovedAsksForWhatIsLeftAndDoesNotError() {
    String projectId = createProject("Advance Release Resolved Only");
    String ticketId = createTicket(projectId, "Tidied away");
    String wrapperId = wrapperIdOf(projectId);
    goneFromTheGitHost(wrapperId, branchOf(ticketId));
    workspaces.willFind(
        resolvedOn(wrapperId, branchOf(ticketId), ticketId, "REMOVED"));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    int asked = workspaces.lookups().size(); // the walk may ask too (qits-887's REFINED note)
    transition(ticketId, "VERIFIED"); // a 200, or this line fails

    assertEquals(
        asked + 1,
        workspaces.lookups().size(),
        "the far side was asked, which is the only way to know");
    List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "the wrapper branch, created again, is asked for: " + requests);
    assertCreatedAtMain(wrapperId, ticketId);
    String said = thread(ticketId).get(thread(ticketId).size() - 1);
    assertTrue(said.endsWith(createdAtMain(ticketId)), said);
    assertFalse(said.contains("no longer exist"), said);
    assertTrue(handedToCleanup(ticketId).isEmpty());
    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId)
        .then()
        .statusCode(200)
        .body("status", equalTo("VERIFIED"));
  }

  /** A ticket nobody ever dispatched an agent onto: the same answer, reached one step earlier. */
  @Test
  public void aTicketWithNoWorkspaceAtAllAsksForNothing() {
    String projectId = createProject("Advance Release Nobody");
    String ticketId = createTicket(projectId, "Walked by hand");

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertTrue(
        releaseRequestsOf(wrapperIdOf(projectId)).isEmpty(),
        "there is no branch to release, so nothing was asked for");
    assertEquals(
        "No agent is working on this ticket, so no release was asked for.",
        thread(ticketId).get(thread(ticketId).size() - 1));
    assertTrue(handedToCleanup(ticketId).isEmpty(), "no agent, no workspace to hand over");
    assertTrue(listing.listed().isEmpty(), "and the git host was not read");
  }

  /**
   * <b>A lookup that could not be made answers empty</b> — that is the port's contract rather than
   * this fake being kind — so an unreachable qits-workspaces is indistinguishable from a ticket
   * nobody is working on, and both ask for nothing. Asking anyway is the one move that cannot be
   * undone.
   */
  @Test
  public void aLookupThatAnswersEmptyIsTreatedAsNoWorkspace() {
    String projectId = createProject("Advance Release Unreachable");
    String ticketId = createTicket(projectId, "Far side is down");
    workspaces.willFind(); // exactly what a failed lookup answers

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    int asked = workspaces.lookups().size(); // the walk may ask too (qits-887's REFINED note)
    transition(ticketId, "VERIFIED");

    assertEquals(
        asked + 1,
        workspaces.lookups().size(),
        "the far side was asked, which is the only way to know");
    assertTrue(releaseRequestsOf(wrapperIdOf(projectId)).isEmpty());
    assertEquals(
        "No agent is working on this ticket, so no release was asked for.",
        thread(ticketId).get(thread(ticketId).size() - 1));
  }

  /**
   * <b>Verifying twice converges.</b> A failed verification moves back and a second one moves
   * forward again — and the wrapper's unit of convergence is the repository, so the second ask joins
   * the request that is already open rather than opening a second one.
   */
  @Test
  public void aSecondMoveIntoVerifiedJoinsTheRequestThatIsAlreadyOpen() {
    String projectId = createProject("Advance Release Twice");
    String ticketId = createTicket(projectId, "Verified again");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(standingOn(wrapperId, branchOf(ticketId), ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");
    String first = (String) releaseRequestsOf(wrapperId).get(0).get("id");
    transition(ticketId, "VERIFYING"); // the step back from VERIFIED since qits-749
    transition(ticketId, "VERIFIED");

    java.util.List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "the estate converges: one request, asked for twice");
    assertEquals(first, requests.get(0).get("id"));
    assertEquals(
        1,
        sourceNamesOf(requests.get(0)).stream().filter(branchOf(ticketId)::equals).count(),
        "and the branch is on it once: " + sourceNamesOf(requests.get(0)));
    assertTrue(
        thread(ticketId)
            .get(thread(ticketId).size() - 1)
            .contains((String) requests.get(0).get("qualifiedId")),
        "the thread says which request it joined: " + thread(ticketId));
  }

  // --- and a move back into VERIFYING names it -----------------------------------------------

  /**
   * <b>A failed verification withdraws nothing and says so.</b> There is no door that removes one
   * source from a request, the wrapper's request is the whole estate's, and deleting the branch
   * would destroy the work — so what the platform owes a person is the request id.
   */
  @Test
  public void aMoveBackIntoVerifyingNamesTheReleaseThatStandsOpen() {
    String projectId = createProject("Advance Release Back");
    String ticketId = createTicket(projectId, "Not fixed after all");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(standingOn(wrapperId, branchOf(ticketId), ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");
    String requestId = (String) releaseRequestsOf(wrapperId).get(0).get("qualifiedId");
    transition(ticketId, "VERIFYING"); // VERIFIED's BACK move since qits-749

    String last = thread(ticketId).get(thread(ticketId).size() - 1);
    assertEquals(
        "Release request "
            + requestId
            + " still names `"
            + branchOf(ticketId)
            + "` as a source: the release was not"
            + " withdrawn, and withdrawing or declining it is a person's decision.",
        last);
  }

  /**
   * <b>The note is conditioned on the request and never on the direction of the move.</b> Arriving
   * at IMPLEMENTED from READY_FOR_DEV nothing has been asked for, so there is nothing to name and nothing
   * is said — which is the same rule, reached from the other side.
   */
  @Test
  public void aMoveIntoImplementedFromReadyForDevNamesNoRelease() {
    String projectId = createProject("Advance Release Forward");
    String ticketId = createTicket(projectId, "First time through");
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");

    assertEquals(
        java.util.List.of(
            "Started the verify phase: the agent working in the workspace on"
                + " `" + branchOf(ticketId) + "` was told."),
        thread(ticketId),
        "nothing was asked for, so nothing is named");
  }

  /** A move that was refused asks for nothing, exactly as it starts nothing. */
  @Test
  public void aRefusedTransitionAsksForNoReleaseEither() {
    String projectId = createProject("Advance Release Refused");
    String ticketId = createTicket(projectId, "Two steps at once");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willFind(standingOn(wrapperId, branchOf(ticketId), ticketId));

    asAdmin("dana")
        .body(new Transition("VERIFIED")) // REPORTED → VERIFIED is three steps
        .when()
        .post("/projects/api/work/" + ticketId + "/status")
        .then()
        .statusCode(409);

    assertTrue(workspaces.lookups().isEmpty(), "a move that was refused looked nothing up");
    assertTrue(releaseRequestsOf(wrapperId).isEmpty(), "and asked for no release");
    asAdmin("dana")
        .when()
        .get("/projects/api/work/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));
  }
}
