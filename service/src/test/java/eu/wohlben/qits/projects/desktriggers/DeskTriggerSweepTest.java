package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentTunnels;
import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.agenthost.FakeProjectDaemon;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The sweep</b> (qits-1610): a due window is closed into its message and posted to the desk's
 * daemon once, through the real reverse tunnel to a {@link FakeProjectDaemon} ({@code
 * ProjectFrontDeskTest}'s fixture); a window not yet due is left alone; a refusal stays owed and is
 * tried again with the same text; no daemon past the give-up fails it; a queued answer is recorded
 * as one; and the oldest owed window of a project goes first, one per pass.
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
            + ". Read /tmp/qits-delivery-brief.md and follow it, then act on what happened"
            + " below. (desk trigger "
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
            + " 412911eb";
    assertEquals(expected, textOf(seen));
    assertEquals(expected, window.message, "what was sent is what is stored");
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
    sweep.sweep();

    DeskTriggerWindow delivered = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.DELIVERED, delivered.state);
    assertEquals(2, delivered.attempts);
    assertEquals(refused.closedAt, delivered.closedAt, "closed once");
    assertEquals(2, daemon.seen().size());
    assertEquals(textOf(daemon.seen().get(0)), textOf(daemon.seen().get(1)), "one message text");
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
}
