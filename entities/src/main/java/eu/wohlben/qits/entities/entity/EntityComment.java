package eu.wohlben.qits.entities.entity;

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
 * One remark on a {@link WorkEntity} of any archetype — the writable log beside the plan. A ticket's
 * thread is the people saying what they found; an epic's, feature's, task's or campaign's is where a
 * finding goes once the scope and the dossier are frozen (qits-551), because the plan is the thing
 * the freeze protects and the thread is not the plan. {@code entityId} is a real intra-module FK
 * (cascade-deleted with the entity).
 *
 * <p>It was {@code TicketComment} until V20: the table's key had named {@code entity (id)} since V12,
 * so the rename moved words and not rows.
 *
 * <p><b>Read oldest first, always.</b> A thread is a sequence and reading it backwards is reading a
 * different thread; {@code EntityCommentRepository} sorts on {@code createdAt} with the id as the
 * tie-break, so two remarks written in one transaction still come back in a stable order. That is
 * the opposite of {@link AuditEntry}'s newest-first, and deliberately: a log is scanned from the
 * top, a conversation is read from the start.
 *
 * <p><b>{@link #author} is stamped, never supplied</b>, exactly as {@link WorkEntity#createdBy} is.
 *
 * <p><b>A {@link CausedRow}</b>, for the reason {@link WorkEntity} gives.
 */
@Entity
@Table(name = "entity_comment")
@EntityListeners(CausationStamp.class)
public class EntityComment extends PanacheEntityBase implements CausedRow {

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

  /** The entity the remark is on, of any archetype. */
  @Column(name = "entity_id", nullable = false, updatable = false)
  public String entityId;

  /** The principal that wrote it; null when the caller was unattributed. */
  @Column(name = "author", updatable = false)
  public String author;

  /** The remark itself, Markdown. Never blank — an empty comment is a comment nobody made. */
  @Column(nullable = false)
  public String body;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
