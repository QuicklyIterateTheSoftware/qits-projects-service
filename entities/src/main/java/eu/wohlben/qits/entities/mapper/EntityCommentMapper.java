package eu.wohlben.qits.entities.mapper;

import eu.wohlben.qits.entities.dto.CommentDto;
import eu.wohlben.qits.entities.entity.EntityComment;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface EntityCommentMapper {

  CommentDto toDto(EntityComment entity);
}
