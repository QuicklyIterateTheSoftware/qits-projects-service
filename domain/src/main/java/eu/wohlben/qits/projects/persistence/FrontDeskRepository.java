package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.FrontDesk;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;

/** The project front desks (qits-767, V37), keyed by project id. */
@ApplicationScoped
public class FrontDeskRepository implements PanacheRepositoryBase<FrontDesk, String> {

  /** Every desk that names a DESK lease (epic qits-1481, V61), by project. */
  public List<FrontDesk> findWithLease() {
    return list("leaseId is not null", Sort.by("projectId"));
  }

  /** The desk that names {@code leaseId}, locked for the write that follows, or null. */
  public FrontDesk lockByLeaseId(UUID leaseId) {
    if (leaseId == null) {
      return null;
    }
    return find("leaseId", leaseId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
  }
}
