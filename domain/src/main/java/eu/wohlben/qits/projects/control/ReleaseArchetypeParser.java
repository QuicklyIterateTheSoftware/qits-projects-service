package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@link ReleaseArtifacts#SLOT_CONFIG} for the one fact {@link ReleaseGates} needs: does the
 * file name an {@code archetype:}.
 *
 * <p><b>A presence rule, not a second parser of the archetype schema.</b> qits-ci is the reader of
 * {@code archetype:} that matters — it composes the per-release-request QA pipeline from the named
 * entry of the wrapper's {@code .config/qits/release-archetypes/*.yml}, each of which declares a
 * {@code release-request:} slot (checked by hand across all six shipped archetypes on 2026-09-13;
 * none lacks one). Reading that file too, to confirm the named archetype really carries the slot,
 * would make this service a second owner of a schema qits-ci already owns, and a second place that
 * schema's changes have to be kept in step — for a fact the presence of one key already answers
 * today. This class stops at the key, deliberately, and {@link ReleaseGates} never looks past what
 * it returns.
 *
 * <p><b>Shaped after {@link ReleaseRequestSettingsParser}</b>: a pure, framework-light helper,
 * unit-testable without a clone, using SnakeYAML's {@link SafeConstructor} so repository content can
 * never instantiate a class. It throws on a structural problem rather than answering {@code false}
 * — {@link ReleaseGates} is the one that decides what a throw means for the gate, and it decides the
 * opposite of what {@link ReleaseRequestSettingsParser}'s throw means there: see its javadoc.
 */
@ApplicationScoped
public class ReleaseArchetypeParser {

  /** The one key this reader looks at. Every other key in the file is {@link ReleaseArtifacts}' business. */
  public static final String ARCHETYPE_KEY = "archetype";

  /** A structural problem in {@link ReleaseArtifacts#SLOT_CONFIG} — named for the caller to log. */
  public static class ReleaseArchetypeException extends RuntimeException {
    public ReleaseArchetypeException(String message) {
      super(message);
    }

    public ReleaseArchetypeException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Whether {@code content} names a non-blank {@code archetype:}.
   *
   * <p>An absent or blank key, an empty document, and a document of comments alone are all {@code
   * false} — a repository whose {@link ReleaseArtifacts#SLOT_CONFIG} declares only {@code
   * artifacts:} or {@code userflows:} and no archetype composes nothing at qits-ci, and answering
   * {@code false} for it is the ordinary case, not a failure to detect anything.
   *
   * <p>Bad YAML and a document whose root is not a mapping both throw, naming {@link
   * ReleaseArtifacts#SLOT_CONFIG}.
   */
  public boolean declaresArchetype(String content) {
    if (content == null || content.isBlank()) {
      return false;
    }
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
    } catch (Exception e) {
      throw new ReleaseArchetypeException(
          "Invalid YAML in " + ReleaseArtifacts.SLOT_CONFIG + ": " + e.getMessage(), e);
    }
    if (root == null) {
      return false;
    }
    if (!(root instanceof Map<?, ?> raw)) {
      throw new ReleaseArchetypeException(
          "Expected a mapping at the root of "
              + ReleaseArtifacts.SLOT_CONFIG
              + ", got: "
              + root.getClass().getSimpleName());
    }
    Object archetype = raw.get(ARCHETYPE_KEY);
    if (archetype == null) {
      return false;
    }
    return !String.valueOf(archetype).isBlank();
  }
}
