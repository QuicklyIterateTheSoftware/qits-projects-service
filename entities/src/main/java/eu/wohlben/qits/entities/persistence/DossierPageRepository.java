package eu.wohlben.qits.entities.persistence;

import eu.wohlben.qits.entities.entity.DossierOwner;
import eu.wohlben.qits.entities.entity.DossierPage;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The dossier's pages, plain CRUD; the caller owns the transaction.
 *
 * <p>Every query is scoped by a {@link DossierOwner} rather than by an epic id. The owner column is
 * chosen in one place — {@link #ownerColumn} — so the epic and ticket halves cannot answer two
 * different questions, and so a third owner (there is none, and V8 argues there should not be) would
 * be one line rather than a second set of methods.
 */
@ApplicationScoped
public class DossierPageRepository implements PanacheRepositoryBase<DossierPage, String> {

  /** The entity field the owner is stored in. Both are indexed, one partial index each (V8). */
  private static String ownerColumn(DossierOwner owner) {
    return owner.isEpic() ? "epicId" : "ticketId";
  }

  /** The owner's pages in the order the nav draws them. */
  public List<DossierPage> listByOwner(DossierOwner owner) {
    return find(ownerColumn(owner), Sort.by("position").and("id"), owner.id()).list();
  }

  /** The page a URL names. Slugs are unique per owner, so at most one. */
  public Optional<DossierPage> findBySlug(DossierOwner owner, String slug) {
    return find(ownerColumn(owner) + " = ?1 and slug = ?2", owner.id(), slug).firstResultOptional();
  }

  /** The last position in use, or -1 for an owner with no dossier — so a create appends. */
  public int maxPosition(DossierOwner owner) {
    return find(ownerColumn(owner), Sort.by("position").descending(), owner.id())
        .firstResultOptional()
        .map(page -> page.position)
        .orElse(-1);
  }

  /**
   * Close the gap a removed page leaves, in one statement, so positions stay dense whatever the
   * session happens to be holding.
   */
  public void closeGapAfter(DossierOwner owner, int position) {
    update(
        "position = position - 1 where " + ownerColumn(owner) + " = ?1 and position > ?2",
        owner.id(),
        position);
  }
}
