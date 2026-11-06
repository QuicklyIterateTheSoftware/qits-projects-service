package eu.wohlben.qits.entities.persistence;

import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.MembershipKind;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The parent/child edges, plain CRUD; the caller owns the transaction.
 *
 * <p><b>The position idiom is {@link DossierPageRepository}'s and is copied rather than reinvented.</b>
 * {@code maxPosition} answers {@code -1} for a parent with no children so a create appends, and
 * {@code closeGapAfter} renumbers a removal's tail in one statement so positions stay dense whatever
 * the session happens to be holding. {@code DossierService.move} is the third piece of that idiom
 * and belongs in the service that ends up owning a reorder here.
 *
 * <p><b>This table is the parent/child relation of the whole planning tree now.</b> {@code
 * WorkEntityService} writes an edge per node create and removes one per node delete —
 * {@link #maxPosition} to append, {@link #closeGapAfter} to keep the survivors dense — {@link
 * #structuralMembershipOf} is how either of them answers "what is this part of", {@link #childrenOf} is the
 * order a listing is drawn in, and {@code WorkEntityService}'s three subtree walks fan out with {@link
 * #childrenOfAll} a level at a time. Every read here is bulk by shape for that last reason: a merged
 * tree walked one node per query is the one performance mistake this model makes easy.
 *
 * <p><b>Every tree read is STRUCTURAL-only, and says so in its query</b> (V18). STRUCTURAL is the
 * one kind of edge since qits-1899 retired the other (V41); the filter stays, so a second kind of
 * edge added later cannot leak into a tree read.
 */
@ApplicationScoped
public class EntityMembershipRepository
    implements PanacheRepositoryBase<EntityMembership, String> {

  private static final Sort IN_ORDER = Sort.by("position").and("id");

  private static final MembershipKind TREE = MembershipKind.STRUCTURAL;

  // --- the tree: STRUCTURAL edges only -------------------------------------------------------------

  /** A parent's children, in the order they are drawn. Served by {@code idx_entity_membership_parent_position}. */
  public List<EntityMembership> childrenOf(String parentId) {
    return find("parentId = ?1 and kind = ?2", IN_ORDER, parentId, TREE).list();
  }

  /**
   * The one structural membership a child has, or empty for a root.
   *
   * <p>At most one, and the database says so ({@code uq_entity_membership_one_parent_per_child}, a
   * partial unique index over STRUCTURAL rows since V18), so this returns an {@link Optional} rather
   * than a list.
   */
  public Optional<EntityMembership> structuralMembershipOf(String childId) {
    return find("childId = ?1 and kind = ?2", childId, TREE).firstResultOptional();
  }

  /**
   * Every structural edge under any of {@code parentIds} — one query for a whole level of a tree.
   * See {@code WorkEntityRepository.listByIds} for why the empty guard is correctness and not
   * tuning: {@code in ()} is a syntax error in postgres.
   */
  public List<EntityMembership> childrenOfAll(Collection<String> parentIds) {
    if (parentIds == null || parentIds.isEmpty()) {
      return List.of();
    }
    return find("parentId in ?1 and kind = ?2", IN_ORDER, parentIds, TREE).list();
  }

  /** Every structural edge above any of {@code childIds} — the upward half of the same bulk read. */
  public List<EntityMembership> membershipsOfAll(Collection<String> childIds) {
    if (childIds == null || childIds.isEmpty()) {
      return List.of();
    }
    return find("childId in ?1 and kind = ?2", IN_ORDER, childIds, TREE).list();
  }

  /** The last structural position in use under {@code parentId}, or {@code -1} for a childless parent. */
  public int maxPosition(String parentId) {
    return maxPosition(parentId, TREE);
  }

  /**
   * Close the gap a removed child leaves among {@code parentId}'s structural children, in one
   * statement, so positions stay dense whatever the session happens to be holding — {@link
   * DossierPageRepository#closeGapAfter}'s rule, unchanged.
   */
  public void closeGapAfter(String parentId, int position) {
    closeGapAfter(parentId, position, TREE);
  }

  // --- shared --------------------------------------------------------------------------------------

  private int maxPosition(String parentId, MembershipKind kind) {
    return find("parentId = ?1 and kind = ?2", Sort.by("position").descending(), parentId, kind)
        .firstResultOptional()
        .map(membership -> membership.position)
        .orElse(-1);
  }

  private void closeGapAfter(String parentId, int position, MembershipKind kind) {
    update(
        "position = position - 1 where parentId = ?1 and kind = ?2 and position > ?3",
        parentId,
        kind,
        position);
  }
}
