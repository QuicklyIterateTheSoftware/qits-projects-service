package eu.wohlben.qits.entities.mapper;

import eu.wohlben.qits.entities.dto.CommentDto;
import eu.wohlben.qits.entities.entity.EntityComment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * The row's own fields. {@code authorAgentOf} and {@code editable} are about the reader and the
 * project table, neither of which this module sees, so the {@code /work} doors fill them.
 */
@Mapper(componentModel = "jakarta")
public interface EntityCommentMapper {

  @Mapping(target = "authorAgentOf", ignore = true)
  @Mapping(target = "editable", ignore = true)
  CommentDto toDto(EntityComment entity);
}
