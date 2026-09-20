package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.CommitBuildStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The per-commit build-status ledger's rows.
 *
 * <p>Plain CRUD and nothing else. <b>No transaction is opened here</b> — the caller owns it: the
 * write comes from {@code BuildStatusLedger} under the durable consumption, the reads from the
 * repositories API on a request thread.
 */
@ApplicationScoped
public class CommitBuildStatusRepository
    implements PanacheRepositoryBase<CommitBuildStatus, String> {

  /** Every verdict for one commit, newest run first. */
  public List<CommitBuildStatus> findByCommit(String repoId, String commitSha) {
    return list(
        "repoId = ?1 and commitSha = ?2 order by finishedAt desc, runId desc", repoId, commitSha);
  }

  /**
   * Every verdict for several commits at once — the release-request listing's read, where asking per
   * row would be a query per row on the busiest read this service has. Newest run first within each
   * commit, exactly as {@link #findByCommit} answers, so a caller can group and read them the same
   * way.
   */
  public List<CommitBuildStatus> findByCommits(List<String> commitShas) {
    if (commitShas.isEmpty()) {
      return List.of();
    }
    return list(
        "commitSha in ?1 order by finishedAt desc, runId desc", List.copyOf(commitShas));
  }

  /**
   * Record one run's verdict, replacing whatever that run had — delete-then-insert, the {@code
   * AgentCredentialRepository.put} shape, so a replayed or corrected announcement converges on one
   * row per run rather than colliding with the primary key.
   */
  public void put(CommitBuildStatus row) {
    deleteById(row.runId);
    persist(row);
  }

  /**
   * Whether some row already names {@code runId} as the run it superseded — i.e. whether a retry of
   * this run has already been recorded here.
   *
   * <p>The forward half of the retry lineage, and it exists for one case: a <b>redelivery</b>. The
   * ledger write commits on this datasource while the durable claim commits on another, so a claim
   * that rolls back after the write has landed offers the same verdict again — and the class javadoc
   * on {@code BuildStatusLedger} already names that as the shape this table converges under. Once a
   * retry has deleted a run's row, re-persisting that run from a redelivered frame would resurrect a
   * verdict the retry answered, and on the commonest shape (a red answered by a green) that is a
   * release gate back down with nothing to push. Asking this first makes the write converge on the
   * same set of rows whichever order the chain arrives in.
   */
  public boolean isSuperseded(String runId) {
    return count("retryOfRunId = ?1", runId) > 0;
  }
}
