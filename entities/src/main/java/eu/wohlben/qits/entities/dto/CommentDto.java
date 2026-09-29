package eu.wohlben.qits.entities.dto;

import java.time.Instant;

/**
 * One remark on an entity's thread, of any archetype. {@link TicketCommentDto} is the same row in
 * the shape the ticket-only routes answered before qits-551, kept for the released clients that
 * still call them.
 */
public record CommentDto(
    String id,
    String entityId,
    String author,
    String body,
    Instant createdAt,
    Instant updatedAt) {}
