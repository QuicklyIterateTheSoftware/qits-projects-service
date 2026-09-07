package eu.wohlben.qits.epics.dto;

import java.time.Instant;

public record TicketCommentDto(
    String id,
    String ticketId,
    String author,
    String body,
    Instant createdAt,
    Instant updatedAt) {}
