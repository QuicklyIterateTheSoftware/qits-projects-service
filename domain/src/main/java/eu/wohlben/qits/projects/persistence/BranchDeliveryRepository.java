package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.BranchDelivery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The branch-delivery ledger (qits-1719, V67): where each branch stands in each repository.
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
   * attempt (qits-1725) — and, for the reconcile, the tip the branch was deleted at (qits-1869),
   * which no step compares.
   */
  public record Position(
      String step,
      String requestId,
      String foldSha,
      String foldAttempt,
      String version,
      String deletedSha) {}

  /**
   * One row's key and version, for the walks over a request's RELEASED rows (LIVE, and a failed
   * post-release gate) and its FAILED ones (a retried gate, qits-1796).
   */
  public record Released(
      String projectId, String repoId, String repoName, String branch, String version) {}

  /** The row for one branch in one repository, as committed or written in this transaction. */
  public Optional<Position> position(String projectId, String repoId, String branch) {
    List<?> rows =
        em().createNativeQuery(
                "select step, request_id, fold_sha, fold_attempt, version, deleted_sha"
                    + " from branch_delivery"
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
            (String) row[0],
            (String) row[1],
            (String) row[2],
            (String) row[3],
            (String) row[4],
            (String) row[5]));
  }

  /**
   * Writes one row whole, inserting or replacing what the key held. A step is never a deletion, so
   * it clears {@code deleted_sha} (qits-1869): a branch recreated and carried again must not keep
   * the tip an earlier deletion recorded.
   */
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
                + " version = excluded.version, changed_at = excluded.changed_at,"
                + " deleted_sha = null")
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
                + " deleted_sha = null, changed_at = ?4"
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

  /**
   * A deleted branch's tip, recorded on every row of {@code (repoId, branch)} that is left — in any
   * project, at any step (qits-1869). Answers how many rows it wrote; none where no row is left.
   */
  public int recordDeletedAt(String repoId, String branch, String sha) {
    return em().createNativeQuery(
            "update branch_delivery set deleted_sha = ?3 where repo_id = ?1 and branch = ?2")
        .setParameter(1, repoId)
        .setParameter(2, branch)
        .setParameter(3, sha)
        .executeUpdate();
  }

  /**
   * The steps of one branch across the repositories of one project that count toward its {@code
   * everywhere}: every repository but an {@code AGENT_CONFIG} one (qits-1736), and every row but a
   * {@code WITHDRAWN} one (qits-1737). The project's
   * {@code qits-agent-config} is platform state a desk approval releases after the work, not
   * software, so its row would hold the branch short of LIVE for good. A row whose repository is
   * gone still counts: only a repository known to be the agent configuration is left out.
   *
   * <p>Nor does a {@code WITHDRAWN} row count (qits-1737): its request was withdrawn, the branch was
   * deleted while on an open request, or the wrapper's empty fold was withdrawn — the branch no
   * longer ships from that repository, and ranked with the rest the row pinned {@code everywhere}
   * at PUSHED for good. A re-push resets it to PUSHED ({@link #resetToPushed}) and a new ask makes
   * it JOINED, and it counts again then. A branch whose every counted row is WITHDRAWN has {@code
   * everywhere} null.
   *
   * <p>The one definition of which rows {@code everywhere} ranges over — the write's announced
   * step and the read door both ask it.
   */
  @SuppressWarnings("unchecked")
  public List<String> stepsOf(String projectId, String branch) {
    return em().createNativeQuery(
            "select d.step from branch_delivery d where d.project_id = ?1 and d.branch = ?2"
                + " and not exists (select 1 from Repository r where r.id = d.repo_id"
                + " and r.archetype = 'AGENT_CONFIG') and d.step <> 'WITHDRAWN'")
        .setParameter(1, projectId)
        .setParameter(2, branch)
        .getResultList();
  }

  /** One counted row of a branch: the repository it stands in, by name, and its step. */
  public record Standing(String repoName, String step) {}

  /**
   * The rows {@link #stepsOf} counts for {@code branch} in {@code projectId}, but for the one in
   * {@code exceptRepoId}, by repository name (qits-1969): where else the branch stands. The delivery
   * desk asks it of a {@code manual-review} request's IMPLEMENTING or IMPLEMENTED item, whose
   * branch must be LIVE everywhere else; the request's own repository is left out, because its row
   * is the request being approved.
   */
  public List<Standing> standingsExcept(String projectId, String branch, String exceptRepoId) {
    List<?> rows =
        em().createNativeQuery(
                "select d.repo_name, d.step from branch_delivery d"
                    + " where d.project_id = ?1 and d.branch = ?2 and d.repo_id <> ?3"
                    + " and not exists (select 1 from Repository r where r.id = d.repo_id"
                    + " and r.archetype = 'AGENT_CONFIG') and d.step <> 'WITHDRAWN'"
                    + " order by d.repo_name, d.repo_id")
            .setParameter(1, projectId)
            .setParameter(2, branch)
            .setParameter(3, exceptRepoId)
            .getResultList();
    return rows.stream()
        .map(Object[].class::cast)
        .map(row -> new Standing((String) row[0], (String) row[1]))
        .toList();
  }

  /** The rows a request released and that still name it: what its finalization makes LIVE. */
  public List<Released> releasedBy(String requestId) {
    return atStep(requestId, "RELEASED");
  }

  /**
   * The rows that still name {@code requestId} at FAILED (qits-1796): for a RELEASED request, the
   * branches a red publish run or a failed deployment of its tag failed, which a retry of that gate
   * puts back to RELEASED. The ledger keeps no failure column, so the request's state is what tells
   * a post-release failure from any other — the caller's to check.
   */
  public List<Released> failedBy(String requestId) {
    return atStep(requestId, "FAILED");
  }

  /**
   * The rows that still name {@code requestId} at MOVED (qits-1830): the branches handed on to it,
   * moved onto it from the request ahead, or carried onto it by a red release it superseded.
   */
  public List<Released> movedBy(String requestId) {
    return atStep(requestId, "MOVED");
  }

  /**
   * The rows that still name {@code requestId} at a step before the tag — JOINED, MOVED, FAILED or
   * CONFLICTED (qits-1830): every branch the unreleased request carries, as a source or not. Which
   * of them are its sources is the caller's to tell, from the request's source rows.
   */
  public List<Released> carriedBy(String requestId) {
    return atSteps(requestId, List.of("JOINED", "MOVED", "FAILED", "CONFLICTED"));
  }

  private List<Released> atStep(String requestId, String step) {
    return atSteps(requestId, List.of(step));
  }

  private List<Released> atSteps(String requestId, List<String> steps) {
    List<?> rows =
        em().createNativeQuery(
                "select project_id, repo_id, repo_name, branch, version from branch_delivery"
                    + " where request_id = ?1 and step in (?2) order by repo_id, branch")
            .setParameter(1, requestId)
            .setParameter(2, steps)
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

  /**
   * The branches whose row still names {@code requestId} in {@code repoId} at a failed position —
   * FAILED or CONFLICTED: what that request's return to PENDING or READY puts back to JOINED.
   */
  @SuppressWarnings("unchecked")
  public List<String> failedBranchesOf(String repoId, String requestId) {
    return em().createNativeQuery(
            "select branch from branch_delivery where repo_id = ?1 and request_id = ?2"
                + " and step in ('FAILED', 'CONFLICTED') order by branch")
        .setParameter(1, repoId)
        .setParameter(2, requestId)
        .getResultList();
  }

  /** The read door: every row of one branch, in every project, by project and repository. */
  public List<BranchDelivery> listByBranch(String branch) {
    return list("branch = ?1 order by projectId, repoName, repoId", branch);
  }

  private EntityManager em() {
    return getEntityManager();
  }
}
