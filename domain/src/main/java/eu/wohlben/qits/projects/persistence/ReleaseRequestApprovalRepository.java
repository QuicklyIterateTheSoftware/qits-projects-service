package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The approval decisions' rows. Plain CRUD like its parent's repository — the caller owns every
 * transaction, because the writers are a request thread and the gate's own consumption, and each
 * brackets itself.
 *
 * <p>Reads only ever ask one of two questions: the decision that is <b>current</b>, and the whole
 * <b>trail</b>. The first is asked in two shapes — one request at a time for the gate, and a page at
 * a time for the list reads, which must not query per row — and that is the only reason there are
 * three methods rather than two. There is deliberately no "is this approved" convenience: that is a
 * judgement about a request, and it belongs where the gate lives rather than in a query.
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
   * The same "newest decision at the current fold" for a whole <b>page</b> of requests, in <b>one</b>
   * query — {@link #latestFor} is the gate's read and this is the list read's, and there are two of
   * them for one reason only: a list is built for every release-request page in the product and a
   * per-row {@code latestFor} would be an N+1 on the busiest read this class has. The batched shape
   * is the one {@code ReleaseRequestSourceRepository.listByRequests} already uses, and this method
   * exists so that the answer stays one query rather than becoming one per row again.
   *
   * <p>The argument is a <b>map</b> and not a list of ids, because "current" is per request: each
   * one has its own {@code mergedSha}, and asking by id alone would drag every fold's history into
   * memory to throw nearly all of it away. Both columns are therefore constrained — the sha set
   * narrows the read to plausible rows — and the pairing is then checked row by row, since {@code
   * merged_sha in (…)} can match a row belonging to a sibling request that happens to sit on the
   * same fold.
   *
   * <p>A request with a null {@code mergedSha} has nothing to have been decided about and is simply
   * not asked for; it is absent from the answer, which the caller reads as "no decision", the same
   * as {@link #latestFor}'s empty.
   */
  public Map<String, ReleaseRequestApproval> currentForEach(Map<String, String> mergedShaByRequest) {
    Map<String, String> wanted =
        mergedShaByRequest.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    if (wanted.isEmpty()) {
      return Map.of();
    }
    List<ReleaseRequestApproval> rows =
        list(
            "requestId in ?1 and mergedSha in ?2 order by decidedAt desc",
            List.copyOf(wanted.keySet()),
            List.copyOf(Set.copyOf(wanted.values())));
    Map<String, ReleaseRequestApproval> current = new HashMap<>();
    for (ReleaseRequestApproval row : rows) {
      if (!row.mergedSha.equals(wanted.get(row.requestId))) {
        // A row about a fold THIS request has moved past, pulled in because a sibling in the page
        // is sitting on that sha. It says nothing about the request it belongs to.
        continue;
      }
      // Newest first, so the first row that arrives for a request is the one that counts.
      current.putIfAbsent(row.requestId, row);
    }
    return current;
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
