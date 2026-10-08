package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import io.quarkus.narayana.jta.QuarkusTransaction;
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

  /**
   * The bean itself, for the one case that cannot be reached through the transition door — see
   * {@link #aTicketThatWasDroppedDeliversNoTurnAndAsksAboutNoBranch}.
   */
  @Inject PhaseAdvance advance;

  @Inject RecordingEntityTransitionAnnouncer transitions;

  @Inject eu.wohlben.qits.entities.control.EntityDispatchService dispatchEntities;

  /** Every project this class made, so the requests its releases opened can be taken away again. */
  private final List<String> projectIds = new ArrayList<>();

  @BeforeEach
  void resetThePort() {
    turns.reset();
    workspaces.reset();
    transitions.reset();
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

  /** A live workspace over at qits-workspaces, standing on a branch and naming this ticket. */
  private WorkspaceAgentDispatch.Reference standingOn(
      String repositoryId, String branch, String ticketId) {
    return RecordingWorkspaceAgentDispatch.live(
        7L, repositoryId, "ws-" + branch, branch, ticketId, null);
  }

  /**
   * A workspace that <b>was</b> on this branch and has since been integrated or abandoned. The port
   * answers these now — that is the point of it, so a ticket keeps a link to where its work
   * happened — and this class is what proves nothing here acts on one.
   */
  private WorkspaceAgentDispatch.Reference resolvedOn(
      String repositoryId, String branch, String ticketId, String status) {
    return RecordingWorkspaceAgentDispatch.resolved(
        9L,
        repositoryId,
        "ws-" + branch,
        branch,
        ticketId,
        null,
        status,
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
    assertEquals(
        wrapperIdOf(projectId),
        verify.repositoryId(),
        "a ticket names no repository, so the turn goes to the project's wrapper");
    assertEquals("ticket/walk-me-through", verify.branch());
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
          .body(Map.of("target", target))
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
    workspaces.willReference(
        standingOn(wrapperIdOf(projectId), "ticket/waits-for-a-person", ticketId));
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
    workspaces.willReference(
        standingOn(wrapperIdOf(projectId), "ticket/goes-all-the-way", ticketId));
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "REFINED"); // the refine agent's claim, made here by dana

    assertEquals(1, turns.calls().size(), "one implement turn");
    RecordingWorkspaceAgentTurns.Spoken implement = turns.lastCall();
    assertEquals("ticket/goes-all-the-way", implement.branch());
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
                + " `ticket/goes-all-the-way` was told."),
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
    workspaces.willReference(standingOn(wrapperIdOf(projectId), "ticket/stops-quietly", ticketId));

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
    workspaces.willReference(
        standingOn(wrapperIdOf(projectId), "ticket/scheduled-by-a-person", ticketId));
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
                    + " `ticket/tell-the-agent` was told."));
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
                + " `ticket/nobody-was-home`, so one was launched to take it."),
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
    turns.willAnswer(WorkspaceAgentTurns.Outcome.NO_WORKSPACE, "no workspace stands on that branch");

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
   * The dispatch door and this flow have to arrive at the <b>same</b> address, or the hand-off talks
   * to a branch nobody made. Both resolve through {@code EntityWorkspaces}; this asserts the answer
   * rather than the sharing, because the answer is what the far side sees.
   */
  @Test
  public void theTurnGoesToTheSameWrapperAndBranchTheDispatchDoorStandsUp() {
    String projectId = createProject("Advance Same Address");
    String ticketId = createTicket(projectId, "One address");
    scheduled(ticketId);
    turns.willAnswer(WorkspaceAgentTurns.Outcome.DELIVERED, "told it");

    transition(ticketId, "IMPLEMENTED");

    assertEquals(wrapperIdOf(projectId), turns.lastCall().repositoryId());
    assertEquals("ticket/one-address", turns.lastCall().branch());
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
  public void aMoveIntoVerifiedAsksForTheReleaseOfTheBranchAWorkspaceStandsOn() {
    String projectId = createProject("Advance Release");
    String ticketId = createTicket(projectId, "Release me");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willReference(standingOn(wrapperId, "ticket/release-me", ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    java.util.List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "one ask, one request");
    Map<String, Object> request = requests.get(0);
    assertTrue(
        sourceNamesOf(request).contains("ticket/release-me"),
        "the ticket's own branch is what was put on the request: " + sourceNamesOf(request));
    assertEquals("Ticket release-me: Release me", request.get("summary"));
    assertTrue(
        thread(ticketId).get(thread(ticketId).size() - 1).contains((String) request.get("id")),
        "the thread names the request the ticket waits on: " + thread(ticketId));
  }

  /** The release is not a phase, so nothing is said to any agent about it. */
  @Test
  public void aMoveIntoVerifiedDeliversNoAgentTurn() {
    String projectId = createProject("Advance Release No Turn");
    String ticketId = createTicket(projectId, "Nothing to say");
    workspaces.willReference(
        standingOn(wrapperIdOf(projectId), "ticket/nothing-to-say", ticketId));

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
   * <b>A workspace on somebody else's branch is not this ticket's, and nothing is asked for.</b>
   * The negative is the point: {@code ReleaseRequests.request} checks a branch name's syntax and
   * nothing else, so an ask naming a branch that is not there would be a PENDING request the sweep
   * retries for ever.
   */
  @Test
  public void aWorkspaceOnAnotherBranchAsksForNothing() {
    String projectId = createProject("Advance Release Elsewhere");
    String ticketId = createTicket(projectId, "Somewhere else");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willReference(standingOn(wrapperId, "epic/something-bigger", ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    assertTrue(
        releaseRequestsOf(wrapperId).isEmpty(), "no branch was there, so nothing was asked for");
    assertEquals(
        "No workspace is standing on `ticket/somewhere-else`, so no release was asked for.",
        thread(ticketId).get(thread(ticketId).size() - 1));
  }

  // --- and a resolved workspace is not one to act on ------------------------------------------

  /**
   * <b>The regression this pair exists to prevent.</b> {@code workspacesReferencing} answers every
   * workspace that names the ticket now — integrated and abandoned ones included, so that the ticket
   * keeps a link to where its work happened — and this flow is the one reader in this service that
   * <em>acts</em> on the answer. A resolved workspace's branch has very often been merged away by the
   * integration that resolved it, and {@code ReleaseRequests.request} checks a branch name's syntax
   * and nothing else, so asking for its release would land a PENDING row the sweep retries for ever.
   *
   * <p>Here both a resolved and a live workspace name this ticket's own branch, and the resolved one
   * comes first in the answer. The ask has to go to the <b>live</b> one — which is measurable because
   * the release is asked for at the reference's own repository, so passing over the live one would
   * ask at the resolved one's repository and leave the wrapper with nothing.
   */
  @Test
  public void aResolvedWorkspaceIsPassedOverForTheLiveOneOnTheSameBranch() {
    String projectId = createProject("Advance Release Resolved And Live");
    String ticketId = createTicket(projectId, "Twice around");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willReference(
        resolvedOn("a-repository-that-is-not-the-wrapper", "ticket/twice-around", ticketId,
            "INTEGRATED"),
        standingOn(wrapperId, "ticket/twice-around", ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");

    java.util.List<Map<String, Object>> requests = releaseRequestsOf(wrapperId);
    assertEquals(1, requests.size(), "the live workspace's repository is where the ask went");
    assertTrue(
        sourceNamesOf(requests.get(0)).contains("ticket/twice-around"),
        "the ticket's own branch is on it: " + sourceNamesOf(requests.get(0)));
    assertTrue(
        thread(ticketId).get(thread(ticketId).size() - 1).contains((String) requests.get(0).get("id")),
        "the thread names the request the ticket waits on: " + thread(ticketId));
  }

  /**
   * <b>And a ticket whose only workspace is resolved asks for nothing, and does not fail doing it.</b>
   * The branch is named by a workspace that no longer stands, which is the same answer as no
   * workspace at all: one plain sentence, no request, and the transition itself untouched.
   */
  @Test
  public void aTicketWhoseOnlyWorkspaceIsResolvedAsksForNothingAndDoesNotError() {
    String projectId = createProject("Advance Release Resolved Only");
    String ticketId = createTicket(projectId, "Tidied away");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willReference(
        resolvedOn(wrapperId, "ticket/tidied-away", ticketId, "ABANDONED"));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    int asked = workspaces.lookups().size(); // the walk may ask too (qits-887's REFINED note)
    transition(ticketId, "VERIFIED"); // a 200, or this line fails

    assertEquals(
        asked + 1,
        workspaces.lookups().size(),
        "the far side was asked, which is the only way to know");
    assertTrue(
        releaseRequestsOf(wrapperId).isEmpty(),
        "an abandoned workspace's branch is not one to ask for the release of");
    assertEquals(
        "No workspace is standing on `ticket/tidied-away`, so no release was asked for.",
        thread(ticketId).get(thread(ticketId).size() - 1));
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
        "No workspace is standing on `ticket/walked-by-hand`, so no release was asked for.",
        thread(ticketId).get(thread(ticketId).size() - 1));
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
    workspaces.willReference(); // exactly what a failed lookup answers

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
        "No workspace is standing on `ticket/far-side-is-down`, so no release was asked for.",
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
    workspaces.willReference(standingOn(wrapperId, "ticket/verified-again", ticketId));

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
        sourceNamesOf(requests.get(0)).stream().filter("ticket/verified-again"::equals).count(),
        "and the branch is on it once: " + sourceNamesOf(requests.get(0)));
    assertTrue(
        thread(ticketId).get(thread(ticketId).size() - 1).contains(first),
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
    workspaces.willReference(standingOn(wrapperId, "ticket/not-fixed-after-all", ticketId));

    scheduled(ticketId);
    transition(ticketId, "IMPLEMENTED");
    transition(ticketId, "VERIFIED");
    String requestId = (String) releaseRequestsOf(wrapperId).get(0).get("id");
    transition(ticketId, "VERIFYING"); // VERIFIED's BACK move since qits-749

    String last = thread(ticketId).get(thread(ticketId).size() - 1);
    assertEquals(
        "Release request "
            + requestId
            + " still names `ticket/not-fixed-after-all` as a source: the release was not"
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
                + " `ticket/first-time-through` was told."),
        thread(ticketId),
        "nothing was asked for, so nothing is named");
  }

  /** A move that was refused asks for nothing, exactly as it starts nothing. */
  @Test
  public void aRefusedTransitionAsksForNoReleaseEither() {
    String projectId = createProject("Advance Release Refused");
    String ticketId = createTicket(projectId, "Two steps at once");
    String wrapperId = wrapperIdOf(projectId);
    workspaces.willReference(standingOn(wrapperId, "ticket/two-steps-at-once", ticketId));

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
