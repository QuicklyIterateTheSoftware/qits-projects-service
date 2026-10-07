package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.dto.WorkspaceReferenceDto;
import eu.wohlben.qits.entities.entity.Archetype;
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
 * Which workspaces name a ticket or an epic — {@code GET /work/{qualifiedId}/workspaces}: one lookup
 * against the workspaces port.
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
    boolean ticket = entity.archetype == Archetype.TICKET;
    if ((!ticket && entity.archetype != Archetype.EPIC) || dispatch.isUnsatisfied()) {
      return List.of();
    }
    return byRow(
            dispatch
                .get()
                .workspacesReferencing(
                    ticket ? List.of(entity.id) : List.of(),
                    ticket ? List.of() : List.of(entity.id)),
            ticket
                ? WorkspaceAgentDispatch.Reference::ticketId
                : WorkspaceAgentDispatch.Reference::epicId)
        .getOrDefault(entity.id, List.of());
  }

  /**
   * The references grouped by the row they name. A reference whose id is null under the chosen
   * accessor is dropped — it answered the other half of a question this call did not ask.
   *
   * <p><b>Nothing is dropped for being resolved.</b> This is a read decorating a page, and the whole
   * reason the port stopped filtering is that a ticket should keep a link to where its work happened
   * after the workspace is integrated or abandoned. The status travels with each row so the browser
   * can draw the difference; deciding what it means is the reader's, per the port's javadoc, and a
   * reader that needs a <em>live</em> workspace says so itself.
   */
  private static Map<String, List<WorkspaceReferenceDto>> byRow(
      List<WorkspaceAgentDispatch.Reference> references,
      java.util.function.Function<WorkspaceAgentDispatch.Reference, String> rowId) {
    Map<String, List<WorkspaceReferenceDto>> grouped = new HashMap<>();
    for (WorkspaceAgentDispatch.Reference reference : references) {
      String id = rowId.apply(reference);
      if (id == null) {
        continue;
      }
      grouped
          .computeIfAbsent(id, key -> new ArrayList<>())
          .add(
              new WorkspaceReferenceDto(
                  reference.workspaceRowId(),
                  reference.repositoryId(),
                  reference.workspaceId(),
                  reference.branch(),
                  reference.status()));
    }
    grouped.replaceAll((key, value) -> List.copyOf(value));
    return grouped;
  }
}
