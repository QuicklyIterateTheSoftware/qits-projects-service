package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.TicketDto;
import eu.wohlben.qits.epics.entity.Ticket;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface TicketMapper {

  /**
   * The row, and no workspaces: nothing in this module can ask which ones are on a ticket — they
   * live in another context, behind a port the service layer holds. So the mapped shape is the empty
   * answer, and a door that wants the real one decorates it with {@code TicketDto.withWorkspaces}.
   * Empty rather than null, so no reader has to guess whether "none" means none or means unasked.
   */
  @Mapping(target = "workspaces", expression = "java(java.util.List.of())")
  TicketDto toDto(Ticket entity);
}
