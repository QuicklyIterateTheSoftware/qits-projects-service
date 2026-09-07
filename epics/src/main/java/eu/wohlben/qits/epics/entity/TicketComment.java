package eu.wohlben.qits.epics.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One remark on a {@link Ticket} — the conversation an epic does not have, because an epic's
 * discussion is its description and its tree while a ticket's is a thread of people saying what
 * they found. {@code ticketId} is a real intra-module FK (cascade-deleted with the ticket).
 *
 * <p><b>Read oldest first, always.</b> A thread is a sequence and reading it backwards is reading a
 * different thread; {@code TicketCommentRepository} sorts on {@code createdAt} with the id as the
 * tie-break, so two remarks written in one transaction still come back in a stable order. That is
 * the opposite of {@link AuditEntry}'s newest-first, and deliberately: a log is scanned from the
 * top, a conversation is read from the start.
 *
 * <p><b>{@link #author} is stamped, never supplied</b>, exactly as {@link Ticket#createdBy} is.
 *
 * <p><b>A {@link CausedRow}</b>, for the reason {@link Ticket} gives.
 */
@Entity
@EntityListeners(CausationStamp.class)
public class TicketComment extends PanacheEntityBase implements CausedRow {

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

  @Column(name = "ticket_id", nullable = false, updatable = false)
  public String ticketId;

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
