package eu.wohlben.qits.entities.entity;

/**
 * <b>What an {@link EntityMembership} edge is</b> (V18), stored as its name behind {@code
 * ck_entity_membership_kind}.
 *
 * <ul>
 *   <li>{@link #STRUCTURAL} — the tree: epic &gt; feature &gt; task. At most one per child ({@code
 *       uq_entity_membership_one_parent_per_child}, a partial unique index since V18), and its id is
 *       the child's.
 * </ul>
 *
 * <p>The second kind, the gathering edge, was retired by qits-1899 (V41) with what it served; the
 * column and the enum stay, so a new kind of edge is one constant and one widened check.
 */
public enum MembershipKind {
  STRUCTURAL
}
