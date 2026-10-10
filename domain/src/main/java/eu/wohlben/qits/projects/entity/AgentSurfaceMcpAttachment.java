package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;

/**
 * The platform's own MCP server, attached to one surface with the narrowing its url carries.
 *
 * <p>Since qits-1174 one server can be attached here: {@code qits}, the central server. The
 * per-service servers ({@code repository}, {@code observability}, {@code actions}) are retired, and
 * V42 deleted their rows. External servers arrive with the catalog, as their own attachment kind.
 *
 * <p>The three narrowing flags are part of the configuration document's wire shape. {@code qits} is
 * scoped by the caller's bearer and carries all three off.
 *
 * <p>{@link #readOnly} is the {@code agentReadOnly=true} marker an unattended run appends, which
 * makes the central server serve only its read tools. It belongs to the surface — {@code
 * epic.autonomous} and {@code ticket.dispatch} carry it and nothing else does — which is why it sits
 * here and not on some notion of the server.
 *
 * <p><b>{@link #allowedTools} is stored but is not operator-editable.</b> The editor's write door
 * never reads it off a request. Newline-delimited in one column: an ordered list of opaque ids that
 * nothing joins on.
 *
 * <p>A {@link CausedRow} like its parent, and written in the same transaction.
 */
@Entity
@Table(name = "agent_surface_mcp_attachment")
@EntityListeners(CausationStamp.class)
public class AgentSurfaceMcpAttachment extends PanacheEntityBase implements CausedRow {

  /** A string UUID minted by the writing service. */
  @Id
  @Column(name = "id")
  public String id;

  /**
   * The surface this attachment belongs to.
   *
   * <p>A plain column and not a JPA relationship, which is this module's standing shape (see {@code
   * ReleaseRequestSource} beside it): the owning row is loaded through its own repository and the
   * set is replaced by an explicit delete-then-insert, so nothing depends on Hibernate's ordering
   * of a cascaded orphan removal against the unique {@code (surface_key, server_key)} constraint —
   * a flush that inserts before it deletes is exactly how that constraint fires on a no-op edit.
   */
  @Column(name = "surface_key", nullable = false)
  public String surfaceKey;

  /** {@code qits}. */
  @Column(name = "server_key", nullable = false)
  public String serverKey;

  /** Render order within the surface. */
  @Column(name = "position", nullable = false)
  public int position;

  @Column(name = "narrow_project", nullable = false)
  public boolean narrowProject;

  @Column(name = "narrow_repository", nullable = false)
  public boolean narrowRepository;

  @Column(name = "narrow_workspace", nullable = false)
  public boolean narrowWorkspace;

  /** Append {@code agentReadOnly=true} — the unattended fence. */
  @Column(name = "read_only", nullable = false)
  public boolean readOnly;

  /** Newline-delimited pre-approved tool ids, in render order. */
  @Column(name = "allowed_tools", nullable = false, columnDefinition = "text")
  public String allowedTools;

  @Column(name = "causation_id")
  public UUID causationId;

  /** The stored column as the list it encodes. Blank reads as no tools, never as one empty id. */
  public List<String> allowedToolList() {
    return decodeTools(allowedTools);
  }

  /** The list as the column encodes it — one id per line, no trailing newline. */
  public static String encodeTools(List<String> tools) {
    return tools == null ? "" : String.join("\n", tools);
  }

  /** The inverse of {@link #encodeTools}. */
  public static List<String> decodeTools(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return List.of();
    }
    return List.of(encoded.split("\n"));
  }

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
