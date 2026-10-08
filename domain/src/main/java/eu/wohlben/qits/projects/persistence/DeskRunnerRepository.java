package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.DeskRunner;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The front-desk runners (qits-767); qits-workspaces-service's {@code WorkspaceRunnerRepository}. */
@ApplicationScoped
public class DeskRunnerRepository implements PanacheRepositoryBase<DeskRunner, UUID> {

  public Optional<DeskRunner> findByName(String name) {
    return find("name", name).firstResultOptional();
  }

  /**
   * The registered runner a commissioned client belongs to. An unregistered row has no client and
   * never matches.
   */
  public Optional<DeskRunner> findByClientId(String clientId) {
    return find("clientId", clientId).firstResultOptional();
  }

  public List<DeskRunner> listByName() {
    return listAll(Sort.by("name"));
  }
}
