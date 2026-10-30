package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * <b>One source moved out of a release request into the one queued after it</b> (qits-1665, V71):
 * who moved which branch, from where, to where and why. Written in the move's transaction by {@code
 * ReleaseRequests.moveSource}, never updated, and read back as {@code ReleaseRequestDto.sourceMoves}
 * on both requests.
 *
 * <p>{@link Uncaused}: an audit row beside the source rows the same move writes; the moved source
 * row carries the causation it was first added under, and the move itself is announced as {@code
 * ReleaseRequestSourceMoved}.
 */
@Entity
@Table(name = "release_request_source_move")
@Uncaused
public class ReleaseRequestSourceMove extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /** The request the source left. */
  @Column(name = "from_request_id", nullable = false)
  public String fromRequestId;

  /** The request queued after it, which holds the source now. */
  @Column(name = "to_request_id", nullable = false)
  public String toRequestId;

  @Column(nullable = false, length = 512)
  public String branch;

  /** The authenticated principal that moved it; null only for an anonymous caller. */
  @Column(name = "moved_by")
  public String movedBy;

  @Column(nullable = false)
  public String reason;

  @Column(name = "moved_at", nullable = false)
  public Instant movedAt;
}
