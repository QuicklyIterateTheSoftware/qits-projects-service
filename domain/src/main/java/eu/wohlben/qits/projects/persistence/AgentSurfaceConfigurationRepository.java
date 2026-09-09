package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentSurfaceConfiguration;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The one-row-per-surface store of agent session configuration.
 *
 * <p>Plain CRUD and nothing else; <b>no transaction is opened here</b> — the caller
 * ({@code control/AgentSurfaceConfigurationService}) owns it, the arrangement every repository in
 * this module makes.
 */
@ApplicationScoped
public class AgentSurfaceConfigurationRepository
    implements PanacheRepositoryBase<AgentSurfaceConfiguration, String> {

  /** Every configured surface, ordered by key so a listing is stable across reads. */
  public List<AgentSurfaceConfiguration> allOrdered() {
    return list("order by surfaceKey");
  }
}
