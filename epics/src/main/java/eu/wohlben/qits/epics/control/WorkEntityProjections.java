package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.WorkEntity;

/**
 * <b>A {@link WorkEntity} read as the {@link Epic}, {@link Ticket}, {@link Feature} or {@link Task}
 * the surfaces above still speak in.</b> All four services read and write {@code entity} now, and
 * they go on answering the four old classes — as <b>detached projections</b> of the row they just
 * read or wrote, built here and never persisted, merged or attached.
 *
 * <p><b>Why a projection rather than a new return type.</b> {@code EpicMapper}, {@code TicketMapper},
 * {@code FeatureMapper}, {@code TaskMapper}, the four DTOs, seven controllers, the MCP tools,
 * {@code DossierService} and {@code EpicChangeHints} are all written against those four classes, and
 * the contract this task is not allowed to move by one byte is the HTTP one. Keeping the shape and
 * moving the storage is what lets the whole of that stay exactly as it is while the source of truth
 * changes underneath it.
 *
 * <p><b>The parent is passed in, never read from the row.</b> A feature's {@code epicId} and a
 * task's {@code featureId} were columns on the old tables and are an {@code entity_membership} row
 * now, so the caller — which has just read or written that edge — supplies it. A projection that
 * went and fetched its own membership would be a query per row on exactly the listings this model
 * makes an N+1 easy on.
 *
 * <p><b>Every value a caller sees comes from the {@code entity} row</b>, timestamps included. The
 * legacy {@code epic}/{@code ticket} row is a write-behind mirror and is never read back here — see
 * {@code EpicService.mirrorLegacyRow} for why it still exists at all. A projection is therefore only
 * ever taken <em>after</em> an explicit flush, because {@code @CreationTimestamp} and
 * {@code @UpdateTimestamp} are populated at flush and a caller is promised a {@code createdAt} the
 * moment a create returns.
 *
 * <p><b>This class is deleted with the old entities.</b> It exists for exactly as long as {@code
 * Epic} and {@code Ticket} do; when the merged model reaches the DTOs there is nothing left for it
 * to translate.
 */
final class WorkEntityProjections {

  private WorkEntityProjections() {}

  /** The epic a row of {@code archetype = EPIC} says. Detached, and never persisted. */
  static Epic epic(WorkEntity source) {
    Epic epic = new Epic();
    epic.id = source.id;
    epic.causationId = source.causationId;
    epic.projectId = source.projectId;
    epic.title = source.title;
    epic.slug = source.slug;
    epic.status = source.status == null ? null : EpicStatus.valueOf(source.status);
    epic.supersededByEpicId = source.supersededByEntityId;
    epic.description = source.description;
    epic.createdAt = source.createdAt;
    epic.updatedAt = source.updatedAt;
    return epic;
  }

  /** The ticket a row of {@code archetype = TICKET} says. Detached, and never persisted. */
  static Ticket ticket(WorkEntity source) {
    Ticket ticket = new Ticket();
    ticket.id = source.id;
    ticket.causationId = source.causationId;
    ticket.projectId = source.projectId;
    ticket.title = source.title;
    ticket.slug = source.slug;
    ticket.type = source.ticketType;
    ticket.status = source.status == null ? null : TicketStatus.valueOf(source.status);
    ticket.assignee = source.assignee;
    ticket.createdBy = source.createdBy;
    ticket.impetus = source.impetus;
    ticket.description = source.description;
    ticket.createdAt = source.createdAt;
    ticket.updatedAt = source.updatedAt;
    return ticket;
  }

  /**
   * The feature a row of {@code archetype = FEATURE} says. Detached, and never persisted.
   *
   * <p>Two columns are renamed on the way out and both renames are the merge's:
   * {@code depends_on_entity_id} is the feature's {@code dependsOnFeatureId}, and
   * {@code implemented_at} is its {@code implementedOn} — one fact that had two names.
   *
   * @param epicId the parent of {@code row}'s {@code entity_membership} edge, which is what the old
   *     {@code feature.epic_id} column was
   */
  static Feature feature(WorkEntity source, String epicId) {
    Feature feature = new Feature();
    feature.id = source.id;
    feature.causationId = source.causationId;
    feature.epicId = epicId;
    feature.title = source.title;
    feature.slug = source.slug;
    feature.description = source.description;
    feature.dependsOnFeatureId = source.dependsOnEntityId;
    feature.implementedOn = source.implementedAt;
    feature.createdAt = source.createdAt;
    feature.updatedAt = source.updatedAt;
    return feature;
  }

  /**
   * The task a row of {@code archetype = TASK} says. Detached, and never persisted.
   *
   * @param featureId the parent of {@code row}'s {@code entity_membership} edge, which is what the
   *     old {@code task.feature_id} column was
   */
  static Task task(WorkEntity source, String featureId) {
    Task task = new Task();
    task.id = source.id;
    task.causationId = source.causationId;
    task.featureId = featureId;
    task.repositoryId = source.repositoryId;
    task.title = source.title;
    task.slug = source.slug;
    task.description = source.description;
    task.dependsOnTaskId = source.dependsOnEntityId;
    task.implementedAt = source.implementedAt;
    task.createdAt = source.createdAt;
    task.updatedAt = source.updatedAt;
    return task;
  }
}
