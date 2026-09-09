package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentSurfaceConfigurationRevision;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The append-only revision trail of surface configuration edits.
 *
 * <p>Insert and read; nothing here updates or deletes, which is what "append-only" means in
 * practice. The caller owns the transaction, and a revision is written inside the same one as the
 * configuration change it describes — a trail that can disagree with the row is worse than none.
 */
@ApplicationScoped
public class AgentSurfaceConfigurationRevisionRepository
    implements PanacheRepositoryBase<AgentSurfaceConfigurationRevision, String> {

  /** One surface's history, newest first. */
  public List<AgentSurfaceConfigurationRevision> forSurface(String surfaceKey) {
    return list("surfaceKey = ?1 order by changedAt desc, id desc", surfaceKey);
  }

  /**
   * When any surface was last edited, across the whole store, or empty on a store nobody has written
   * yet.
   *
   * <p>It exists for one caller and one reason: {@code
   * AgentSurfaceConfigurationService.documentForContainerSpec} stamps the injected document with
   * this instead of with {@code now}, because those bytes go into a container spec the orchestrator
   * hashes. A wall clock in there would make every spec differ from the last one and turn every wake
   * into a container replacement — the exact defect {@code AgentContainerFactory.forRestart}'s
   * javadoc records. This value moves when the configuration moves and at no other time, which is
   * also the honest answer to "how old is what this container holds".
   */
  public java.util.Optional<java.time.Instant> latestChangeAt() {
    return find("order by changedAt desc, id desc")
        .firstResultOptional()
        .map(revision -> revision.changedAt);
  }
}
