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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One front-desk runner an operator declared (qits-767): a machine that registers with this
 * service, holds a socket to it and runs the project front desks it is given on its own node. A
 * copy of qits-workspaces-service's {@code WorkspaceRunner} without the workspace memory limits;
 * {@code V36__desk_runner.sql} names every column.
 *
 * <p><b>Its registration state is which of two pairs is set.</b> {@link #registrationTokenId} and
 * {@link #registrationTokenSubject} name the one-use token qits-idp commissioned at create (or at
 * the last rotation): the id so it can be deleted, the subject because the register door compares
 * the caller's {@code sub} to it. The token's value is on no column. {@link #clientId} and {@link
 * #registeredAt} are set once, by the register door.
 *
 * <p><b>{@link #capabilities} is the runner's own word about itself</b>, merged key by key on every
 * report and read through {@link DeskRunnerCapabilities}. A {@code String} attribute over a {@code
 * jsonb} column, so nothing here binds another party's payload.
 *
 * <p>Connection state is not on the row: it lives in memory, with the socket.
 *
 * <p>A {@link CausedRow}: a runner is created by an operator's request, and the REST filter's
 * restored scope is standing when it is, so the stamp fills the column on its own.
 */
@Entity
@Table(name = "desk_runner")
@EntityListeners(CausationStamp.class)
public class DeskRunner extends PanacheEntityBase implements CausedRow {

  @Id public UUID id;

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

  /** Unique, {@code [a-z][a-z0-9-]{0,63}}; see {@code DeskRunners.NAME}. */
  @Column(nullable = false)
  public String name;

  @Column public String description;

  /** How many desks this runner may hold at once; at least one. */
  @Column(nullable = false)
  public int slots;

  /** What the runner last said about itself, as a JSON object's text; null until it registers. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String capabilities;

  @Column(name = "registration_token_id")
  public String registrationTokenId;

  @Column(name = "registration_token_subject")
  public String registrationTokenSubject;

  /** The commissioned client the register door answered with, or null while unregistered. */
  @Column(name = "client_id")
  public String clientId;

  /**
   * When this runner was taken out of service, or null while it is in it. Set and cleared together
   * with {@link #quarantineReason}; {@link #slots} is never touched by either.
   */
  @Column(name = "quarantined_at")
  public Instant quarantinedAt;

  @Column(name = "quarantine_reason")
  public String quarantineReason;

  /** When the newest health check settled; null until one has. */
  @Column(name = "last_health_check_at")
  public Instant lastHealthCheckAt;

  /** Whether the newest health check passed; null until one has settled. */
  @Column(name = "last_health_check_ok")
  public Boolean lastHealthCheckOk;

  /** The node's agent login, {@code PRESENT} or {@code ABSENT}, as last probed; null until then. */
  @Column(name = "login_state")
  public String loginState;

  @Column(name = "registered_at")
  public Instant registeredAt;

  /** Host-stamped each time the runner is heard from; null until it first is. */
  @Column(name = "last_seen_at")
  public Instant lastSeenAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** Whether the register door has answered this runner. */
  public boolean registered() {
    return clientId != null;
  }

  /** Whether this runner is out of service: it takes no desk but its own health check. */
  public boolean quarantined() {
    return quarantinedAt != null;
  }
}
