package eu.wohlben.qits.entities.milestone;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/** The milestones, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class MilestoneRepository implements PanacheRepositoryBase<Milestone, String> {

  /** A project's milestones, oldest first. */
  public List<Milestone> ofProject(String projectId) {
    return find("projectId", Sort.by("createdAt").and("id"), projectId).list();
  }

  /** The milestones {@code ids} name, in one query; {@code in ()} is a syntax error, hence the guard. */
  public List<Milestone> listByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return find("id in ?1", Sort.by("createdAt").and("id"), ids).list();
  }
}
