package eu.wohlben.qits.entities.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.time.Instant;

/**
 * <b>One entity as it stands after a transition</b> — the post-state that was actually written,
 * read back off the row and its edge.
 *
 * <p>It is the answer shape and the announcement shape at once, which is deliberate: what the caller
 * is handed and what the platform is told are the same facts, so a consumer of either is reading one
 * vocabulary. {@code EntityTransitionService} answers a map of these keyed by id — the same shape
 * the request is keyed in, so a caller can put the two side by side and see exactly what its
 * statement became.
 *
 * <p><b>It is the merged model and not one kind's shape.</b> The four old classes each dropped the
 * columns their kind had no slot for; a transition's whole subject is a row changing which kind it
 * is, so an answer shaped as one kind could not describe the other end of the change. This record
 * carries every property the merged table has, null where the archetype has no slot for it — {@link
 * #number} included. That argument is why this record was written against {@link WorkEntity} from
 * the start rather than against a projection, and why nothing about it moved when the four shapes
 * were deleted in V13.
 *
 * <p><b>{@link #qualifiedId} is the one component this module cannot fill.</b> The qualified form
 * is {@code <project-slug>-<number>} and the project slug lives in {@code domain}'s {@code project}
 * table, in a different physical database, which {@code entities} depends on nowhere. So {@link #of}
 * leaves it null and the {@code service} module puts it on through {@link #withQualifiedId} — the
 * device {@code EpicDto.withWorkspaces} already is, for the same boundary and the same reason. See
 * {@code projects/api/QualifiedEntityIds}.
 *
 * @param id the entity
 * @param archetype what it is now
 * @param projectId the owning project. Carried unchanged — a transition does not move work between
 *     projects; see {@code EntityTransitionService} for the refusal that enforces it
 * @param number the per-project numeric id, unique within {@link #projectId} and never reused. A
 *     transition allocates nothing and moves none: the number names a NODE, and a node that changes
 *     which kind it is is still the same node
 * @param qualifiedId {@code <project-slug>-<number>}, the form that is written by hand into a
 *     commit subject — or <b>null</b> when nobody has resolved the slug yet, which is what {@link
 *     #of} always answers
 * @param title the label
 * @param slug the git-safe path segment. <b>Never re-minted and never cleared</b>: it is
 *     {@code @Column(updatable = false)} and names branches already cut
 * @param slugScope what {@link #slug} is unique within — the parent id for a child, the project id
 *     for a root. <b>This is what a move changes</b>
 * @param description the long-form body
 * @param status the status word as stored, or null for a kind with no lifecycle
 * @param statusBefore the status word the transition moved the row <em>from</em>: the pre-state's
 *     {@link #status}, which is {@link #status} itself for a row the transition reshaped without
 *     moving its status, and <b>null</b> for a row the transition created (a supersede's successor
 *     draft) or for a kind with no lifecycle. <b>Null on every read</b> — {@code
 *     EntityCatalogService} answers a row as it stands, and a read moved nothing
 * @param ticketType a ticket's kind, or null
 * @param impetus why a ticket came about, or null
 * @param assignee who is looking at it, or null
 * @param createdBy who filed it. Carried when the archetype permits it, cleared when it does not
 * @param supersededBy the successor draft, or null
 * @param repositoryId the task's repository, or null
 * @param implementedAt the implemented marker, or null
 * @param dependsOn the sibling ordering edge, or null. Never nesting
 * @param parent what it is part of now, or null for a root
 * @param position where among its siblings it sits, or null for a root. Dense and zero-based
 * @param createdAt unchanged by a transition
 * @param updatedAt when the transition committed
 * @param changedBy who made the transition — the audit principal the write was recorded under.
 *     <b>Null on every read</b>, for {@link #statusBefore}'s reason
 * @param blocked whether the phase the entity's status starts is stuck — {@code entity.blocked}, on
 *     every archetype with a lifecycle (a ticket since qits-548, an epic and a campaign since
 *     qits-592, when the block door stopped being a ticket's alone). <b>Null, and left off the wire,
 *     for a feature and a task</b>: they have no phase of their own, so a {@code false} there would
 *     claim a flag nothing can raise
 */
