package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/**
 * The approval-requirement notices' rows (epic qits-1038). Insert-only, and the insert is the
 * deduplication: see {@link #recordIfAbsent}. The caller owns the transaction.
 */
@ApplicationScoped
public class ReleaseRequestApprovalNoticeRepository
    implements PanacheRepositoryBase<ReleaseRequestApprovalNotice, ReleaseRequestApprovalNotice.Key> {

  /**
   * Records that {@code requirementId} of {@code requestId}'s fold {@code mergedSha} reached {@code
   * state}, <b>unless that was recorded already</b> — and answers whether this call is the one that
   * recorded it, which is the whole question "should this be announced?". {@code on conflict do
   * nothing} against the four-column primary key, so two transactions racing each other record it
   * once and exactly one of them answers true.
   */
  public boolean recordIfAbsent(
      String requestId,
      String mergedSha,
      String requirementId,
      ReleaseRequestApprovalNotice.State state,
      String reason,
      String admits,
      boolean excludesAuthors,
      String detail,
      Instant at) {
    return getEntityManager()
            .createNativeQuery(
                "insert into release_request_approval_notice (request_id, merged_sha,"
                    + " requirement_id, state, reason, admits, excludes_authors, detail,"
                    + " announced_at) values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)"
                    + " on conflict do nothing")
            .setParameter(1, requestId)
            .setParameter(2, mergedSha)
            .setParameter(3, requirementId)
            .setParameter(4, state.name())
            .setParameter(5, reason)
            .setParameter(6, admits)
            .setParameter(7, excludesAuthors)
            .setParameter(8, detail)
            .setParameter(9, at)
            .executeUpdate()
        == 1;
  }

  /** Every notice of one request, every fold, oldest first. */
  public List<ReleaseRequestApprovalNotice> listByRequest(String requestId) {
    return list("requestId = ?1 order by announcedAt", requestId);
  }
}
