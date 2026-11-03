package eu.wohlben.qits.entities.milestone;

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
 * <b>A foreseeable "needs to be met"</b> (epic qits-615, epics V38): project-scoped, not an
 * archetype, with no lifecycle of work — it holds or it does not. Its condition is the monotone
 * DNF campaign memberships used to own: OR'd {@link MilestoneGroup}s of AND'd {@link
 * MilestoneCriterion}s ({@link eu.wohlben.qits.entities.campaign.Conditions}).
 *
 * <p>{@link #satisfiedAt} means "met since": the earliest date among its holding groups, written by
 * {@link MilestoneDates} after every latch and every edit, and cleared when an edit leaves no group
 * holding. Work entities wait on it through {@link WorkMilestone}, campaign memberships through
 * {@link CampaignMembershipMilestone}.
 */
@Entity
@Table(name = "milestone")
@EntityListeners(CausationStamp.class)
public class Milestone extends PanacheEntityBase implements CausedRow {

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

  @Column(name = "project_id", nullable = false)
  public String projectId;

  @Column(name = "display_name", nullable = false)
  public String displayName;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  /** Met since; null while no group holds. */
  @Column(name = "satisfied_at")
  public Instant satisfiedAt;

  /** Whether it holds now. */
  public boolean met() {
    return satisfiedAt != null;
  }
}
