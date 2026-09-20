package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.FeatureDto;
import eu.wohlben.qits.epics.entity.Feature;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface FeatureMapper {

  /**
   * {@code number} and {@code projectId} map by name off the projection. {@code qualifiedId} is
   * ignored <b>explicitly</b>: the project slug the rendering needs lives in {@code domain}, which
   * this module depends on nowhere, so {@code projects/api/QualifiedEntityIds} fills it and nothing
   * here does. See {@code EpicMapper}.
   */
  @Mapping(target = "qualifiedId", ignore = true)
  FeatureDto toDto(Feature entity);
}
