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
 * <b>One principal that took part in a release request</b> (qits-1673, V66): it asked for the
 * request, converged on it, joined a branch to it, or an internal path added a source on its behalf.
 * One row per (request, principal), inserted if absent and <b>never deleted</b> — a source row goes
 * when its branch is deleted, and the participant it named stays, so a request keeps its authors.
 *
 * <p>It is what attribution reads now: whether a request is unattended (every participant a machine),
 * who is told of its failure, and whether the desk may say an agent owns it. The principal is always
 * the authenticated caller's own — the doors no longer take a stated one.
 *
 * <p>{@link Uncaused}: an idempotent index of who asked, written with {@code on conflict do nothing}
 * by {@code ReleaseRequestParticipantRepository}. The request and source rows the same ask writes are
 * the caused ones.
 */
@Entity
@Table(name = "release_request_participant")
@IdClass(ReleaseRequestParticipant.Key.class)
@Uncaused
public class ReleaseRequestParticipant extends PanacheEntityBase {

  @Id
  @Column(name = "request_id", nullable = false)
  public String requestId;

  /** The authenticated principal's name, as the token or the forwarded identity carries it. */
  @Id
  @Column(nullable = false)
  public String principal;

  /** When the principal first took part; a later ask does not move it. */
  @Column(name = "first_seen_at", nullable = false)
  public Instant firstSeenAt;

  /** The composite key. */
  public static class Key implements Serializable {
    public String requestId;
    public String principal;

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(requestId, key.requestId)
          && Objects.equals(principal, key.principal);
    }

    @Override
    public int hashCode() {
      return Objects.hash(requestId, principal);
    }
  }
}
