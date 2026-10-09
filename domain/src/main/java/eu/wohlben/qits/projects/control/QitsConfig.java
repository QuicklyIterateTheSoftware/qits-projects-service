package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import java.util.List;
import java.util.Map;

/**
 * The parsed, framework-free representation of a workspace checkout's committed qits config file
 * ({@code .config/qits/repository.yml}, or the legacy root-level {@code .qits-config.yml} as
 * fallback). The file is <strong>authoritative</strong>: it is read in-container by the
 * workspace-daemon and surfaced to the host over the control socket as the Part-2 wire schema
 * ({@link WorkspaceConfigView} wraps it; {@code WorkspaceDaemonRegistry} Jackson-deserializes it).
 * There is no host-side DB config store and no reconciler — declared actions/bootstrap steps live
 * only in the file.
 *
 * <p>Every declared entry carries an explicit, deterministic string {@code id:} (defaulting to its
 * {@code name} when absent) that identifies it across the wire; a duplicate id is a user error,
 * allowed to collide.
 *
 * <p>There used to be a fourth component here, {@code services} (with a {@code @JsonAlias} onto
 * the even older {@code daemons:} key) — a workspace-container dev server the daemon's {@code
 * ServiceSupervisor} ran. That concept is retired estate-wide (qits-947): no repository declares
 * an active {@code services:} key any more, and this record carries no field for one.
 * {@link QitsConfigParser#parse} already never looks either key up, so a committed file that
 * still carries a {@code services:} or {@code daemons:} block parses exactly as if it had neither;
 * the class-level {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps that true for any
 * other Jackson-based reading of this record too.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record QitsConfig(
    RepositorySection repository,
    List<FrameworkDecl> frameworks,
    List<ActionDecl> actions,
    List<BootstrapDecl> bootstrap) {

  /** An absent/empty file — the no-op that keeps a config-free workspace on the old path. */
  public static final QitsConfig EMPTY = new QitsConfig(null, List.of(), List.of(), List.of());

  /** Normalize the collections to non-null so callers never null-check. */
  public QitsConfig {
    frameworks = frameworks == null ? List.of() : List.copyOf(frameworks);
    actions = actions == null ? List.of() : List.copyOf(actions);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
  }

  public boolean isEmpty() {
    return repository == null && frameworks.isEmpty() && actions.isEmpty() && bootstrap.isEmpty();
  }

  /** The {@code repository:} section: fields the file may own on the repository itself. */
  public record RepositorySection(String mainBranch, RepositoryArchetype archetype) {}

  /** One {@code frameworks[]} entry — a detection override/hint, consumed live, never stored. */
  public record FrameworkDecl(String kind, String root) {}

  /** One {@code actions[]} entry — a config-declared workspace action. */
  public record ActionDecl(
      String id,
      String name,
      String description,
      String execute,
      String check,
      boolean interactive,
      Map<String, String> environment) {
    /** {@code id} defaults to {@code name} when absent/blank. */
    public ActionDecl {
      id = id == null || id.isBlank() ? name : id;
    }
  }

  /**
   * One {@code bootstrap[]} entry — a config-declared bootstrap step; list position is the
   * execution order.
   */
  public record BootstrapDecl(
      String id,
      String name,
      String description,
      String execute,
      String check,
      Map<String, String> environment) {
    /** {@code id} defaults to {@code name} when absent/blank. */
    public BootstrapDecl {
      id = id == null || id.isBlank() ? name : id;
    }
  }
}
