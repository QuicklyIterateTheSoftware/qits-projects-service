package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.StoredGateSet;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The repositories' stored gate sets (qits-1201). The write is an SQL upsert, as {@link
 * RefinementPromptDraftRepository}'s is, so a resolve and a background refresh writing the same
 * repository at once collapse into one row instead of a primary-key 500 — and an older reading never
 * overwrites a newer one.
 */
@ApplicationScoped
public class StoredGateSetRepository implements PanacheRepositoryBase<StoredGateSet, String> {

  /** Every stored set among {@code repoIds}, in one query. Inside the caller's transaction. */
  public List<StoredGateSet> listByRepos(Collection<String> repoIds) {
    return repoIds.isEmpty() ? List.of() : list("repoId in ?1", repoIds);
  }

  /** Insert or overwrite {@code repoId}'s set, unless what is stored was read later. */
  public void upsert(String repoId, String gateSet, Instant readAt) {
    getEntityManager()
        .createNativeQuery(
            "insert into release_gate_set (repo_id, gate_set, read_at) values (?1, ?2, ?3)"
                + " on conflict (repo_id) do update set"
                + " gate_set = excluded.gate_set, read_at = excluded.read_at"
                + " where release_gate_set.read_at <= excluded.read_at")
        .setParameter(1, repoId)
        .setParameter(2, gateSet)
        .setParameter(3, readAt)
        .executeUpdate();
  }
}
