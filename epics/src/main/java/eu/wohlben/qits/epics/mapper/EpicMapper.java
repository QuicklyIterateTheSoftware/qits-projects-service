package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.entity.Epic;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface EpicMapper {

  /** {@code TicketMapper.toDto}'s rule, for the same reason: the row, and the empty answer. */
  @Mapping(target = "workspaces", expression = "java(java.util.List.of())")
  EpicDto toDto(Epic entity);
}
