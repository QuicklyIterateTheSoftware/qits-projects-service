package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One <b>person's override of gate 3</b> for one upstream request (epic qits-897, qits-914): the
 * request {@link #requestId} may start QA although the request {@link #upstreamRequestId}, in a
 * repository upstream of it, is still building.
 *
 * <p><b>An override is about a pair of requests, not a fold</b> — unlike {@link
 * ReleaseRequestApproval} and {@link ReleaseRequestAutomationWaiver}. A re-fold of either request
 * keeps it, and so does the upstream going inactive and building again: the person judged that this
 * request need not wait for that one. Keyed by the pair; overriding it again rewrites the row.
 *
 * <p>No foreign key to {@code release_request}, for the reasons {@link ReleaseRequest#supersededBy}
 * states. A {@link CausedRow}: every write happens on the request thread of the person overriding.
 */
@Entity
@Table(name = "release_request_upstream_override")
@IdClass(ReleaseRequestUpstreamOverride.Key.class)
@EntityListeners(CausationStamp.class)
public class ReleaseRequestUpstreamOverride extends PanacheEntityBase implements CausedRow {

  /** The request let through. A plain column: the parent is loaded by id, never joined. */
  @Id
  @Column(name = "request_id", nullable = false)
  public String requestId;

  /** The upstream request it no longer waits for. */
  @Id
  @Column(name = "upstream_request_id", nullable = false)
  public String upstreamRequestId;

  /** Who overrode — the verified person ({@code PersonCheck}), the point of the whole row. */
  @Column(name = "decided_by", nullable = false)
  public String decidedBy;

  @Column(name = "decided_at", nullable = false)
  public Instant decidedAt;

  /** What they said, or null. */
  @Column public String note;

  /** The platform's uniform column, never part of any constraint. */
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

  /** The composite key: the pair. */
  public static class Key implements Serializable {
    public String requestId;
    public String upstreamRequestId;

    public Key() {}

    public Key(String requestId, String upstreamRequestId) {
      this.requestId = requestId;
      this.upstreamRequestId = upstreamRequestId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(requestId, key.requestId)
          && Objects.equals(upstreamRequestId, key.upstreamRequestId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(requestId, upstreamRequestId);
    }
  }
}
