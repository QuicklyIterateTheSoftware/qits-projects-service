package eu.wohlben.qits.entities.milestone;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * <b>A CAMPAIGN membership waits on a milestone</b> (epics V38). The campaign executor dispatches
 * the membership once every milestone it waits on is met; a membership with none has no condition
 * and is dispatchable at once. A claimed membership is frozen: un-meeting does nothing to it.
 *
 * <p>{@link #generatedCondition} marks the one milestone the membership's condition doors read and
 * write ({@code CampaignService#setCondition}, {@code #approve}) — the milestone V38 made out of a
 * campaign-era condition, or the one those doors create on first use. At most one per membership
 * ({@code uq_campaign_membership_milestone_generated}).
 */
@Entity
@Table(name = "campaign_membership_milestone")
@IdClass(CampaignMembershipMilestone.Key.class)
@EntityListeners(CausationStamp.class)
public class CampaignMembershipMilestone extends PanacheEntityBase implements CausedRow {

  /**
   * The composite key, {@code (membership_id, milestone_id)}. Registered for reflection: Hibernate
   * instantiates it by its no-argument constructor, which a native image keeps only when told to.
   */
  @RegisterForReflection
  public static class Key implements Serializable {
    public String membershipId;
    public String milestoneId;

    public Key() {}

    public Key(String membershipId, String milestoneId) {
      this.membershipId = membershipId;
      this.milestoneId = milestoneId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(membershipId, key.membershipId)
          && Objects.equals(milestoneId, key.milestoneId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(membershipId, milestoneId);
    }
  }

  @Id
  @Column(name = "membership_id")
  public String membershipId;

  @Id
  @Column(name = "milestone_id")
  public String milestoneId;

  /** The milestone the membership's condition doors own — see the class javadoc. */
  @Column(name = "generated_condition", nullable = false)
  public boolean generatedCondition;

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

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;
}
