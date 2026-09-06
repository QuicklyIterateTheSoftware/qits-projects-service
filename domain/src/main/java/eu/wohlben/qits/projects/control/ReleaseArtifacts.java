package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.ReleaseArtifactDto;
import eu.wohlben.qits.projects.dto.ReleaseArtifactsDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * <b>What a release published, read out of the released tag's own tree.</b> The one answer this
 * service can give to "the release landed — now where is the thing it made".
 *
 * <p><b>The tree is the source, and that is a decision rather than a convenience.</b> qits-ci
 * announces a {@code SoftwareRelease} per published artifact, so a record of what was built exists —
 * somewhere else, for the releases whose repositories carry a recipe, and never for the ones that do
 * not. The recipe at the tag is a fact this service can always reach and that cannot go stale: it is
 * the declaration the pipeline itself acted on. So a release made before this endpoint existed is
 * answerable, and one whose CI announced nothing at all is answerable too.
 *
 * <p><b>Nothing here is an error.</b> The port cannot be asked, the tag cannot be read, the recipe
 * will not parse — each is an answer about the release with a sentence on {@code detail}, exactly
 * the stance {@link ReleaseFinalization#deployability} takes over the same tree read, and for the
 * same reason: a panel that says "we could not ask" is useful and a 500 is not. The only refusal is
 * a request id that names nothing.
 *
 * <p><b>Two file kinds are read, and {@link #SLOT_CONFIG} is asked first.</b> A migrated repository
 * declares its release as configuration — one {@code .config/qits/release.yml} whose {@code
 * artifacts:} block is the same declaration and whose {@code userflows:} key replaces the substring
 * hunt below — and the pipelines it used to spell out are composed by qits-ci from a wrapper-owned
 * archetype, so the two legacy files are not in that repository's tree at all. Asking for the new
 * file first is what makes the migration invisible here: where it answers, it is the whole answer
 * and no legacy path runs, so a repository migrating mid-flight never has two declarations read into
 * one list.
 *
 * <p><b>The legacy read is not deprecated, it is permanent.</b> The tree at a tag is immutable, so
 * a release made before its repository migrated still carries the two old files and nothing will
 * ever put a {@code release.yml} in that tree — and answering for old releases forever is this
 * class's entire point. The fallback goes when the last pre-migration tag stops being interesting,
 * which is to say never.
 *
 * <p><b>Absent is not empty-with-an-excuse.</b> A repository declaring none of the three files at
 * all publishes nothing and gets no {@code detail} — every SPA on this platform was in that case,
 * and putting a sentence there would turn the ordinary answer into a warning.
 */
@ApplicationScoped
public class ReleaseArtifacts {

  private static final Logger LOG = Logger.getLogger(ReleaseArtifacts.class);

  /**
   * The repository's release configuration, and the first thing looked for at the tag.
   *
   * <p>It is not a pipeline: qits-ci composes the two release triggers from this file and a
   * wrapper-owned archetype recipe, so what is here is declaration alone. Two keys are read —
   * {@code artifacts:}, entry-for-entry what {@link #RELEASE_RECIPE} used to carry, and {@code
   * userflows:}. Everything else it may carry ({@code archetype:}, {@code release-request:}, {@code
   * release:}, and a per-artifact {@code sbom:} path) is the composer's business and is passed over
   * here rather than refused: this reader must not become a second schema owner that fails a panel
   * over a key it was never told about.
   */
  static final String SLOT_CONFIG = ".config/qits/release.yml";

  /** The release pipeline's own declaration of what it publishes — {@code artifacts:} is read. */
  static final String RELEASE_RECIPE = ".config/qits/ci-event-release.yml";

  /**
   * The per-release-request QA pipeline. It is read for one substring and never parsed: what is
   * wanted from it is whether this repository publishes a userflow bundle, and that is a shell line
   * inside a step rather than anything the recipe declares. {@link #SLOT_CONFIG}'s {@code
   * userflows:} key is the declaration that retires the hunt — for the tags that have it.
   */
  static final String QA_RECIPE = ".config/qits/ci-event-release-request.yml";

  /** The docs scope every userflow bundle on this platform is published under. */
  static final String USERFLOWS_SCOPE = "@userflows/";

  /** {@code @userflows/<site>} as it appears in the QA recipe's publish line. */
  private static final Pattern USERFLOWS_SITE =
      Pattern.compile(Pattern.quote(USERFLOWS_SCOPE) + "([A-Za-z0-9][A-Za-z0-9._-]*)");

  @Inject ReleaseRequestRepository requests;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject Instance<ReleaseGitHost> gitHosts;

  /** What one release request's tag carries, or why that cannot be said. */
  public ReleaseArtifactsDto of(String repoId, String requestId) {
    Released released =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row =
                      requests
                          .findByIdOptional(requestId)
                          .filter(candidate -> candidate.repoId.equals(repoId))
                          .orElseThrow(
                              () ->
                                  new NotFoundException(
                                      "Release request not found: " + requestId));
                  if (row.state != ReleaseRequest.State.RELEASED
                      || row.version == null
                      || row.version.isBlank()) {
                    return null;
                  }
                  return new Released(
                      row.version.trim(),
                      pendingTags
                          .findByRequest(requestId)
                          .map(tag -> tag.releasedSha)
                          .orElse(null),
                      row.repoName,
                      row.mergedSha);
                });
    if (released == null) {
      // The honest answer to "what did this publish" for a request that has not released, and not
      // an error: the page asks it of every request it draws.
      return new ReleaseArtifactsDto(null, null, false, List.of(), "Not released yet");
    }
    return read(repoId, released);
  }

  /** The facts about one landed release that the tree read needs, carried out of its transaction. */
  private record Released(String version, String releasedSha, String repoName, String mergedSha) {}

  private ReleaseArtifactsDto read(String repoId, Released released) {
    if (!gitHosts.isResolvable()) {
      return nothing(
          released,
          false,
          "No git host is configured, so what this release published cannot be read");
    }
    ReleaseGitHost host = gitHosts.get();
    String rev = "refs/tags/" + released.version();
    ReleaseGitHost.Answer<List<String>> tree;
    try {
      tree = host.tree(repoId, rev);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not be read as an answer.
      LOG.debugf(e, "The git host threw reading the tree of %s at %s", repoId, rev);
      return nothing(released, false, "The git host could not be asked what " + rev + " contains");
    }
    if (!tree.ok()) {
      return nothing(released, false, tree.detail());
    }
    // The same file, the same reading, one seam over: this is the platform's declaration that
    // something deploys the repository, and ReleaseFinalization forks the whole publish phase on it.
    boolean deployable = tree.value().contains(ReleaseFinalization.DEPLOYMENTS_MANIFEST);

    List<ReleaseArtifactDto> artifacts = new ArrayList<>();
    if (tree.value().contains(SLOT_CONFIG)) {
      ReleaseGitHost.Answer<String> config = file(host, repoId, rev, SLOT_CONFIG);
      if (config == null || !config.ok()) {
        return nothing(
            released,
            deployable,
            "The release configuration could not be read: "
                + (config == null ? "the git host could not be asked" : config.detail()));
      }
      try {
        Map<String, Object> document = document(config.value());
        artifacts.addAll(declared(document, released.version()));
        userflows(document, released).ifPresent(artifacts::add);
      } catch (RuntimeException e) {
        LOG.debugf(
            "The release configuration of %s at %s does not parse: %s", repoId, rev, e.toString());
        return nothing(
            released,
            deployable,
            "The release configuration does not read as a release declaration");
      }
      // The configured file is the whole declaration. A repository migrating mid-flight may still
      // carry a legacy recipe at this tag, and reading it too would answer one release's artifacts
      // out of two declarations that were never written to agree.
      return answer(released, deployable, artifacts);
    }
    if (tree.value().contains(RELEASE_RECIPE)) {
      ReleaseGitHost.Answer<String> recipe = file(host, repoId, rev, RELEASE_RECIPE);
      if (recipe == null || !recipe.ok()) {
        return nothing(
            released,
            deployable,
            "The release recipe could not be read: "
                + (recipe == null ? "the git host could not be asked" : recipe.detail()));
      }
      try {
        artifacts.addAll(declared(recipe.value(), released.version()));
      } catch (RuntimeException e) {
        LOG.debugf("The release recipe of %s at %s does not parse: %s", repoId, rev, e.toString());
        return nothing(
            released, deployable, "The release recipe does not declare a readable artifact list");
      }
    }
    userflows(host, repoId, rev, tree.value(), released).ifPresent(artifacts::add);
    return answer(released, deployable, artifacts);
  }

  /** What one release published, with nothing to explain. */
  private static ReleaseArtifactsDto answer(
      Released released, boolean deployable, List<ReleaseArtifactDto> artifacts) {
    return new ReleaseArtifactsDto(
        released.version(),
        released.releasedSha(),
        deployable,
        List.copyOf(artifacts),
        // A repository that declares nothing published nothing, and that is not a problem to
        // explain. The empty list is the whole answer.
        null);
  }

  /**
   * The bundle the QA pipeline publishes, where it publishes one.
   *
   * <p><b>Derived, because nothing declares it.</b> The userflow docs site is a {@code curl} inside
   * a step rather than an entry under {@code artifacts:}, so the recipe is read for the coordinate
   * it spells rather than parsed. The site name is taken from the recipe itself and not composed
   * from the repository's name, because the two genuinely differ — {@code qits-projects-service}
   * publishes {@code @userflows/qits-projects} — and a composed name would be a link to nothing.
   *
   * <p><b>Its version is the fold's sha and not the calver</b>, because that pipeline runs per
   * release request and publishes at {@code $QITS_CI_SHA}. Asking for the calver would 404 on a
   * bundle that is certainly there.
   */
  private Optional<ReleaseArtifactDto> userflows(
      ReleaseGitHost host, String repoId, String rev, List<String> tree, Released released) {
    if (!tree.contains(QA_RECIPE) || released.mergedSha() == null) {
      return Optional.empty();
    }
    ReleaseGitHost.Answer<String> recipe = file(host, repoId, rev, QA_RECIPE);
    if (recipe == null || !recipe.ok()) {
      return Optional.empty();
    }
    Matcher site = USERFLOWS_SITE.matcher(recipe.value());
    String name =
        site.find()
            ? USERFLOWS_SCOPE + site.group(1)
            : (released.repoName() == null || released.repoName().isBlank()
                ? null
                : USERFLOWS_SCOPE + released.repoName());
    if (name == null || !recipe.value().contains(USERFLOWS_SCOPE)) {
      return Optional.empty();
    }
    return Optional.of(
        new ReleaseArtifactDto("userflows", name, released.mergedSha()));
  }

  /**
   * The bundle {@link #SLOT_CONFIG} <b>declares</b>, which is the same fact one paragraph up said
   * out loud instead of left to be found in a shell line.
   *
   * <p>Two spellings, because the two cases are genuinely different facts. {@code userflows: true}
   * says "this repository publishes its bundle under its own name", and the site is then the
   * repository's name — which is what a composed pipeline publishes to, since the archetype has
   * nothing else to interpolate. {@code userflows: <site>} states the site, and it is there for the
   * repositories whose bundle is not named after them: {@code qits-projects-service} publishes
   * {@code @userflows/qits-projects}, so its file says {@code userflows: qits-projects}. Absent, and
   * {@code false}, are both "no bundle" — which is most repositories.
   *
   * <p>Anything else under the key is thrown rather than passed over, the same stance {@link
   * #declared} takes: a list or a mapping there is a file whose author meant something this reader
   * cannot see, and quietly dropping a bundle is the failure {@code detail} exists to avoid.
   *
   * <p><b>Its version is still the fold's sha and not the calver</b>, for the reason the legacy
   * reading gives: the bundle is published per release request, at {@code $QITS_CI_SHA}.
   */
  private static Optional<ReleaseArtifactDto> userflows(
      Map<String, Object> document, Released released) {
    Object declared = document.get("userflows");
    if (declared == null || Boolean.FALSE.equals(declared)) {
      return Optional.empty();
    }
    String site;
    if (Boolean.TRUE.equals(declared)) {
      site = text(released.repoName());
    } else if (declared instanceof Map || declared instanceof List) {
      throw new IllegalArgumentException("userflows is neither a flag nor a site name");
    } else {
      site = text(declared);
    }
    if (site == null || released.mergedSha() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ReleaseArtifactDto("userflows", USERFLOWS_SCOPE + site, released.mergedSha()));
  }

  /** {@link ReleaseGitHost#file} with the port's must-not-throw promise held to. */
  private ReleaseGitHost.Answer<String> file(
      ReleaseGitHost host, String repoId, String rev, String path) {
    try {
      return host.file(repoId, rev, path);
    } catch (RuntimeException e) {
      LOG.debugf(e, "The git host threw reading %s of %s at %s", path, repoId, rev);
      return null;
    }
  }

  /**
   * The {@code artifacts:} list of a release recipe or a release configuration, as {@link
   * ReleaseArtifactDto}s at the released version. <b>One reading serves both files</b>, because the
   * block is the same block — that sameness is what made the migration a file move rather than a
   * translation, and it is worth keeping.
   *
   * <p>Anything that is not a list of mappings with a {@code type} and a {@code name} is thrown
   * rather than skipped, so the caller can say "the recipe does not parse" instead of quietly
   * answering a shorter list than the repository declares. An entry's <b>other</b> keys are passed
   * over, not refused — a configuration's {@code sbom:} path is a fact about how the pipeline builds
   * the artifact and says nothing about where the artifact went, which is the only question here.
   */
  private static List<ReleaseArtifactDto> declared(String yaml, String version) {
    return declared(document(yaml), version);
  }

  /**
   * One repository-authored YAML document as plain maps and lists, or a throw.
   *
   * <p>{@link SafeConstructor} — never an arbitrary class out of repository content — the posture
   * {@link QitsConfigParser} states and the reason it is worth restating: this file comes from a
   * repository and is not trusted input. An empty document is a mapping with nothing in it, which
   * is what makes "declares no artifacts" and "declares nothing at all" the same answer.
   */
  private static Map<String, Object> document(String yaml) {
    Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    if (root == null) {
      return Map.of();
    }
    if (!(root instanceof Map<?, ?> document)) {
      throw new IllegalArgumentException("the document root is not a mapping");
    }
    return asMap(document);
  }

  private static List<ReleaseArtifactDto> declared(Map<String, Object> document, String version) {
    Object declared = document.get("artifacts");
    if (declared == null) {
      return List.of();
    }
    if (!(declared instanceof List<?> entries)) {
      throw new IllegalArgumentException("artifacts is not a list");
    }
    List<ReleaseArtifactDto> out = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> item)) {
        throw new IllegalArgumentException("an artifacts entry is not a mapping");
      }
      Map<String, Object> fields = asMap(item);
      String type = text(fields.get("type"));
      String name = text(fields.get("name"));
      if (type == null || name == null) {
        throw new IllegalArgumentException("an artifacts entry names no type or no name");
      }
      out.add(new ReleaseArtifactDto(type, name, version));
    }
    return out;
  }

  private static Map<String, Object> asMap(Map<?, ?> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach((key, value) -> out.put(String.valueOf(key), value));
    return out;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  /** A released request whose artifacts could not be established, with the reason on the answer. */
  private static ReleaseArtifactsDto nothing(
      Released released, boolean deployable, String detail) {
    return new ReleaseArtifactsDto(
        released.version(),
        released.releasedSha(),
        deployable,
        List.of(),
        detail == null ? "What this release published could not be read" : detail);
  }
}
