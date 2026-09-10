package eu.wohlben.qits.projects.entity;

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
 * One HTML design kept with a refinement — a self-contained document of a single page, with its
 * styles inline, that the refining route's Design tab shows beside the epic being drafted.
 *
 * <p><b>It is a document, not a proposal.</b> There is no lifecycle: an agent and a person write
 * and rewrite the same row, the way they both write the epic's description. The gate on a draft is
 * the epic's own {@code REFINING → IMPLEMENTATION} transition, which a person controls and which
 * freezes the whole plan at once. {@link #version} is what keeps two writers from silently
 * overwriting each other — a write carrying a stale version is refused, never merged.
 *
 * <p><b>It is never served as a page.</b> There is no content route on this table and there must
 * not be one: the HTML is agent-authored, and same-origin delivery would make every design an XSS
 * door into the platform's own session. The SPA renders it in a sandboxed iframe with scripts off,
 * which is why these bytes travel as a JSON field and nothing else. That rule is scoped, not
 * absolute: a <em>copy</em> of a design, in the epics database as a dossier asset, is served from
 * one hardened route which sets {@code Content-Security-Policy: sandbox} on the response itself, so
 * the document lands in an opaque origin even when the URL is opened directly. This table's own
 * bytes stay off the wire as HTML.
 *
 * <p>Cascades with the refinement, like the prompt draft and attachments beside it: a design has no
 * life of its own once the refinement it belongs to is discarded. A dossier page that inlined it
 * survives that discard, because the dossier holds a copy rather than a reference.
 *
 * <p><b>A {@link CausedRow}.</b> A write is minted on the request thread that carried the agent's
 * tool call, so the stamp records what asked for it — the one trace tying a design back to the turn
 * that wrote it.
 */
@Entity
@Table(name = "refinement_design")
@EntityListeners(CausationStamp.class)
public class RefinementDesign extends PanacheEntityBase implements CausedRow {

  /** A string UUID minted by the control class. */
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "refinement_id_fk", nullable = false)
  public Long refinementId;

  @Column(name = "title", nullable = false)
  public String title;

  /** The route in the framed application this design was captured from, if one was known. */
  @Column(name = "source_route")
  public String sourceRoute;

  /** The whole document, styles inline. */
  @Column(name = "html", nullable = false, columnDefinition = "text")
  public String html;

  /** UTF-8 length of {@link #html} — what a list shows without carrying the document. */
  @Column(name = "html_bytes", nullable = false)
  public int htmlBytes;

  /** The capture was cut short, so the document is a partial page. */
  @Column(name = "truncated", nullable = false)
  public boolean truncated;

  /** Bumped on every write; a write carrying a stale value is refused with a conflict. */
  @Column(name = "version", nullable = false)
  public long version;

  @Column(name = "created_by", nullable = false)
  public String createdBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

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
}
