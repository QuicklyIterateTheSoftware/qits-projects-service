package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One entity's refinement room — the row behind the projects SPA's refining route. Since epic
 * qits-770 every room is a <b>session</b> in its project's front desk, keyed by the entity id (V58);
 * the per-room containers, their branches and commissioned clients are retired (qits-1316, V59). The
 * entity is an epic, a ticket or a campaign.
 *
 * <p><b>Keyed by entity, addressed by row id.</b> An entity has at most one refinement (the unique
 * constraint on {@code entity_id} is what makes find-or-create race-safe), and every URL — lifecycle
 * verbs, the SSE channel — carries the numeric row id, the same shape the
 * workspaces domain used and the shape the SPA's panels already take.
 *
 * <p><b>The entity id is a key, not a relation.</b> The entity lives in the {@code entities}
 * module's own database and Flyway lineage; a foreign key across persistence units is not a thing,
 * and that module deliberately depends on nothing here. The row is torn down by an explicit discard,
 * never by a cascade from a table it cannot see. Nor is the archetype copied here (V29 says why): the
 * open reads the entity live, and nothing after it needs to know.
 *
 * <p><b>A {@link CausedRow}.</b> The insert runs on the request thread that opened the refining
 * route, so the stamp records what asked for it.
 */
@Entity
@Table(name = "refinement")
@EntityListeners(CausationStamp.class)
public class Refinement extends PanacheEntityBase implements CausedRow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  /**
   * The entity being refined — an epic or a ticket. Unique — an entity has at most one refinement.
   * The column was {@code epic_id} until V29 renamed it; ids are one space across archetypes, so no
   * value moved.
   */
  @Column(name = "entity_id", nullable = false, unique = true)
  public String entityId;

  /** The project the entity belongs to. A key into another table of this database, kept plain. */
  @Column(name = "project_id", nullable = false)
  public String projectId;

  /** The project's wrapper repository — what the desk the session runs in clones. */
  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /**
   * A human-readable label ({@code refining-<slug>}, sanitized), announced to the daemon as its
   * workspace id so logs and frames read well. Decoration — the row id is the address.
   */
  @Column(name = "label", nullable = false)
  public String label;

  /**
   * This room's own {@code qits_tok_} at qits-idp (qits-1482, V50): kind {@code refinement}, claims
   * {@code {project, entity}}. Its id, to revoke it with; null until a mint has landed.
   */
  @Column(name = "token_id", columnDefinition = "text")
  public String tokenId;

  /** The token's value — a credential, never logged and never on the wire of a read. */
  @Column(name = "token_value", columnDefinition = "text")
  public String tokenValue;

  /** The {@code sub} the token introspects as ({@code tok-…}). */
  @Column(name = "token_subject", columnDefinition = "text")
  public String tokenSubject;

  /**
   * When somebody last asked for this session room's session (V58): the open, the ensure door, the
   * session's own activity. The session is desired RUNNING while this is younger than {@code
   * qits.projects.agent-idle-timeout}; the stop door clears it.
   */
  @Column(name = "last_demand_at")
  public Instant lastDemandAt;

  /**
   * The harness session id the desk's daemon last reported for this room's key — kept after the
   * session stops, so the next launch resumes it.
   */
  @Column(name = "session_id", columnDefinition = "text")
  public String sessionId;

  /** The key's last reported state ({@code STARTING|RUNNING|STOPPED|FAILED}), or null. */
  @Column(name = "session_state", columnDefinition = "text")
  public String sessionState;

  /** The report's sentence for a person, or null. Prose, never parsed. */
  @Column(name = "session_detail", columnDefinition = "text")
  public String sessionDetail;

  /** The daemon command running the session while it is RUNNING, else null. */
  @Column(name = "session_command_id", columnDefinition = "text")
  public String sessionCommandId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

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
