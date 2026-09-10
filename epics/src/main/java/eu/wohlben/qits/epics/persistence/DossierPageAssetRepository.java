package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.DossierPageAsset;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** The reverse index between pages and the figures they inline. */
@ApplicationScoped
public class DossierPageAssetRepository
    implements PanacheRepositoryBase<DossierPageAsset, DossierPageAsset.Key> {

  public List<DossierPageAsset> listByPage(String pageId) {
    return list("pageId", pageId);
  }

  /** Whether any page still names this asset — the reference count, asked as a question. */
  public boolean anyReferences(String assetId) {
    return count("assetId", assetId) > 0;
  }

  public void deleteByPage(String pageId) {
    delete("pageId", pageId);
  }
}
