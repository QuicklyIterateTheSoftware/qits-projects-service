package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.DossierAsset;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/** The copied figures, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class DossierAssetRepository implements PanacheRepositoryBase<DossierAsset, String> {

  public List<DossierAsset> listByEpic(String epicId) {
    return list("epicId", epicId);
  }

  /**
   * Which of {@code ids} this epic holds a copy of — the "in use" answer for a whole listing in one
   * query rather than one per row. The id match works because a copy keeps the source's id.
   */
  public List<String> idsHeldByEpic(String epicId, Collection<String> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return find("epicId = ?1 and id in ?2", epicId, ids).stream().map(row -> row.id).toList();
  }
}
