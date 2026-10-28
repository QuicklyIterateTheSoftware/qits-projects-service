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
 * <b>capacity-opened</b> (qits-1689): an agent leaves, and the project now runs fewer items than
 * its {@code max_running}, so the desk is told a slot opened — unless the leave freed no slot, or
 * the desk caused it itself (qits-1694).
 */
@QuarkusTest
class CapacityOpenedSourceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject DeskTriggerListener listener;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  private final List<String> projects = new ArrayList<>();

  private final List<String> audited = new ArrayList<>();

  private long numbers = 1;

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
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
  }

  @Test
  void anAgentLeavingBelowMaxRunningIsOneLineBundledAndAReplayAddsNothing() {
    String projectId = project(true, 2);
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
    assertEquals("its agent is done; running 1 of 2", line.label);
    DeskTriggerEvent removed =
        lines.stream().filter(l -> l.itemId.equals("agent-" + other.id)).findFirst().orElseThrow();
    assertEquals("its agent was removed (DISCARDED); running 1 of 2", removed.label);
  }

  @Test
  void anAgentLeavingWhileTheProjectIsStillFullAdmitsNothing() {
    String projectId = project(true, 1);
    WorkEntity done = ticket(projectId, "Done now");
    WorkEntity still = ticket(projectId, "Still running");
    dispatch.willFind(active(still));

    listener.onFrame(left("AgentObsolete", "agent-" + done.id, done.id, null));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
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

  private String project(boolean delivery, int maxRunning) {
    String id = DeskTriggerFixtures.project(delivery);
    projects.add(id);
    QuarkusTransaction.requiringNew()
        .run(() -> Project.<Project>findById(id).deliveryMaxRunning = maxRunning);
    return id;
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
