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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A small-scoped piece of work — a bug or an improvement — owned by a project and standing beside
 * {@link Epic} rather than under it. The two are siblings on purpose: an epic is a plan with a
 * scope to freeze and a tree beneath it, and forcing "the button is the wrong colour" through that
 * shape produces a one-feature epic that no phase describes.
 *
 * <p>{@code projectId} references {@code domain}'s {@code Project} by String id — no JPA {@code
 * @ManyToOne} and no cross-DB FK (epics is a separate physical DB); existence is validated in the
 * {@code service} controller, exactly as {@link Epic} does it.
 *
 * <p><b>{@link #createdBy} is stamped, never supplied.</b> The REST layer reads it from the request
 * identity ({@code EpicsPrincipal.changedBy}) and the MCP tools from the session's, so no client
 * can claim to be somebody else. It is the same value the row's CREATE {@link AuditEntry} carries;
 * it lives here as well because a ticket list wants a reporter without a join against the log.
 *
 * <p><b>A {@link CausedRow}</b>, for the reason {@link Epic} gives: {@code TicketService} is
 * reached from the SPA and from {@code TicketMcpTools} alike, on the request thread, where the
 * scope the {@code X-Qits-Causation-Id} filter restored is still standing. An agent filing a bug it
 * found while working is exactly the flow worth tracing back.
 */
@Entity
@EntityListeners(CausationStamp.class)
public class Ticket extends PanacheEntityBase implements CausedRow {

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

  @Column(name = "project_id", nullable = false)
  public String projectId;

  /** Short label for lists and breadcrumbs. */
  @Column(nullable = false)
  public String title;

  /**
   * Git-safe path segment, minted from the title at create and never changed after — the rule
   * {@link Epic#slug} carries, and for the same reason: it is a stable address for the row, so a
   * retitled ticket must not move. Unique within the project.
   */
  @Column(nullable = false, updatable = false)
  public String slug;

  /** Bug or improvement. Editable — a report often turns out to be the other one. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public TicketType type;

  /** Open or resolved; moved only through the transition endpoint. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public TicketStatus status;

  /** Who is looking at it, as a free-text name. Null while nobody has taken it. */
  @Column(name = "assignee")
  public String assignee;

  /** The principal that filed it; null when the caller was unattributed. Never client-supplied. */
  @Column(name = "created_by", updatable = false)
  public String createdBy;

  /** The long-form Markdown body. */
  public String description;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
