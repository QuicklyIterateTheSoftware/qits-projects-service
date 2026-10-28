package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestParticipant;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The participants of release requests (qits-1673). Like its siblings it owns no transaction: every
 * call runs inside the caller's, which is the ask, the join or the read that needs it.
 */
@ApplicationScoped
public class ReleaseRequestParticipantRepository
    implements PanacheRepositoryBase<ReleaseRequestParticipant, ReleaseRequestParticipant.Key> {

  /**
   * Records {@code principal} as a participant of {@code requestId}, unless it already is one. A
   * null or blank principal — an anonymous caller — records nothing. {@code on conflict do nothing}
   * rather than a read and an insert, so two concurrent asks by one caller cannot abort each other.
   */
  public void add(String requestId, String principal) {
    if (requestId == null || principal == null || principal.isBlank()) {
      return;
    }
    getEntityManager()
        .createNativeQuery(
            "insert into release_request_participant (request_id, principal, first_seen_at)"
                + " values (?1, ?2, ?3) on conflict do nothing")
        .setParameter(1, requestId)
        .setParameter(2, principal.trim())
        .setParameter(3, Instant.now())
        .executeUpdate();
  }

  /** One request's participants, sorted and distinct. */
  public List<String> principalsOf(String requestId) {
    return List.copyOf(
        new TreeSet<>(
            list("requestId", requestId).stream().map(row -> row.principal).toList()));
  }

  /** Every participant of a set of requests, sorted per request, for a list read: one query. */
  public Map<String, List<String>> principalsOfEach(List<String> requestIds) {
    Map<String, TreeSet<String>> sorted = new HashMap<>();
    if (!requestIds.isEmpty()) {
      list("requestId in ?1", requestIds)
          .forEach(
              row ->
                  sorted.computeIfAbsent(row.requestId, id -> new TreeSet<>()).add(row.principal));
    }
    Map<String, List<String>> answer = new HashMap<>();
    sorted.forEach((id, principals) -> answer.put(id, List.copyOf(principals)));
    return answer;
  }
}
