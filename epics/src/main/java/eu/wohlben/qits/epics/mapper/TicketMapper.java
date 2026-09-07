package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.TicketDto;
import eu.wohlben.qits.epics.entity.Ticket;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface TicketMapper {
  TicketDto toDto(Ticket entity);
}
