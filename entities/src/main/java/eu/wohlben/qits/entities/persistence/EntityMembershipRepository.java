package eu.wohlben.qits.entities.persistence;

import eu.wohlben.qits.entities.entity.EntityMembership;
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
 * FeatureService} and {@code TaskService} write an edge per create and remove one per delete —
 * {@link #maxPosition} to append, {@link #closeGapAfter} to keep the survivors dense — {@link
 * #membershipOf} is how either of them answers "what is this part of", {@link #childrenOf} is the
 * order a listing is drawn in, and {@code EpicService}'s three subtree walks fan out with {@link
 * #childrenOfAll} a level at a time. Every read here is bulk by shape for that last reason: a merged
 * tree walked one node per query is the one performance mistake this model makes easy.
 */
@ApplicationScoped
public class EntityMembershipRepository
    implements PanacheRepositoryBase<EntityMembership, String> {

  private static final Sort IN_ORDER = Sort.by("position").and("id");

  /** A parent's children, in the order they are drawn. Served by {@code idx_entity_membership_parent_position}. */
  public List<EntityMembership> childrenOf(String parentId) {
    return find("parentId", IN_ORDER, parentId).list();
  }

  /**
   * The one membership a child has, or empty for a root.
   *
   * <p>At most one, and the database says so ({@code uq_entity_membership_one_parent_per_child}), so
   * this returns an {@link Optional} rather than a list. When the campaign work relaxes that
   * constraint into a partial one, this method keeps its signature and gains a kind — the caller
   * asking "what is this row part of, structurally" is asking a question that still has one answer.
   */
  public Optional<EntityMembership> membershipOf(String childId) {
    return find("childId", childId).firstResultOptional();
  }

  /**
   * Every edge under any of {@code parentIds} — one query for a whole level of a tree. See {@code
   * WorkEntityRepository.listByIds} for why the empty guard is correctness and not tuning: {@code in
   * ()} is a syntax error in postgres.
   */
  public List<EntityMembership> childrenOfAll(Collection<String> parentIds) {
    if (parentIds == null || parentIds.isEmpty()) {
      return List.of();
    }
    return find("parentId in ?1", IN_ORDER, parentIds).list();
  }

  /** Every edge above any of {@code childIds} — the upward half of the same bulk read. */
  public List<EntityMembership> membershipsOfAll(Collection<String> childIds) {
    if (childIds == null || childIds.isEmpty()) {
      return List.of();
    }
    return find("childId in ?1", IN_ORDER, childIds).list();
  }

  /** The last position in use under {@code parentId}, or {@code -1} for a childless parent. */
  public int maxPosition(String parentId) {
    return find("parentId", Sort.by("position").descending(), parentId)
        .firstResultOptional()
        .map(membership -> membership.position)
        .orElse(-1);
  }

  /**
   * Close the gap a removed child leaves, in one statement, so positions stay dense whatever the
   * session happens to be holding — {@link DossierPageRepository#closeGapAfter}'s rule, unchanged.
   */
  public void closeGapAfter(String parentId, int position) {
    update("position = position - 1 where parentId = ?1 and position > ?2", parentId, position);
  }
}
