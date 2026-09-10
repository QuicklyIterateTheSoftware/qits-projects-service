package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The approval decisions' rows. Plain CRUD like its parent's repository — the caller owns every
 * transaction, because the writers are a request thread and the gate's own consumption, and each
 * brackets itself.
 *
 * <p>Reads only ever go one of two ways, and they are the two methods here: the gate wants the one
 * decision that is current, and a person wants the whole trail. There is deliberately no "is this
 * approved" convenience — that is a judgement about a request, and it belongs where the gate lives
 * rather than in a query.
 */
@ApplicationScoped
public class ReleaseRequestApprovalRepository
    implements PanacheRepositoryBase<ReleaseRequestApproval, String> {

  /**
   * The newest decision made about <b>this fold</b> of this request, if a person has made one — the
   * gate's read, and the one the index is shaped for.
   *
   * <p>Empty is the answer for a request nobody has decided on <em>and</em> for one whose decisions
   * were all made against a merged sha it has since moved past. Those are the same answer on
   * purpose: a re-armed request is a fold nobody has looked at, however much history it carries.
   */
  public Optional<ReleaseRequestApproval> latestFor(String requestId, String mergedSha) {
    if (requestId == null || mergedSha == null) {
      return Optional.empty();
    }
    return find(
            "requestId = ?1 and mergedSha = ?2 order by decidedAt desc", requestId, mergedSha)
        .firstResultOptional();
  }

  /**
   * One request's whole decision trail, newest first, across every fold it has ever had — the
   * history read. Rows about superseded shas are in it deliberately: what a person refused and why
   * is the part of the trail worth showing, and the sha on each row says which fold it judged.
   */
  public List<ReleaseRequestApproval> listByRequest(String requestId) {
    return list("requestId = ?1 order by decidedAt desc", requestId);
  }
}
