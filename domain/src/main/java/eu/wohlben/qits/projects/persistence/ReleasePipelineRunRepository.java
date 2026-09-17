package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The release pipeline's phase-run rows.
 *
 * <p>Plain CRUD and nothing else, {@code CommitBuildStatusRepository}'s shape one table over.
 * <b>No transaction is opened here</b> — the caller owns it: the write comes from {@code
 * ReleasePipelineRuns} under the durable consumption, the reads from the release-request API on a
 * request thread.
 */
@ApplicationScoped
public class ReleasePipelineRunRepository
    implements PanacheRepositoryBase<ReleasePipelineRun, String> {

  /**
   * Every phase run of one request, newest transition first.
   *
   * <p>The order is the fold's input: the assembler takes the first row of each phase as that
   * phase's current run, so "newest first" here is what makes "the newest run of this phase" a head
   * rather than a scan. {@code runId} breaks a tie, because two frames of two runs can genuinely
   * carry one instant and a fold has to be deterministic across reads.
   */
  public List<ReleasePipelineRun> findByRequest(String releaseRequestId) {
    return list(
        "releaseRequestId = ?1 order by updatedAt desc, runId desc", releaseRequestId);
  }

  /**
   * The same for several requests at once — the release-request listing's read, where asking per row
   * would be a query per row on the busiest read this service has. Ordered identically, so a caller
   * can group by request and read each group exactly as {@link #findByRequest} answers it.
   */
  public List<ReleasePipelineRun> findByRequests(List<String> releaseRequestIds) {
    if (releaseRequestIds.isEmpty()) {
      return List.of();
    }
    return list(
        "releaseRequestId in ?1 order by updatedAt desc, runId desc",
        List.copyOf(releaseRequestIds));
  }
}
