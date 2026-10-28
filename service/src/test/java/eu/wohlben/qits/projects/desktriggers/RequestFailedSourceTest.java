package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.ReleaseRequestStateChanged;
import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch.Agent;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestParticipant;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestFailureNotice;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>request-failed</b> (qits-1689): a release request that goes REJECTED, CONFLICTED or FAILED
 * reaches the desk only when no active agent was told of it (qits-1672's notices) — and, since
 * qits-1694, only while that told agent is still ACTIVE, once per rejection, so a re-rejection at
 * the same fold is decided again rather than from the first rejection's outcome.
 */
@QuarkusTest
class RequestFailedSourceTest {

  private static final String SHA = "412911eb" + "0".repeat(32);

  private static final String AGENT_ID = "0123abcd-0000-4000-8000-000000000001";

  private static final String WORK_ID = "work-told-" + UUID.randomUUID();

  @Inject DeskTriggerListener listener;

  @Inject DeskTriggerSweep sweep;

  @Inject ReleaseRequestNumbers numbers;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  private final List<String> projects = new ArrayList<>();

  private final List<String> requests = new ArrayList<>();

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
  }

  @AfterEach
  void drop() {
    dispatch.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String id : requests) {
                ReleaseRequestFailureNotice.delete("requestId", id);
                ReleaseRequestApproval.delete("requestId", id);
              }
            });
    requests.clear();
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  @Test
  void aFailureNoAgentOwnsIsOneLineBundledWithTheNextAndAReplayAddsNothing() {
    String projectId = project();
    ReleaseRequest first = request(projectId, "qits-maintenance");
    ReleaseRequest second = request(projectId, "tok-person");
    EventFrame frame = changed(first, "REJECTED", "Run r1 finished FAILED for " + SHA);

    listener.onFrame(frame);
    listener.onFrame(frame);
    listener.onFrame(changed(second, "CONFLICTED", "src/a.txt differs"));

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "bundled in one window");
    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(2, lines.size(), "a replay adds nothing");
    DeskTriggerEvent line =
        lines.stream().filter(l -> l.itemId.equals(first.id)).findFirst().orElseThrow();
    assertEquals("request-failed", line.source);
    assertEquals(first.qualifiedId, line.itemRef, "the item ref links the request");
    assertEquals("REJECTED: Run r1 finished FAILED for " + SHA + "; no agent owns it", line.label);
    assertEquals("fold 412911eb", line.cause);
    DeskTriggerEvent conflict =
        lines.stream().filter(l -> l.itemId.equals(second.id)).findFirst().orElseThrow();
    assertEquals("CONFLICTED: src/a.txt differs; no agent owns it", conflict.label);
  }

  @Test
  void anActiveAgentToldMeansNoDeskLineForTheRejectionOrARejectionAgainAtTheFold() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "tok-workspace-agent-0123abcd-0000-4000-x");
    notice(row, "DELIVERED");
    dispatch.willFind(agent(Agent.ACTIVE));

    listener.onFrame(changed(row, "REJECTED", "Run r2 finished FAILED"));
    sweep.sweep(Instant.now());
    listener.onFrame(changed(row, "REJECTED", "Run r2b finished FAILED"));
    sweep.sweep(Instant.now());

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
    assertTrue(
        dispatch.lookups().stream().anyMatch(l -> l.workIds().equals(List.of(WORK_ID))),
        "the told notice's work item is what is asked about: " + dispatch.lookups());
  }

  @Test
  void aSameFoldReRejectionWhoseToldAgentIsGoneIsOneLine() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "tok-workspace-agent-0123abcd-0000-4000-x");
    notice(row, "DELIVERED");
    dispatch.willFind(agent(Agent.ACTIVE));
    listener.onFrame(changed(row, "REJECTED", "Run r5 finished FAILED"));
    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty(), "its agent owned the first");

    // reconsider() reopened the fold at the same sha, the agent went, and the next red gate
    // rejected it again: qits-1672 owes no new notice, so the first's DELIVERED is all there is.
    dispatch.willFind(agent("REMOVED"));
    EventFrame again = changed(row, "REJECTED", "Run r6 finished FAILED");
    listener.onFrame(again);
    listener.onFrame(again);
    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "one line; the replay and the sweep add nothing");
    assertEquals("REJECTED: Run r6 finished FAILED; its agent is not active", lines.get(0).label);
    assertEquals(RequestFailedSource.foldKey(row.id, SHA), lines.get(0).eventId);
  }

  @Test
  void aToldAgentNoLongerListedCountsAsNotTold() {
    String projectId = project();
    ReleaseRequest gone = request(projectId, "tok-workspace-agent-0123abcd-0000-4000-x");
    notice(gone, "RESUMED");

    listener.onFrame(changed(gone, "REJECTED", "Run r7 finished FAILED"));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals("REJECTED: Run r7 finished FAILED; its agent is gone", lines.get(0).label);
  }

  @Test
  void aNotToldReRejectionAfterAFoldKeyLineIsASecondLineKeyedOnItsEvent() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "tok-workspace-agent-0123abcd-0000-4000-x");
    notice(row, "NOT_ACTIVE");
    listener.onFrame(changed(row, "REJECTED", "Run r8 finished FAILED"));

    EventFrame again = changed(row, "REJECTED", "Run r9 finished FAILED");
    listener.onFrame(again);
    listener.onFrame(again);
    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(2, lines.size(), "one line per rejection; a replay and the sweep add nothing");
    assertEquals(RequestFailedSource.foldKey(row.id, SHA), lines.get(0).eventId);
    assertEquals("REJECTED: Run r8 finished FAILED; its agent is not active", lines.get(0).label);
    assertEquals(UUID.fromString(again.id()), lines.get(1).eventId);
    assertEquals("REJECTED: Run r9 finished FAILED; its agent is not active", lines.get(1).label);
  }

  @Test
  void aFailedRequestWithNoNoticesIsALine() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "qits-maintenance");
    EventFrame failed = changed(row, "FAILED", "the release worker could not push the tag");

    listener.onFrame(failed);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals(
        "FAILED: the release worker could not push the tag; no agent owns it", lines.get(0).label);
    assertEquals(UUID.fromString(failed.id()), lines.get(0).eventId);
  }

  @Test
  void aNoticeStillOwedIsDecidedByTheSweepOnceItSettles() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "tok-workspace-agent-0123abcd-0000-4000-x");
    notice(row, null);
    rowDetail(row, "Run r3 finished FAILED");
    EventFrame frame = changed(row, "REJECTED", "Run r3 finished FAILED");

    listener.onFrame(frame);
    sweep.sweep(Instant.now());
    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty(), "not decided while owed");

    settle(row, "NOT_ACTIVE");
    sweep.sweep(Instant.now());
    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "once: sweep and second sweep are one line");
    assertEquals("REJECTED: Run r3 finished FAILED; its agent is not active", lines.get(0).label);
    assertEquals(RequestFailedSource.foldKey(row.id, SHA), lines.get(0).eventId);
  }

  @Test
  void aGoneAgentMeansADeskLineAtOnce() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "tok-workspace-agent-0123abcd-0000-4000-x");
    notice(row, "NO_AGENT");

    listener.onFrame(changed(row, "REJECTED", "Run r4 finished FAILED"));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals("REJECTED: Run r4 finished FAILED; its agent is gone", lines.get(0).label);
  }

  @Test
  void theDesksOwnDeclineIsDroppedAndAPersonsIsNot() {
    String projectId = project();
    DeskTriggerFixtures.desk(projectId, "tok-desk", "tok-delivery", "tok-session");
    ReleaseRequest mine = request(projectId, "tok-person");
    ReleaseRequest theirs = request(projectId, "tok-person");
    decline(mine, "tok-delivery");
    decline(theirs, "dana");

    listener.onFrame(changed(mine, "REJECTED", "Declined"));
    listener.onFrame(changed(theirs, "REJECTED", "Declined"));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals(theirs.id, lines.get(0).itemId);
    assertEquals("dana", lines.get(0).cause);
  }

  @Test
  void otherStatesAdmitNothing() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "qits-maintenance");

    for (String state : List.of("PENDING", "READY", "RELEASED", "FINALIZED", "WITHDRAWN")) {
      listener.onFrame(changed(row, state, null));
    }

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  // -------------------------------------------------------------------------------------------

  private String project() {
    String id = DeskTriggerFixtures.project(true);
    projects.add(id);
    return id;
  }

  private ReleaseRequest request(String projectId, String requester) {
    String id = UUID.randomUUID().toString();
    requests.add(id);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Repository repository = new Repository();
              repository.id = "desk-trigger-repo-" + UUID.randomUUID();
              repository.project = Project.findById(projectId);
              repository.mainBranch = "main";
              repository.persist();
              ReleaseRequest row = new ReleaseRequest();
              row.id = id;
              row.repoId = repository.id;
              row.projectId = projectId;
              row.repoName = "qits-qits";
              row.state = ReleaseRequest.State.REJECTED;
              row.retryable = false;
              row.armedAt = Instant.now();
              row.summary = "a request";
              row.requester = requester;
              row.mergedSha = SHA;
              row.createdAt = Instant.now();
              row.updatedAt = Instant.now();
              row.name(numbers.next(row.repoId));
              row.persist();
              // Who filed it is the request's participants now (qits-1673), not the column.
              if (requester != null) {
                ReleaseRequestParticipant participant = new ReleaseRequestParticipant();
                participant.requestId = id;
                participant.principal = requester;
                participant.firstSeenAt = Instant.now();
                participant.persist();
              }
              return row;
            });
  }

  /** The told notice's agent, in {@code state}. */
  private static Agent agent(String state) {
    return Agent.ACTIVE.equals(state)
        ? RecordingWorkspaceAgentDispatch.active(AGENT_ID, 7L, "wrapper", WORK_ID, "ticket/x")
        : RecordingWorkspaceAgentDispatch.inState(
            AGENT_ID, 7L, "wrapper", WORK_ID, "ticket/x", state, Instant.now());
  }

  private static EventFrame changed(ReleaseRequest row, String state, String detail) {
    return DeskTriggerTestFrames.frameOf(
        new ReleaseRequestStateChanged(
            row.id,
            row.qualifiedId,
            row.projectId,
            row.repoId,
            row.repoName,
            row.backingBranch,
            state,
            "PENDING".equals(state) ? "REJECTED" : "PENDING",
            Instant.now(),
            SHA,
            null,
            detail,
            List.of("main")));
  }

  private static void notice(ReleaseRequest row, String outcome) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestFailureNotice notice = new ReleaseRequestFailureNotice();
              notice.requestId = row.id;
              notice.mergedSha = SHA;
              notice.agentKey = "0123abcd-0000-4000";
              notice.subject = row.requester;
              notice.owedAt = Instant.now();
              notice.outcome = outcome;
              if (outcome != null && RequestFailedSource.TOLD.contains(outcome)) {
                notice.agentId = AGENT_ID;
                notice.workId = WORK_ID;
              }
              notice.persist();
            });
  }

  private static void rowDetail(ReleaseRequest row, String detail) {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(row.id).detail = detail);
  }

  private static void settle(ReleaseRequest row, String outcome) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleaseRequestFailureNotice.update(
                    "outcome = ?1 where requestId = ?2", outcome, row.id));
  }

  private static void decline(ReleaseRequest row, String actor) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestApproval decline = new ReleaseRequestApproval();
              decline.id = UUID.randomUUID().toString();
              decline.requestId = row.id;
              decline.mergedSha = SHA;
              decline.decision = ReleaseRequestApproval.Decision.DECLINED;
              decline.actor = actor;
              decline.decidedAt = Instant.now();
              decline.persist();
            });
  }
}
