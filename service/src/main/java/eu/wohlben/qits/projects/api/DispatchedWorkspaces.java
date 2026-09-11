package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.dto.TicketDto;
import eu.wohlben.qits.epics.dto.WorkspaceReferenceDto;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills the {@code workspaces} field on a ticket or an epic: one batched lookup, then a row-for-row
 * decoration of what the mapper already produced.
 *
 * <h2>Why it sits in {@code projects.api} and is called from {@code epics.api}</h2>
 *
 * <p>The epics module depends on {@code domain} nowhere and must keep not depending on it, so it
 * cannot reach {@link WorkspaceAgentDispatch} and cannot answer this question for itself. The
 * <em>service</em> layer may cross — {@code ProjectTicketsController} already validates a project id
 * against {@code domain}, and {@link TicketDispatchController} is the whole dispatch door living
 * here for exactly this reason — so the crossing happens once, in this class, declared by the
 * package it is in.
 *
 * <h2>One call per listing, never one per row</h2>
 *
 * <p>Every entry point takes the whole collection and makes a single lookup for it. A per-row call
 * would put a network hop inside a loop over a page, and a project with forty tickets would pay
 * forty round trips for a field that decides one button.
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

  /** The tickets, each told which live workspaces name it. One lookup for the whole list. */
  public List<TicketDto> decorateTickets(List<TicketDto> tickets) {
    if (tickets.isEmpty() || dispatch.isUnsatisfied()) {
      return tickets;
    }
    Map<String, List<WorkspaceReferenceDto>> byTicket =
        byRow(
            dispatch
                .get()
                .workspacesReferencing(tickets.stream().map(TicketDto::id).toList(), List.of()),
            WorkspaceAgentDispatch.Reference::ticketId);
    return tickets.stream()
        .map(ticket -> ticket.withWorkspaces(byTicket.getOrDefault(ticket.id(), List.of())))
        .toList();
  }

  /** One ticket — the same call, asked about a list of one. */
  public TicketDto decorate(TicketDto ticket) {
    return decorateTickets(List.of(ticket)).get(0);
  }

  /** The epics, each told which live workspaces name it. */
  public List<EpicDto> decorateEpics(List<EpicDto> epics) {
    if (epics.isEmpty() || dispatch.isUnsatisfied()) {
      return epics;
    }
    Map<String, List<WorkspaceReferenceDto>> byEpic =
        byRow(
            dispatch
                .get()
                .workspacesReferencing(List.of(), epics.stream().map(EpicDto::id).toList()),
            WorkspaceAgentDispatch.Reference::epicId);
    return epics.stream()
        .map(epic -> epic.withWorkspaces(byEpic.getOrDefault(epic.id(), List.of())))
        .toList();
  }

  /** One epic. */
  public EpicDto decorate(EpicDto epic) {
    return decorateEpics(List.of(epic)).get(0);
  }

  /**
   * The references grouped by the row they name. A reference whose id is null under the chosen
   * accessor is dropped — it answered the other half of a question this call did not ask.
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
                  reference.branch()));
    }
    grouped.replaceAll((key, value) -> List.copyOf(value));
    return grouped;
  }
}
