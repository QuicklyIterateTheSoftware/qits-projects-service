package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.WorkEntity;

/**
 * <b>A {@link WorkEntity} read as the {@link Epic} or the {@link Ticket} the surfaces above still
 * speak in.</b> {@code EpicService} and {@code TicketService} read and write {@code entity} now, and
 * they go on answering {@code Epic} and {@code Ticket} — as <b>detached projections</b> of the row
 * they just read or wrote, built here and never persisted, merged or attached.
 *
 * <p><b>Why a projection rather than a new return type.</b> {@code EpicMapper}, {@code TicketMapper},
 * {@code EpicDto}, {@code TicketDto}, five controllers, the MCP tools, {@code DossierService} and
 * {@code EpicChangeHints} are all written against those two classes, and the contract this task is
 * not allowed to move by one byte is the HTTP one. Keeping the shape and moving the storage is what
 * lets the whole of that stay exactly as it is while the source of truth changes underneath it.
 *
 * <p><b>Every value a caller sees comes from the {@code entity} row</b>, timestamps included. The
 * legacy {@code epic}/{@code ticket} row is a write-behind mirror and is never read back here — see
 * {@code EpicService.mirrorLegacyRow} for why it still exists at all. A projection is therefore only
 * ever taken <em>after</em> an explicit flush, because {@code @CreationTimestamp} and
 * {@code @UpdateTimestamp} are populated at flush and a caller is promised a {@code createdAt} the
 * moment a create returns.
 *
 * <p><b>This class is deleted with the old entities.</b> It exists for exactly as long as {@code
 * Epic} and {@code Ticket} do; when the merged model reaches the DTOs there is nothing left for it
 * to translate.
 */
final class WorkEntityProjections {

  private WorkEntityProjections() {}

  /** The epic a row of {@code archetype = EPIC} says. Detached, and never persisted. */
  static Epic epic(WorkEntity source) {
    Epic epic = new Epic();
    epic.id = source.id;
    epic.causationId = source.causationId;
    epic.projectId = source.projectId;
    epic.title = source.title;
    epic.slug = source.slug;
    epic.status = source.status == null ? null : EpicStatus.valueOf(source.status);
    epic.supersededByEpicId = source.supersededByEntityId;
    epic.description = source.description;
    epic.createdAt = source.createdAt;
    epic.updatedAt = source.updatedAt;
    return epic;
  }

  /** The ticket a row of {@code archetype = TICKET} says. Detached, and never persisted. */
  static Ticket ticket(WorkEntity source) {
    Ticket ticket = new Ticket();
    ticket.id = source.id;
    ticket.causationId = source.causationId;
    ticket.projectId = source.projectId;
    ticket.title = source.title;
    ticket.slug = source.slug;
    ticket.type = source.ticketType;
    ticket.status = source.status == null ? null : TicketStatus.valueOf(source.status);
    ticket.assignee = source.assignee;
    ticket.createdBy = source.createdBy;
    ticket.impetus = source.impetus;
    ticket.description = source.description;
    ticket.createdAt = source.createdAt;
    ticket.updatedAt = source.updatedAt;
    return ticket;
  }
}
