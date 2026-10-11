package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.dto.WorkspaceReferenceDto;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Which agents work on a ticket or an epic, and in which workspace — {@code GET
 * /work/{qualifiedId}/workspaces}: one lookup against the workspaces port, by work id (qits-1152).
 *
 * <h2>Why it sits in {@code projects.api} and is called from {@code entities.api}</h2>
 *
 * <p>The entities module depends on {@code domain} nowhere and must keep not depending on it, so it
 * cannot reach {@link WorkspaceAgentDispatch} and cannot answer this question for itself. The
 * <em>service</em> layer may cross — {@link WorkDispatchController} is the whole dispatch door living
 * here for exactly this reason — so the crossing happens once, in this class, declared by the
 * package it is in.
 *
 * <h2>Absent, and away, are both "no workspaces"</h2>
 *
 * <p>With no implementation of the port present — an assembly that does not run qits-workspaces —
 * there is nothing to ask and every row gets an empty list. The port's own contract covers the other
 * case: a lookup that fails warns and answers empty rather than throwing, so a sibling service
 * restarting costs the links on a page and never the page. Both degrade to what these screens did
 * before the field existed: every button live, no link drawn.
 */
@ApplicationScoped
public class DispatchedWorkspaces {

  /** Optional, like every port here — absent means there are no workspaces to find. */
  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  /**
   * The workspaces naming one work entity, live and resolved alike — what the deleted per-archetype
   * read carried as its {@code workspaces} field, for {@code GET /work/{qualifiedId}/workspaces}
   * (qits-970). Only a
   * ticket and an epic are dispatched onto a branch, so every other archetype has none.
   */
  public List<WorkspaceReferenceDto> referencing(WorkEntity entity) {
    boolean dispatched =
        entity.archetype == Archetype.TICKET || entity.archetype == Archetype.EPIC;
    if (!dispatched || dispatch.isUnsatisfied()) {
      return List.of();
    }
    return dispatch.get().agentsFor(List.of(entity.id)).stream()
        // Matched back to this row: an agent of another row must never become a link on this one.
        .filter(agent -> entity.id.equals(agent.workId()))
        .map(DispatchedWorkspaces::reference)
        .toList();
  }

  /**
   * One agent as the wire's reference (qits-1152): the workspace it is placed in, its wrapper branch
   * and its state. <b>Nothing is dropped for being OBSOLETE or REMOVED</b>: this is a read
   * decorating a page, and a work item keeps a link to where its work happened. The state travels
   * with each row so the browser can draw the difference.
   */
  private static WorkspaceReferenceDto reference(WorkspaceAgentDispatch.Agent agent) {
    return new WorkspaceReferenceDto(
        agent.agentId(),
        agent.workspaceRowId(),
        agent.repositoryId(),
        agent.entityId(),
        agent.wrapperBranch(),
        agent.state());
  }
}
