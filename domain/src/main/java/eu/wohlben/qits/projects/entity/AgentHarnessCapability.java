package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one harness binary, in one image build, reported it can be configured with.
 *
 * <p><b>Keyed by harness <em>and</em> image version, and the second half is the whole point.</b> A
 * capability report is only true of the binary that produced it. Two containers on this platform can
 * be running two different builds of the workspace image at once — the project's agent container and
 * a given workspace's are provisioned independently — so folding their answers into one union would
 * offer the editor a set of models no single binary actually has. Each pair keeps its own row; the
 * read takes the newest and <em>names</em> the image version it came from, listing the others beside
 * it rather than merging them.
 *
 * <p><b>Written only by a report, never by an operator.</b> There is no editor door onto this table
 * and there must not be one: a hand-typed model list is exactly the hardcoded catalogue this feature
 * exists to remove. The values are discovered by running the harness, which can only happen inside a
 * container, so a daemon probes once at container start and reports through {@code GET
 * /agents/available}.
 *
 * <p><b>Three booleans that are each distinct from an empty list</b>, and conflating any of them is
 * how the editor ends up lying:
 *
 * <ul>
 *   <li>{@link #modelsEnumerated} false — the harness has no listing command and the list is a
 *       shipped alias set (Claude Code). The editor leads with the free-text escape, because pinning
 *       a full model id is exactly what someone comes here for.
 *   <li>{@link #effortSupported} false — the harness has no effort concept at all (Kimi). The editor
 *       shows <em>no</em> effort control, not a disabled one carrying the other harness's values.
 *   <li>{@link #probeFailed} true — the lists are whatever the fallback supplied. "Stale because the
 *       probe broke" and "what the binary offers" must not look alike to whoever reads the editor.
 * </ul>
 *
 * <p>{@link #authenticated} rides along because auth is a property of the harness and the credential
 * volume rather than of a surface (feature e560229a), and it belongs in the same answer as the rest
 * of "what can this harness do right now".
 *
 * <p>A {@link CausedRow}: the write happens on a thread that may carry a causation scope, and the
 * stamp is insert-only, so a row records the cause of the first report for that pair.
 */
@Entity
@Table(name = "agent_harness_capability")
@EntityListeners(CausationStamp.class)
public class AgentHarnessCapability extends PanacheEntityBase implements CausedRow {

  /** A synthetic string id; the identity of the row is the {@code (harness, imageVersion)} pair. */
  @Id
  @Column(name = "id")
  public String id;

  /**
   * {@code CLAUDE} or {@code KIMI}, as a string and not an enum column.
   *
   * <p>Same reading as {@code AgentSurfaceConfiguration.surfaceKey}: a third harness must cost a
   * Java constant rather than a migration, and a row naming a harness this service does not know is
   * ignored on read instead of failing the whole catalogue read.
   */
  @Column(name = "harness", nullable = false)
  public String harness;

  /** The image build the reporting container runs. Empty when the reporter could not name it. */
  @Column(name = "image_version", nullable = false, columnDefinition = "text")
  public String imageVersion;

  /** What the harness answers for {@code --version}, verbatim. Empty when unreadable. */
  @Column(name = "harness_version", nullable = false, columnDefinition = "text")
  public String harnessVersion;

  /** Newline-delimited model ids or aliases, in the order the harness enumerated them. */
  @Column(name = "models", nullable = false, columnDefinition = "text")
  public String models;

  /** Newline-delimited effort levels, in the order the harness enumerated them. */
  @Column(name = "effort_levels", nullable = false, columnDefinition = "text")
  public String effortLevels;

  /** False when {@link #models} is a shipped alias set rather than something the binary printed. */
  @Column(name = "models_enumerated", nullable = false)
  public boolean modelsEnumerated;

  /** False for a harness with no effort flag at all. */
  @Column(name = "effort_supported", nullable = false)
  public boolean effortSupported;

  /** Whether anybody is signed in on this container's credential volume. */
  @Column(name = "authenticated", nullable = false)
  public boolean authenticated;

  /** What the harness said about its auth state — an account, or why it could not tell. */
  @Column(name = "auth_detail", nullable = false, columnDefinition = "text")
  public String authDetail;

  /** True when the probe fell back rather than read the binary. */
  @Column(name = "probe_failed", nullable = false)
  public boolean probeFailed;

  /** Why the probe fell back. Empty when it did not. */
  @Column(name = "probe_detail", nullable = false, columnDefinition = "text")
  public String probeDetail;

  /** Free text naming the reporting container. Display only; never trusted for anything. */
  @Column(name = "reported_by", nullable = false, columnDefinition = "text")
  public String reportedBy;

  /** When the report arrived. This decides which image version wins a read. */
  @Column(name = "reported_at", nullable = false)
  public Instant reportedAt;

  @Column(name = "causation_id")
  public UUID causationId;

  /** {@link #models} as the list it encodes. */
  public List<String> modelList() {
    return decode(models);
  }

  /** {@link #effortLevels} as the list it encodes. */
  public List<String> effortLevelList() {
    return decode(effortLevels);
  }

  /** One id per line, no trailing newline — the same encoding the attachment tool lists use. */
  public static String encode(List<String> values) {
    return values == null ? "" : String.join("\n", values);
  }

  /** The inverse of {@link #encode}. Blank reads as no values, never as one empty value. */
  public static List<String> decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return List.of();
    }
    return List.of(encoded.split("\n"));
  }

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
