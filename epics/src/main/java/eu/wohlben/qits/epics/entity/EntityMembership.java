package eu.wohlben.qits.epics.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * <b>The parent/child relation as a row of its own</b> (V9) — the second half of the merge, and the
 * half that makes promotion possible.
 *
 * <p>Today the edge is a column on the child: {@code feature.epic_id}, {@code task.feature_id}. That
 * bakes the shape of the tree into the child's table, and it is why a feature cannot become an epic
 * — an epic has no {@code epic_id}, so the promotion would have to move the row between tables while
 * every id pointing at it stayed behind. Here the edge is a row, so re-archetyping an entity and
 * re-pointing its membership are two ordinary updates a single transaction makes together. That is
 * exactly what the multi-entity transition API exists to offer, and exactly why the nesting rule has
 * to be checked over a <em>post-state</em> rather than one row at a time: neither half of a
 * promotion is legal on its own.
 *
 * <p><b>At most one parent per child</b>, said by {@code uq_entity_membership_one_parent_per_child}
 * — the hierarchy is a tree. The campaign work widens that with an overlapping membership kind (a
 * campaign gathers rows that already hang somewhere else), which is a {@code kind} column on this
 * table and that constraint becoming partial; the constraint is named for what it asserts rather
 * than for the column it covers so the relaxation reads as a relaxation.
 *
 * <p><b>Both ends cascade.</b> An edge to a row that is gone is not a fact about anything. The
 * services still tear subtrees down in-service so every removed row gets its own {@link AuditEntry}
 * — V1's stated reading of {@code fk_feature_epic}, unchanged — and the cascade is the safety net
 * for a row removed outside them.
 *
 * <p><b>A {@link CausedRow}</b> like every other row in this module, and not incidentally: an edge
 * is created by the same request that creates the child, and a reparent is a change somebody or
 * something asked for. A membership with no recorded cause is a tree that moved with nothing saying
 * why.
 */
@Entity
@Table(name = "entity_membership")
@EntityListeners(CausationStamp.class)
public class EntityMembership extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  /** The containing entity. A real intra-module FK, {@code on delete cascade}. */
  @Column(name = "parent_id", nullable = false)
  public String parentId;

  /**
   * The contained entity. A real intra-module FK, {@code on delete cascade}, and unique across the
   * table — see the class javadoc for what that asserts and what will relax it.
   */
  @Column(name = "child_id", nullable = false)
  public String childId;

  /**
   * Where this child sits among its parent's children. <b>Dense and zero-based</b>, the way {@link
   * DossierPage#position} already is: the service renumbers the affected span on a move and closes
   * the gap a removal leaves, so the numbers are an order and never a sparse key.
   *
   * <p>It is on the <em>edge</em> rather than on the child, which is the placement the overlapping
   * campaign membership needs: a row that belongs to two things has two positions, one per
   * membership, and a column on the child could hold only one of them.
   */
  @Column(nullable = false)
  public int position;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
