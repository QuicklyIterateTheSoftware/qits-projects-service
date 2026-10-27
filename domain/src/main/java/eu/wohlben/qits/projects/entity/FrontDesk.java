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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A project's front desk (qits-767, V37): its agent container, as this service wants it and as
 * qits-system last reported the DESK lease it runs under (epic qits-1481, V62).
 *
 * <ul>
 *   <li><b>The lease</b> — {@link #leaseId} names the DESK lease the desk runs under ({@code
 *       ownerRef <projectId>}, {@code workKey desk-<projectId>}), null while it has none; {@link
 *       #targetId} the target it was granted on. A wanted desk with no live lease is given one; an
 *       unwanted desk's lease is ended, and its work volume kept on the target for the next.
 *   <li><b>Desired state</b> — {@link #desired}, recomputed from the project's {@code
 *       front_desk_lifecycle} and from {@link #lastDemandAt} by the front-desk demand.
 *   <li><b>The token</b> — one {@code qits_tok_} per lease, minted immediately before the lease is
 *       asked for, carried in its spec and revoked once the lease is over: {@link #tokenId} to revoke it, {@link #tokenValue} so the spec is reproducible,
 *       {@link #tokenSubject} because the control socket and the dial-back bind a {@code tok-}
 *       bearer to it. The {@code delivery_token_*} columns hold a second one, the delivery
 *       loop's, only while the project's loop is enabled (epic qits-1440); the {@code
 *       session_token_*} columns a third, the desk's sessions' own (qits-1482), carried in no
 *       environment.
 *   <li><b>The spec</b> — {@link #specJson} is the spec last <em>sent</em> to qits-system for the
 *       current lease (the applied one) and {@link #specHash} its SHA-256 over canonical JSON; a
 *       roll replaces the lease's spec and the target recreates the container. {@link #rollRequestedAt} marks a
 *       settings change waiting to roll between turns (qits-1514).
 *   <li><b>The report</b> — {@code reported_*} is qits-system's last word on the lease (its state and
 *       spec hash), {@link #leaseDetail} why a QUEUED lease waits; {@link #failureDetail} why the
 *       desk is not usable, when it is not; {@link #sessionId} the desk's own
 *       session as its daemon last reported it.
 * </ul>
 *
 * <p>{@link Uncaused}: machine state derived from the project and from demand, written by sweeps
 * and socket frames off any request scope, so a stamp would record null for ever.
 *
 * <p>{@link DynamicUpdate}: the row has many writers (the roll, the runner's report, the daemon's
 * session report), each changing its own columns. A writer flushes only what it changed, so one
 * that loaded the row before {@link #sessionId} moved never writes the old id back (qits-1619).
 */
@Entity
@Table(name = "front_desk")
@Uncaused
@DynamicUpdate
public class FrontDesk extends PanacheEntityBase {

  @Id
  @Column(name = "project_id")
  public String projectId;

  /** The DESK lease the desk runs under, or null while it has none (V62). */
  @Column(name = "lease_id")
  public UUID leaseId;

  /** The target that lease was granted on, or null while it waits or has none. */
  @Column(name = "target_id")
  public UUID targetId;

  /**
   * The retired front-desk runner's container and volume this desk's work was adopted from ({@code
   * qits-projects-desk-runner-desk-<projectId>-<oldRunnerId8>}), or null. Every lease names it in
   * {@code adopt}, because the target mounts an adopted volume only when the lease says so.
   */
  @Column(name = "adopted_volume", columnDefinition = "text")
  public String adoptedVolume;

  /** Why a QUEUED lease waits, as qits-system last said it, or null. */
  @Column(name = "lease_detail", columnDefinition = "text")
  public String leaseDetail;

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
   * desk-delivery}, held only while the project's {@code delivery_enabled} is true — minted when the
   * loop is enabled, revoked and cleared when it is disabled. The desk's {@code project.delivery}
   * session carries it; nothing else does.
   */
  @Column(name = "delivery_token_id")
  public String deliveryTokenId;

  @Column(name = "delivery_token_value")
  public String deliveryTokenValue;

  @Column(name = "delivery_token_subject")
  public String deliveryTokenSubject;

  /**
   * The desk's session token (qits-1482, V49): a {@code qits_tok_} of idp context kind {@code
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

  /**
   * When a front-desk settings change ({@code front_desk.lifecycle} or {@code front_desk.delivery})
   * asked for the desk to roll (qits-1514, V51), or null when none is pending. While set, the spec
   * roll replaces a RUNNING desk's spec as soon as no turn is actually running on it — a WAITING
   * rollup does not hold this up, unlike the quiet rule for an unrequested roll (see {@code
   * deskhost/FrontDeskSpecRoll#turnRunning}) — without the quiet window; storing a spec, or finding
   * the composed spec already applied, clears it.
   */
  @Column(name = "roll_requested_at")
  public Instant rollRequestedAt;

  /**
   * The harness session id the desk's daemon last reported for the front desk's own session key
   * ({@code front-desk}, qits-1619, V60), or null when it never reported one. Kept after the session
   * stops — a report without one never clears it — so the {@code sessions} frame can hand it back as
   * that entry's {@code resumeSessionId} and a recreated desk resumes its previous session.
   */
  @Column(name = "session_id")
  public String sessionId;

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

  /** Whether the desk runs under a lease. */
  public boolean placed() {
    return leaseId != null;
  }

  /** Whether the desk should be running. */
  public boolean wanted() {
    return desired == FrontDeskDesired.RUNNING;
  }
}
