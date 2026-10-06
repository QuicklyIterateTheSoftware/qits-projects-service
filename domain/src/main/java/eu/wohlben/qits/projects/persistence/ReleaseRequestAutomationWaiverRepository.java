package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequestAutomationWaiver;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The automation waivers' rows, read the way {@link ReleaseRequestApprovalRepository} reads its
 * decisions: the one that is <b>current</b> — one request at a time for the gate, and a page at a
 * time for the list reads, which must not query per row.
 */
@ApplicationScoped
public class ReleaseRequestAutomationWaiverRepository
    implements PanacheRepositoryBase<ReleaseRequestAutomationWaiver, String> {

  /**
   * The newest waiver of <b>this fold</b> of this request, if a person has made one. Empty for a
   * request nobody waived and for one whose waivers were all about a fold it has since left — the
   * same answer on purpose.
   */
  public Optional<ReleaseRequestAutomationWaiver> latestFor(String requestId, String mergedSha) {
    if (requestId == null || mergedSha == null) {
      return Optional.empty();
    }
    return find("requestId = ?1 and mergedSha = ?2 order by waivedAt desc", requestId, mergedSha)
        .firstResultOptional();
  }

  /**
   * {@link #latestFor} for a whole page in one query, keyed by request — {@code
   * ReleaseRequestApprovalRepository.currentForEach}'s shape and its reasons, including the row-by-row
   * pairing check, since {@code merged_sha in (…)} can match a sibling's row on the same fold.
   */
  public Map<String, ReleaseRequestAutomationWaiver> currentForEach(
      Map<String, String> mergedShaByRequest) {
    Map<String, String> wanted =
        mergedShaByRequest.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    if (wanted.isEmpty()) {
      return Map.of();
    }
    List<ReleaseRequestAutomationWaiver> rows =
        list(
            "requestId in ?1 and mergedSha in ?2 order by waivedAt desc",
            List.copyOf(wanted.keySet()),
            List.copyOf(Set.copyOf(wanted.values())));
    Map<String, ReleaseRequestAutomationWaiver> current = new HashMap<>();
    for (ReleaseRequestAutomationWaiver row : rows) {
      if (row.mergedSha.equals(wanted.get(row.requestId))) {
        current.putIfAbsent(row.requestId, row);
      }
    }
    return current;
  }
}
