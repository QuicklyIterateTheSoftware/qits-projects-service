package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * <b>One thing said to a branch's owning agent about the branch</b> (qits-1722, V69): that it
 * failed its build at a fold, or moved from one request to another. The record of who was told,
 * when and about which fold, and the outbox the message is sent from. See {@code
 * control/BranchDeliveryNotices}.
 *
 * <p><b>Keyed by the notice alone, not by the agent.</b> The key ({@link #noticeKey}) is the dedupe
 * rule — once per fold and branch for FAILED, once per (branch, from, to) for MOVED. Who owns the
 * branch is a question to qits-workspaces, asked by the delivery after the commit, so {@link
 * #agentId} is filled in then and is no part of the key: a branch has one owner (the longest
 * matching wrapper branch), and so a notice is one message.
 *
 * <p>Replaces qits-1672's request-keyed {@code release_request_failure_notice} rows (V61), which
 * are no longer written and stay as history.
 *
 * <p>{@link Uncaused}: machine bookkeeping written beside the step that owes it and by the
 * delivery worker and the sweep.
 */
@Entity
@Table(name = "branch_delivery_notice")
@Uncaused
public class BranchDeliveryNotice extends PanacheEntityBase {

  /**
   * {@code FAILED:<requestId>:<foldSha>:<repoId>:<branch>} or {@code
   * MOVED:<repoId>:<branch>:<from>:<to>}.
   */
  @Id
  @Column(name = "notice_key", nullable = false)
  public String noticeKey;

  /** The ACTIVE agent the branch resolved to at delivery; null while owed or when none owns it. */
  @Column(name = "agent_id", length = 64)
  public String agentId;

  @Column(name = "project_id", nullable = false)
  public String projectId;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  @Column(nullable = false)
  public String branch;

  /** The branch's step: FAILED or MOVED (qits-1725 adds others). */
  @Column(nullable = false, length = 32)
  public String step;

  /** The request carrying the branch at the step — the target, on MOVED. */
  @Column(name = "request_id", nullable = false)
  public String requestId;

  /** That request's merged sha at the step. */
  @Column(name = "fold_sha")
  public String foldSha;

  /** The message, exactly as it is said. */
  @Column(nullable = false)
  public String text;

  @Column(name = "work_id")
  public String workId;

  /** DELIVERED, LAUNCHED, RESUMED, NO_AGENT, NOT_ACTIVE or GAVE_UP; null while owed. */
  @Column(length = 32)
  public String outcome;

  @Column(nullable = false)
  public int attempts;

  @Column(name = "claimed_at")
  public Instant claimedAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** When the agent was told; null when it was not. */
  @Column(name = "delivered_at")
  public Instant deliveredAt;

  @Column public String detail;
}
