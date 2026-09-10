package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.DossierPageDto;
import eu.wohlben.qits.epics.entity.DossierPage;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface DossierPageMapper {
  DossierPageDto toDto(DossierPage entity);
}
