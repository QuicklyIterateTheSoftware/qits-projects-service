package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.NamedAgent;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** A project's named agents (qits-2154, V76), keyed by project and name. */
@ApplicationScoped
public class NamedAgentRepository implements PanacheRepositoryBase<NamedAgent, NamedAgent.Key> {

  /** The row of {@code projectId}'s agent {@code name}, or null while it has none. */
  public NamedAgent find(String projectId, String name) {
    return findById(new NamedAgent.Key(projectId, name));
  }

  /** Every named agent row of {@code projectId}, by name. */
  public List<NamedAgent> listByProject(String projectId) {
    return list("projectId", Sort.by("name"), projectId);
  }
}
