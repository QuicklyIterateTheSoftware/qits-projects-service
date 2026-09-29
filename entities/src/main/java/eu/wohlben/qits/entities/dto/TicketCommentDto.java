package eu.wohlben.qits.entities.dto;

import java.time.Instant;

/**
 * A remark in the shape the ticket-only routes answered before qits-551 — {@code GET/POST
 * /tickets/{id}/comments} and {@code PUT /ticket-comments/{id}} — with the entity id spelled {@code
 * ticketId}. Those routes stay for the released CLI and SPA that call them; everything new answers
 * {@link CommentDto}.
 */
public record TicketCommentDto(
    String id,
    String ticketId,
    String author,
    String body,
    Instant createdAt,
    Instant updatedAt) {}
