package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.DossierPage;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** The dossier's pages, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class DossierPageRepository implements PanacheRepositoryBase<DossierPage, String> {

  /** The epic's pages in the order the nav draws them. */
  public List<DossierPage> listByEpic(String epicId) {
    return find("epicId", Sort.by("position").and("id"), epicId).list();
  }

  /** The page a URL names. Slugs are unique per epic, so at most one. */
  public Optional<DossierPage> findBySlug(String epicId, String slug) {
    return find("epicId = ?1 and slug = ?2", epicId, slug).firstResultOptional();
  }

  /** The last position in use, or -1 for an epic with no dossier — so a create appends. */
  public int maxPosition(String epicId) {
    return find("epicId", Sort.by("position").descending(), epicId)
        .firstResultOptional()
        .map(page -> page.position)
        .orElse(-1);
  }
}
