package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.PitchVersion;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A pitch's versions (qits-2155, V77), append-only; the caller owns the transaction. */
@ApplicationScoped
public class PitchVersionRepository
    implements PanacheRepositoryBase<PitchVersion, PitchVersion.Key> {

  /** Every version of {@code pitchId}, oldest first. */
  public List<PitchVersion> listByPitch(UUID pitchId) {
    return list("pitchId = ?1 order by number", pitchId);
  }

  /** Version {@code number} of {@code pitchId}, or null. */
  public PitchVersion find(UUID pitchId, int number) {
    return findById(new PitchVersion.Key(pitchId, number));
  }

  /** The highest version number of {@code pitchId}, or 0 while it has none. */
  public int latest(UUID pitchId) {
    Integer latest =
        getEntityManager()
            .createQuery(
                "select max(v.number) from PitchVersion v where v.pitchId = :pitch", Integer.class)
            .setParameter("pitch", pitchId)
            .getSingleResult();
    return latest == null ? 0 : latest;
  }

  /** The highest version number of each of {@code pitchIds}, in one query. */
  public Map<UUID, Integer> latest(Collection<UUID> pitchIds) {
    Map<UUID, Integer> out = new HashMap<>();
    if (pitchIds.isEmpty()) {
      return out;
    }
    List<Object[]> rows =
        getEntityManager()
            .createQuery(
                "select v.pitchId, max(v.number) from PitchVersion v where v.pitchId in :pitches"
                    + " group by v.pitchId",
                Object[].class)
            .setParameter("pitches", pitchIds)
            .getResultList();
    for (Object[] row : rows) {
      out.put((UUID) row[0], ((Number) row[1]).intValue());
    }
    return out;
  }
}
