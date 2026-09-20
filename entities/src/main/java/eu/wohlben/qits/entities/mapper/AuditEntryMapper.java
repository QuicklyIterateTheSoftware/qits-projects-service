package eu.wohlben.qits.entities.mapper;

import eu.wohlben.qits.entities.dto.AuditEntryDto;
import eu.wohlben.qits.entities.entity.AuditEntry;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface AuditEntryMapper {
  AuditEntryDto toDto(AuditEntry entity);
}
