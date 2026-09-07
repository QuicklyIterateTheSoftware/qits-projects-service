package eu.wohlben.qits.epics.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One append-only audit row per create/update/delete of an epic/feature/task — the git replacement
 * ("who changed what, when"). Deliberately NOT FK'd back to the live entity (a DELETE row must
 * survive the row it describes). {@code snapshot} is a JSON copy of the entity's changed/current
 * fields.
 *
 * <p><b>A {@link CausedRow}, and the one that covers what the live rows cannot.</b> {@link
 * #changedBy} answers <em>who</em>; this column answers <em>because of what</em>. Epic, Feature and
 * Task each carry the column too, but the stamp is insert-only there: a live row records the cause
 * of its own creation and never the cause of an update, and a deleted row is gone while its DELETE
 * entry here stays. Every audit row is an insert, written by {@code AuditService.record} inside the
 * mutating method's transaction and on its thread — a REST request or an MCP tool call — so the
 * {@code CausationStamp} listener reads the scope the {@code X-Qits-Causation-Id} filter restored.
 * An agent driving the epics surface with a cause leaves a traceable change; a person clicking in
 * the SPA sends no header and the row is rootless, which is correct rather than missing.
 */
@Entity
@EntityListeners(CausationStamp.class)
public class AuditEntry extends PanacheEntityBase implements CausedRow {

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

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false)
  public AuditEntityType entityType;

  @Column(name = "entity_id", nullable = false)
  public String entityId;

  /**
   * The owning epic id for every row (an epic's own rows carry their own id). Lets the epic audit
   * endpoint query the whole subtree's history by a single column — surviving deletion of the live
   * rows, which live-row joins could not.
   *
   * <p><b>It is the subtree key, not a foreign key</b>, and V4's tickets are what make the
   * distinction visible: a {@link Ticket} belongs to no epic, so its rows and its comments' rows
   * carry the <em>ticket's</em> id here. The column keeps its name because renaming an applied
   * migration's column across a live log buys nothing — what it has always meant is "the root this
   * change hangs under", and a ticket is its own root exactly as an epic is.
   */
  @Column(name = "epic_id", nullable = false)
  public String epicId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public AuditOperation operation;

  /** The authenticated principal that made the change (null if unattributed). */
  @Column(name = "changed_by")
  public String changedBy;

  @CreationTimestamp
  @Column(name = "changed_at", nullable = false, updatable = false)
  public Instant changedAt;

  /** JSON snapshot of the entity's fields at the time of the change. */
  public String snapshot;
}
