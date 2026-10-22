package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.DeskRole;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * The project desks (qits-767, V37), keyed by desk id (qits-1482, V49): a FRONT desk's id is its
 * project id, a DELIVERY desk's {@code <projectId>-delivery} ({@link DeskRole#deskId}).
 */
@ApplicationScoped
public class FrontDeskRepository implements PanacheRepositoryBase<FrontDesk, String> {

  /**
   * A placeable desk — wanted, unplaced and holding its token: what {@link #queued} lists, what
   * {@link #countQueued} counts as a runner's backlog, and what placement tries to claim.
   */
  private static final String QUEUED = "runnerId is null and desired = ?1 and tokenValue is not null";

  /** Every desk placed on {@code runnerId}, by desk id. */
  public List<FrontDesk> onRunner(UUID runnerId) {
    return list("runnerId", Sort.by("deskId"), runnerId);
  }

  /** {@code projectId}'s desk of {@code role}, or null. */
  public FrontDesk ofProject(String projectId, DeskRole role) {
    return findById(role.deskId(projectId));
  }

  /** Every desk of {@code projectId}. */
  public List<FrontDesk> allOfProject(String projectId) {
    return list("projectId", Sort.by("role"), projectId);
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
        QUEUED,
        Sort.by("queuedAt", Sort.NullPrecedence.NULLS_LAST).and("createdAt"),
        FrontDeskDesired.RUNNING);
  }

  /** How many desks are waiting for a runner: {@link #queued}'s size, the backlog a runner is told. */
  public long countQueued() {
    return count(QUEUED, FrontDeskDesired.RUNNING);
  }
}
