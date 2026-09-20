package eu.wohlben.qits.entities.mapper;

import eu.wohlben.qits.entities.dto.DossierPageDto;
import eu.wohlben.qits.entities.entity.DossierPage;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface DossierPageMapper {
  DossierPageDto toDto(DossierPage entity);
}
