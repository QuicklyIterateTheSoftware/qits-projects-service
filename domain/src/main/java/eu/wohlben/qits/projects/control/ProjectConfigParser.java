package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code .config/qits/project.yml} — the wrapper's declaration <em>about the project</em>,
 * as opposed to {@link QitsConfigParser}'s {@code repository.yml}, which is about one repository.
 *
 * <p>Shaped exactly after {@link ReleaseRequestSettingsParser}: a pure, framework-light helper that
 * is unit-testable without a clone, over SnakeYAML's {@link SafeConstructor} so repository content
 * can never instantiate a class, and with no {@code readConfig} arm of its own — <b>the caller does
 * the reading</b> and this class only ever sees bytes. {@link ReleaseGates} is the existing example
 * of that calling pattern; here the caller is {@code WrapperReconcileService}, which already has the
 * wrapper's mirror open to read {@code .gitmodules} out of.
 *
 * <p><b>It never fails open.</b> Bad YAML, a document that is not a mapping and a {@link
 * #SUPPORTS_ENVIRONMENTS} that is not a boolean all throw {@link ProjectConfigException}, naming
 * {@link #CONFIG_PATH}. Absence of the file, absence of the key and an explicit {@code true} are one
 * answer — {@link ProjectConfig#DEFAULT}, the project supports environments — and only an explicit
 * {@code false} changes anything. Degrading a malformed declaration to either value would let a typo
 * re-route a project's deployments silently, which is the one outcome this seam exists to prevent;
 * absence is decided by the caller's listing rather than here.
 *
 * <p><b>An unknown key is ignored</b>, so a reader that predates a key added later still answers,
 * and a file written for a newer platform does not brick an older one.
 */
@ApplicationScoped
public class ProjectConfigParser {

  /** The committed location, in the project's wrapper. There is no legacy path: the file is new. */
  public static final String CONFIG_PATH = ".config/qits/project.yml";

  /**
   * The one key today. Spelled with an underscore because that is how the platform's project-level
   * declaration was specified; {@code release-requests.yml} beside it uses a hyphen, and neither
   * file reads the other's spelling.
   */
  public static final String SUPPORTS_ENVIRONMENTS = "supports_environments";

  /** A structural problem in {@link #CONFIG_PATH}. It names the file; see the class javadoc. */
  public static class ProjectConfigException extends RuntimeException {
    public ProjectConfigException(String message) {
      super(message);
    }

    public ProjectConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The configuration the given file content declares.
   *
   * <p>An empty or whitespace-only file, and one whose document is empty ({@code ---} alone, a file
   * of comments), are {@link ProjectConfig#DEFAULT}: the file exists and declares nothing, which is
   * the same answer as declaring {@code true}. Everything else that will not read as {@code
   * supports_environments: <boolean>} throws.
   */
  public ProjectConfig parse(String content) {
    if (content == null || content.isBlank()) {
      return ProjectConfig.DEFAULT;
    }
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
    } catch (Exception e) {
      throw new ProjectConfigException("Invalid YAML in " + CONFIG_PATH + ": " + e.getMessage(), e);
    }
    if (root == null) {
      return ProjectConfig.DEFAULT;
    }
    if (!(root instanceof Map<?, ?> raw)) {
      throw new ProjectConfigException(
          "Expected a mapping at the root of "
              + CONFIG_PATH
              + ", got: "
              + root.getClass().getSimpleName());
    }
    Map<String, Object> map = new LinkedHashMap<>();
    raw.forEach((k, v) -> map.put(String.valueOf(k), v));

    Object supportsEnvironments = map.get(SUPPORTS_ENVIRONMENTS);
    if (supportsEnvironments == null) {
      // Absent key: the same answer as `true`, and deliberately not an error. A file that carries
      // only a key this reader does not know yet is exactly this case.
      return ProjectConfig.DEFAULT;
    }
    if (!(supportsEnvironments instanceof Boolean flag)) {
      throw new ProjectConfigException(
          "Expected a boolean at '"
              + SUPPORTS_ENVIRONMENTS
              + "' in "
              + CONFIG_PATH
              + ", got: "
              + supportsEnvironments.getClass().getSimpleName());
    }
    return new ProjectConfig(flag);
  }
}
