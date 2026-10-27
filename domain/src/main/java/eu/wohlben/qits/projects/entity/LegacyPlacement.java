package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A front desk a retired front-desk runner held when the runner tables were dropped ({@code V62},
 * epic qits-1481): which runner, and what was known about its docker engine. The desk's first lease
 * adopts the runner's container and volume ({@code
 * qits-projects-desk-runner-desk-<projectId>-<oldRunnerId8>}) and is pinned to the target on the
 * same engine; the entry is deleted once that lease is RUNNING, or with the desk.
 *
 * <p>{@code @Uncaused} by decision: every row is written by the migration, never by a request, and
 * the only other write is its deletion.
 */
@Entity
@Table(name = "legacy_placement")
@Uncaused
public class LegacyPlacement extends PanacheEntityBase {

  /** The project, {@code FrontDesk.projectId}. */
  @Id
  @Column(name = "project_id")
  public String projectId;

  /** The retired runner that held the desk; its first eight characters end the old names. */
  @Column(name = "old_runner_id", nullable = false)
  public UUID oldRunnerId;

  /**
   * The runner's docker engine id, from its {@code capabilities.engineId}. Null for every desk
   * today: qits-projects-desk-runner-daemon never reported one.
   */
  @Column(name = "engine_id", columnDefinition = "text")
  public String engineId;

  /** The runner's host name, from its {@code capabilities.hostname}; null for the same reason. */
  @Column(name = "hostname", columnDefinition = "text")
  public String hostname;

  /** The runner's own name, the last way to find the target on the same node. */
  @Column(name = "runner_name", columnDefinition = "text")
  public String runnerName;

  /** {@code qits-projects-desk-runner-desk-<projectId>-<oldRunnerId8>}: container and volume alike. */
  public String legacyName() {
    return legacyName(projectId, oldRunnerId);
  }

  /** {@link #legacyName()} for any desk and runner. */
  public static String legacyName(String projectId, UUID oldRunnerId) {
    return "qits-projects-desk-runner-desk-"
        + projectId
        + "-"
        + oldRunnerId.toString().substring(0, 8);
  }
}
