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
 * One of the platform's own MCP servers, attached to one surface with the narrowing its url carries.
 *
 * <p>Three servers exist and nothing outside them can be attached here: {@code repository}
 * (qits-projects), {@code observability} (qits-observability) and {@code actions}. External servers
 * arrive with the catalog, as their own attachment kind; the reserved keys are why an external entry
 * may not claim one of these three names.
 *
 * <p><b>The narrowing is three booleans in a fixed order, and that is faithful rather than lazy.</b>
 * Every url the two daemons build today appends its query parameters in one canonical order —
 * {@code projectId}, then {@code repositoryId}, then {@code workspaceId} — and the rendered command
 * line is asserted as a literal for both harnesses, so the order is part of the contract. These
 * three flags reproduce all five shapes in use ({@code ?projectId}, {@code
 * ?projectId&repositoryId}, {@code ?projectId&repositoryId&workspaceId}, {@code
 * ?repositoryId&workspaceId}, {@code ?repositoryId}) and cannot express an order nothing has ever
 * rendered.
 *
 * <p>{@link #readOnly} is the {@code agentReadOnly=true} marker an autonomous run appends, which
 * puts qits-projects' own tool filter in front of every mutating tool. It belongs to the surface —
 * {@code epic.autonomous} and {@code ticket.dispatch} carry it and nothing else does — which is why
 * it sits here and not on some notion of the server.
 *
 * <p><b>{@link #allowedTools} is stored but is not operator-editable</b>, and the two facts are not
 * in tension. The pre-approval lists stay shipped constants and the editor's write door never reads
 * them off a request; they are persisted because the two daemons' lists for the <em>same</em> server
 * key genuinely differ — the workspace daemon's {@code repository} list carries four write
 * exceptions ({@code add_ticket_comment}, {@code update_ticket_comment}, {@code transition_ticket},
 * {@code mark_task_implemented}) the projects daemon's does not — so a single constant keyed by
 * server could not seed both faithfully. Newline-delimited in one column: an ordered list of opaque
 * ids that nothing joins on.
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

  /** {@code repository}, {@code observability} or {@code actions}. */
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

  /** Append {@code agentReadOnly=true} — the autonomous fence. */
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
