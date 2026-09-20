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

/**
 * One page of a dossier — the long form of an epic <em>or</em> of a ticket. The owner's own prose is
 * the short form (an epic's description is the value pitch, a ticket's is what the refine phase
 * worked out); the dossier is the breakdown, with examples, and a page is one section of it.
 *
 * <p><b>A dossier page belongs to the EPIC, not to the refinement that produced it.</b> That is the
 * whole reason this entity is in this module. A page is written on the refining route and inlines
 * that route's sketches and designs, so the tempting home is beside {@code RefinementDesign} in
 * {@code domain} — but a refinement is a container, and discarding it cascades everything hanging
 * off it away. The plan has to outlive the container, and implementation reads it months later when
 * no refinement is open at all. Living here, it inherits the {@code REFINING}-only mutation guard
 * that already freezes features and tasks, an {@code AuditEntry} beside every change, and the
 * causation stamp; and a refinement discard cannot touch it.
 *
 * <p><b>A ticket's dossier is the same argument one level down.</b> It is what the refine phase
 * writes when the ticket's own body cannot hold it — an error scenario crossing four services, a
 * sequence worth a figure — and it outlives the workspace that phase ran in for exactly the reason
 * an epic's outlives the refinement: the implement phase reads it from a container that no longer
 * exists, and the verify phase reads it again after that. What does <em>not</em> carry over is the
 * freeze: a ticket commits to no scope ({@code TicketLifecycle}'s first sentence), so its pages are
 * writable at every status.
 *
 * <p><b>Flat, and deliberately so.</b> There is no {@code parentId}: the second level of the tab's
 * navigation is this page's own {@code h1}/{@code h2}/{@code h3}, derived in the browser from the
 * rendered markdown and stored nowhere. It cannot drift from the page, because it <em>is</em> the
 * page.
 *
 * <p><b>{@link #version} is what stands in for an acceptance step.</b> Nothing on this route accepts
 * a write, so a person editing in the SPA while an agent writes from a prompt is the ordinary case;
 * a write carrying a stale version is refused with the current page attached, never merged.
 *
 * <p>Public fields and no getters, matching {@link WorkEntity}; the id is a string
 * minted by the service, like every other id in this module.
 */
@Entity
@Table(name = "dossier_page")
@EntityListeners(CausationStamp.class)
public class DossierPage extends PanacheEntityBase implements CausedRow {

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

  /**
   * The owning epic, or null when a ticket owns this page. <b>Exactly one of {@link #epicId} and
   * {@link #ticketId} is set</b>, and the database says so too ({@code ck_dossier_page_owner}, V8);
   * {@link DossierOwner} is the value that makes it unconstructable otherwise.
   *
   * <p>An epic owner means the page is part of a plan: it is written while the epic is {@code
   * REFINING}, frozen with the rest of the scope, and read by the agent implementing it.
   *
   * <p>A real intra-module FK, like {@link #ticketId}: deleting the epic takes its dossier.
   */
  @Column(name = "epic_id")
  public String epicId;

  /**
   * The owning ticket, or null when an epic owns this page — see {@link #epicId} for the rule.
   *
   * <p>A ticket owner means the page is what the refine phase wrote when the ticket's own body could
   * not hold it. Nothing about a ticket freezes, so these pages are writable while the ticket is
   * {@code REPORTED}, {@code IMPLEMENTED} and {@code DONE} alike.
   *
   * <p>A real FK for the reason {@link #epicId} is one: {@code Ticket} is in this database too, so
   * deleting a ticket takes its pages with it rather than leaving them addressable under an id
   * nothing answers to.
   */
  @Column(name = "ticket_id")
  public String ticketId;

  /**
   * Minted from the title at create and never changed after — the rule {@link WorkEntity#slug} follows,
   * and for the same reason: it is in URLs people have already sent each other. Unique <b>per
   * owner</b>: the same slug under an epic and under a ticket is two different addresses.
   */
  @Column(nullable = false, updatable = false)
  public String slug;

  @Column(nullable = false)
  public String title;

  /** Dense and zero-based. The service renumbers the affected span on a move. */
  @Column(nullable = false)
  public int position;

  /** The markdown, figures inlined as ordinary image syntax. */
  @Column(nullable = false, columnDefinition = "text")
  public String body;

  /** Bumped by the service on every write; a stale write is refused. */
  @Column(nullable = false)
  public long version;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
