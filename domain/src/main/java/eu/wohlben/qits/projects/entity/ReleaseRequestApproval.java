package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One <b>person's decision</b> about one fold of a release request — the record behind the approval
 * gate a wrapper release is held by, in addition to the build gate.
 *
 * <p><b>A decision is about a sha, not about a request.</b> The row carries the {@link
 * ReleaseRequest#mergedSha} it was made against, which is what makes the existing re-arm semantics
 * carry the whole of the invalidation: anything that changes what the fold would produce lands a new
 * merged sha, and a decision made against the old one no longer matches — for free, with no column
 * to clear and no path that has to remember to clear it. The gate reads the <b>newest</b> row whose
 * {@code merged_sha} equals the request's current {@code mergedSha}; every older row is history
 * about a fold the request has moved past.
 *
 * <p><b>Insert-only. Nothing updates a row and nothing deletes one.</b> A decline, the fix that
 * answers it and the approval that follows are three facts, not one field changing its mind twice,
 * and the difference matters beyond the audit: this trail is the corpus the future
 * goal-verification step is measured against — what a person refused, what was changed, and what
 * they accepted in the end. A row rewritten in place would delete exactly the evidence that step
 * needs. So a change of mind is another row.
 *
 * <p>A {@link CausedRow}: every insert happens on the request thread of the person deciding, so the
 * stamp records who asked, the same reading {@link ReleaseRequestSource} gets. Nothing on the
 * machine paths writes one — an approval is by definition not machine-made.
 */
@Entity
@Table(name = "release_request_approval")
@EntityListeners(CausationStamp.class)
public class ReleaseRequestApproval extends PanacheEntityBase implements CausedRow {

  /**
   * What a person decided. Stored as a string with no check constraint, the platform's usual
   * reasoning — the vocabulary grows without a migration and every historical row keeps its word,
   * the same freedom that let {@link ReleaseRequest.State} gain CONFLICTED at no DDL cost.
   */
  public enum Decision {
    APPROVED,
    DECLINED
  }

  @Id public String id;

  /** The request decided about. A plain column: the parent is loaded by id, never joined. */
  @Column(name = "request_id", nullable = false)
  public String requestId;

  /**
   * The fold this decision is about — the request's {@link ReleaseRequest#mergedSha} at the moment
   * it was made. Never null: there is nothing to decide about before the first merge lands, and a
   * decision with no sha would be a standing approval of whatever the request becomes next.
   */
  @Column(name = "merged_sha", nullable = false)
  public String mergedSha;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public Decision decision;

  /** Who decided — the forwarded identity, the point of the whole gate. */
  @Column(nullable = false)
  public String actor;

  /** What they said about it; null where they said nothing, which an approval often does. */
  @Column public String note;

  /** When they decided. The tiebreak between two rows about the same fold, newest wins. */
  @Column(name = "decided_at", nullable = false)
  public Instant decidedAt;

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
}
