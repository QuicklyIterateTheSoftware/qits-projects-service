package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.EntityMembership;
import eu.wohlben.qits.epics.entity.TicketType;
import eu.wohlben.qits.epics.entity.WorkEntity;
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
 * <p><b>It is the merged model and not one of the four projections.</b> {@code
 * WorkEntityProjections} exists to keep {@code Epic}/{@code Ticket}/{@code Feature}/{@code Task}
 * answerable while the storage moved underneath them, and every one of those four drops the columns
 * its kind has no slot for. A transition's whole subject is a row changing which kind it is, so an
 * answer shaped as one kind could not describe the other end of the change. This record carries
 * every property the merged table has, null where the archetype has no slot for it.
 *
 * @param id the entity
 * @param archetype what it is now
 * @param projectId the owning project. Carried unchanged — a transition does not move work between
 *     projects; see {@code EntityTransitionService} for the refusal that enforces it
 * @param title the label
 * @param slug the git-safe path segment. <b>Never re-minted and never cleared</b>: it is
 *     {@code @Column(updatable = false)} and names branches already cut
 * @param slugScope what {@link #slug} is unique within — the parent id for a child, the project id
 *     for a root. <b>This is what a move changes</b>
 * @param description the long-form body
 * @param status the status word as stored, or null for a kind with no lifecycle
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
 */
public record TransitionedEntity(
    String id,
    Archetype archetype,
    String projectId,
    String title,
    String slug,
    String slugScope,
    String description,
    String status,
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
    Instant updatedAt) {

  /**
   * The row and its edge, read back. {@code edge} is null for a root, which is a statement and not
   * an omission — see {@code EntityFact.parentId}.
   */
  static TransitionedEntity of(WorkEntity row, EntityMembership edge) {
    return new TransitionedEntity(
        row.id,
        row.archetype,
        row.projectId,
        row.title,
        row.slug,
        row.slugScope,
        row.description,
        row.status,
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
        row.updatedAt);
  }
}
