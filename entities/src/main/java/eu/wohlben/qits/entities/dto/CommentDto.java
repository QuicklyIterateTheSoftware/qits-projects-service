package eu.wohlben.qits.entities.dto;

import java.time.Instant;

/** One remark on an entity's thread, of any archetype. */
public record CommentDto(
    String id,
    String entityId,
    String author,
    String body,
    Instant createdAt,
    Instant updatedAt) {}
