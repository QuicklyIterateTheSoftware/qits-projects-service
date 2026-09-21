package eu.wohlben.qits.entities.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param number the per-project numeric id — the bare {@code long} as stored; see {@link
 *     EpicDto#number} for why both it and the rendering travel.
 * @param qualifiedId {@code <project-slug>-<number>} — {@code qits-1337}. Null until the project
 *     slug is resolved, which happens in {@code projects/api/QualifiedEntityIds} and nowhere else;
 *     see {@link EpicDto#qualifiedId}.
 * @param impetus what brought the ticket about, in the reporter's words — see {@code
 *     Ticket.impetus} for the length rule and for why it is never rewritten by a later phase.
 * @param description the refinement's output, absent until the refine phase has written it.
 * @param workspaces the workspaces cut for this ticket, <b>live or resolved</b>, each carrying its
 *     own {@code status} — see {@link WorkspaceReferenceDto}. Derived per read and never stored:
 *     empty means no workspace was ever cut, and a resolved one stays on the list so the ticket
 *     keeps a link to where its work happened after somebody integrated or abandoned it. The count
 *     therefore says nothing about whether anybody is working — a reader asking that question
 *     (drawing "Assign agent", say) looks for a workspace whose status is {@code ACTIVE}. Empty on
 *     every write's answer, which is honest — a create has no workspace, and an edit is not the
 *     read that asks.
 */
public record TicketDto(
    String id,
    String projectId,
    long number,
    String qualifiedId,
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
        number,
        qualifiedId,
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

  /** The same ticket, told what it is called in a commit subject. */
  public TicketDto withQualifiedId(String rendered) {
    return new TicketDto(
        id,
        projectId,
        number,
        rendered,
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
        workspaces);
  }
}
