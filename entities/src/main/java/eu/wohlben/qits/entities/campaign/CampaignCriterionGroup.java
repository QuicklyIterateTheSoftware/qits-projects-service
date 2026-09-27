package eu.wohlben.qits.entities.campaign;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * <b>One OR'd alternative of a campaign member's condition</b> (V19): the member may run once every
 * {@link CampaignCriterion} of <em>some</em> group has latched — see {@link Conditions}.
 *
 * <p>Owned by a CAMPAIGN {@code entity_membership} row and cascaded with it ({@code
 * fk_campaign_criterion_group_membership}). Positions are dense and zero-based per membership, and
 * a condition write replaces the lot ({@link CampaignService#setCondition}).
 */
@Entity
@Table(name = "campaign_criterion_group")
@EntityListeners(CausationStamp.class)
public class CampaignCriterionGroup extends PanacheEntityBase implements CausedRow {

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

  /** The CAMPAIGN membership this group belongs to. */
  @Column(name = "membership_id", nullable = false)
  public String membershipId;

  @Column(nullable = false)
  public int position;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;
}
