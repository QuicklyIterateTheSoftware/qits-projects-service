package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentMcpCredential;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The values behind the external MCP catalog's credential keys.
 *
 * <p>Plain CRUD, keyed by the credential key; the caller owns the transaction. Listing is ordered by
 * key so the listing door does not reshuffle between reads.
 */
@ApplicationScoped
public class AgentMcpCredentialRepository
    implements PanacheRepositoryBase<AgentMcpCredential, String> {

  /** Every credential, by key. */
  public List<AgentMcpCredential> allOrdered() {
    return list("order by credentialKey");
  }
}
