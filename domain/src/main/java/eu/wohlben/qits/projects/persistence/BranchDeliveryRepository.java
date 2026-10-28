package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.BranchDelivery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The branch-delivery ledger (qits-1719, V66): where each branch stands in each repository.
 *
 * <p><b>Every write is native SQL, and so is every read the writer makes.</b> The ledger is written
 * inside other flows' transactions — an ask, a fold, a verdict, a release — and a branch's push
 * arrives off the bus at the same moment its agent asks for its release. Two transactions inserting
 * one key through the entity API would fail the second one's commit, and with it the ask or the
 * release that carried it; an {@code on conflict} upsert cannot. And because the writes bypass the
 * persistence context, {@code control/BranchDeliveries} reads the row back natively too, never as a
 * managed entity a native update would leave stale. The entity-typed reads below are the read
 * door's, in transactions that write nothing.
 */
@ApplicationScoped
public class BranchDeliveryRepository
    implements PanacheRepositoryBase<BranchDelivery, BranchDelivery.Key> {

  /**
   * What a step is compared with before it is published: the row's step, request, fold and fold
   * attempt (qits-1725).
   */
  public record Position(
      String step, String requestId, String foldSha, String foldAttempt, String version) {}

  /** One row's key and version, for the LIVE step's walk over a request's RELEASED rows. */
  public record Released(
      String projectId, String repoId, String repoName, String branch, String version) {}

  /** The row for one branch in one repository, as committed or written in this transaction. */
  public Optional<Position> position(String projectId, String repoId, String branch) {
    List<?> rows =
        em().createNativeQuery(
                "select step, request_id, fold_sha, fold_attempt, version from branch_delivery"
                    + " where project_id = ?1 and repo_id = ?2 and branch = ?3")
            .setParameter(1, projectId)
            .setParameter(2, repoId)
            .setParameter(3, branch)
            .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = (Object[]) rows.get(0);
    return Optional.of(
        new Position(
            (String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4]));
  }

  /** Writes one row whole, inserting or replacing what the key held. */
  public void upsert(BranchDelivery row) {
    em().createNativeQuery(
            "insert into branch_delivery (project_id, repo_id, repo_name, branch, step, request_id,"
                + " qualified_id, fold_sha, fold_attempt, run_id, version, changed_at)"
                + " values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)"
                + " on conflict (project_id, repo_id, branch) do update set"
                + " repo_name = excluded.repo_name, step = excluded.step,"
                + " request_id = excluded.request_id, qualified_id = excluded.qualified_id,"
                + " fold_sha = excluded.fold_sha, fold_attempt = excluded.fold_attempt,"
                + " run_id = excluded.run_id,"
                + " version = excluded.version, changed_at = excluded.changed_at")
        .setParameter(1, row.projectId)
        .setParameter(2, row.repoId)
        .setParameter(3, row.repoName)
        .setParameter(4, row.branch)
        .setParameter(5, row.step)
        .setParameter(6, row.requestId)
        .setParameter(7, row.qualifiedId)
        .setParameter(8, row.foldSha)
        .setParameter(9, row.foldAttempt)
        .setParameter(10, row.runId)
        .setParameter(11, row.version)
        .setParameter(12, row.changedAt)
        .executeUpdate();
  }

  /**
   * A push of a branch no row knows: a {@code PUSHED} row, unless one appeared meanwhile — then
   * whatever that row says stands.
   */
  public void insertPushed(
      String projectId, String repoId, String repoName, String branch, Instant at) {
    em().createNativeQuery(
            "insert into branch_delivery (project_id, repo_id, repo_name, branch, step, changed_at)"
                + " values (?1, ?2, ?3, ?4, 'PUSHED', ?5) on conflict do nothing")
        .setParameter(1, projectId)
        .setParameter(2, repoId)
        .setParameter(3, repoName)
        .setParameter(4, branch)
        .setParameter(5, at)
        .executeUpdate();
  }

  /**
   * A push to a branch whose row is LIVE or WITHDRAWN: the name was reused, so the row starts over
   * at {@code PUSHED} with no request. Any other row is left as it is.
   */
  public int resetToPushed(String projectId, String repoId, String branch, Instant at) {
    return em().createNativeQuery(
            "update branch_delivery set step = 'PUSHED', request_id = null, qualified_id = null,"
                + " fold_sha = null, fold_attempt = null, run_id = null, version = null,"
                + " changed_at = ?4"
                + " where project_id = ?1 and repo_id = ?2 and branch = ?3"
                + " and step in ('LIVE', 'WITHDRAWN')")
        .setParameter(1, projectId)
        .setParameter(2, repoId)
        .setParameter(3, branch)
        .setParameter(4, at)
        .executeUpdate();
  }

  /** A deleted branch that never got past {@code PUSHED} leaves the ledger; any other row stays. */
  public int deletePushed(String repoId, String branch) {
    return em().createNativeQuery(
            "delete from branch_delivery where repo_id = ?1 and branch = ?2 and step = 'PUSHED'")
        .setParameter(1, repoId)
        .setParameter(2, branch)
        .executeUpdate();
  }

  /** The steps of one branch across every repository of one project. */
  @SuppressWarnings("unchecked")
  public List<String> stepsOf(String projectId, String branch) {
    return em().createNativeQuery(
            "select step from branch_delivery where project_id = ?1 and branch = ?2")
        .setParameter(1, projectId)
        .setParameter(2, branch)
        .getResultList();
  }

  /** The rows a request released and that still name it: what its finalization makes LIVE. */
  public List<Released> releasedBy(String requestId) {
    List<?> rows =
        em().createNativeQuery(
                "select project_id, repo_id, repo_name, branch, version from branch_delivery"
                    + " where request_id = ?1 and step = 'RELEASED' order by repo_id, branch")
            .setParameter(1, requestId)
            .getResultList();
    return rows.stream()
        .map(Object[].class::cast)
        .map(
            row ->
                new Released(
                    (String) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (String) row[4]))
        .toList();
  }

  /** The read door: every row of one branch, in every project, by project and repository. */
  public List<BranchDelivery> listByBranch(String branch) {
    return list("branch = ?1 order by projectId, repoName, repoId", branch);
  }

  private EntityManager em() {
    return getEntityManager();
  }
}
