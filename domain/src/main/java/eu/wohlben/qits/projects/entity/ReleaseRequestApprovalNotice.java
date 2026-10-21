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
 * <b>One thing said on the bus about one approval requirement</b> (epic qits-1038): that the
 * requirement {@link #requirementId} of request {@link #requestId}'s fold {@link #mergedSha} reached
 * {@link #state}. The row is what makes {@code ApprovalRequirementChanged} — and the work-item comment
 * an OPEN requirement earns — happen <b>once per (request, fold, requirement, state)</b>: an
 * announcement is made only for a row that was not there, inserted by {@code insert … on conflict do
 * nothing}, so a restart, the sweep and a door racing each other say it once.
 *
 * <p>{@link Uncaused}: machine bookkeeping written by the sweep as often as by a door, where no
 * causation scope stands — {@code StoredGateSet}'s reasoning.
 */
@Entity
@Table(name = "release_request_approval_notice")
@IdClass(ReleaseRequestApprovalNotice.Key.class)
@Uncaused
public class ReleaseRequestApprovalNotice extends PanacheEntityBase {

  /** The states announced. VOID: a re-fold left the requirement behind. */
  public enum State {
    OPEN,
    SATISFIED,
    DECLINED,
    VOID
  }

  @Id
  @Column(name = "request_id", nullable = false)
  public String requestId;

  @Id
  @Column(name = "merged_sha", nullable = false)
  public String mergedSha;

  @Id
  @Column(name = "requirement_id", nullable = false)
  public String requirementId;

  @Id
  @Column(nullable = false, length = 32)
  public String state;

  @Column public String reason;

  /** The admitted roles as announced, rendered and space-separated. */
  @Column public String admits;

  @Column(name = "excludes_authors", nullable = false)
  public boolean excludesAuthors;

  @Column public String detail;

  @Column(name = "announced_at", nullable = false)
  public Instant announcedAt;

  /** The composite key: the four columns the primary key is made of. */
  public static class Key implements Serializable {
    public String requestId;
    public String mergedSha;
    public String requirementId;
    public String state;

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(requestId, key.requestId)
          && Objects.equals(mergedSha, key.mergedSha)
          && Objects.equals(requirementId, key.requirementId)
          && Objects.equals(state, key.state);
    }

    @Override
    public int hashCode() {
      return Objects.hash(requestId, mergedSha, requirementId, state);
    }
  }
}
