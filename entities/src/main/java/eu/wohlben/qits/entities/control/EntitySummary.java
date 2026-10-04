package eu.wohlben.qits.entities.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.TicketType;
import java.time.Instant;

/**
 * <b>One entity in a list</b>: a {@link TransitionedEntity} without its {@code description}. The
 * body is long-form Markdown and only a detail view shows it, so a list neither reads it ({@link
 * EntityCatalogService#listByProjectWithoutDescription}) nor answers it. The REST detail ({@code GET
 * /projects/api/entities/{id}}) and the MCP {@code get_entity} answer it. See {@link
 * TransitionedEntity} for each component.
 */
public record EntitySummary(
    String id,
    Archetype archetype,
    String projectId,
    long number,
    String qualifiedId,
    String title,
    String slug,
    String slugScope,
    String status,
    String statusBefore,
    TicketType ticketType,
    String impetus,
    String assignee,
    String createdBy,
    String supersededBy,
    String repositoryId,
    Instant implementedAt,
    Instant implementingAt,
    String dependsOn,
    String parent,
    Integer position,
    Instant createdAt,
    Instant updatedAt,
    String changedBy,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean blocked) {

  public static EntitySummary of(TransitionedEntity e) {
    return new EntitySummary(
        e.id(),
        e.archetype(),
        e.projectId(),
        e.number(),
        e.qualifiedId(),
        e.title(),
        e.slug(),
        e.slugScope(),
        e.status(),
        e.statusBefore(),
        e.ticketType(),
        e.impetus(),
        e.assignee(),
        e.createdBy(),
        e.supersededBy(),
        e.repositoryId(),
        e.implementedAt(),
        e.implementingAt(),
        e.dependsOn(),
        e.parent(),
        e.position(),
        e.createdAt(),
        e.updatedAt(),
        e.changedBy(),
        e.blocked());
  }
}
