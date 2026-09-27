package eu.wohlben.qits.entities.entity;

/**
 * <b>What an {@link EntityMembership} edge is</b> (V18), stored as its name behind {@code
 * ck_entity_membership_kind}.
 *
 * <ul>
 *   <li>{@link #STRUCTURAL} — the tree: epic &gt; feature &gt; task. At most one per child ({@code
 *       uq_entity_membership_one_parent_per_child}, a partial unique index since V18), and its id is
 *       the child's.
 *   <li>{@link #CAMPAIGN} — a campaign gathering work that already hangs somewhere. Any number per
 *       child, at most one per (campaign, child) ({@code uq_entity_membership_campaign_child}), and
 *       its id is random. It is <em>not</em> containment: a campaign's members are not its subtree,
 *       and nothing that reads the tree ever sees one.
 * </ul>
 */
public enum MembershipKind {
  STRUCTURAL,
  CAMPAIGN
}
