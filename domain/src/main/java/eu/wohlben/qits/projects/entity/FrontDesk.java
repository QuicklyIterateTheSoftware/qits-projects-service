package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A project's desk (qits-767, V37): an agent container, as this service wants it and as the
 * front-desk runner holding it last reported it.
 *
 * <p><b>A project has a desk per {@link DeskRole}</b> (qits-1482, V49): its FRONT desk, and while its
 * delivery loop is enabled a DELIVERY desk the loop runs in alone. The row is keyed by {@link
 * #deskId} — {@link DeskRole#deskId}: the project id for the FRONT desk, {@code
 * <projectId>-delivery} for the DELIVERY one — and everything keyed by a desk (the runner's entries
 * and reports, the control socket, the proxy, the spec roll) is keyed by it.
 *
 * <ul>
 *   <li><b>Placement</b> — {@link #runnerId} is null until a runner's {@code reserve} claims the row
 *       (compare-and-swap on {@code runner_id is null}, oldest {@link #queuedAt} first), and only a
 *       {@code DELETE} of the desk, the project's deletion or the runner's deletion ever changes it
 *       again: a desk is sticky to its runner.
 *   <li><b>Desired state</b> — {@link #desired}, recomputed from the project's {@code
 *       front_desk_lifecycle} and from {@link #lastDemandAt} by the front-desk demand.
 *   <li><b>The token</b> — one {@code qits_tok_} per desk, minted at row creation and carried in the
 *       spec: {@link #tokenId} to revoke it, {@link #tokenValue} so the spec is reproducible,
 *       {@link #tokenSubject} because the control socket and the dial-back bind a {@code tok-}
 *       bearer to it. The {@code delivery_token_*} columns hold a second one, the delivery
 *       loop's, only while the project's loop is enabled (epic qits-1440).
 *   <li><b>The spec</b> — {@link #specJson} is the spec last <em>sent</em> (the applied one) and
 *       {@link #specHash} its SHA-256 over canonical JSON; the runner labels the container with the
 *       hash and recreates it when an estate carries another.
 *   <li><b>The report</b> — {@code reported_*} is the runner's last inventory of the desk; {@link
 *       #failureDetail} why it is not usable, when it is not.
 * </ul>
 *
 * <p>{@link Uncaused}: machine state derived from the project and from demand, written by sweeps
 * and socket frames off any request scope, so a stamp would record null for ever.
 */
@Entity
@Table(name = "front_desk")
@Uncaused
public class FrontDesk extends PanacheEntityBase {

  /** The desk's key: {@link DeskRole#deskId} of its project and role. */
  @Id
  @Column(name = "desk_id")
  public String deskId;

  @Column(name = "project_id", nullable = false)
  public String projectId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public DeskRole role = DeskRole.FRONT;

  @Column(name = "runner_id")
  public UUID runnerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public FrontDeskDesired desired = FrontDeskDesired.STOPPED;

  @Column(name = "last_demand_at")
  public Instant lastDemandAt;

  @Column(name = "queued_at")
  public Instant queuedAt;

  @Column(name = "placed_at")
  public Instant placedAt;

  @Column(name = "token_id")
  public String tokenId;

  @Column(name = "token_value")
  public String tokenValue;

  @Column(name = "token_subject")
  public String tokenSubject;

  /**
   * The delivery token (epic qits-1440, V48): a second {@code qits_tok_}, of idp context kind {@code
   * desk-delivery}, held by the project's DELIVERY desk (qits-1482) — which exists only while the
   * project's {@code delivery_enabled} is true and goes, token revoked, when it is disabled. The
   * desk's {@code project.delivery} session carries it; nothing else does. A FRONT desk holds none:
   * one left from before V49 is revoked and cleared.
   */
  @Column(name = "delivery_token_id")
  public String deliveryTokenId;

  @Column(name = "delivery_token_value")
  public String deliveryTokenValue;

  @Column(name = "delivery_token_subject")
  public String deliveryTokenSubject;

  /**
   * The FRONT desk's session token (qits-1482, V49): a {@code qits_tok_} of idp context kind {@code
   * desk-session} ({@code qits:agent}), claims {@code {project}}, minted beside the desk token and
   * revoked with the row. It is in no container's environment: the daemon is to receive it over the
   * control socket, so the desk's sessions run on a principal other than the daemon's.
   */
  @Column(name = "session_token_id")
  public String sessionTokenId;

  @Column(name = "session_token_value")
  public String sessionTokenValue;

  @Column(name = "session_token_subject")
  public String sessionTokenSubject;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "spec_json", columnDefinition = "jsonb")
  public String specJson;

  @Column(name = "spec_hash")
  public String specHash;

  @Column(name = "reported_state")
  public String reportedState;

  @Column(name = "reported_spec_hash")
  public String reportedSpecHash;

  @Column(name = "reported_at")
  public Instant reportedAt;

  @Column(name = "failure_detail")
  public String failureDetail;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** Whether a runner holds the desk. */
  public boolean placed() {
    return runnerId != null;
  }

  /** A new, unplaced, stopped row of {@code projectId}'s desk of {@code role}. */
  public static FrontDesk of(String projectId, DeskRole role) {
    FrontDesk row = new FrontDesk();
    row.deskId = role.deskId(projectId);
    row.projectId = projectId;
    row.role = role;
    return row;
  }

  /** The desk's role; a row built by hand without one is a FRONT desk. */
  public DeskRole role() {
    return role == null ? DeskRole.FRONT : role;
  }

  /** The desk's key; a row built by hand without one is keyed by its role and project. */
  public String deskId() {
    return deskId != null ? deskId : role().deskId(projectId);
  }

  /** Whether the desk should be running. */
  public boolean wanted() {
    return desired == FrontDeskDesired.RUNNING;
  }
}
