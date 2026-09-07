package eu.wohlben.qits.epics.dto;

import java.time.Instant;

public record TicketDto(
    String id,
    String projectId,
    String title,
    String slug,
    String type,
    String status,
    String assignee,
    String createdBy,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
