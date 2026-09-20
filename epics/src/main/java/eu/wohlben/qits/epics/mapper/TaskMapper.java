package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.TaskDto;
import eu.wohlben.qits.epics.entity.Task;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface TaskMapper {

  /** {@code FeatureMapper.toDto}'s rule, one level down and for the same reason. */
  @Mapping(target = "qualifiedId", ignore = true)
  TaskDto toDto(Task entity);
}
