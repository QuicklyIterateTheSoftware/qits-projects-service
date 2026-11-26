package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch.Agent;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RunningItems#read} (qits-2573): an item runs only while its agent's workspace holds its
 * lease. A QUEUED one — no workspace yet, or a workspace whose lease waits — is waiting, NONE is
 * neither, and an agent from a qits-workspaces that does not say its slot is read from its run state.
 */
@QuarkusTest
class RunningItemsTest {

  @Inject RunningItems running;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  private final String projectId = "running-items-" + UUID.randomUUID();

  private long numbers = 1;

  @BeforeEach
  void reset() {
    dispatch.reset();
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew().run(() -> WorkEntity.delete("projectId", projectId));
    dispatch.reset();
  }

  @Test
  void aHeldSlotRunsAQueuedOneWaitsAndNoneIsNeither() {
    WorkEntity held = ticket("Held");
    WorkEntity queued = ticket("Queued, no workspace");
    WorkEntity placed = ticket("Placed in a workspace whose lease waits");
    WorkEntity none = ticket("Its workspace failed");
    dispatch.willFind(
        slot(active(held), "HELD"),
        slot(queued(queued), "QUEUED"),
        slot(active(placed), "QUEUED"),
        slot(active(none), "NONE"));

    RunningItems.Slots slots = read();

    assertEquals(List.of(held.id), ids(slots.running()));
    assertEquals(List.of("agent-" + held.id), slots.runningAgentIds());
    assertEquals(java.util.Set.of(queued.id, placed.id), java.util.Set.copyOf(ids(slots.waiting())));
    assertEquals(List.of(held.id), ids(running.of(projectId, null)), "of is the running items");
  }

  @Test
  void anAgentWithNoSlotSaidIsReadFromItsRunStateAndWorkspace() {
    WorkEntity placed = ticket("RUNNING in a workspace");
    WorkEntity queued = ticket("QUEUED, no workspace");
    dispatch.willFind(active(placed), queued(queued));

    RunningItems.Slots slots = read();

    assertEquals(List.of(placed.id), ids(slots.running()));
    assertEquals(List.of(queued.id), ids(slots.waiting()));
  }

  @Test
  void theExceptedItemAndAnInactiveAgentCountNowhere() {
    WorkEntity self = ticket("The pressed item");
    WorkEntity obsolete = ticket("Its agent is OBSOLETE");
    dispatch.willFind(
        queued(self),
        RecordingWorkspaceAgentDispatch.inState(
            "agent-" + obsolete.id, 7L, "wrapper", obsolete.id, "ticket/x", "OBSOLETE", null));

    RunningItems.Slots slots =
        QuarkusTransaction.requiringNew().call(() -> running.read(projectId, self.id));

    assertTrue(slots.running().isEmpty());
    assertTrue(slots.waiting().isEmpty());
  }

  // -------------------------------------------------------------------------------------------

  private RunningItems.Slots read() {
    return QuarkusTransaction.requiringNew().call(() -> running.read(projectId, null));
  }

  private static List<String> ids(List<WorkEntity> rows) {
    return rows.stream().map(row -> row.id).toList();
  }

  private static Agent slot(Agent agent, String slot) {
    return RecordingWorkspaceAgentDispatch.withSlot(agent, slot);
  }

  private static Agent active(WorkEntity item) {
    return RecordingWorkspaceAgentDispatch.active(
        "agent-" + item.id, 7L, "wrapper", item.id, "ticket/" + item.slug);
  }

  private static Agent queued(WorkEntity item) {
    return RecordingWorkspaceAgentDispatch.queued(
        "agent-" + item.id, "wrapper", item.id, "ticket/" + item.slug);
  }

  /** A ticket of the project with an assignee, so an ACTIVE agent makes it a candidate. */
  private WorkEntity ticket(String title) {
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
              row.createdAt = Instant.now().plusMillis(number);
              row.updatedAt = Instant.now();
              row.persist();
              return row;
            });
  }
}
