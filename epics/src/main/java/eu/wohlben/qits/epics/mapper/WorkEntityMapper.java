package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.dto.FeatureDto;
import eu.wohlben.qits.epics.dto.TaskDto;
import eu.wohlben.qits.epics.dto.TicketDto;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.WorkEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * <b>One mapper where there were four.</b> {@code EpicMapper}, {@code TicketMapper}, {@code
 * FeatureMapper} and {@code TaskMapper} each translated one of the four old shapes; those shapes are
 * gone and the four services answer the merged {@link WorkEntity} row, so the four translations are
 * four methods here instead of four files.
 *
 * <p><b>The four DTOs do not move by one byte.</b> Every component of {@link EpicDto}, {@link
 * TicketDto}, {@link FeatureDto} and {@link TaskDto} is populated from exactly the value it was
 * populated from before — which is the whole point of the method list being four rather than one:
 * the HTTP contract is four shapes and stays four shapes. What moved is only where each value is
 * read from.
 *
 * <h2>The renames this mapper carries, and why each one exists</h2>
 *
 * <ul>
 *   <li>{@code supersededByEntityId} → {@code supersededByEpicId}: the self-reference is within one
 *       merged table now, so the column lost the word "epic"; the DTO keeps it.
 *   <li>{@code ticketType} → {@code type}: {@code type} on a row holding four archetypes reads as
 *       the archetype, which is the one thing it is not, so the column was renamed and the DTO was
 *       not.
 *   <li>{@code implementedAt} → {@code implementedOn} on a feature: one fact that had two names,
 *       merged into one column. The feature DTO keeps the old spelling.
 *   <li>{@code dependsOnEntityId} → {@code dependsOnFeatureId}/{@code dependsOnTaskId}: one sibling
 *       ordering edge for what were two columns. <b>Never nesting</b> — see {@code
 *       WorkEntity.dependsOnEntityId}.
 *   <li>{@code epicId}/{@code featureId} are <b>parameters</b>, because the parent is an {@code
 *       entity_membership} row rather than a column. See {@code control/Nested}, which is what the
 *       two descendant services hand their callers and what a caller reads the argument off.
 * </ul>
 *
 * <h2>{@code qualifiedId} is ignored EXPLICITLY, and that is a statement</h2>
 *
 * <p>MapStruct would leave it null in silence. The null is carried deliberately instead, the way the
 * four deleted mappers carried it: nothing in this module can render {@code
 * <project-slug>-<number>}, because the project slug lives in {@code domain}'s {@code project}
 * table, in a different physical database, and {@code epics} depends on {@code domain} nowhere. A
 * reader who finds the null has to be able to tell it from an omission — the one place it is filled
 * is {@code projects/api/QualifiedEntityIds}, one module up, at the DTO boundary.
 *
 * <h2>{@code workspaces} is the EMPTY answer, never null</h2>
 *
 * <p>Nothing here can ask which workspaces are on an epic or a ticket: they live in another context,
 * behind a port the service layer holds. So the mapped shape is the empty list and a door that wants
 * the real one decorates it with {@code withWorkspaces}. Empty rather than null, so no reader has to
 * guess whether "none" means none or means unasked.
 */
@Mapper(componentModel = "jakarta")
public interface WorkEntityMapper {

  /**
   * An {@code EPIC} row as the shape the epic routes have always answered.
   *
   * <p>{@code number} and {@code projectId} map by name off the merged row.
   */
  @Mapping(target = "status", source = "status", qualifiedByName = "epicStatus")
  @Mapping(target = "supersededByEpicId", source = "supersededByEntityId")
  @Mapping(target = "workspaces", expression = "java(java.util.List.of())")
  @Mapping(target = "qualifiedId", ignore = true)
  EpicDto toEpicDto(WorkEntity entity);

  /** A {@code TICKET} row as the shape the ticket routes have always answered. */
  @Mapping(target = "type", source = "ticketType")
  @Mapping(target = "status", source = "status", qualifiedByName = "ticketStatus")
  @Mapping(target = "workspaces", expression = "java(java.util.List.of())")
  @Mapping(target = "qualifiedId", ignore = true)
  TicketDto toTicketDto(WorkEntity entity);

  /**
   * A {@code FEATURE} row plus the epic its membership edge names.
   *
   * @param epicId {@code Nested.parentId} — the {@code parent_id} of the row's {@code
   *     entity_membership} edge, which is what the old {@code feature.epic_id} column was
   */
  @Mapping(target = "epicId", source = "epicId")
  @Mapping(target = "dependsOnFeatureId", source = "entity.dependsOnEntityId")
  @Mapping(target = "implementedOn", source = "entity.implementedAt")
  @Mapping(target = "qualifiedId", ignore = true)
  FeatureDto toFeatureDto(WorkEntity entity, String epicId);

  /**
   * A {@code TASK} row plus the feature its membership edge names.
   *
   * @param featureId {@code Nested.parentId} — what the old {@code task.feature_id} column was
   */
  @Mapping(target = "featureId", source = "featureId")
  @Mapping(target = "dependsOnTaskId", source = "entity.dependsOnEntityId")
  @Mapping(target = "qualifiedId", ignore = true)
  TaskDto toTaskDto(WorkEntity entity, String featureId);

  /**
   * <b>The stored word, read as an {@link EpicStatus} and written back out as its {@code name()}.</b>
   *
   * <p>The DTO component is a {@code String} and the column is a {@code String}, so a straight
   * pass-through would compile and would be <em>wrong in one direction that matters</em>: {@code
   * ck_entity_status} spells the UNION of both lifecycles' words, so an {@code EPIC} row holding
   * {@code REPORTED} satisfies the constraint and would be handed to a client as an epic status.
   * Going through the enum is exactly what the deleted {@code WorkEntityProjections.epic} did
   * ({@code EpicStatus.valueOf(source.status)}), and it keeps that refusal where it was rather than
   * quietly widening the epic vocabulary to nine words at the DTO boundary. The database spells the
   * vocabulary; {@code control/Archetypes} spells the rule; this keeps the reader honest about which
   * half it is reading.
   *
   * <p>Null in, null out: a status-less row is an ordinary row here (a feature and a task hold
   * none), and {@code valueOf(null)} would be a {@code NullPointerException} rather than an answer.
   */
  @Named("epicStatus")
  static String epicStatus(String stored) {
    return stored == null ? null : EpicStatus.valueOf(stored).name();
  }

  /** {@link #epicStatus}'s rule for the other lifecycle, and for its reason. */
  @Named("ticketStatus")
  static String ticketStatus(String stored) {
    return stored == null ? null : TicketStatus.valueOf(stored).name();
  }
}
