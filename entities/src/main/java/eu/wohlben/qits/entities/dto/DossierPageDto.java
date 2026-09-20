package eu.wohlben.qits.entities.dto;

import java.time.Instant;

/**
 * One dossier page, body included. The list carries bodies too: a dossier is a handful of pages and
 * the tab renders one immediately, so a second round trip per page would buy nothing.
 *
 * <p><b>Exactly one of {@code epicId} and {@code ticketId} is set</b> — a page belongs to an epic or
 * to a ticket (V8). Both are carried rather than one folded {@code ownerId}, so a client never has
 * to be told which kind it asked for in order to read the answer.
 */
public record DossierPageDto(
    String id,
    String epicId,
    String ticketId,
    String slug,
    String title,
    int position,
    String body,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
