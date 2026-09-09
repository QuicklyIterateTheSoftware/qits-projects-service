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
 * One append-only revision of a surface's configuration — who changed it, when, and what it said
 * afterwards.
 *
 * <p><b>The shape is {@code AuditEntry}'s</b>, this estate's answer for edited text that needs a
 * history and an author, and it is copied here rather than reinvented: one row per write, the acting
 * principal beside a JSON snapshot of the whole thing as it stood. What makes it worth having is
 * that a system prompt is text somebody words, rewords, and later wants the earlier wording of — and
 * the question asked after a session behaved oddly is always "what did this say last week, and who
 * changed it".
 *
 * <p><b>Deliberately not foreign-keyed</b> to {@link AgentSurfaceConfiguration}, for the reason
 * {@code AuditEntry} is not keyed to its epic: a surface retired from the vocabulary takes its row
 * with it, and the trail of what it used to be configured as must survive that. {@link #surfaceKey}
 * is a key, not a relation.
 *
 * <p>The snapshot is the <em>whole</em> configuration after the change, MCP attachments included, so
 * a revision reads on its own rather than as a delta needing every row before it. Diffing is the
 * reader's job.
 *
 * <p>A {@link CausedRow}: the insert runs inside the write's own transaction, on the request thread
 * that carried the edit, so the stamp records what asked for it. The boot seed writes one too, with
 * a null {@link #changedBy} — unattributed, which is the honest answer for a row nobody edited.
 */
@Entity
@Table(name = "agent_surface_configuration_revision")
@EntityListeners(CausationStamp.class)
public class AgentSurfaceConfigurationRevision extends PanacheEntityBase implements CausedRow {

  /** A string UUID minted by the writing service. */
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "surface_key", nullable = false)
  public String surfaceKey;

  /** The authenticated principal that made the change; null when the write was unattributed. */
  @Column(name = "changed_by")
  public String changedBy;

  @Column(name = "changed_at", nullable = false)
  public Instant changedAt;

  /** JSON of the whole configuration as it stood after this change. */
  @Column(name = "snapshot", nullable = false, columnDefinition = "text")
  public String snapshot;

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
