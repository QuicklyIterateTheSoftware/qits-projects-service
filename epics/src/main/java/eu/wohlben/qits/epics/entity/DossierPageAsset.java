package eu.wohlben.qits.epics.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * The reverse index: which page names which asset. Rewritten in full for one page on every save of
 * that page, which is what makes the reference count exact without a sweeper.
 *
 * <p>Derived state, so it is deliberately {@code @Uncaused}: the rows it describes carry the cause,
 * and this table is rebuilt from a body rather than created by a request.
 */
@Entity
@Table(name = "dossier_page_asset")
@IdClass(DossierPageAsset.Key.class)
@eu.wohlben.qits.eventstream.Uncaused
public class DossierPageAsset extends PanacheEntityBase {

  @Id
  @Column(name = "page_id", nullable = false)
  public String pageId;

  @Id
  @Column(name = "asset_id", nullable = false)
  public String assetId;

  /** The composite key, as JPA wants it spelled. */
  public static class Key implements Serializable {
    public String pageId;
    public String assetId;

    public Key() {}

    public Key(String pageId, String assetId) {
      this.pageId = pageId;
      this.assetId = assetId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(pageId, key.pageId)
          && Objects.equals(assetId, key.assetId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(pageId, assetId);
    }
  }
}
