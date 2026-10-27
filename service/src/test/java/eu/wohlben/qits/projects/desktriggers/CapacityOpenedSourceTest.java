package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.entities.entity.Archetype;
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
 * its {@code max_running}, so the desk is told a slot opened.
 */
@QuarkusTest
class CapacityOpenedSourceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject DeskTriggerListener listener;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  private final List<String> projects = new ArrayList<>();

  private long numbers = 1;

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(() -> projects.forEach(id -> WorkEntity.delete("projectId", id)));
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

  // -------------------------------------------------------------------------------------------

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
    ObjectNode payload = JSON.createObjectNode();
    payload.put("agentId", agentId);
    payload.put("workId", workId);
    payload.put("workspaceId", "ws-1");
    payload.put("repositoryId", "repo-1");
    if (reason != null) {
      payload.put("reason", reason);
      payload.put("harnessStopped", true);
    }
    payload.put("occurredAt", "2026-10-27T18:01:00Z");
    return new EventFrame(
        UUID.randomUUID().toString(), name, Instant.now(), payload.toString(), null, null, null);
  }
}
