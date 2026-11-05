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
 * <b>One reset of a repository's backup twin</b> (qits-1885, V75): who let the forge copy's main be
 * replaced, why, and what it held before and after. Written once by {@code
 * RepositoryService.resetBackupTwin} after the push succeeded, never updated, and read back by
 * {@code GET /repositories/{repoId}/backup-resets}.
 *
 * <p>No foreign key to {@code repository}: the row records a decision about a forge outside the
 * platform and outlives the repository row it names. A {@link CausedRow}: it is inserted on the
 * request thread of the person or desk pressing the door.
 */
@Entity
@Table(name = "repository_backup_reset")
@EntityListeners(CausationStamp.class)
public class RepositoryBackupReset extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** A plain column: the repository is loaded by id, never joined. */
  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /**
   * Who decided — the verified person, or the project's delivery desk under its token subject
   * ({@code PersonCheck.requireAdminOrDesk}).
   */
  @Column(name = "decided_by", nullable = false)
  public String decidedBy;

  @Column(name = "decided_at", nullable = false)
  public Instant decidedAt;

  /** Why, as they said it; never blank. */
  @Column(nullable = false)
  public String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public BackupResetKind kind;

  /** The twin's main before the push; null when the twin had no main at all. */
  @Column(name = "twin_sha_before", length = 64)
  public String twinShaBefore;

  /** Our main, which the twin's main is now. */
  @Column(name = "pushed_sha", nullable = false, length = 64)
  public String pushedSha;

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
