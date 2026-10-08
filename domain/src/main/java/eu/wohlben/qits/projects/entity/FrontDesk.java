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
 * A project's front desk (qits-767, V37): its agent container, as this service wants it and as the
 * front-desk runner holding it last reported it.
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
 *       bearer to it.
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

  @Id
  @Column(name = "project_id")
  public String projectId;

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

  /** Whether the desk should be running. */
  public boolean wanted() {
    return desired == FrontDeskDesired.RUNNING;
  }
}
