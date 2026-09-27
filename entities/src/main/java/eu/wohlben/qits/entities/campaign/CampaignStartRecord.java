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

/**
 * <b>A campaign's start</b> (V19, table {@code campaign_start}): one row per campaign that has ever
 * been started. Named {@code …Record} so it cannot collide with the executor's {@code
 * CampaignStarter}, which writes it.
 *
 * <ul>
 *   <li>{@link #firstStartedAt} is the forward-only floor for event criteria and never moves.
 *   <li>{@link #active} is what the executor acts on, together with the campaign being REFINED. It
 *       is cleared by the pause hook in {@code WorkEntityService.transition}, in the same transaction
 *       as the campaign leaving REFINED, and set again only by a new start press.
 * </ul>
 */
@Entity
@Table(name = "campaign_start")
@EntityListeners(CausationStamp.class)
public class CampaignStartRecord extends PanacheEntityBase implements CausedRow {

  /** The campaign's entity id; {@code fk_campaign_start_campaign}, {@code on delete cascade}. */
  @Id
  @Column(name = "campaign_id")
  public String campaignId;

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

  @Column(name = "first_started_at", nullable = false)
  public Instant firstStartedAt;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  @Column(name = "started_by", nullable = false)
  public String startedBy;

  @Column(nullable = false)
  public boolean active;
}
