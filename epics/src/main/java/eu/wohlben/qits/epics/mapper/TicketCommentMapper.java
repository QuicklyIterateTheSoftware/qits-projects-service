package eu.wohlben.qits.epics.mapper;

import eu.wohlben.qits.epics.dto.TicketCommentDto;
import eu.wohlben.qits.epics.entity.TicketComment;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface TicketCommentMapper {
  TicketCommentDto toDto(TicketComment entity);
}
