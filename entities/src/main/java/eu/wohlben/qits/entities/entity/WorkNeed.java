package eu.wohlben.qits.entities.entity;

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
 * <b>A work item needs another at a status</b> (epics V40, qits-1903): {@link #entityId} waits
 * until {@link #neededEntityId} reaches {@link #status} on the walk, read by {@code
 * NeedRule.reached} and never latched. One row per pair, so an item needs another once; between the
 * needs of one item it is AND only. Cascaded with either end.
 *
 * <p>A composite key, {@link Key}. Written whole by the work doors, never by a transition — see
 * V40's header.
 */
@Entity
@Table(name = "work_need")
@IdClass(WorkNeed.Key.class)
@EntityListeners(CausationStamp.class)
public class WorkNeed extends PanacheEntityBase implements CausedRow {

  /**
   * The composite key, {@code (entity_id, needed_entity_id)}. Registered for reflection: Hibernate
   * instantiates it by its no-argument constructor, which a native image keeps only when told to.
   */
  @RegisterForReflection
  public static class Key implements Serializable {
    public String entityId;
    public String neededEntityId;

    public Key() {}

    public Key(String entityId, String neededEntityId) {
      this.entityId = entityId;
      this.neededEntityId = neededEntityId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(entityId, key.entityId)
          && Objects.equals(neededEntityId, key.neededEntityId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(entityId, neededEntityId);
    }
  }

  /** The item that waits. */
  @Id
  @Column(name = "entity_id")
  public String entityId;

  /** The item it waits for, of the same project. */
  @Id
  @Column(name = "needed_entity_id")
  public String neededEntityId;

  /** The status {@link #neededEntityId} must reach: a word of the walk, or DROPPED. */
  @Column(name = "status", nullable = false, length = 32)
  public String status;

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
