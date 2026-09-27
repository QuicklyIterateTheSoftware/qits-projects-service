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
 * <p><b>Every tree read is STRUCTURAL-only, and says so in its query</b> (V18). The table carries
 * campaign edges too, and a campaign's members are not its children: a subtree delete that followed
 * one would delete work the campaign merely gathers, a reshape that rewrote one would wipe it, and a
 * listing that drew one would show a ticket as part of something it is not part of. So the tree
 * methods keep their names and gain a kind filter, the one whose old meaning ("the membership")
 * stopped being singular is renamed to {@link #structuralMembershipOf}, and the campaign reads are
 * separate methods — {@link #campaignMembers}, {@link #campaignMembershipsOf}, {@link
 * #campaignMaxPosition}, {@link #campaignCloseGapAfter} — so no caller asking about the tree can see
 * a CAMPAIGN row by accident.
 */
@ApplicationScoped
public class EntityMembershipRepository
    implements PanacheRepositoryBase<EntityMembership, String> {

  private static final Sort IN_ORDER = Sort.by("position").and("id");

  private static final MembershipKind TREE = MembershipKind.STRUCTURAL;

  private static final MembershipKind GATHERED = MembershipKind.CAMPAIGN;

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
   * than a list. Renamed from {@code membershipOf} when campaign edges arrived, so that no caller
   * kept asking "the membership" of a row that may now have several.
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

  // --- campaigns: CAMPAIGN edges only --------------------------------------------------------------

  /** A campaign's members, in the order it runs them — position, then id. */
  public List<EntityMembership> campaignMembers(String campaignId) {
    return find("parentId = ?1 and kind = ?2", IN_ORDER, campaignId, GATHERED).list();
  }

  /**
   * The members of every campaign in {@code campaignIds}, in one query — a project's campaign listing
   * counts them without a query per campaign. The empty guard is {@link #childrenOfAll}'s.
   */
  public List<EntityMembership> campaignMembersOfAll(Collection<String> campaignIds) {
    if (campaignIds == null || campaignIds.isEmpty()) {
      return List.of();
    }
    return find("parentId in ?1 and kind = ?2", IN_ORDER, campaignIds, GATHERED).list();
  }

  /** Every campaign {@code childId} has joined, one edge per campaign, in (position, id) order. */
  public List<EntityMembership> campaignMembershipsOf(String childId) {
    return find("childId = ?1 and kind = ?2", IN_ORDER, childId, GATHERED).list();
  }

  /** The last member position in use in {@code campaignId}, or {@code -1} for an empty campaign. */
  public int campaignMaxPosition(String campaignId) {
    return maxPosition(campaignId, GATHERED);
  }

  /** {@link #closeGapAfter}'s rule over one campaign's members. */
  public void campaignCloseGapAfter(String campaignId, int position) {
    closeGapAfter(campaignId, position, GATHERED);
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
