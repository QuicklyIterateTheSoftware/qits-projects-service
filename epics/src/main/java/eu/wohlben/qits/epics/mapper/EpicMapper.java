package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.entity.Epic;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface EpicMapper {

  /**
   * {@code TicketMapper.toDto}'s rule, for the same reason: the row, and the empty answer.
   *
   * <p>{@code number} maps by name. {@code qualifiedId} is ignored <b>explicitly</b> rather than by
   * MapStruct's silence, because the null it leaves is a statement: nothing in this module can
   * render {@code <project-slug>-<number>} — the slug is {@code domain}'s, in another database —
   * and {@code projects/api/QualifiedEntityIds} is the one place that fills it in.
   */
  @Mapping(target = "workspaces", expression = "java(java.util.List.of())")
  @Mapping(target = "qualifiedId", ignore = true)
  EpicDto toDto(Epic entity);
}
