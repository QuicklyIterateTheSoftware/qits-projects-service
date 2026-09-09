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
import java.util.List;
import java.util.UUID;

/**
 * One external MCP server, defined once for the whole platform and attached by any number of
 * surfaces.
 *
 * <p>Beside the three servers this platform owns ({@code repository}, {@code observability}, {@code
 * actions}) an operator may define servers it does not: a key, a display name, a URL, an optional
 * header name with the qits-configuration key holding its value, and the tools pre-approved for it.
 * Defined once and attached many times, so a server is not re-entered — with its token — for each of
 * eight surfaces.
 *
 * <p><b>{@link #url} and nothing else, because the render path has no room for anything else.</b>
 * Kimi carries servers protocol-native over ACP as {@code (key, url, tools)} with nowhere to put a
 * stdio command, and a stdio server would need its binary inside the workspace image anyway. There
 * is no command field here and there must not be one.
 *
 * <p><b>The three built-in keys are reserved.</b> An external entry claiming {@code repository},
 * {@code observability} or {@code actions} would silently displace a platform server in the rendered
 * {@code mcpServers} object — the session would look entirely normal and be talking to somebody
 * else's server. It is refused on write, and refused again at render in the library, because a
 * document can reach a container from an older service.
 *
 * <p><b>{@link #credentialKey} is a reference and never material.</b> It names a qits-configuration
 * entry; the value is read when a container's document is built and is not stored here, not written
 * into a revision snapshot and never logged. When qits-configuration grows its {@code secret} entry
 * class the same key is served as a secret and nothing on this entity moves — that the reference is
 * the model rather than the value is the whole design, and a {@code plain} entry today is accepted
 * on purpose.
 *
 * <p><b>The namespace.</b> qits-configuration addresses a value by {@code (env, application, key)}
 * and its key grammar is closed — {@code ConfigurationKeys.requireKey} takes {@code env.<VAR>} and
 * four indexed families and refuses the rest — so no new key prefix can be written there without
 * changing that service. The open axis is the application segment, which is also the axis the
 * concern is really about ("an MCP credential is not an env var of <em>any app</em>"): the reference
 * is {@code env.<VAR>} under the reserved application {@code qits-agent-mcp}, an application nothing
 * deploys, so no deployer ever renders these keys into a container's environment. See {@code
 * control/AgentMcpCatalog}.
 *
 * <p><b>{@link #allowedTools} is operator-editable here, unlike the built-ins'.</b> The platform
 * ships no pre-approval constant for a server it has never heard of, so the person entering the
 * server is the only one who can say which of its tools may be auto-approved. Empty is a value and
 * means "pre-approve nothing" — which, under {@code --dangerously-skip-permissions}, is the only
 * lever there is until the permission-mode knob is used.
 */
@Entity
@Table(name = "agent_mcp_catalog_entry")
@EntityListeners(CausationStamp.class)
public class AgentMcpCatalogEntry extends PanacheEntityBase implements CausedRow {

  /** The key this server renders under. One definition per key, platform-wide. */
  @Id
  @Column(name = "catalog_key")
  public String catalogKey;

  /** What an operator reads in the editor's list. Never rendered into a command. */
  @Column(name = "display_name", nullable = false, columnDefinition = "text")
  public String displayName;

  /** An {@code http://} or {@code https://} URL, validated on write. */
  @Column(name = "url", nullable = false, columnDefinition = "text")
  public String url;

  /** The header the credential is presented in. Empty means the server takes no credential. */
  @Column(name = "header_name", nullable = false, columnDefinition = "text")
  public String headerName;

  /** The qits-configuration key holding the header's value. Empty exactly when the header is. */
  @Column(name = "credential_key", nullable = false, columnDefinition = "text")
  public String credentialKey;

  /** Newline-delimited pre-approved tool ids, in render order. */
  @Column(name = "allowed_tools", nullable = false, columnDefinition = "text")
  public String allowedTools;

  /** The principal behind the most recent edit. */
  @Column(name = "updated_by")
  public String updatedBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  @Column(name = "causation_id")
  public UUID causationId;

  /** The stored column as the list it encodes. */
  public List<String> allowedToolList() {
    return AgentSurfaceMcpAttachment.decodeTools(allowedTools);
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
