package eu.wohlben.qits.entities.milestone;

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

/**
 * <b>One OR'd alternative of a {@link Milestone}</b> (epics V38): it holds once every {@link
 * MilestoneCriterion} in it has latched, and {@link #satisfiedAt} is then when its last criterion
 * latched. Cascaded with its milestone; positions are dense and zero-based per milestone, and an
 * edit replaces the lot ({@link MilestoneService#replaceGroups}).
 */
@Entity
@Table(name = "milestone_group")
@EntityListeners(CausationStamp.class)
public class MilestoneGroup extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** The platform's uniform column, never part of any constraint. */
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

  @Column(name = "milestone_id", nullable = false)
  public String milestoneId;

  @Column(nullable = false)
  public int position;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  /** When it came to hold — its last criterion's latch — or null while it does not. */
  @Column(name = "satisfied_at")
  public Instant satisfiedAt;
}
