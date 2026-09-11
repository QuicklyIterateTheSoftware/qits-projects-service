package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code .config/qits/release-requests.yml} — the repository's release-request settings, and
 * the one file the release-quality-gates work adds.
 *
 * <p>Shaped after {@link QitsConfigParser}: a pure, framework-light helper that is unit-testable
 * without a clone, using SnakeYAML's {@link SafeConstructor} so repository content can never
 * instantiate a class. What it does <em>not</em> copy is that parser's {@code readConfig} arm: the
 * gate set is read from the git host's tree listing rather than out of a bare origin, so the caller
 * ({@link ReleaseGates}) does the reading and this class only ever sees bytes.
 *
 * <p><b>It never fails open.</b> Bad YAML, a document that is not a mapping and a {@code
 * manual-review} that is not a boolean all throw {@link ReleaseRequestSettingsException}, naming
 * {@link #SETTINGS_PATH}. Degrading any of them to {@link ReleaseRequestSettings#NONE} would make a
 * typo delete an approval gate, silently, on the repository whose releases most need one. Absence is
 * the only thing that means no gate, and absence is decided by the caller's listing rather than
 * here.
 *
 * <p><b>An unknown key is ignored</b>, so a reader that predates a key added later still answers,
 * and a file written for a newer platform does not brick an older one.
 */
@ApplicationScoped
public class ReleaseRequestSettingsParser {

  /** The committed settings location. There is no legacy path: the file is new. */
  public static final String SETTINGS_PATH = ".config/qits/release-requests.yml";

  /** The one key today. */
  public static final String MANUAL_REVIEW = "manual-review";

  /** A structural problem in {@link #SETTINGS_PATH}. It names the file; see the class javadoc. */
  public static class ReleaseRequestSettingsException extends RuntimeException {
    public ReleaseRequestSettingsException(String message) {
      super(message);
    }

    public ReleaseRequestSettingsException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The settings the given file content declares.
   *
   * <p>An empty or whitespace-only file, and one whose document is empty ({@code ---} alone, a file
   * of comments), are {@link ReleaseRequestSettings#NONE}: the file exists and declares nothing,
   * which is the same answer as declaring {@code false}. Everything else that will not read as
   * {@code manual-review: <boolean>} throws.
   */
  public ReleaseRequestSettings parse(String content) {
    if (content == null || content.isBlank()) {
      return ReleaseRequestSettings.NONE;
    }
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
    } catch (Exception e) {
      throw new ReleaseRequestSettingsException(
          "Invalid YAML in " + SETTINGS_PATH + ": " + e.getMessage(), e);
    }
    if (root == null) {
      return ReleaseRequestSettings.NONE;
    }
    if (!(root instanceof Map<?, ?> raw)) {
      throw new ReleaseRequestSettingsException(
          "Expected a mapping at the root of "
              + SETTINGS_PATH
              + ", got: "
              + root.getClass().getSimpleName());
    }
    Map<String, Object> map = new LinkedHashMap<>();
    raw.forEach((k, v) -> map.put(String.valueOf(k), v));

    Object manualReview = map.get(MANUAL_REVIEW);
    if (manualReview == null) {
      // Absent key: the same answer as `false`, and deliberately not an error. A file that carries
      // only a key this reader does not know yet is exactly this case.
      return ReleaseRequestSettings.NONE;
    }
    if (!(manualReview instanceof Boolean flag)) {
      throw new ReleaseRequestSettingsException(
          "Expected a boolean at '"
              + MANUAL_REVIEW
              + "' in "
              + SETTINGS_PATH
              + ", got: "
              + manualReview.getClass().getSimpleName());
    }
    return new ReleaseRequestSettings(flag);
  }
}
