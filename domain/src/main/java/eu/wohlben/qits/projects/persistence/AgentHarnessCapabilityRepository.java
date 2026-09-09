package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentHarnessCapability;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The cache of what each harness binary reported, one row per {@code (harness, image version)}.
 *
 * <p>Plain CRUD; no transaction is opened here — the caller ({@code
 * control/AgentCapabilityCatalogueService}) owns it, the arrangement every repository in this module
 * makes.
 *
 * <p>Every read is ordered <b>newest report first</b>, because that is the only order this table is
 * ever asked in: the catalogue takes the newest per harness and names the rest. Ordering by image
 * version instead would be ordering a set of names.
 */
@ApplicationScoped
public class AgentHarnessCapabilityRepository
    implements PanacheRepositoryBase<AgentHarnessCapability, String> {

  /** Every report, newest first, so the catalogue is one query. */
  public List<AgentHarnessCapability> allNewestFirst() {
    return list("order by reportedAt desc, imageVersion desc");
  }

  /** The row for one {@code (harness, image version)} pair, which is what a report replaces. */
  public Optional<AgentHarnessCapability> find(String harness, String imageVersion) {
    return find("harness = ?1 and imageVersion = ?2", harness, imageVersion).firstResultOptional();
  }
}
