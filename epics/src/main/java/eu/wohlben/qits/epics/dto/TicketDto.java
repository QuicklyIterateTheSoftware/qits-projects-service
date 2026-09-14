package eu.wohlben.qits.epics.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param impetus what brought the ticket about, in the reporter's words — see {@code
 *     Ticket.impetus} for the length rule and for why it is never rewritten by a later phase.
 * @param description the refinement's output, absent until the refine phase has written it.
 * @param workspaces the live workspaces working on this ticket — see {@link WorkspaceReferenceDto}.
 *     Derived per read and never stored: empty means nobody is on it, and one or more means "Assign
 *     agent" has already been pressed and here is the way in. Empty on every write's answer, which
 *     is honest — a create has no workspace, and an edit is not the read that asks.
 */
public record TicketDto(
    String id,
    String projectId,
    String title,
    String slug,
    String type,
    String status,
    String assignee,
    String createdBy,
    String impetus,
    String description,
    Instant createdAt,
    Instant updatedAt,
    List<WorkspaceReferenceDto> workspaces) {

  /** The same ticket, told which workspaces are on it. */
  public TicketDto withWorkspaces(List<WorkspaceReferenceDto> found) {
    return new TicketDto(
        id,
        projectId,
        title,
        slug,
        type,
        status,
        assignee,
        createdBy,
        impetus,
        description,
        createdAt,
        updatedAt,
        found == null ? List.of() : found);
  }
}
