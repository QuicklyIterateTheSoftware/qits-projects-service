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
}
