package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One repository's gate set as {@code ReleaseGates} last read it from its {@code main} (qits-1201,
 * V44) — what the release-request list shows without asking the git host.
 *
 * <p><b>A reading of the repository's configuration, not a gate's answer about a request.</b> Nothing
 * that decides reads it: {@code ReleaseGates.resolve} still reads {@code main} (fresh within its
 * window), and this row is written beside that read whenever it comes back known, and by the list's
 * background refresh. Only a known set is ever stored, so "could not read the configuration" never
 * outlives the moment it was true.
 *
 * <p>{@link Uncaused}, {@code RepositoryName}'s reasoning: a derived, idempotent reading rewritten by
 * background work off any request context, where no {@code CausationScope} stands.
 */
@Entity
@Table(name = "release_gate_set")
@Uncaused
public class StoredGateSet extends PanacheEntityBase {

  @Id
  @Column(name = "repo_id")
  public String repoId;

  /** {@code ReleaseGates}' own JSON for the set — see {@code ReleaseGates.toJson}. */
  @Column(name = "gate_set", nullable = false)
  public String gateSet;

  /** When the read that produced {@link #gateSet} began. */
  @Column(name = "read_at", nullable = false)
  public Instant readAt;
}
