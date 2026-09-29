package eu.wohlben.qits.entities.persistence;

import eu.wohlben.qits.entities.entity.EntityComment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

@ApplicationScoped
public class EntityCommentRepository implements PanacheRepositoryBase<EntityComment, String> {

  // Oldest first — a thread is a sequence and reading it backwards is reading a different thread —
  // with the id as a deterministic tie-breaker so two remarks sharing a created_at (several writes
  // in one transaction) come back in a stable order. The mirror of AuditRepository's NEWEST_FIRST.
  private static final Sort OLDEST_FIRST = Sort.by("createdAt").and("id");

  /** Every comment on one entity, oldest first. */
  public List<EntityComment> listByEntity(String entityId) {
    return find("entityId", OLDEST_FIRST, entityId).list();
  }

  /** Every comment on any of {@code entityIds}, oldest first — one query for a whole subtree. */
  public List<EntityComment> listByEntities(Collection<String> entityIds) {
    if (entityIds.isEmpty()) {
      return List.of();
    }
    return find("entityId in ?1", OLDEST_FIRST, entityIds).list();
  }
}
