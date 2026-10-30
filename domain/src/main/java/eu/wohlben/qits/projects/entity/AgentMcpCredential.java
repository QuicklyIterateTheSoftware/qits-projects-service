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
 * The value behind one external MCP catalog credential key ({@code V72}).
 *
 * <p>An {@link AgentMcpCatalogEntry} names a {@code credentialKey} and never a value; this row is
 * where the value lives. It is written by the catalog's credential door, read only when a container's
 * document is built, and <b>never answered, logged or put in a revision snapshot</b> — the listing
 * door answers {@link #credentialKey}, {@link #updatedAt} and {@link #updatedBy} and nothing else.
 * {@link #toString} is deliberately left as {@code Object}'s so the value cannot reach a log line by
 * accident.
 */
@Entity
@Table(name = "agent_mcp_credential")
@EntityListeners(CausationStamp.class)
public class AgentMcpCredential extends PanacheEntityBase implements CausedRow {

  /** The key a catalog entry references: {@code env.<VAR>}. */
  @Id
  @Column(name = "credential_key")
  public String credentialKey;

  /** The header value, verbatim. Secret material. */
  @Column(name = "value", nullable = false, columnDefinition = "text")
  public String value;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  /** The principal behind the most recent set. */
  @Column(name = "updated_by")
  public String updatedBy;

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
