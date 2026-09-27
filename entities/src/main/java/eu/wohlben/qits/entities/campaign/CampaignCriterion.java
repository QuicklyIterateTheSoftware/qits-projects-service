package eu.wohlben.qits.entities.campaign;

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
 * <b>One AND'd fact a campaign member waits on</b> (V19), inside a {@link CampaignCriterionGroup}.
 *
 * <p>The fact is a {@link CriterionKind} and a {@link CriterionPredicate}, the latter stored as
 * canonical JSON in {@link #predicate}. An ENTITY_STATUS target's id lives in that JSON and not in a
 * foreign key, on purpose: a deleted target must leave the criterion behind as unsatisfiable, where
 * a person can see it, rather than silently making its member runnable.
 *
 * <p><b>A latch, and it carries its evidence.</b> {@link #satisfiedAt} goes from null to a time and
 * never back; {@code ck_campaign_criterion_evidence} refuses a latch with no evidence — an event
 * ({@link #evidenceEventId}), a person ({@link #approvedBy}, APPROVAL only), or the target's state
 * when the campaign started ({@link #STATE_AT_START}, ENTITY_STATUS only, no event id).
 */
@Entity
@Table(name = "campaign_criterion")
@EntityListeners(CausationStamp.class)
public class CampaignCriterion extends PanacheEntityBase implements CausedRow {

  /** The evidence signature of a latch read off the target's current state, not off an event. */
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

  /**
   * True for the criterion a member was given on joining, waiting on its predecessor. Nothing treats
   * it specially after the insert; it exists so a screen can label the row.
   */
  @Column(nullable = false)
  public boolean seeded;

  @Column(name = "satisfied_at")
  public Instant satisfiedAt;

  /** The event that latched it; null for APPROVAL and for {@link #STATE_AT_START}. */
  @Column(name = "evidence_event_id")
  public UUID evidenceEventId;

  /** The latching event's signature, or {@link #STATE_AT_START}. */
  @Column(name = "evidence_signature", length = 128)
  public String evidenceSignature;

  /** A one-line rendering of the evidence, stored with the latch. */
  @Column(name = "evidence_summary")
  public String evidenceSummary;

  @Column(name = "approved_by")
  public String approvedBy;

  @Column(name = "approval_note")
  public String approvalNote;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  /** The predicate, decoded. */
  public CriterionPredicate decoded() {
    return CriterionPredicate.parse(kind, predicate);
  }
}
