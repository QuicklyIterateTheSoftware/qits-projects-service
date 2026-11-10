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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>A project's running items</b>: what counts toward {@code front_desk.delivery.max_running}.
 * Epics and tickets that are not DONE or DROPPED, are not explicitly blocked, are not held by an
 * unmet need, carry an assignee, and have an ACTIVE agent at qits-workspaces. With no workspaces
 * context nothing is known to run.
 *
 * <p><b>Two things free the slot, and nothing else does.</b> The explicit flag (qits-1569): {@link
 * WorkEntity#blocked}, a person's or an agent's "this waits on someone", never the derived block of
 * {@code EntityBlockState}. An agent waiting on its own release request reads as blocked, but it is
 * real in-flight work and keeps its slot. And a needs hold (qits-2053): an item at a status its
 * needs gate ({@link UnmetNeeds#gates}, REFINED or READY_FOR_DEV) with a need not met now is not
 * in-flight work — the platform refuses to start it — and counting it kept the desk from
 * dispatching the very items it waits on once the cap was full. It counts again once its last need
 * is met, read now and never latched, and {@code PhaseAdvance.resumeHeld} schedules it, which does
 * not pass the cap check.
 *
 * <p><b>Needs are read through {@link NeedService#unmet}</b>, in a transaction of its own and only
 * for a row at a gating status, before the agent lookup, so a held row is never sent to
 * qits-workspaces. Not {@link UnmetNeeds#holding}: that reads project slugs from the projects
 * datasource, and two callers count inside an epics transaction, where a second non-XA resource
 * fails.
 *
 * <p>One reading for the desk's dispatch check ({@link EntityDispatch}) and the desk trigger that
 * says a slot opened ({@code desktriggers/CapacityOpenedSource}, qits-1689).
 */
@ApplicationScoped
public class RunningItems {

  @Inject WorkEntityService lifecycle;

  @Inject NeedService needs;

  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  /** The project's running items, leaving out {@code exceptId} (null leaves out none). */
  public List<WorkEntity> of(String projectId, String exceptId) {
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
      return List.of();
    }
    Set<String> active =
        dispatch.get().agentsFor(candidates.stream().map(row -> row.id).toList()).stream()
            .filter(WorkspaceAgentDispatch.Agent::active)
            .map(WorkspaceAgentDispatch.Agent::workId)
            .collect(Collectors.toSet());
    return candidates.stream().filter(row -> active.contains(row.id)).toList();
  }

  /** Whether a need not met now holds {@code row} (qits-2053): asked only at a gating status. */
  private boolean held(WorkEntity row) {
    return UnmetNeeds.gates(row) && !needs.unmet(row.id).isEmpty();
  }
}
