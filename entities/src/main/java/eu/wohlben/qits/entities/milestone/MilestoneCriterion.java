package eu.wohlben.qits.entities.milestone;

import eu.wohlben.qits.entities.campaign.CriterionKind;
import eu.wohlben.qits.entities.campaign.CriterionPredicate;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * <b>One AND'd fact a {@link Milestone} waits on</b> (epics V38), inside a {@link MilestoneGroup} —
 * {@code campaign_criterion}'s columns, unchanged, now owned by a milestone.
 *
 * <p>The fact is a {@link CriterionKind} and a {@link CriterionPredicate}, the latter canonical JSON
 * in {@link #predicate}. An ENTITY_STATUS target's id lives in that JSON and not in a foreign key,
 * on purpose: a deleted target leaves the criterion behind as unsatisfiable, where a person sees it.
 *
 * <p><b>A latch that carries its evidence.</b> {@code ck_milestone_criterion_evidence} refuses a
 * latch with none: an event ({@link #evidenceEventId}), a person ({@link #approvedBy}, APPROVAL
 * only), or the target's state ({@link #CURRENT_STATE}, or a migrated {@link #STATE_AT_START};
 * ENTITY_STATUS only, no event id). An event never unlatches it; an edit of its predicate does.
 *
 * <p>{@link #createdAt} is the forward-only floor: no event from before it latches it. An edit that
 * changes the predicate moves it to the edit, since the criterion is then a new fact.
 */
@Entity
@Table(name = "milestone_criterion")
@EntityListeners(CausationStamp.class)
public class MilestoneCriterion extends PanacheEntityBase implements CausedRow {

  /** A latch read off the target's state when the criterion was written or edited. */
  public static final String CURRENT_STATE = "CURRENT_STATE";

  /** A campaign-era latch read off the target's state when its campaign started (migrated). */
  public static final String STATE_AT_START = "STATE_AT_START";

  @Id public String id;

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

  @Column(name = "group_id", nullable = false)
  public String groupId;

  @Column(nullable = false)
  public int position;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CriterionKind kind;

  /** Canonical JSON, one shape per {@link #kind} — decode with {@link CriterionPredicate#parse}. */
  @Column(nullable = false)
  public String predicate;

  /** True for the criterion a campaign member was given on joining, waiting on its predecessor. */
  @Column(nullable = false)
  public boolean seeded;

  @Column(name = "satisfied_at")
  public Instant satisfiedAt;

  /** The event that latched it; null for APPROVAL and for a state latch. */
  @Column(name = "evidence_event_id")
  public UUID evidenceEventId;

  /** The latching event's signature, {@link #CURRENT_STATE} or {@link #STATE_AT_START}. */
  @Column(name = "evidence_signature", length = 128)
  public String evidenceSignature;

  /** A one-line rendering of the evidence, stored with the latch. */
  @Column(name = "evidence_summary")
  public String evidenceSummary;

  @Column(name = "approved_by")
  public String approvedBy;

  @Column(name = "approval_note")
  public String approvalNote;

  /** The forward-only floor; moved to the edit when the predicate changes. */
  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** The predicate, decoded. */
  public CriterionPredicate decoded() {
    return CriterionPredicate.parse(kind, predicate);
  }

  /** Clears the latch and everything it carried. */
  public void unlatch() {
    satisfiedAt = null;
    evidenceEventId = null;
    evidenceSignature = null;
    evidenceSummary = null;
    approvedBy = null;
    approvalNote = null;
  }
}
