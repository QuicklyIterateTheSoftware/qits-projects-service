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
 * One <b>pitch</b> (qits-2155, V77): a mock snapshot of one screenshot of the UI builder, pitched by
 * a named agent ({@code control/NamedAgents}) and kept on the server per agent. A person compares it
 * with the original without committing to anything; one they like becomes work when the agent is
 * asked to file a ticket or an idea that links it.
 *
 * <ul>
 *   <li><b>It names its screenshot</b>: the repository, the freeze's {@link #route}, its {@link
 *       #screenshot} key in {@code manifest.json}, the state, viewport and scheme it shows, and the
 *       {@code @routes} bundle version ({@link #manifestVersion}) it came from — so the UI builder
 *       finds the pitches of the screenshot it has open.
 *   <li><b>Every version of its HTML is kept</b>, in {@link PitchVersion}: append-only, never
 *       rewritten, so a reader picks the one to compare.
 *   <li><b>One agent owns it</b>: {@link #agentName} is the only agent that may add a version to
 *       it, and {@link #createdBy} the token subject that created it.
 *   <li><b>Nothing is deleted</b>: an archive stamps {@link #archivedAt} and hides it from the
 *       default listing. Rows go only with their project or their repository (cascade).
 * </ul>
 *
 * <p>Separate from {@link RefinementDesign} on purpose: a design belongs to a refinement, dies with
 * it and is rewritten in place under an optimistic version; a pitch exists before any work item and
 * keeps its history. The same safety rule holds: the HTML only ever travels as a JSON field.
 *
 * <p><b>A {@link CausedRow}</b>, like {@link RefinementDesign}: a pitch is written on the request
 * thread that carried the agent's call, so the stamp records the turn that pitched it.
 */
@Entity
@Table(name = "pitch")
@EntityListeners(CausationStamp.class)
public class Pitch extends PanacheEntityBase implements CausedRow {

  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "project_id", nullable = false)
  public String projectId;

  /** The named agent that pitched it; the only one that may add a version. */
  @Column(name = "agent_name", nullable = false)
  public String agentName;

  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /** The freeze's {@code route}. */
  @Column(name = "route", nullable = false, columnDefinition = "text")
  public String route;

  /** The freeze's {@code file} key in {@code manifest.json}. */
  @Column(name = "screenshot", nullable = false, columnDefinition = "text")
  public String screenshot;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, columnDefinition = "text")
  public PitchKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "viewport", nullable = false, columnDefinition = "text")
  public PitchViewport viewport;

  /** Null for an app with no colour schemes. */
  @Enumerated(EnumType.STRING)
  @Column(name = "color_scheme", columnDefinition = "text")
  public PitchColorScheme colorScheme;

  /** The {@code @routes} bundle version the screenshot came from: opaque, the caller's. */
  @Column(name = "manifest_version", nullable = false, columnDefinition = "text")
  public String manifestVersion;

  @Column(name = "title", nullable = false, columnDefinition = "text")
  public String title;

  @Column(name = "note", columnDefinition = "text")
  public String note;

  /** Set once by an archive; never cleared. */
  @Column(name = "archived_at")
  public Instant archivedAt;

  /** The token subject of the agent's credential that created it. */
  @Column(name = "created_by", nullable = false, columnDefinition = "text")
  public String createdBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** Created, a version appended, archived. */
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
