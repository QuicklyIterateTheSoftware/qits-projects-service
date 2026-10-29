package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestSourceMove;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The moves of sources between release requests (qits-1665). Like its siblings it owns no
 * transaction: every call runs inside the caller's.
 */
@ApplicationScoped
public class ReleaseRequestSourceMoveRepository
    implements PanacheRepositoryBase<ReleaseRequestSourceMove, String> {

  /** The moves into or out of one request, oldest first. */
  public List<ReleaseRequestSourceMove> listOf(String requestId) {
    return list(
        "fromRequestId = ?1 or toRequestId = ?1 order by movedAt, id", requestId);
  }

  /**
   * The moves into or out of each of {@code requestIds}, oldest first, keyed by request: one query
   * for a page of rows. A request with no move is absent.
   */
  public Map<String, List<ReleaseRequestSourceMove>> listOfEach(Collection<String> requestIds) {
    Map<String, List<ReleaseRequestSourceMove>> answer = new HashMap<>();
    if (requestIds.isEmpty()) {
      return answer;
    }
    for (ReleaseRequestSourceMove move :
        list(
            "fromRequestId in ?1 or toRequestId in ?1 order by movedAt, id",
            List.copyOf(requestIds))) {
      if (requestIds.contains(move.fromRequestId)) {
        answer.computeIfAbsent(move.fromRequestId, id -> new java.util.ArrayList<>()).add(move);
      }
      if (requestIds.contains(move.toRequestId) && !move.toRequestId.equals(move.fromRequestId)) {
        answer.computeIfAbsent(move.toRequestId, id -> new java.util.ArrayList<>()).add(move);
      }
    }
    return answer;
  }
}
