package eu.wohlben.qits.entities.persistence;

import eu.wohlben.qits.entities.entity.Idea;
import eu.wohlben.qits.entities.entity.IdeaStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/** The {@code idea} table (qits-1387). Reads and writes; no delete is ever made through it. */
@ApplicationScoped
public class IdeaRepository implements PanacheRepositoryBase<Idea, String> {

  private static final Sort OLDEST_FIRST = Sort.by("createdAt").and("id");

  /**
   * A project's ideas, oldest first. {@code includeArchived} false leaves the archived ones out;
   * {@code status} null means every status.
   */
  public List<Idea> listByProject(String projectId, boolean includeArchived, IdeaStatus status) {
    StringBuilder query = new StringBuilder("projectId = ?1");
    List<Object> params = new ArrayList<>();
    params.add(projectId);
    if (!includeArchived) {
      query.append(" and archived = false");
    }
    if (status != null) {
      params.add(status);
      query.append(" and status = ?").append(params.size());
    }
    return find(query.toString(), OLDEST_FIRST, params.toArray()).list();
  }

  /** The ideas naming {@code id} as their {@code enabledBy}, oldest first. */
  public List<Idea> listEnabledBy(String id) {
    return find("enabledBy", OLDEST_FIRST, id).list();
  }

  /** Whether any idea names {@code id} as its {@code enabledBy}. */
  public boolean enablesAny(String id) {
    return count("enabledBy", id) > 0;
  }

  /** Every idea of every project, for the boot reconcile. */
  public List<Idea> listEvery() {
    return findAll(OLDEST_FIRST).list();
  }
}
