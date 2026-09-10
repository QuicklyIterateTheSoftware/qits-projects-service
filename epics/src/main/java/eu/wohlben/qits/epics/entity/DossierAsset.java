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
 * One figure a dossier page inlines — an image from the Sketch tab, or a design from the Design tab
 * — <b>copied into the epic</b>, bytes and all.
 *
 * <p><b>The id is the source figure's id.</b> Not a fresh UUID: a markdown URL written once stays
 * valid, re-inlining the same figure is idempotent, and the "in use / dangling" filter on the two
 * source tabs becomes one id join. Two pages inlining the same sketch are one row here.
 *
 * <p><b>The bytes are a snapshot.</b> Rewriting the design upstream does not change a page that
 * already argued from it, which is what lets a dossier read months later as the argument it was.
 *
 * <p>The copy is what makes the dossier survive its container: the sources cascade away when the
 * refinement is discarded, and a page that linked to them would go blank on exactly the day the
 * plan matters most.
 */
@Entity
@Table(name = "dossier_asset")
@EntityListeners(CausationStamp.class)
public class DossierAsset extends PanacheEntityBase implements CausedRow {

  /** What the renderer draws: an {@code <img>} or a sandboxed {@code <iframe>}. */
  public enum Kind {
    IMAGE,
    DESIGN
  }

  /** The SOURCE figure's id, deliberately reused — see the class javadoc. */
  @Id public String id;

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

  @Column(name = "epic_id", nullable = false)
  public String epicId;

  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  @Column(nullable = false)
  public Kind kind;

  /** Served verbatim from the row; the content route never sniffs one. */
  @Column(name = "mime_type", nullable = false)
  public String mimeType;

  /** The figure's own name, which becomes the alt text of the line the inline door hands back. */
  @Column(nullable = false)
  public String label;

  @Column(nullable = false)
  public byte[] bytes;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
