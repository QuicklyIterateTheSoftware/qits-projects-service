package eu.wohlben.qits.projects.api;

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
 * Epics and tickets that are not DONE or DROPPED, are not explicitly blocked, carry an assignee,
 * and have an ACTIVE agent at qits-workspaces. With no workspaces context nothing is known to run.
 *
 * <p><b>Only the explicit flag frees the slot</b> (qits-1569): {@link WorkEntity#blocked}, a
 * person's or an agent's "this waits on someone", never the derived block of {@code
 * EntityBlockState}. An agent waiting on its own release request reads as blocked, but it is real
 * in-flight work and keeps its slot.
 *
 * <p>One reading for the desk's dispatch check ({@link EntityDispatch}) and the desk trigger that
 * says a slot opened ({@code desktriggers/CapacityOpenedSource}, qits-1689).
 */
@ApplicationScoped
public class RunningItems {

  @Inject WorkEntityService lifecycle;

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
            && !row.assignee.isBlank()) {
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
}
