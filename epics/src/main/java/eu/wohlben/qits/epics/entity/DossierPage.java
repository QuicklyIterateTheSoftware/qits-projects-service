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

/**
 * One page of an epic's dossier — the epic's long form. The epic's description is the value pitch;
 * the dossier is the breakdown, with examples, and a page is one section of it.
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
 * <p><b>Flat, and deliberately so.</b> There is no {@code parentId}: the second level of the tab's
 * navigation is this page's own {@code h1}/{@code h2}/{@code h3}, derived in the browser from the
 * rendered markdown and stored nowhere. It cannot drift from the page, because it <em>is</em> the
 * page.
 *
 * <p><b>{@link #version} is what stands in for an acceptance step.</b> Nothing on this route accepts
 * a write, so a person editing in the SPA while an agent writes from a prompt is the ordinary case;
 * a write carrying a stale version is refused with the current page attached, never merged.
 *
 * <p>Public fields and no getters, matching {@link Epic} and {@link Feature}; the id is a string
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

  /** The owning epic. A real intra-module FK: deleting the epic takes its dossier. */
  @Column(name = "epic_id", nullable = false)
  public String epicId;

  /**
   * Minted from the title at create and never changed after — the rule {@link Epic#slug} follows,
   * and for the same reason: it is in URLs people have already sent each other. Unique per epic.
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
