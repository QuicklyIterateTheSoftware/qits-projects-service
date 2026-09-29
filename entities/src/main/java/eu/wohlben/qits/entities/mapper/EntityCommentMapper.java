package eu.wohlben.qits.entities.mapper;

import eu.wohlben.qits.entities.dto.CommentDto;
import eu.wohlben.qits.entities.dto.TicketCommentDto;
import eu.wohlben.qits.entities.entity.EntityComment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface EntityCommentMapper {

  CommentDto toDto(EntityComment entity);

  /** The pre-qits-551 shape the ticket-only routes still answer: the entity id as {@code ticketId}. */
  @Mapping(target = "ticketId", source = "entityId")
  TicketCommentDto toTicketDto(EntityComment entity);
}
