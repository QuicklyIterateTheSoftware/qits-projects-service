package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * <b>One agent told that a fold of its release request failed its build</b> (qits-1672): the
 * record of who was told, when, and about which fold, and the outbox the message is sent from. One
 * row per (request, fold, agent); the key is the once-per-fold rule. See {@code
 * control/ReleaseRequestFailureNotices}.
 *
 * <p>{@link Uncaused}: machine bookkeeping written by the gate and the sweep.
 */
@Entity
@Table(name = "release_request_failure_notice")
@IdClass(ReleaseRequestFailureNotice.Key.class)
@Uncaused
public class ReleaseRequestFailureNotice extends PanacheEntityBase {

  @Id
  @Column(name = "request_id", nullable = false)
  public String requestId;

  @Id
  @Column(name = "merged_sha", nullable = false)
  public String mergedSha;

  /** The agent id's first three groups, as its token subject carries them. */
  @Id
  @Column(name = "agent_key", nullable = false, length = 64)
  public String agentKey;

  /** The token subject that filed or joined. */
  @Column(nullable = false)
  public String subject;

  @Column(name = "owed_at", nullable = false)
  public Instant owedAt;

  @Column(name = "claimed_at")
  public Instant claimedAt;

  @Column(nullable = false)
  public int attempts;

  @Column(name = "agent_id", length = 64)
  public String agentId;

  @Column(name = "work_id")
  public String workId;

  /** DELIVERED, LAUNCHED, RESUMED, NO_AGENT, NOT_ACTIVE or GAVE_UP; null while owed. */
  @Column(length = 32)
  public String outcome;

  @Column(name = "told_at")
  public Instant toldAt;

  @Column public String detail;

  /** The composite key. */
  public static class Key implements Serializable {
    public String requestId;
    public String mergedSha;
    public String agentKey;

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(requestId, key.requestId)
          && Objects.equals(mergedSha, key.mergedSha)
          && Objects.equals(agentKey, key.agentKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(requestId, mergedSha, agentKey);
    }
  }
}
