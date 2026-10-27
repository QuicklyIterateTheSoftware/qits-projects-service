package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.ReleaseRequestStateChanged;
import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestFailureNotice;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>request-failed</b> (qits-1689): a release request that goes REJECTED, CONFLICTED or FAILED
 * reaches the desk only when no active agent was told of it (qits-1672's notices).
 */
@QuarkusTest
class RequestFailedSourceTest {

  private static final String SHA = "412911eb" + "0".repeat(32);

  @Inject DeskTriggerListener listener;

  @Inject DeskTriggerSweep sweep;

  @Inject ReleaseRequestNumbers numbers;

  private final List<String> projects = new ArrayList<>();

  private final List<String> requests = new ArrayList<>();

  @AfterEach
  void drop() {
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
  void anActiveAgentToldMeansNoDeskLine() {
    String projectId = project();
    ReleaseRequest row = request(projectId, "tok-workspace-agent-0123abcd-0000-4000-x");
    notice(row, "DELIVERED");

    listener.onFrame(changed(row, "REJECTED", "Run r2 finished FAILED"));
    sweep.sweep(Instant.now());

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
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
    listener.onFrame(frame);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "once: sweep, second sweep and replay are one line");
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
              return row;
            });
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
