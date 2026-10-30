package eu.wohlben.qits.entities.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * <b>An idea</b> (qits-1387, epic qits-888, epics V33): a short, titled note owned by a project, which
 * an epic, a ticket or a campaign can name as what inspired it ({@link WorkEntity#inspiredByIdeaId}).
 *
 * <p><b>Not a work archetype, deliberately.</b> It has no lifecycle the caller moves, no dispatch,
 * no thread, no assignee, no children and no acceptance criteria. Its {@link #status} is derived
 * from the work that names it ({@link IdeaStatus}) and is only ever written by {@code
 * control/IdeaStatusRecompute}.
 *
 * <p><b>Never deleted.</b> An idea is archived with a reason and can be unarchived; archiving hides it
 * from the default listing and leaves its status as it was.
 */
@Entity
@Table(name = "idea")
@EntityListeners(CausationStamp.class)
public class Idea extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** The platform's uniform causation column, never part of any constraint. */
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

  /** The owning project, by id: a plain string, no cross-database foreign key (V1's rule). */
  @Column(name = "project_id", nullable = false)
  public String projectId;

  /** One line: the acceptance-criterion item rules apply ({@code control/AcceptanceCriteria}). */
  @Column(nullable = false)
  public String title;

  /** Optional Markdown, at most three paragraphs and 600 characters. */
  public String description;

  /**
   * The one idea of the same project that enables this one, or null. One layer deep: the target has
   * no {@code enabledBy} of its own, and an idea that enables others is never given one.
   */
  @Column(name = "enabled_by")
  public String enabledBy;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public IdeaStatus status;

  @Column(nullable = false)
  public boolean archived;

  @Column(name = "archive_reason")
  public String archiveReason;

  @Column(name = "archived_at")
  public Instant archivedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
