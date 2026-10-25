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
 * qits-770 (V57) a new room is a <b>session</b> in its project's front desk, keyed by the entity id;
 * a room opened before that is a legacy <b>container</b> room on a {@code refining/*} branch, kept
 * working until qits-1316 removes the container machinery ({@link #containerRoom()} tells them
 * apart). The entity is an epic or a ticket, the two archetypes with a lifecycle (qits-395; until
 * then only an epic could be refined).
 *
 * <p><b>Keyed by entity, addressed by row id.</b> An entity has at most one refinement (the unique
 * constraint on {@code entity_id} is what makes find-or-create race-safe), and every URL — lifecycle
 * verbs, the daemon proxy, the SSE channel — carries the numeric row id, the same shape the
 * workspaces domain used and the shape the SPA's panels already take.
 *
 * <p><b>The entity id is a key, not a relation.</b> The entity lives in the {@code entities}
 * module's own database and Flyway lineage; a foreign key across persistence units is not a thing,
 * and that module deliberately depends on nothing here. The row is torn down by an explicit discard,
 * never by a cascade from a table it cannot see. Nor is the archetype copied here (V29 says why): the
 * open reads the entity live, and nothing after it needs to know.
 *
 * <p><b>The commissioned credential is two columns, and that is forced rather than chosen.</b> The
 * pair reaches the container as environment, and qits-containers hashes a workload's whole spec —
 * environment included — to decide whether an {@code ensure} may start the container in place or
 * must replace it. A wake that could not reproduce the same two values byte for byte would be a
 * spec change destroying the container on every resume. The same argument, at length, on {@link
 * AgentCredential}.
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

  /** The project's wrapper repository — what the container clones and the branch lives on. */
  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /**
   * A legacy container room's branch on the wrapper: {@code refining/<slug>}, the entity's slug. A
   * root entity's slug is unique per project across archetypes ({@code uq_entity_slug_scope_slug}),
   * so an epic and a ticket can never cut the same branch.
   *
   * <p><b>Null on a session room</b> (V57, epic qits-770): a room opened since then is a session in
   * its project's front desk and cuts no branch. The branch is the rule that tells the two apart —
   * see {@link #containerRoom()}.
   */
  @Column(name = "branch")
  public String branch;

  /**
   * The branch a legacy container room forked from — the wrapper's default branch at create time.
   * Null on a session room.
   */
  @Column(name = "parent")
  public String parent;

  /**
   * A human-readable label ({@code refining-<slug>}, sanitized), announced to the daemon as its
   * workspace id so logs and frames read well. Decoration — the row id is the address.
   */
  @Column(name = "label", nullable = false)
  public String label;

  /** The commissioned idp client this refinement's container holds, or null before any container. */
  @Column(name = "commissioned_client_id", columnDefinition = "text")
  public String commissionedClientId;

  /** Its secret. See the class javadoc for why this is a column at all. */
  @Column(name = "commissioned_client_secret", columnDefinition = "text")
  public String commissionedClientSecret;

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
   * When somebody last asked for this session room's session (V57): the open, the ensure door, the
   * session's own activity. The session is desired RUNNING while this is younger than {@code
   * qits.projects.agent-idle-timeout}; the stop door clears it. Unused on a container room.
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

  /**
   * Whether this is a legacy container room (a {@code refining/<slug>} branch and its own
   * container, until qits-1316 removes them) rather than a session in the project's front desk
   * (epic qits-770). The branch decides: every row opened before V57 has one, none opened since
   * does.
   */
  public boolean containerRoom() {
    return branch != null;
  }

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
