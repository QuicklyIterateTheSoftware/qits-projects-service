package eu.wohlben.qits.entities.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import eu.wohlben.qits.entities.entity.WorkTag;
import java.time.Instant;
import java.util.List;

/**
 * <b>One entity in a list</b>: a {@link TransitionedEntity} without its {@code description}. The
 * body is long-form Markdown and only a detail view shows it, so a list neither reads it ({@link
 * EntityCatalogService#listByProjectWithoutDescription}) nor answers it. The REST detail ({@code GET
 * /projects/api/work/{qualifiedId}}) and the MCP {@code get_entity} answer it. See {@link
 * TransitionedEntity} for each component — {@code blocked} is the effective block there and here,
 * with its {@code blockSource}, {@code blockReason} and {@code blockedBy} (qits-895), and {@code
 * waitingOn} beside it (qits-1153). {@code outcome} and {@code supersededBy} (qits-248) are on every
 * archetype and always on the wire, null included, and so is {@code priority} (qits-753); {@code
 * tags} (qits-634) is always on the wire too, {@code []} when there are none. {@code needs} and
 * {@code unmetNeeds} (qits-1903) are the project listing's: what each entry needs, item to item,
 * read for the whole project in one batch.
 *
 * <p><b>{@code excerpt} is what a list says about the body instead</b> (qits-1821): a short
 * plain-text lead of the description — its first prose paragraph, inline Markdown stripped, cut at
 * a word past 240 characters ({@link DescriptionExcerpt}) — for the desk's cards. It is derived from
 * the description's first 2000 characters, which is all the listing reads of that column, so the
 * entry is still one <em>without its description</em>. Filled for a ticket and an epic only; null
 * on a feature and a task, and on a ticket or an epic with no prose to lead with.
 * Always on the wire, null included, like {@code outcome}. Only the listing answers it: {@link
 * TransitionedEntity} carries it unserialised, so the detail's shape does not change.
 */
public record EntitySummary(
    String id,
    Archetype archetype,
    String projectId,
    long number,
    String qualifiedId,
    String title,
    String excerpt,
    String slug,
    String slugScope,
    String status,
    String statusBefore,
    TicketType ticketType,
    String impetus,
    String assignee,
    String createdBy,
    String supersededBy,
    WorkOutcome outcome,
    String repositoryId,
    Instant implementedAt,
    Instant implementingAt,
    String parent,
    Integer position,
    Integer priority,
    Instant createdAt,
    Instant updatedAt,
    String changedBy,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean blocked,
    @JsonInclude(JsonInclude.Include.NON_NULL) String blockSource,
    @JsonInclude(JsonInclude.Include.NON_NULL) String blockReason,
    @JsonInclude(JsonInclude.Include.NON_NULL) String blockedBy,
    @JsonInclude(JsonInclude.Include.NON_NULL) String waitingOn,
    List<WorkTag> tags,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<NeedRef> needs,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer unmetNeeds) {

  public static EntitySummary of(TransitionedEntity e) {
    return new EntitySummary(
        e.id(),
        e.archetype(),
        e.projectId(),
        e.number(),
        e.qualifiedId(),
        e.title(),
        e.excerpt(),
        e.slug(),
        e.slugScope(),
        e.status(),
        e.statusBefore(),
        e.ticketType(),
        e.impetus(),
        e.assignee(),
        e.createdBy(),
        e.supersededBy(),
        e.outcome(),
        e.repositoryId(),
        e.implementedAt(),
        e.implementingAt(),
        e.parent(),
        e.position(),
        e.priority(),
        e.createdAt(),
        e.updatedAt(),
        e.changedBy(),
        e.blocked(),
        e.blockSource(),
        e.blockReason(),
        e.blockedBy(),
        e.waitingOn(),
        e.tags(),
        e.needs(),
        e.unmetNeeds());
  }
}
