package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestUpstreamOverride;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gate 3's overrides (qits-914), read a page at a time: the list reads must not query per row.
 */
@ApplicationScoped
public class ReleaseRequestUpstreamOverrideRepository
    implements PanacheRepositoryBase<
        ReleaseRequestUpstreamOverride, ReleaseRequestUpstreamOverride.Key> {

  /** The override of one pair, if a person has made one. */
  public Optional<ReleaseRequestUpstreamOverride> forPair(
      String requestId, String upstreamRequestId) {
    if (requestId == null || upstreamRequestId == null) {
      return Optional.empty();
    }
    return findByIdOptional(new ReleaseRequestUpstreamOverride.Key(requestId, upstreamRequestId));
  }

  /**
   * Every override of the given requests in one query: request id → upstream request id → the
   * override. A request nobody overrode for is absent.
   */
  public Map<String, Map<String, ReleaseRequestUpstreamOverride>> forEach(
      Collection<String> requestIds) {
    if (requestIds == null || requestIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Map<String, ReleaseRequestUpstreamOverride>> out = new HashMap<>();
    for (ReleaseRequestUpstreamOverride row : list("requestId in ?1", List.copyOf(requestIds))) {
      out.computeIfAbsent(row.requestId, key -> new HashMap<>()).put(row.upstreamRequestId, row);
    }
    return out;
  }
}
