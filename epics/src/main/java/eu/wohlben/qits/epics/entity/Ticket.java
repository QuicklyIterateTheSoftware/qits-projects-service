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

  /**
   * What has been achieved so far — see {@link TicketStatus} for the five words and for the phase
   * each of them starts. Moved only through the transition endpoint, one step at a time.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public TicketStatus status;

  /** Who is looking at it, as a free-text name. Null while nobody has taken it. */
  @Column(name = "assignee")
  public String assignee;

  /** The principal that filed it; null when the caller was unattributed. Never client-supplied. */
  @Column(name = "created_by", updatable = false)
  public String createdBy;

  /**
   * <b>Why this ticket exists, in the reporter's or the triage agent's own words.</b> It is the
   * high-level statement of what brought the ticket about, and it is what a {@link
   * TicketStatus#REPORTED} ticket consists of — an impetus and nothing else.
   *
   * <p><b>The length rule, which is the whole of the field's discipline.</b> An impetus takes one
   * of two shapes — <em>"{some error} occurs {in some context}"</em> or <em>"{an existing part}
   * should be {something to introduce or improve}"</em> — and is almost always one sentence, rarely
   * a paragraph, very rarely two. A bug's steps to reproduce may be included and do not count
   * against that length: they are part of saying what occurs.
   *
   * <p><b>Why the rule exists.</b> An impetus that grows into an essay is indistinguishable from
   * the refined {@link #description}, and at that point it stops being a record of what was
   * originally asked for — which is the one thing nothing else in the row holds. Two fields that
   * say the same thing leave the next reader to work out which one the work was actually agreed
   * against.
   *
   * <p><b>It is never rewritten by a later phase.</b> Refinement writes {@link #description};
   * implementation and verification write neither. It stays editable by triage, because a report
   * filed in haste is often the wrong words for the right problem — but an edit is a correction of
   * what was asked for, never a restatement of what was later decided.
   *
   * <p>Nullable in the column and required at the intake surfaces: rows that predate V7 have none,
   * and a migration cannot invent what somebody meant.
   */
  @Column(name = "impetus")
  public String impetus;

  /**
   * <b>The refinement's output</b> — the long-form Markdown statement of what to do about {@link
   * #impetus}, written by the refine phase rather than supplied at intake. Null on a {@link
   * TicketStatus#REPORTED} ticket, and its presence is what {@link TicketStatus#REFINED} claims.
   */
  public String description;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
