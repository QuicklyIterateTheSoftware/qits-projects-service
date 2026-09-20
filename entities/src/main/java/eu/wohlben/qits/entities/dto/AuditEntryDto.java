package eu.wohlben.qits.entities.dto;

import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import java.time.Instant;

public record AuditEntryDto(
    String id,
    AuditEntityType entityType,
    String entityId,
    String epicId,
    AuditOperation operation,
    String changedBy,
    Instant changedAt,
    String snapshot) {}
