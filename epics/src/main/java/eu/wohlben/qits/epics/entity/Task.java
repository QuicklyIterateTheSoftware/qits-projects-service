package eu.wohlben.qits.epics.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Glues a feature to a concrete repository, owned by a feature. {@code featureId} is a real
 * intra-module FK (cascade-deleted with the feature); {@code repositoryId} references {@code
 * domain}'s {@code Repository} by String id — no JPA {@code @ManyToOne} and no cross-DB FK;
 * existence is validated in the {@code service} controller. {@code dependsOnTaskId} is a nullable
 * self-reference.
 *
 * <p><b>This class is a SHAPE now, and its table is no longer written</b> — {@link Feature}'s
 * paragraph applies here word for word, one level down.
 *
 * <p><b>A {@link CausedRow}</b>, for the reason {@link Epic} gives — and the one of the three most
 * likely to be machine-minted, since a task is what an agent creates when it decides a feature
 * needs work in a concrete repository ({@code EpicMcpTools.createTask}).
 */
@Entity
@EntityListeners(CausationStamp.class)
public class Task extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
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

  @Column(name = "feature_id", nullable = false)
  public String featureId;

  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /** Short label for lists/breadcrumbs. */
  @Column(nullable = false)
  public String title;

  /**
   * Git-safe path segment, minted from the title at create and never changed after: it names the
   * task's branch {@code task/<epic-slug>/<feature-slug>/<slug>}. Unique within the feature.
   */
  @Column(nullable = false, updatable = false)
  public String slug;

  /** The long-form Markdown body. */
  public String description;

  /** Nullable self-reference to another task this one depends on. */
  @Column(name = "depends_on_task_id")
  public String dependsOnTaskId;

  /** Set when the task is done; null while unimplemented. */
  @Column(name = "implemented_at")
  public Instant implementedAt;

  /**
   * <b>The per-project numeric id, carried out of {@code entity.number}.</b> {@code @Transient}: the
   * legacy table this class is still mapped to has no such column, and this class is a SHAPE the
   * services answer with rather than a row anybody writes. It is the bare number — the qualified
   * form {@code <project-slug>-<number>} is assembled in the {@code service} module, because the
   * slug lives in {@code domain}'s {@code project} table and {@code epics} depends on {@code domain}
   * nowhere. See {@code projects/api/QualifiedEntityIds}.
   */
  @Transient public long number;

  /**
   * <b>The owning project, carried out of {@code entity.project_id}.</b> {@code @Transient} for
   * {@link #number}'s reason — the legacy table never had it, because a descendant used to reach its
   * project by walking up. Every merged row carries one, and it is what {@code
   * projects/api/QualifiedEntityIds} resolves the project slug from.
   */
  @Transient public String projectId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
