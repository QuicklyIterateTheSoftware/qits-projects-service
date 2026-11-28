package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.agenthost.AgentTunnels;
import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.agenthost.FakeProjectDaemon;
import eu.wohlben.qits.projects.control.DevelopmentGuideDefaults;
import eu.wohlben.qits.projects.control.DevelopmentGuideService;
import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup.ActiveAgent;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentLookup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
import eu.wohlben.qits.projects.targethost.FakeSystemLeases;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The sweep</b> (qits-1610): a due window is closed into its message and posted to the desk's
 * daemon once, through the real reverse tunnel to a {@link FakeProjectDaemon} ({@code
 * ProjectFrontDeskTest}'s fixture); a window not yet due is left alone; a refusal stays owed and is
 * tried again with the same text; no daemon past the give-up fails it; a queued answer is recorded
 * as one; and the oldest owed window of a project goes first, one per pass. A delivery project's
 * message ends with its Slots section (qits-1778), and a reading that fails leaves it out without
 * keeping the window from closing.
 *
 * <p>qits-2501: every line is re-checked on each pass over an OPEN window, at the close and before
 * every delivery attempt; a line that no longer holds, or that a newer line of the same request
 * supersedes, is dropped (stamped, never deleted) and left out of the message, and a window with no
 * live line left goes REDUNDANT without a prompt.
 */
@QuarkusTest
class DeskTriggerSweepTest {

  private static final String DELIVERED =
      "{\"commandId\":\"cmd-1\",\"state\":\"RUNNING\",\"delivered\":true,\"queued\":false,"
          + "\"position\":null}";

  private static final String QUEUED =
      "{\"commandId\":\"cmd-2\",\"state\":\"RUNNING\",\"delivered\":false,\"queued\":true,"
          + "\"position\":2}";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject Vertx vertx;

  @Inject AgentTunnels tunnels;

  @Inject DeskTriggerWindows windows;

  @Inject DeskTriggerSweep sweep;

  @Inject DevelopmentGuideService guide;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @Inject ReleaseRequestNumbers numbers;

  @Inject RecordingWorkspaceAgentLookup agents;

  @Inject FakeSystemLeases system;

  @TestHTTPResource("/")
  URL root;

  private FakeProjectDaemon daemon;

  /** What the fake answers to the prompt next; set per test. */
  private final AtomicReference<FakeProjectDaemon.Reply> reply = new AtomicReference<>();

  private final List<String> projects = new ArrayList<>();

  @AfterEach
  void tearDown() {
    if (daemon != null) {
      daemon.close();
      daemon = null;
    }
    dispatch.reset();
    agents.reset();
    system.reset();
    DeskTriggerFixtures.dropWork(projects);
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  private String project() {
    String id = DeskTriggerFixtures.project(true);
    projects.add(id);
    return id;
  }

  private void connectDaemon(String projectId) {
    daemon =
        FakeProjectDaemon.connect(
            vertx,
            tunnels,
            root.getHost(),
            root.getPort(),
            projectId,
            seen ->
                seen.uri().endsWith("/front-desk/prompt")
                    ? reply.get()
                    : new FakeProjectDaemon.Reply(404, "{\"message\":\"No such endpoint\"}"));
  }

  private UUID verified(String projectId, String ref, String title, Instant at) {
    return windows.admit(
        projectId,
        WorkVerifiedSource.ID,
        new DeskTrigger(projectId, ref, null, "dana", title, UUID.randomUUID(), at),
        at);
  }

  private static String textOf(FakeProjectDaemon.Seen seen) throws Exception {
    return JSON.readTree(seen.body()).path("text").asText();
  }

  @Test
  void aWindowOfTwoVerifiedItemsAndAnApprovalIsDeliveredAsOneMessage() throws Exception {
    String projectId = project();
    String slug = DeskTriggerFixtures.slugOf(projectId);
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, slug + "-1580", "Title of 1580", opened);
    verified(projectId, slug + "-1597", "Title of 1597", opened.plusSeconds(1));
    windows.admit(
        projectId,
        ApprovalNeededSource.ID,
        new DeskTrigger(
            projectId,
            "qits-qits-rr-201",
            UUID.randomUUID().toString(),
            "fold 412911eb",
            "Release qits-1597 wrapper; waits on manual-review: configured by manual-review",
            UUID.randomUUID(),
            opened),
        opened.plusSeconds(2));
    reply.set(new FakeProjectDaemon.Reply(200, DELIVERED));
    connectDaemon(projectId);
    // qits-2573: M is N plus what the targets can grant.
    system.willGrant(2, "target lab has no free WORKSPACE slot");

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.DELIVERED, window.state);
    assertEquals("cmd-1", window.commandId);
    assertNotNull(window.acceptedAt);
    assertEquals(1, window.attempts);
    assertEquals(200, window.lastStatus);
    assertEquals(1, daemon.seen().size(), "one POST for the whole window");
    FakeProjectDaemon.Seen seen = daemon.seen().get(0);
    assertEquals("POST", seen.method());
    assertEquals(ContainerProxyPath.base(projectId) + "front-desk/prompt", seen.uri());
    String expected =
        "You are the delivery desk of project "
            + slug
            + ". "
            + DeskBrief.pointer(guide.renderHash(DevelopmentGuideDefaults.DESK))
            + " Then act on what happened below. (desk trigger "
            + windowId
            + ", "
            + window.openedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            + " to "
            + window.closesAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            + ")\n\nMoved to VERIFIED:\n- "
            + slug
            + "-1580 (Title of 1580)\n- "
            + slug
            + "-1597 (Title of 1597)\n\nNeeds your approval:\n- qits-qits-rr-201 (Release"
            + " qits-1597 wrapper; waits on manual-review: configured by manual-review), fold"
            + " 412911eb"
            + "\n\nSlots: running 0 of 2";
    assertEquals(expected, textOf(seen));
    assertEquals(expected, window.message, "what was sent is what is stored");
    // qits-1734: the pointer is DeskBrief's, with the desk variant's guide-hash at close.
    assertTrue(window.message.contains(DeskBrief.INSTRUCTION), window.message);
    assertTrue(
        window.message.contains(guide.renderHash(DevelopmentGuideDefaults.DESK)), window.message);
  }

  @Test
  void aWindowNotYetDueIsNotDelivered() {
    String projectId = project();
    UUID windowId = verified(projectId, "p-1", "fresh", Instant.now());
    reply.set(new FakeProjectDaemon.Reply(200, DELIVERED));
    connectDaemon(projectId);

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OPEN, window.state);
    assertNull(window.message);
    assertTrue(daemon.seen().isEmpty());
  }

  @Test
  void aRefusalStaysOwedAndTheNextPassDeliversTheSameText() throws Exception {
    String projectId = project();
    UUID windowId = verified(projectId, "p-1", "one", Instant.now().minus(Duration.ofMinutes(20)));
    reply.set(new FakeProjectDaemon.Reply(409, "{\"reason\":\"no kept desk command\"}"));
    connectDaemon(projectId);

    sweep.sweep();

    DeskTriggerWindow refused = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OWED, refused.state);
    assertEquals(1, refused.attempts);
    assertEquals(409, refused.lastStatus);
    assertEquals("no kept desk command", refused.lastDetail);
    assertNotNull(refused.closedAt);

    reply.set(new FakeProjectDaemon.Reply(200, DELIVERED));
    // The retry goes out at once on the keep-alive connection the refusal came back on, through the
    // tunnel's byte pipe; a pass that loses that race to the transport answers 503 and rightly
    // leaves the window owed (qits-1734, CI run 7284b317). Production's next pass is 30s later, so
    // a 503 here is the test's tempo, not the sweep's contract: allow a couple more passes for it,
    // and only for it. Anything else stays a failure, now with what the window recorded.
    DeskTriggerWindow delivered = DeskTriggerFixtures.window(windowId);
    for (int pass = 0; pass < 3; pass++) {
      sweep.sweep();
      delivered = DeskTriggerFixtures.window(windowId);
      if (delivered.state != DeskTriggerWindowState.OWED
          || delivered.lastStatus == null
          || delivered.lastStatus.intValue() != 503) {
        break;
      }
    }

    String recorded =
        "attempts="
            + delivered.attempts
            + " lastStatus="
            + delivered.lastStatus
            + " lastDetail="
            + delivered.lastDetail;
    assertEquals(DeskTriggerWindowState.DELIVERED, delivered.state, recorded);
    assertTrue(delivered.attempts >= 2, recorded);
    assertEquals(refused.closedAt, delivered.closedAt, "closed once");
    List<FakeProjectDaemon.Seen> seen = daemon.seen();
    assertTrue(seen.size() >= 2, "the refusal and the delivery reached the daemon: " + seen.size());
    String first = textOf(seen.get(0));
    for (FakeProjectDaemon.Seen each : seen) {
      assertEquals(first, textOf(each), "one message text");
    }
  }

  @Test
  void noDaemonStaysOwedAndPastTheGiveUpFails() {
    String projectId = project();
    Instant longAgo = Instant.now().minus(Duration.ofHours(5));
    UUID windowId = verified(projectId, "p-1", "old", longAgo.minus(Duration.ofMinutes(20)));

    sweep.sweep(longAgo);

    DeskTriggerWindow owed = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OWED, owed.state);
    assertEquals(1, owed.attempts);
    assertEquals(503, owed.lastStatus);
    assertTrue(owed.lastDetail.contains("No front desk daemon is connected"), owed.lastDetail);

    sweep.sweep();

    DeskTriggerWindow failed = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.FAILED, failed.state);
    assertEquals(1, failed.attempts, "given up without another attempt");
    assertTrue(failed.lastDetail.contains("PT4H"), failed.lastDetail);
  }

  @Test
  void aQueuedAnswerIsRecordedWithItsPosition() {
    String projectId = project();
    UUID windowId = verified(projectId, "p-1", "one", Instant.now().minus(Duration.ofMinutes(20)));
    reply.set(new FakeProjectDaemon.Reply(200, QUEUED));
    connectDaemon(projectId);

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.QUEUED, window.state);
    assertEquals(2, window.queuePosition);
    assertEquals("cmd-2", window.commandId);
    assertNotNull(window.acceptedAt);
  }

  @Test
  void aMalformedMessageFailsAndIsNotRetried() {
    String projectId = project();
    UUID windowId = verified(projectId, "p-1", "one", Instant.now().minus(Duration.ofMinutes(20)));
    reply.set(new FakeProjectDaemon.Reply(400, "{\"reason\":\"A prompt needs text.\"}"));
    connectDaemon(projectId);

    sweep.sweep();
    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.FAILED, window.state);
    assertEquals(400, window.lastStatus);
    assertEquals(1, daemon.seen().size());
  }

  @Test
  void aTriggerAdmittedAfterTheCloseOpensANewWindow() {
    String projectId = project();
    UUID first = verified(projectId, "p-1", "one", Instant.now().minus(Duration.ofMinutes(20)));

    sweep.sweep();
    UUID second = verified(projectId, "p-2", "two", Instant.now());

    assertNotEquals(first, second);
    assertEquals(DeskTriggerWindowState.OWED, DeskTriggerFixtures.window(first).state);
    assertEquals(DeskTriggerWindowState.OPEN, DeskTriggerFixtures.window(second).state);
    assertEquals(1, DeskTriggerFixtures.events(first).size(), "the closed window kept its line");
  }

  @Test
  void twoOwedWindowsOfAProjectGoOldestFirstOnePerPass() throws Exception {
    String projectId = project();
    Instant now = Instant.now();
    UUID older = verified(projectId, "p-1", "older", now.minus(Duration.ofMinutes(40)));
    sweep.sweep(now.minus(Duration.ofMinutes(24)));
    UUID newer = verified(projectId, "p-2", "newer", now.minus(Duration.ofMinutes(20)));
    reply.set(new FakeProjectDaemon.Reply(200, DELIVERED));
    connectDaemon(projectId);

    sweep.sweep();

    assertEquals(DeskTriggerWindowState.DELIVERED, DeskTriggerFixtures.window(older).state);
    assertEquals(DeskTriggerWindowState.OWED, DeskTriggerFixtures.window(newer).state);
    assertTrue(textOf(daemon.seen().get(0)).contains("p-1 (older)"));

    sweep.sweep();

    assertEquals(DeskTriggerWindowState.DELIVERED, DeskTriggerFixtures.window(newer).state);
    assertEquals(2, daemon.seen().size());
    assertTrue(textOf(daemon.seen().get(1)).contains("p-2 (newer)"));
  }

  /**
   * qits-1778: a closing window of a delivery project ends with its slots — what runs of N + F
   * (qits-2573), and each VERIFIED item, oldest first, with its open requests in scope.
   */
  @Test
  void aClosingWindowEndsWithTheProjectsSlots() {
    String projectId = project();
    String slug = DeskTriggerFixtures.slugOf(projectId);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    // Delivery came on five minutes ago, so verified-unshipped measures from then and says nothing
    // about the item with no request: this window holds the one verified line and the slots.
    DeskTriggerFixtures.delivery(projectId, now.minus(Duration.ofMinutes(5)));
    WorkEntity shipping = DeskTriggerFixtures.ticket(projectId, 1774, "VERIFIED");
    WorkEntity unasked = DeskTriggerFixtures.ticket(projectId, 1769, "VERIFIED");
    WorkEntity working = DeskTriggerFixtures.ticket(projectId, 1780, "IMPLEMENTING");
    DeskTriggerFixtures.audited(shipping, "IMPLEMENTED", now.minus(Duration.ofHours(3)));
    DeskTriggerFixtures.audited(shipping, "VERIFIED", now.minus(Duration.ofMinutes(65)));
    DeskTriggerFixtures.audited(shipping, "VERIFIED", now.minus(Duration.ofMinutes(60)));
    DeskTriggerFixtures.audited(unasked, "VERIFIED", now.minus(Duration.ofMinutes(43)));
    String wrapper =
        DeskTriggerFixtures.request(
            numbers,
            projectId,
            slug + "-" + slug,
            ReleaseRequest.State.PENDING,
            "ticket/" + slug + "-1774");
    DeskTriggerFixtures.request(
        numbers,
        projectId,
        "some-service",
        ReleaseRequest.State.FINALIZED,
        "ticket/" + slug + "-1769-fix");
    dispatch.willFind(
        DeskTriggerFixtures.activeAgent(shipping),
        DeskTriggerFixtures.activeAgent(unasked),
        DeskTriggerFixtures.activeAgent(working));
    system.willGrant(0, "target lab runs its maximum of 3 WORKSPACE lease(s)");
    UUID windowId = verified(projectId, "p-1", "one", now.minus(Duration.ofMinutes(20)));

    sweep.sweep(now);

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OWED, window.state, "no daemon: closed and owed");
    assertTrue(
        window.message.endsWith(
            "\n\nMoved to VERIFIED:\n- p-1 (one)"
                + "\n\nSlots: running 3 of 3, 2 VERIFIED waiting to ship\n"
                + "- "
                + slug
                + "-1774 (VERIFIED 1h 5m; "
                + wrapper
                + " PENDING)\n"
                + "- "
                + slug
                + "-1769 (VERIFIED 43m; no open release request)"),
        window.message);
  }

  /** qits-1778: an agent port that throws costs the section, never the close. */
  @Test
  void aSlotsReadingThatFailsClosesTheWindowWithoutTheSection() {
    String projectId = project();
    DeskTriggerFixtures.ticket(projectId, 7, "VERIFIED");
    dispatch.willFailLookups(new IllegalStateException("qits-workspaces adapter broke"));
    UUID windowId = verified(projectId, "p-1", "one", Instant.now().minus(Duration.ofMinutes(20)));

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OWED, window.state);
    assertNotNull(window.closedAt);
    assertTrue(window.message.endsWith("\n\nMoved to VERIFIED:\n- p-1 (one)"), window.message);
    assertFalse(window.message.contains("Slots:"), window.message);
  }

  // ---- qits-2501: lines that no longer hold ------------------------------------------------------

  private static final String FOLD_A = "aaaaaaaa" + "0".repeat(32);

  private static final String FOLD_B = "bbbbbbbb" + "0".repeat(32);

  private UUID line(
      String projectId,
      String source,
      String ref,
      String itemId,
      String cause,
      String label,
      UUID eventId,
      Instant at) {
    return windows.admit(
        projectId, source, new DeskTrigger(projectId, ref, itemId, cause, label, eventId, at), at);
  }

  /** A release request of the project at {@code state}, changed by {@code change}; its id. */
  private String request(String projectId, ReleaseRequest.State state, Consumer<ReleaseRequest> change) {
    String qualifiedId =
        DeskTriggerFixtures.request(
            numbers,
            projectId,
            "repo-" + UUID.randomUUID().toString().substring(0, 8),
            state,
            "ticket/x-" + UUID.randomUUID().toString().substring(0, 8));
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row =
                  ReleaseRequest.<ReleaseRequest>find(
                          "qualifiedId = ?1 and projectId = ?2", qualifiedId, projectId)
                      .firstResult();
              change.accept(row);
              return row.id;
            });
  }

  private static void changeRequest(String id, Consumer<ReleaseRequest> change) {
    QuarkusTransaction.requiringNew()
        .run(() -> change.accept(ReleaseRequest.<ReleaseRequest>findById(id)));
  }

  private static DeskTriggerEvent lineOf(UUID windowId, String itemRef) {
    return DeskTriggerFixtures.events(windowId).stream()
        .filter(event -> event.itemRef.equals(itemRef))
        .findFirst()
        .orElseThrow();
  }

  private static void assertDropped(UUID windowId, String itemRef, String reason) {
    DeskTriggerEvent event = lineOf(windowId, itemRef);
    assertEquals(reason, event.droppedReason, itemRef);
    assertNotNull(event.droppedAt, itemRef);
  }

  private static void assertLive(UUID windowId, String itemRef) {
    DeskTriggerEvent event = lineOf(windowId, itemRef);
    assertNull(event.droppedReason, itemRef);
    assertNull(event.droppedAt, itemRef);
  }

  @Test
  void anApprovalAtASupersededFoldOrOfAFinalizedRequestIsNotDelivered() throws Exception {
    String projectId = project();
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    String refolded = request(projectId, ReleaseRequest.State.PENDING, row -> row.mergedSha = FOLD_B);
    String finalized =
        request(projectId, ReleaseRequest.State.FINALIZED, row -> row.mergedSha = FOLD_A);
    String waiting = request(projectId, ReleaseRequest.State.PENDING, row -> row.mergedSha = FOLD_A);
    line(projectId, ApprovalNeededSource.ID, "rr-refolded", refolded, "fold aaaaaaaa",
        "Old; waits on manual-review: a person approves", UUID.randomUUID(), opened);
    line(projectId, ApprovalNeededSource.ID, "rr-finalized", finalized, "fold aaaaaaaa",
        "Done; waits on manual-review: a person approves", UUID.randomUUID(), opened);
    line(projectId, ApprovalNeededSource.ID, "rr-waiting", waiting, "fold aaaaaaaa",
        "Still; waits on manual-review: a person approves", UUID.randomUUID(), opened);
    reply.set(new FakeProjectDaemon.Reply(200, DELIVERED));
    connectDaemon(projectId);

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.DELIVERED, window.state);
    String sent = textOf(daemon.seen().get(0));
    assertEquals(window.message, sent);
    assertTrue(sent.contains("\n\nMoved to VERIFIED:\n- p-1 (informational)"), sent);
    assertTrue(sent.contains("- rr-waiting (Still; waits on manual-review"), sent);
    assertFalse(sent.contains("rr-refolded"), sent);
    assertFalse(sent.contains("rr-finalized"), sent);
    assertDropped(windowId, "rr-refolded", DeskTriggerEvent.RESOLVED);
    assertDropped(windowId, "rr-finalized", DeskTriggerEvent.RESOLVED);
    assertLive(windowId, "rr-waiting");
    assertLive(windowId, "p-1");
  }

  @Test
  void anApprovalWhoseRequirementWasSettledAtItsFoldIsDropped() {
    String projectId = project();
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    String requestId = request(projectId, ReleaseRequest.State.PENDING, row -> row.mergedSha = FOLD_A);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String state : List.of("OPEN", "SATISFIED")) {
                var notice = new eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice();
                notice.requestId = requestId;
                notice.mergedSha = FOLD_A;
                notice.requirementId = "manual-review";
                notice.state = state;
                notice.announcedAt = Instant.now();
                notice.persist();
              }
            });
    line(projectId, ApprovalNeededSource.ID, "rr-approved", requestId, "fold aaaaaaaa",
        "Approved; waits on manual-review: a person approves", UUID.randomUUID(), opened);

    sweep.sweep();

    assertDropped(windowId, "rr-approved", DeskTriggerEvent.RESOLVED);
    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertFalse(window.message.contains("rr-approved"), window.message);
  }

  @Test
  void aFailureWhoseRequestRefoldedOrLeftTheStateIsNotDelivered() {
    String projectId = project();
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    String refolded = request(projectId, ReleaseRequest.State.REJECTED, row -> row.mergedSha = FOLD_B);
    String reopened = request(projectId, ReleaseRequest.State.PENDING, row -> row.mergedSha = FOLD_A);
    String stillRed = request(projectId, ReleaseRequest.State.REJECTED, row -> row.mergedSha = FOLD_A);
    line(projectId, RequestFailedSource.ID, "rr-refolded", refolded, "fold aaaaaaaa",
        "REJECTED: red build; no agent owns it", UUID.randomUUID(), opened);
    line(projectId, RequestFailedSource.ID, "rr-reopened", reopened, "fold aaaaaaaa",
        "REJECTED: red build; no agent owns it", UUID.randomUUID(), opened);
    line(projectId, RequestFailedSource.ID, "rr-red", stillRed, "fold aaaaaaaa",
        "REJECTED: red build; no agent owns it", UUID.randomUUID(), opened);

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OWED, window.state);
    assertDropped(windowId, "rr-refolded", DeskTriggerEvent.RESOLVED);
    assertDropped(windowId, "rr-reopened", DeskTriggerEvent.RESOLVED);
    assertLive(windowId, "rr-red");
    assertTrue(window.message.contains("- rr-red (REJECTED: red build; no agent owns it)"),
        window.message);
    assertFalse(window.message.contains("rr-refolded"), window.message);
    assertFalse(window.message.contains("rr-reopened"), window.message);
  }

  @Test
  void aRequestIsNamedOncePerSourceAndAnApprovalSupersedesItsFailure() {
    String projectId = project();
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    // A request this service cannot read holds (fail open), so only supersession drops a line.
    String unknown = UUID.randomUUID().toString();
    line(projectId, RequestFailedSource.ID, "rr-9", unknown, "fold aaaaaaaa",
        "REJECTED: red build; no agent owns it", UUID.randomUUID(), opened);
    line(projectId, ApprovalNeededSource.ID, "rr-9", unknown, "fold aaaaaaaa",
        "First; waits on manual-review: a person approves", UUID.randomUUID(), opened);
    line(projectId, ApprovalNeededSource.ID, "rr-9", unknown, "fold bbbbbbbb",
        "Second; waits on manual-review: a person approves", UUID.randomUUID(),
        opened.plusSeconds(30));

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    List<DeskTriggerEvent> lines =
        DeskTriggerFixtures.events(windowId).stream().filter(e -> e.itemRef.equals("rr-9")).toList();
    assertEquals(3, lines.size(), "nothing is deleted");
    for (DeskTriggerEvent event : lines) {
      boolean newest = event.label != null && event.label.startsWith("Second");
      assertEquals(newest ? null : DeskTriggerEvent.SUPERSEDED, event.droppedReason, event.label);
    }
    assertEquals(1, window.message.split("- rr-9 ", -1).length - 1, window.message);
    assertTrue(window.message.contains("- rr-9 (Second; waits on manual-review"), window.message);
    assertFalse(window.message.contains("REJECTED"), window.message);
  }

  @Test
  void anIdleAgentThatIsNowBusyOrABrokenWaitOfAGoneAgentIsNotDelivered() {
    String projectId = project();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant opened = now.minus(Duration.ofMinutes(20));
    Instant last = now.minus(Duration.ofHours(3));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    String busy = "busy0000-0000-4000-8000-000000000001";
    String idle = "idle0000-0000-4000-8000-000000000002";
    agents.stage(new ActiveAgent(busy, "w-" + busy, null, null, "BUSY", last, List.of()));
    agents.stage(new ActiveAgent(idle, "w-" + idle, null, null, "IDLE", last, List.of()));
    line(projectId, AgentIdleSource.ID, "p-busy", busy, "agent busy0000", "went quiet",
        AgentIdleSource.idleKey(busy, last), opened);
    line(projectId, AgentIdleSource.ID, "p-idle", idle, "agent idle0000", "went quiet",
        AgentIdleSource.idleKey(idle, last), opened);
    line(projectId, WaitBrokenSource.ID, "agent gone0000", UUID.randomUUID().toString(),
        "agent gone0000", "broken wait \"x\": gone", UUID.randomUUID(), opened);
    line(projectId, WaitBrokenSource.ID, "p-idle-wait", UUID.randomUUID().toString(),
        "agent idle0000", "broken wait \"y\": broke", UUID.randomUUID(), opened);

    sweep.sweep(now);

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertDropped(windowId, "p-busy", DeskTriggerEvent.RESOLVED);
    assertLive(windowId, "p-idle");
    assertDropped(windowId, "agent gone0000", DeskTriggerEvent.RESOLVED);
    assertLive(windowId, "p-idle-wait");
    assertFalse(window.message.contains("p-busy"), window.message);
    assertFalse(window.message.contains("agent gone0000"), window.message);
    assertTrue(window.message.contains("- p-idle (went quiet)"), window.message);
    assertTrue(window.message.contains("- p-idle-wait: broken wait"), window.message);
  }

  @Test
  void anAgentListThatCannotBeReadKeepsTheLines() {
    String projectId = project();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant opened = now.minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    agents.willThrow(new IllegalStateException("qits-workspaces away"));
    line(projectId, AgentIdleSource.ID, "p-quiet", "nobody", "agent nobody", "went quiet",
        UUID.randomUUID(), opened);
    line(projectId, WaitBrokenSource.ID, "agent nobody", UUID.randomUUID().toString(),
        "agent nobody", "broken wait \"x\": gone", UUID.randomUUID(), opened);

    sweep.sweep(now);

    assertLive(windowId, "p-quiet");
    assertLive(windowId, "agent nobody");
  }

  @Test
  void aSlotOpenedLineIsNotDeliveredWhileTheSlotsAreFull() {
    String projectId = project();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    DeskTriggerFixtures.delivery(projectId, now.minus(Duration.ofMinutes(5)));
    WorkEntity working = DeskTriggerFixtures.ticket(projectId, 3, "IMPLEMENTING");
    dispatch.willFind(DeskTriggerFixtures.activeAgent(working));
    // qits-2573: full means no target can grant a workspace lease.
    system.willGrant(0, "target lab has no free WORKSPACE slot");
    Instant opened = now.minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    line(projectId, CapacityOpenedSource.ID, "p-2", "agent-left", "agent agent-le",
        "its agent is done; running 0 of 1", UUID.randomUUID(), opened);

    sweep.sweep(now);

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertDropped(windowId, "p-2", DeskTriggerEvent.RESOLVED);
    assertFalse(window.message.contains("A slot opened"), window.message);
    assertTrue(window.message.endsWith("\n\nSlots: running 1 of 1"), window.message);
  }

  @Test
  void aWindowWithNoLiveLineGoesRedundantWithoutAPromptAndTheNextTriggerOpensANewOne() {
    String projectId = project();
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    String finalized =
        request(projectId, ReleaseRequest.State.FINALIZED, row -> row.mergedSha = FOLD_A);
    UUID windowId =
        line(projectId, ApprovalNeededSource.ID, "rr-done", finalized, "fold aaaaaaaa",
            "Done; waits on manual-review: a person approves", UUID.randomUUID(), opened);
    reply.set(new FakeProjectDaemon.Reply(200, DELIVERED));
    connectDaemon(projectId);

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.REDUNDANT, window.state);
    assertNotNull(window.closedAt);
    assertNull(window.message, "nothing was rendered");
    assertEquals(0, window.attempts);
    assertTrue(daemon.seen().isEmpty(), "the desk is not prompted");
    assertDropped(windowId, "rr-done", DeskTriggerEvent.RESOLVED);

    UUID next = verified(projectId, "p-2", "next", Instant.now());

    assertNotNull(next);
    assertNotEquals(windowId, next);
    assertEquals(DeskTriggerWindowState.OPEN, DeskTriggerFixtures.window(next).state);
    assertEquals(DeskTriggerWindowState.REDUNDANT, DeskTriggerFixtures.window(windowId).state);
  }

  @Test
  void anOpenWindowShowsItsSummaryShrinkingBeforeItCloses() {
    String projectId = project();
    Instant opened = Instant.now();
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    String finalized =
        request(projectId, ReleaseRequest.State.FINALIZED, row -> row.mergedSha = FOLD_A);
    line(projectId, ApprovalNeededSource.ID, "rr-done", finalized, "fold aaaaaaaa",
        "Done; waits on manual-review: a person approves", UUID.randomUUID(), opened);

    sweep.sweep();

    assertEquals(DeskTriggerWindowState.OPEN, DeskTriggerFixtures.window(windowId).state);
    assertDropped(windowId, "rr-done", DeskTriggerEvent.RESOLVED);
    assertLive(windowId, "p-1");
  }

  @Test
  void aRetryAfterARefusalSendsAMessageReRenderedFromTheLiveLines() throws Exception {
    String projectId = project();
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    UUID windowId = verified(projectId, "p-1", "informational", opened);
    String requestId = request(projectId, ReleaseRequest.State.PENDING, row -> row.mergedSha = FOLD_A);
    line(projectId, ApprovalNeededSource.ID, "rr-7", requestId, "fold aaaaaaaa",
        "Ship it; waits on manual-review: a person approves", UUID.randomUUID(), opened);
    reply.set(new FakeProjectDaemon.Reply(409, "{\"reason\":\"no kept desk command\"}"));
    connectDaemon(projectId);

    sweep.sweep();

    DeskTriggerWindow refused = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OWED, refused.state);
    assertTrue(refused.message.contains("- rr-7 (Ship it"), refused.message);
    assertTrue(textOf(daemon.seen().get(0)).contains("- rr-7 (Ship it"));

    changeRequest(requestId, row -> row.state = ReleaseRequest.State.FINALIZED);
    reply.set(new FakeProjectDaemon.Reply(200, DELIVERED));
    DeskTriggerWindow delivered = refused;
    for (int pass = 0; pass < 3; pass++) {
      sweep.sweep();
      delivered = DeskTriggerFixtures.window(windowId);
      if (delivered.state != DeskTriggerWindowState.OWED
          || delivered.lastStatus == null
          || delivered.lastStatus.intValue() != 503) {
        break;
      }
    }

    assertEquals(DeskTriggerWindowState.DELIVERED, delivered.state, delivered.lastDetail);
    List<FakeProjectDaemon.Seen> seen = daemon.seen();
    String last = textOf(seen.get(seen.size() - 1));
    assertEquals(delivered.message, last, "the re-rendered text is stored and sent");
    assertFalse(last.contains("rr-7"), last);
    assertTrue(last.contains("\n\nMoved to VERIFIED:\n- p-1 (informational)"), last);
    assertDropped(windowId, "rr-7", DeskTriggerEvent.RESOLVED);
  }

  @Test
  void anOwedWindowWhoseLinesAllResolvedGoesRedundantInsteadOfBeingRetried() {
    String projectId = project();
    Instant opened = Instant.now().minus(Duration.ofMinutes(20));
    String requestId = request(projectId, ReleaseRequest.State.PENDING, row -> row.mergedSha = FOLD_A);
    UUID windowId =
        line(projectId, ApprovalNeededSource.ID, "rr-7", requestId, "fold aaaaaaaa",
            "Ship it; waits on manual-review: a person approves", UUID.randomUUID(), opened);

    sweep.sweep();

    DeskTriggerWindow owed = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OWED, owed.state, "no daemon: refused, owed");
    assertEquals(1, owed.attempts);

    changeRequest(requestId, row -> row.state = ReleaseRequest.State.WITHDRAWN);
    sweep.sweep();

    DeskTriggerWindow redundant = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.REDUNDANT, redundant.state);
    assertEquals(1, redundant.attempts, "not tried again");
    assertEquals(owed.closedAt, redundant.closedAt);
  }
}
