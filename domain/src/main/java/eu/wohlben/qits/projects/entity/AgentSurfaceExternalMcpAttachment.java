package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One catalog entry, attached to one surface. The whole of what a surface says about an external
 * server: <em>this one</em>, in this position.
 *
 * <p><b>A sibling of {@link AgentSurfaceMcpAttachment} rather than a row in it, and the two have
 * almost nothing in common.</b> A built-in attachment carries three narrowing booleans, a read-only
 * marker and a per-attachment tool list, because this platform builds those urls and fences them per
 * surface. An external attachment carries none of them: the url is fixed by the catalog entry, there
 * is no query narrowing this platform could apply to somebody else's server, {@code
 * agentReadOnly=true} is qits-projects' own marker and means nothing over there, and the tool list
 * belongs to the entry rather than to the surface. Sharing one table would have meant five columns
 * that are always null for half the rows and a {@code kind} column deciding which half of the schema
 * applies — the shape that eventually gets read wrong.
 *
 * <p><b>Deliberately not foreign-keyed to {@link AgentMcpCatalogEntry}.</b> Deleting an entry three
 * surfaces still attach must be a refusal an operator can read, naming those surfaces — not a
 * constraint violation arriving as a 500. The check that produces that message lives in {@code
 * control/AgentMcpCatalogService}, and an FK would make the good message unreachable and the bad one
 * inevitable.
 *
 * <p>The uniqueness that actually matters — no key attached twice to one surface, across both kinds
 * — spans two tables and is not one constraint. {@code AgentSurfaceConfigurationService} enforces it,
 * helped by the reserved-key rule that keeps the two vocabularies disjoint to begin with.
 */
@Entity
@Table(name = "agent_surface_external_mcp_attachment")
@EntityListeners(CausationStamp.class)
public class AgentSurfaceExternalMcpAttachment extends PanacheEntityBase implements CausedRow {

  /** A string UUID minted by the writing service. */
  @Id
  @Column(name = "id")
  public String id;

  /** The surface this attachment belongs to. A plain column, as its built-in sibling's is. */
  @Column(name = "surface_key", nullable = false)
  public String surfaceKey;

  /** The catalog entry attached. */
  @Column(name = "catalog_key", nullable = false)
  public String catalogKey;

  /** Render order within the surface's external servers, which follow the built-ins. */
  @Column(name = "position", nullable = false)
  public int position;

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
