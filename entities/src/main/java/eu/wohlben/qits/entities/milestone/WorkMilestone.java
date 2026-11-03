package eu.wohlben.qits.entities.milestone;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * <b>A work entity waits on a milestone</b> (epics V38): one row per (entity, milestone). Between
 * the milestones of one entity it is AND only — it waits while any of them is unmet. Cascaded with
 * either end.
 */
@Entity
@Table(name = "work_milestone")
@IdClass(WorkMilestone.Key.class)
@EntityListeners(CausationStamp.class)
public class WorkMilestone extends PanacheEntityBase implements CausedRow {

  /**
   * The composite key, {@code (entity_id, milestone_id)}. Registered for reflection: Hibernate
   * instantiates it by its no-argument constructor, which a native image keeps only when told to.
   */
  @RegisterForReflection
  public static class Key implements Serializable {
    public String entityId;
    public String milestoneId;

    public Key() {}

    public Key(String entityId, String milestoneId) {
      this.entityId = entityId;
      this.milestoneId = milestoneId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(entityId, key.entityId)
          && Objects.equals(milestoneId, key.milestoneId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(entityId, milestoneId);
    }
  }

  @Id
  @Column(name = "entity_id")
  public String entityId;

  @Id
  @Column(name = "milestone_id")
  public String milestoneId;

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

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;
}
