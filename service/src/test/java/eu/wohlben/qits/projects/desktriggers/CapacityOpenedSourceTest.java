package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.targethost.FakeSystemLeases;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>capacity-opened</b> (qits-1689, qits-2573): an agent leaves, and a workspace lease can now be
 * granted with none of the project's workspaces waiting, so the desk is told a slot opened — unless
 * the leave freed no slot, or the desk caused it itself (qits-1694). And the scan: capacity that
 * opens with no agent leaving.
 */
@QuarkusTest
class CapacityOpenedSourceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject DeskTriggerListener listener;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @Inject FakeSystemLeases system;

  @Inject CapacityOpenedSource source;

  @Inject DeskTriggerWindows windows;

  private final List<String> projects = new ArrayList<>();

  private final List<String> audited = new ArrayList<>();

  private long numbers = 1;

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
    system.reset();
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              audited.forEach(id -> AuditEntry.delete("entityId", id));
              projects.forEach(id -> WorkEntity.delete("projectId", id));
            });
    audited.clear();
    DeskTriggerFixtures.drop(projects);
    projects.clear();
    dispatch.reset();
    system.reset();
  }

  @Test
  void anAgentLeavingWhileALeaseCanBeGrantedIsOneLineBundledAndAReplayAddsNothing() {
    String projectId = project(true, 1);
    WorkEntity done = ticket(projectId, "Done now");
    WorkEntity still = ticket(projectId, "Still running");
    WorkEntity other = ticket(projectId, "Also done");
    dispatch.willFind(active(still));
    EventFrame obsolete = left("AgentObsolete", "agent-" + done.id, done.id, null);

    listener.onFrame(obsolete);
    listener.onFrame(obsolete);
    listener.onFrame(left("AgentRemoved", "agent-" + done.id, done.id, "RELEASED_IDLE_CLEAN"));
    listener.onFrame(left("AgentRemoved", "agent-" + other.id, other.id, "DISCARDED"));

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "bundled in one window");
    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(2, lines.size(), "one line per agent; a replay and its removal add nothing");
    DeskTriggerEvent line =
        lines.stream().filter(l -> l.itemId.equals("agent-" + done.id)).findFirst().orElseThrow();
    assertEquals("capacity-opened", line.source);
    assertEquals(DeskTriggerFixtures.slugOf(projectId) + "-" + done.number, line.itemRef);
    assertEquals("its agent is done; running 1 of 2", line.label, "M is N + F, never max_running");
    DeskTriggerEvent removed =
        lines.stream().filter(l -> l.itemId.equals("agent-" + other.id)).findFirst().orElseThrow();
    assertEquals("its agent was removed (DISCARDED); running 1 of 2", removed.label);
  }

  /** AC3: no target can grant a workspace lease (F = 0): no slot opened. */
  @Test
  void anAgentLeavingWhileNoLeaseCanBeGrantedAdmitsNothing() {
    String projectId = project(true, 0);
    WorkEntity done = ticket(projectId, "Done now");
    WorkEntity still = ticket(projectId, "Still running");
    dispatch.willFind(active(still));

    listener.onFrame(left("AgentObsolete", "agent-" + done.id, done.id, null));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  /** AC3: a workspace of the project waits (K > 0): no slot opened, whatever the targets grant. */
  @Test
  void anAgentLeavingWhileAWorkspaceWaitsAdmitsNothing() {
    String projectId = project(true, 3);
    WorkEntity done = ticket(projectId, "Done now");
    WorkEntity waiting = ticket(projectId, "Waiting for a workspace");
    WorkEntity placed = ticket(projectId, "Placed in a workspace whose lease waits");
    dispatch.willFind(queued(waiting));

    listener.onFrame(left("AgentObsolete", "agent-" + done.id, done.id, null));
    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty(), "QUEUED with no workspace");

    dispatch.willFind(RecordingWorkspaceAgentDispatch.withSlot(active(placed), "QUEUED"));
    listener.onFrame(left("AgentRemoved", "agent-" + done.id, done.id, "DISCARDED", "dana"));
    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty(), "RUNNING in a QUEUED workspace");
  }

  /** AC3: a capacity that cannot be read announces nothing. */
  @Test
  void anAgentLeavingWhileTheCapacityCannotBeReadAdmitsNothing() {
    String projectId = project(true, 2);
    system.willAnswerCapacity(Optional.empty());
    WorkEntity done = ticket(projectId, "Done now");

    listener.onFrame(left("AgentObsolete", "agent-" + done.id, done.id, null));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  /**
   * The re-check (qits-2501): a stored line drops once no lease can be granted or a workspace waits,
   * and holds while the slots are open or the reading cannot be made.
   */
  @Test
  void aStoredLineIsResolvedOnceNoLeaseCanBeGrantedOrAWorkspaceWaits() {
    String projectId = project(true, 1);
    WorkEntity done = ticket(projectId, "Done now");
    WorkEntity other = ticket(projectId, "Next");
    listener.onFrame(left("AgentObsolete", "agent-" + done.id, done.id, null));
    List<DeskTriggerEventRow> lines =
        DeskTriggerFixtures.eventsOf(projectId).stream().map(DeskTriggerEventRow::of).toList();
    assertEquals(1, lines.size());
    Instant now = Instant.now();

    assertTrue(source.resolved(projectId, lines, now).isEmpty(), "open: holds");
    system.willAnswerCapacity(Optional.empty());
    assertTrue(source.resolved(projectId, lines, now).isEmpty(), "unknown: holds");
    system.willGrant(0, "target lab has no free WORKSPACE slot");
    assertEquals(1, source.resolved(projectId, lines, now).size(), "F = 0: resolved");
    system.willGrant(1, null);
    dispatch.willFind(queued(other));
    assertEquals(1, source.resolved(projectId, lines, now).size(), "K > 0: resolved");
  }

  /**
   * The scan (qits-2573): capacity that opens with no agent leaving is announced once it held on two
   * passes, never within 15 minutes of another slot line, once per running set, and not at all for a
   * project with delivery off.
   */
  @Test
  void theScanAnnouncesCapacityThatHeldTwoPassesOncePerRunningSet() {
    String projectId = project(true, 2);
    String off = project(false, 2);
    WorkEntity running = ticket(projectId, "Running");
    dispatch.willFind(active(running));
    Instant t0 = Instant.now();

    assertTrue(scan(projectId, t0).isEmpty(), "one pass is not enough");
    List<DeskTrigger> second = scan(projectId, t0.plusSeconds(30));
    assertEquals(1, second.size());
    DeskTrigger line = second.get(0);
    assertEquals(DeskTriggerFixtures.slugOf(projectId), line.itemRef());
    assertEquals("slots", line.cause());
    assertEquals(
        "running 1 of 3; the targets can grant 2 more workspace leases: lab 2", line.label());
    assertEquals(null, line.principal());
    assertEquals(line.eventId().toString(), line.itemId());
    assertTrue(scan(off, t0.plusSeconds(30)).isEmpty(), "delivery off: never scanned");
    QuarkusTransaction.requiringNew()
        .run(() -> windows.admit(projectId, CapacityOpenedSource.ID, line, t0.plusSeconds(30)));

    // Within 15 minutes of that line: nothing, even on a new running set.
    WorkEntity more = ticket(projectId, "Also running");
    dispatch.willFind(active(running), active(more));
    assertTrue(scan(projectId, t0.plusSeconds(60)).isEmpty());
    // Later, the same running set as the stored line: nothing; a new one: one line.
    dispatch.willFind(active(running));
    assertTrue(scan(projectId, t0.plus(Duration.ofMinutes(20))).isEmpty(), "one per running set");
    dispatch.willFind(active(running), active(more));
    assertEquals(1, scan(projectId, t0.plus(Duration.ofMinutes(21))).size());

    // A pass with the slots full resets the debounce.
    system.willGrant(0, "target lab has no free WORKSPACE slot");
    assertTrue(scan(projectId, t0.plus(Duration.ofMinutes(22))).isEmpty());
    system.willGrant(1, null);
    assertTrue(scan(projectId, t0.plus(Duration.ofMinutes(23))).isEmpty(), "first open pass again");
  }

  /** The scan holds off within 15 minutes of an event-path slot line too. */
  @Test
  void theScanHoldsOffWithinTheWindowOfAnEventLine() {
    String projectId = project(true, 2);
    WorkEntity done = ticket(projectId, "Done now");
    listener.onFrame(left("AgentObsolete", "agent-" + done.id, done.id, null));
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());
    Instant now = Instant.now();

    scan(projectId, now);
    assertTrue(scan(projectId, now.plusSeconds(30)).isEmpty());
  }

  @Test
  void aProjectWithDispatchingOffAdmitsNothing() {
    String projectId = project(false, 2);
    WorkEntity done = ticket(projectId, "Done now");

    listener.onFrame(left("AgentObsolete", "agent-" + done.id, done.id, null));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void aDeskCausedDropAdmitsNothingAndAPersonsDropIsALine() {
    String projectId = project(true, 2);
    DeskTriggerFixtures.desk(projectId, "tok-desk", "tok-delivery", "tok-session");
    WorkEntity mine = ticket(projectId, "Dropped by the desk");
    WorkEntity theirs = ticket(projectId, "Dropped by a person");
    drop(mine, "tok-delivery");
    drop(theirs, "dana");

    listener.onFrame(left("AgentObsolete", "agent-" + mine.id, mine.id, null));
    listener.onFrame(left("AgentObsolete", "agent-" + theirs.id, theirs.id, null));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "the desk is not told of its own drop");
    assertEquals("agent-" + theirs.id, lines.get(0).itemId);
    assertEquals("dana", lines.get(0).cause, "the drop's author is the principal");
  }

  @Test
  void aDeskCausedDiscardAdmitsNothingAndAPersonsIsOneLine() {
    String projectId = project(true, 2);
    DeskTriggerFixtures.desk(projectId, "tok-desk", "tok-delivery", "tok-session");
    WorkEntity mine = ticket(projectId, "Discarded by the desk");
    WorkEntity theirs = ticket(projectId, "Discarded by a person");

    listener.onFrame(
        left("AgentRemoved", "agent-" + mine.id, mine.id, "DISCARDED", "tok-delivery"));
    listener.onFrame(left("AgentRemoved", "agent-" + theirs.id, theirs.id, "DISCARDED", "dana"));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "the desk is not told of its own discard");
    DeskTriggerEvent line = lines.get(0);
    assertEquals("agent-" + theirs.id, line.itemId);
    assertEquals("dana", line.cause);
    assertEquals("its agent was removed (DISCARDED); running 0 of 2", line.label);
  }

  @Test
  void aRemovalAfterObsoleteFreesNoSlotAndAdmitsNothing() {
    String projectId = project(true, 1);
    for (String reason :
        List.of(
            "RELEASED_IDLE_CLEAN",
            "DROPPED_IDLE_CLEAN",
            "OBSOLETE_NEVER_PLACED",
            "OBSOLETE_WORKSPACE_GONE")) {
      WorkEntity done = ticket(projectId, "Left at OBSOLETE, " + reason);
      // The project was full when this agent went OBSOLETE, so nothing was admitted then; it has
      // room now (nothing ACTIVE), and the removal must not report a slot that opened earlier.
      listener.onFrame(left("AgentRemoved", "agent-" + done.id, done.id, reason));
    }

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void anAgentLosingItsDaemonIsALine() {
    String projectId = project(true, 2);
    WorkEntity lost = ticket(projectId, "Its daemon lost it");

    listener.onFrame(left("AgentRemoved", "agent-" + lost.id, lost.id, "UNKNOWN_TO_DAEMON"));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals("its agent was removed (UNKNOWN_TO_DAEMON); running 0 of 2", lines.get(0).label);
  }

  @Test
  void anAgentLeavingABlockedOrUnassignedItemAdmitsNothing() {
    String projectId = project(true, 2);
    WorkEntity blocked = ticket(projectId, "Waits on someone");
    WorkEntity unassigned = ticket(projectId, "Nobody on it");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkEntity.<WorkEntity>findById(blocked.id).blocked = true;
              WorkEntity.<WorkEntity>findById(unassigned.id).assignee = " ";
            });

    listener.onFrame(left("AgentObsolete", "agent-" + blocked.id, blocked.id, null));
    listener.onFrame(
        left("AgentRemoved", "agent-" + unassigned.id, unassigned.id, "DISCARDED", "dana"));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  // -------------------------------------------------------------------------------------------

  /** The item DROPPED by {@code by}: its status, and the audit row the drop writes. */
  private void drop(WorkEntity item, String by) {
    audited.add(item.id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkEntity.<WorkEntity>findById(item.id).status = "DROPPED";
              AuditEntry older = new AuditEntry();
              older.id = UUID.randomUUID().toString();
              older.entityType = AuditEntityType.TICKET;
              older.entityId = item.id;
              older.epicId = item.id;
              older.operation = AuditOperation.CREATE;
              older.changedBy = "someone-earlier";
              older.persist();
            });
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AuditEntry entry = new AuditEntry();
              entry.id = UUID.randomUUID().toString();
              entry.entityType = AuditEntityType.TICKET;
              entry.entityId = item.id;
              entry.epicId = item.id;
              entry.operation = AuditOperation.UPDATE;
              entry.changedBy = by;
              entry.persist();
            });
  }

  /**
   * A project, and the targets able to grant {@code free} more workspace leases (qits-2573). Its
   * {@code max_running} is 1, which nothing reads any more.
   */
  private String project(boolean delivery, int free) {
    String id = DeskTriggerFixtures.project(delivery);
    projects.add(id);
    QuarkusTransaction.requiringNew()
        .run(() -> Project.<Project>findById(id).deliveryMaxRunning = 1);
    system.willGrant(free, free == 0 ? "target lab has no free WORKSPACE slot" : null);
    return id;
  }

  /** One scan pass at {@code now}, as the sweep runs it: the triggers of {@code projectId}. */
  private List<DeskTrigger> scan(String projectId, Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(() -> source.scan(now))
        .stream()
        .filter(t -> projectId.equals(t.projectId()))
        .toList();
  }

  /** A ticket of the project with an assignee, so an ACTIVE agent makes it count as running. */
  private WorkEntity ticket(String projectId, String title) {
    long number = numbers++;
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkEntity row = new WorkEntity();
              row.id = UUID.randomUUID().toString();
              row.projectId = projectId;
              row.archetype = Archetype.TICKET;
              row.number = number;
              row.title = title;
              row.slug = "slug-" + row.id;
              row.slugScope = projectId;
              row.status = "IMPLEMENTING";
              row.assignee = "ws-agent-" + number;
              row.createdAt = Instant.now();
              row.updatedAt = Instant.now();
              row.persist();
              return row;
            });
  }

  private static eu.wohlben.qits.projects.control.WorkspaceAgentDispatch.Agent active(
      WorkEntity item) {
    return RecordingWorkspaceAgentDispatch.active(
        "agent-" + item.id, 7L, "wrapper", item.id, "ticket/" + item.slug);
  }

  /** An ACTIVE agent of {@code item} still waiting for a workspace. */
  private static eu.wohlben.qits.projects.control.WorkspaceAgentDispatch.Agent queued(
      WorkEntity item) {
    return RecordingWorkspaceAgentDispatch.queued(
        "agent-" + item.id, "wrapper", item.id, "ticket/" + item.slug);
  }

  /** qits-workspaces' event as the wire carries it: its type is not on this classpath. */
  private static EventFrame left(String name, String agentId, String workId, String reason) {
    return left(name, agentId, workId, reason, null);
  }

  /** {@link #left(String, String, String, String)} with qits-workspaces' {@code discardedBy}. */
  private static EventFrame left(
      String name, String agentId, String workId, String reason, String discardedBy) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("agentId", agentId);
    payload.put("workId", workId);
    payload.put("workspaceId", "ws-1");
    payload.put("repositoryId", "repo-1");
    if (reason != null) {
      payload.put("reason", reason);
      payload.put("harnessStopped", true);
      payload.put("discardedBy", discardedBy);
    }
    payload.put("occurredAt", "2026-10-27T18:01:00Z");
    return new EventFrame(
        UUID.randomUUID().toString(), name, Instant.now(), payload.toString(), null, null, null);
  }
}
