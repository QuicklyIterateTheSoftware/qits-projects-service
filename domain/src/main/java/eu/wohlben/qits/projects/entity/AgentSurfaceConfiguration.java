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
 * What one <b>session surface</b> is configured to launch as — the row a constant in {@code
 * AgentLaunchService} used to be.
 *
 * <p>A surface is <em>where in the product a session was started from</em>: the epics overview's
 * refinement panel is {@code project.epics}, the tickets page is {@code project.tickets}, the
 * refining route's two tabs are {@code epic.chat} and {@code epic.agent}, and so on. It is the key
 * everything here hangs off, and it is deliberately a free {@link #surfaceKey string} rather than an
 * enum column: adding a surface must be an additive change in one shipped-defaults constant, not a
 * migration and not a schema change in three repositories.
 *
 * <p><b>Platform-wide.</b> One row per surface for the whole platform — no project id, and the
 * surface key is the whole primary key. This configuration sits <em>above</em> the resolution chain
 * the daemons already have (request parameter → checkout {@code .qits-config.yml} → daemon default)
 * as the source those defaults come from. Per-project overrides are a later epic and nothing here is
 * shaped for them.
 *
 * <p><b>Empty is a value, everywhere text is stored.</b> {@link #systemPrompt} is the case that
 * forced the rule: {@code project.epics} steers with nothing at all, deliberately, and that is what
 * lets it render byte-identically to the pre-desk launch. So the text columns are {@code not null}
 * and {@code ""} means "render nothing" rather than "nobody has said". {@link #model} and {@link
 * #effort} read the same way — empty is the harness's own default, which is what every surface but
 * the refinement flow renders today.
 *
 * <p><b>An edit applies to the next container, and that is not surfaced anywhere.</b> A container is
 * created carrying the resolved document for every surface it can serve and keeps it for its whole
 * life; changing a row here does not reach a running session. What makes that safe rather than
 * opaque is that each launch records what it actually ran with, so a session stays readable after
 * the store has moved on.
 *
 * <p>The MCP servers a surface attaches live in {@link AgentSurfaceMcpAttachment}, reached through
 * their own repository rather than a mapped collection — this module's standing shape, and here it
 * also keeps a whole-set replacement out of Hibernate's hands.
 *
 * <p><b>A {@link CausedRow}.</b> Both writes — the boot seed and an operator's edit — run on a
 * thread that may carry a causation scope, and the stamp is insert-only, so a row records the cause
 * of its creation. The cause of an <em>update</em> is what {@link AgentSurfaceConfigurationRevision}
 * carries, which is the same division of labour {@code AuditEntry} makes for epics.
 */
@Entity
@Table(name = "agent_surface_configuration")
@EntityListeners(CausationStamp.class)
public class AgentSurfaceConfiguration extends PanacheEntityBase implements CausedRow {

  /** The surface key — {@code project.epics}, {@code epic.chat}, {@code ticket.dispatch}, … */
  @Id
  @Column(name = "surface_key")
  public String surfaceKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "harness", nullable = false)
  public AgentHarness harness;

  /** The model id or alias to pass, or {@code ""} for the harness's own default. */
  @Column(name = "model", nullable = false, columnDefinition = "text")
  public String model;

  /** The effort level to pass, or {@code ""}. A harness with no effort concept ignores it. */
  @Column(name = "effort", nullable = false, columnDefinition = "text")
  public String effort;

  /** Whether the session is bridged to claude.ai's remote session list. */
  @Column(name = "remote_control", nullable = false)
  public boolean remoteControl;

  @Enumerated(EnumType.STRING)
  @Column(name = "permission_mode", nullable = false)
  public AgentPermissionMode permissionMode;

  /**
   * Whether the turn-boundary activity hooks are wired. Per surface here; one daemon-wide boolean
   * ({@code AgentDefaults.activityTrackingEnabled}) today.
   */
  @Column(name = "activity_tracking", nullable = false)
  public boolean activityTracking;

  /** Appended to the harness's own system prompt. {@code ""} appends nothing. */
  @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
  public String systemPrompt;

  /** A turn pushed at session start, before anything the caller composed. {@code ""} pushes none. */
  @Column(name = "initial_prompt", nullable = false, columnDefinition = "text")
  public String initialPrompt;

  /** The principal behind the most recent edit; null on a row nobody has edited since the seed. */
  @Column(name = "updated_by")
  public String updatedBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

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
