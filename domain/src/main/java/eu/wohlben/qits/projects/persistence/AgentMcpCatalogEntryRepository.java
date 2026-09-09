package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentMcpCatalogEntry;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The platform-wide catalog of external MCP servers.
 *
 * <p>Plain CRUD, keyed by the server key; the caller owns the transaction. Listing is ordered by key
 * so the catalog route does not reshuffle between reads.
 */
@ApplicationScoped
public class AgentMcpCatalogEntryRepository
    implements PanacheRepositoryBase<AgentMcpCatalogEntry, String> {

  /** Every entry, by key. */
  public List<AgentMcpCatalogEntry> allOrdered() {
    return list("order by catalogKey");
  }
}