public record TransitionedEntity(
    String id,
    Archetype archetype,
    String projectId,
    long number,
    String qualifiedId,
    String title,
    String slug,
    String slugScope,
    String description,
    String status,
    String statusBefore,
    TicketType ticketType,
    String impetus,
    String assignee,
    String createdBy,
    String supersededBy,
    String repositoryId,
    Instant implementedAt,
    String dependsOn,
    String parent,
    Integer position,
    Instant createdAt,
    Instant updatedAt,
    String changedBy,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean blocked) {

  /**
   * The same entity, told what it is called in a commit subject. {@code EpicDto.withWorkspaces}'
   * device, for the same boundary: the value is resolved in {@code service} and put on here.
   */
  public TransitionedEntity withQualifiedId(String rendered) {
    return new TransitionedEntity(
        id,
        archetype,
        projectId,
        number,
        rendered,
        title,
        slug,
        slugScope,
        description,
        status,
        statusBefore,
        ticketType,
        impetus,
        assignee,
        createdBy,
        supersededBy,
        repositoryId,
        implementedAt,
        dependsOn,
        parent,
        position,
        createdAt,
        updatedAt,
        changedBy,
        blocked);
  }

  /**
   * The row and its edge, read back — a <b>read</b>, so {@link #statusBefore} and {@link
   * #changedBy} are null: nothing moved. {@code edge} is the row's <b>structural</b> edge — the
   * tree parent and the position under it, never a campaign membership — and null for a root, which
   * is a statement and not an omission — see {@code EntityFact.parentId}.
   *
   * <p><b>{@code qualifiedId} is left null here and that is deliberate</b>, not an oversight: see
   * the class javadoc. {@link #withQualifiedId} is how it is filled.
   */
  static TransitionedEntity of(WorkEntity row, EntityMembership edge) {
    return of(row, edge, null, null);
  }

  /**
   * The row a field edit wrote ({@link WorkEntityService#update}), hung where {@code before} says
   * it hung — a read's shape, {@link #statusBefore} and {@link #changedBy} null.
   *
   * <p>An edit moves no edge, so the membership read before it is still the membership after it,
   * and this is what spares the edit a second read. That read would be <b>wrong</b>, not merely
   * redundant: outside a transaction a request keeps one persistence context, the read before the
   * edit filled it, and the edit's own transaction is a different one — so a re-read in the same
   * request answers the row as it stood before the edit (measured, through {@code PATCH
   * /entities/{id}}).
   */
  public static TransitionedEntity edited(WorkEntity row, TransitionedEntity before) {
    return new TransitionedEntity(
        row.id,
        row.archetype,
        row.projectId,
        row.number,
        null,
        row.title,
        row.slug,
        row.slugScope,
        row.description,
        row.status,
        null,
        row.ticketType,
        row.impetus,
        row.assignee,
        row.createdBy,
        row.supersededByEntityId,
        row.repositoryId,
        row.implementedAt,
        row.dependsOnEntityId,
        before.parent(),
        before.position(),
        row.createdAt,
        row.updatedAt,
        null,
        blockedOf(row));
  }

  /**
   * The row a lifecycle move wrote ({@link WorkEntityService#transition}), hung where {@code before}
   * says it hung, told the status it moved from and who moved it — the answer of {@code POST
   * /entities/{id}/status} (qits-548). A move changes no edge, so {@link #edited}'s reasoning about
   * the membership and about a re-read holds here unchanged.
   */
  public static TransitionedEntity moved(
      WorkEntity row, TransitionedEntity before, String statusBefore, String changedBy) {
    TransitionedEntity read = edited(row, before);
    return new TransitionedEntity(
        read.id(),
        read.archetype(),
        read.projectId(),
        read.number(),
        null,
        read.title(),
        read.slug(),
        read.slugScope(),
        read.description(),
        read.status(),
        statusBefore,
        read.ticketType(),
        read.impetus(),
        read.assignee(),
        read.createdBy(),
        read.supersededBy(),
        read.repositoryId(),
        read.implementedAt(),
        read.dependsOn(),
        read.parent(),
        read.position(),
        read.createdAt(),
        read.updatedAt(),
        changedBy,
        read.blocked());
  }

  /**
   * The row and its edge as a transition left them, told the status it moved from and who moved it
   * — the announcement shape. {@code statusBefore} is captured by the caller <em>before</em> it
   * wrote the row, because afterwards the managed entity only knows its new status.
   */
  static TransitionedEntity of(
      WorkEntity row, EntityMembership edge, String statusBefore, String changedBy) {
    return new TransitionedEntity(
        row.id,
        row.archetype,
        row.projectId,
        row.number,
        null,
        row.title,
        row.slug,
        row.slugScope,
        row.description,
        row.status,
        statusBefore,
        row.ticketType,
        row.impetus,
        row.assignee,
        row.createdBy,
        row.supersededByEntityId,
        row.repositoryId,
        row.implementedAt,
        row.dependsOnEntityId,
        edge == null ? null : edge.parentId,
        edge == null ? null : edge.position,
        row.createdAt,
        row.updatedAt,
        changedBy,
        blockedOf(row));
  }

  /** A lifecycle kind's flag, and null for a kind no door raises it on — see {@link #blocked}. */
  private static Boolean blockedOf(WorkEntity row) {
    return Archetypes.legalStatuses(row.archetype).isEmpty() ? null : row.blocked;
  }
}
