package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/** The project front desks (qits-767, V37), keyed by project id. */
@ApplicationScoped
public class FrontDeskRepository implements PanacheRepositoryBase<FrontDesk, String> {

  /** Every desk placed on {@code runnerId}, by project. */
  public List<FrontDesk> onRunner(UUID runnerId) {
    return list("runnerId", Sort.by("projectId"), runnerId);
  }

  /** How many desks on {@code runnerId} are wanted running: the server's own slot count. */
  public long wantedOn(UUID runnerId) {
    return count("runnerId = ?1 and desired = ?2", runnerId, FrontDeskDesired.RUNNING);
  }

  /**
   * The desks waiting for a runner — wanted, unplaced and holding their token — oldest queued
   * first: the order placement tries them in.
   */
  public List<FrontDesk> queued() {
    return list(
        "runnerId is null and desired = ?1 and tokenValue is not null",
        Sort.by("queuedAt", Sort.NullPrecedence.NULLS_LAST).and("createdAt"),
        FrontDeskDesired.RUNNING);
  }
}
