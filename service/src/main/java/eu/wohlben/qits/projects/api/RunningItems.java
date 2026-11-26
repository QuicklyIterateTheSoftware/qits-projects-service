package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.NeedService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>A project's running items</b>: what takes one of the delivery desk's slots ({@link DeskSlots},
 * qits-2573). Epics and tickets that are not DONE or DROPPED, are not explicitly blocked, are not
 * held by an unmet need, carry an assignee, and have an ACTIVE agent at qits-workspaces <b>whose
 * workspace holds its lease</b> ({@code slot} HELD). With no workspaces context nothing is known to
 * run.
 *
 * <p><b>Running and waiting</b> (qits-2573, {@link #read}). An agent whose {@code slot} is QUEUED —
 * no workspace yet, or a workspace whose lease still waits at qits-system — is not running: its item
 * is <em>waiting</em> for a workspace. qits-workspaces places a QUEUED agent into a new workspace
 * and sets it RUNNING at once while that workspace's lease waits, so {@code runState} alone counted
 * five running while one held a workspace. An agent with {@code slot} NONE is neither. A
 * qits-workspaces that predates {@code slot} is read by {@link
 * WorkspaceAgentDispatch.Agent#slotOrFallback}: {@code runState} QUEUED or no workspace is waiting,
 * anything else running.
 *
 * <p><b>Two things free the slot, and nothing else does.</b> The explicit flag (qits-1569): {@link
 * WorkEntity#blocked}, a person's or an agent's "this waits on someone", never the derived block of
 * {@code EntityBlockState}. An agent waiting on its own release request reads as blocked, but it is
 * real in-flight work and keeps its slot. And a needs hold (qits-2053): an item at a status its
 * needs gate ({@link UnmetNeeds#gates}, REFINED or READY_FOR_DEV) with a need not met now is not
 * in-flight work — the platform refuses to start it — and counting it kept the desk from
 * dispatching the very items it waits on once the cap was full. It counts again once its last need
 * is met, read now and never latched, and {@code PhaseAdvance.resumeHeld} schedules it, which does
 * not pass the desk's slot check.
 *
 * <p><b>Needs are read through {@link NeedService#unmet}</b>, in a transaction of its own and only
 * for a row at a gating status, before the agent lookup, so a held row is never sent to
 * qits-workspaces. Not {@link UnmetNeeds#holding}: that reads project slugs from the projects
 * datasource, and two callers count inside an epics transaction, where a second non-XA resource
 * fails.
 *
 * <p>One reading for the desk's dispatch check ({@link EntityDispatch}), the desk trigger that
 * says a slot opened ({@code desktriggers/CapacityOpenedSource}, qits-1689) and the Slots line,
 * each through {@link DeskSlots}.
 */
@ApplicationScoped
public class RunningItems {

  @Inject WorkEntityService lifecycle;

  @Inject NeedService needs;

  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  /**
   * The project's slots as its agents hold them: the {@code running} items (their agent's workspace
   * holds its lease) with those agents' ids, and the items {@code waiting} for a workspace.
   */
  public record Slots(List<WorkEntity> running, List<String> runningAgentIds, List<WorkEntity> waiting) {
    public Slots {
      running = List.copyOf(running);
      runningAgentIds = List.copyOf(runningAgentIds);
      waiting = List.copyOf(waiting);
    }

    static final Slots NONE = new Slots(List.of(), List.of(), List.of());
  }

  /** The project's running items, leaving out {@code exceptId} (null leaves out none). */
  public List<WorkEntity> of(String projectId, String exceptId) {
    return read(projectId, exceptId).running();
  }

  /**
   * The project's running and waiting items, leaving out {@code exceptId} (null leaves out none).
   * Inside the caller's epics transaction.
   */
  public Slots read(String projectId, String exceptId) {
    List<WorkEntity> candidates = new ArrayList<>();
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.TICKET)) {
      for (WorkEntity row : lifecycle.listByProject(archetype, projectId)) {
        if (!row.id.equals(exceptId)
            && !EntityStatus.DONE.name().equals(row.status)
            && !EntityStatus.DROPPED.name().equals(row.status)
            && !row.blocked
            && row.assignee != null
            && !row.assignee.isBlank()
            && !held(row)) {
          candidates.add(row);
        }
      }
    }
    if (candidates.isEmpty() || dispatch.isUnsatisfied()) {
      return Slots.NONE;
    }
    Map<String, WorkspaceAgentDispatch.Agent> active = new HashMap<>();
    for (WorkspaceAgentDispatch.Agent agent :
        dispatch.get().agentsFor(candidates.stream().map(row -> row.id).toList())) {
      if (agent.active() && agent.workId() != null) {
        active.putIfAbsent(agent.workId(), agent);
      }
    }
    List<WorkEntity> running = new ArrayList<>();
    List<String> agentIds = new ArrayList<>();
    List<WorkEntity> waiting = new ArrayList<>();
    for (WorkEntity row : candidates) {
      WorkspaceAgentDispatch.Agent agent = active.get(row.id);
      if (agent == null) {
        continue;
      }
      String slot = agent.slotOrFallback();
      if (WorkspaceAgentDispatch.Agent.HELD.equals(slot)) {
        running.add(row);
        agentIds.add(agent.agentId());
      } else if (WorkspaceAgentDispatch.Agent.QUEUED.equals(slot)) {
        waiting.add(row);
      }
    }
    return new Slots(running, agentIds, waiting);
  }

  /** Whether a need not met now holds {@code row} (qits-2053): asked only at a gating status. */
  private boolean held(WorkEntity row) {
    return UnmetNeeds.gates(row) && !needs.unmet(row.id).isEmpty();
  }
}
