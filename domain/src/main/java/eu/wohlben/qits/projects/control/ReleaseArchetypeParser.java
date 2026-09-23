package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@link ReleaseArtifacts#SLOT_CONFIG} for the one fact {@link ReleaseGates} needs: does the
 * file declare a QA pipeline — an {@code archetype:} to compose one from, or a {@code
 * release-request:} slot of the repository's own.
 *
 * <p><b>A presence rule, not a second parser of the slot schema.</b> qits-ci is the reader that
 * matters — it composes the per-release-request QA pipeline either from the named entry of the
 * wrapper's {@code .config/qits/release-archetypes/*.yml}, each of which declares a {@code
 * release-request:} slot (checked by hand across all six shipped archetypes on 2026-09-13; none
 * lacks one), or from the repository's <em>own</em> {@code release-request:} slot, which wins
 * outright when it is there ({@code CiReleaseComposer.choose}: the repository's slot is taken when
 * present, archetype or not). Reading either document's steps here, to confirm what is composed,
 * would make this service a second owner of a schema qits-ci already owns, and a second place that
 * schema's changes have to be kept in step — for a fact the presence of a key already answers
 * today. This class stops at the keys, deliberately, and {@link ReleaseGates} never looks past what
 * it returns.
 *
 * <p><b>{@link ReleaseGates} is now its ONLY caller, and the audit above is why the other one had to
 * go</b> (2026-09-16). {@code ReleaseFinalization} used to ask the same question of the released
 * tag's tree to decide the <b>publish</b> gate, and that was wrong on a fact the audit did not cover:
 * what the audit verified is that every archetype declares a {@code release-request:} slot, which is
 * <em>this</em> gate's slot. The publish gate's slot is {@code release:}, and {@code spa-frontend}
 * and {@code cli} deliberately declare none — an SPA publishes nothing, since the consuming service
 * carries it as a submodule and builds the bundle into its own image — so "names an archetype"
 * stamped a gate for releases no run would ever answer and their {@code main} stopped moving. The
 * publish gate asks qits-ci itself now ({@code control/PublishRuns}), because the archetype is in the
 * wrapper repository and a repository's own file may override the slot wholesale, which puts the
 * answer out of this service's reach entirely. <b>Do not widen this class towards that question</b>:
 * the presence rule is right for the gate it serves and only for that gate.
 *
 * <p><b>Shaped after {@link ReleaseRequestSettingsParser}</b>: a pure, framework-light helper,
 * unit-testable without a clone, using SnakeYAML's {@link SafeConstructor} so repository content can
 * never instantiate a class. It throws on a structural problem rather than answering {@code false}
 * — {@link ReleaseGates} is the one that decides what a throw means for the gate, and it decides the
 * opposite of what {@link ReleaseRequestSettingsParser}'s throw means there: see its javadoc.
 */
@ApplicationScoped
public class ReleaseArchetypeParser {

  /**
   * One of the two keys this reader looks at. Every other key in the file is {@link
   * ReleaseArtifacts}' business.
   */
  public static final String ARCHETYPE_KEY = "archetype";

  /**
   * The other: a repository's own QA slot, qits-ci's key for the pipeline a release request is
   * gated by. A repository may inline it instead of naming an archetype, and qits-ci takes it
   * outright when it is there — so it composes a QA pipeline for such a repository exactly as an
   * archetype does.
   */
  public static final String RELEASE_REQUEST_KEY = "release-request";

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
   * Whether {@code content} declares a QA pipeline: a non-blank {@code archetype:}, or a non-empty
   * {@code release-request:} of its own. Either one makes qits-ci compose a pipeline for a release
   * request of this repository, so either one is the gate.
   *
   * <p><b>The {@code release-request:} half is what the 2026-09-22 defect cost.</b> This asked for
   * {@code archetype:} alone, on the stated reasoning that a {@link ReleaseArtifacts#SLOT_CONFIG}
   * declaring only {@code artifacts:} or {@code userflows:} "composes nothing at qits-ci". That is
   * not true of a file that inlines its own slots: {@code CiReleaseComposer.choose} takes the
   * repository's slot list outright when it is present, archetype or not, so such a repository does
   * get a QA run — it simply got no gate, released within a second of the request being created, and
   * its QA run then died on a {@code release/<id>} branch the release had already deleted. Measured
   * three times on {@code qits-landing-app}, the one repository of 49 on the estate with slots and
   * no archetype.
   *
   * <p>An absent key, an empty document, and a document of comments alone are all {@code false}, and
   * so is a key that declares nothing — a blank {@code archetype:}, an empty {@code
   * release-request:}, {@code []} or <code>{}</code>. A key declaring nothing is not a pipeline.
   *
   * <p>Bad YAML and a document whose root is not a mapping both throw, naming {@link
   * ReleaseArtifacts#SLOT_CONFIG}.
   */
  public boolean declaresQaPipeline(String content) {
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
    return declaresSomething(raw.get(ARCHETYPE_KEY)) || declaresSomething(raw.get(RELEASE_REQUEST_KEY));
  }

  /**
   * Whether a key's value declares anything at all. Presence only: an empty list and an empty
   * mapping are the YAML spellings of "nothing", exactly as a blank scalar is, and reading any
   * further into a list of steps would be the second schema reader this class refuses to be.
   */
  private static boolean declaresSomething(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Map<?, ?> mapping) {
      return !mapping.isEmpty();
    }
    if (value instanceof Iterable<?> items) {
      return items.iterator().hasNext();
    }
    return !String.valueOf(value).isBlank();
  }
}
