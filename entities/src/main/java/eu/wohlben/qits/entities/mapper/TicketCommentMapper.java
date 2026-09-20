package eu.wohlben.qits.entities.mapper;

import eu.wohlben.qits.entities.dto.TicketCommentDto;
import eu.wohlben.qits.entities.entity.TicketComment;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface TicketCommentMapper {
  TicketCommentDto toDto(TicketComment entity);
}
