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
 * somewhere else, for the releases whose repositories publish one at all, and never for the ones
 * that do not. The declaration at the tag is a fact this service can always reach and that cannot go
 * stale: it is what the pipeline itself was composed from. So a release made before this endpoint
 * existed is answerable, and one whose CI announced nothing at all is answerable too.
 *
 * <p><b>Nothing here is an error.</b> The port cannot be asked, the tag cannot be read, the
 * configuration will not parse — each is an answer about the release with a sentence on {@code
 * detail}, exactly the stance {@link ReleaseFinalization#deployability} takes over the same tree
 * read, and for the same reason: a panel that says "we could not ask" is useful and a 500 is not.
 * The only refusal is a request id that names nothing.
 *
 * <p><b>One file is read, and it is {@link #SLOT_CONFIG}.</b> A repository declares its release as
 * configuration — one {@code .config/qits/release.yml} whose {@code artifacts:} block is the
 * declaration and whose {@code userflows:} key states the bundle — and the pipelines it used to
 * spell out are composed by qits-ci from a wrapper-owned archetype, so nothing here has a pipeline
 * to parse.
 *
 * <p><b>The legacy pair is gone, and what that costs is stated rather than hidden.</b> This class
 * read {@code .config/qits/ci-event-release.yml} for the same {@code artifacts:} block and hunted
 * {@code .config/qits/ci-event-release-request.yml} for a {@code @userflows/} substring until
 * 2026-09-18, on the argument that a tag's tree is immutable so the old files answer for old
 * releases for ever. What retired the argument is that no repository in the estate carries either
 * file on its {@code main} any more (checked across every submodule), so the branch was reachable
 * only by a tag cut before its repository migrated — and the price of the removal, which is real, is
 * that such a tag now answers an empty artifact list instead of the one its recipe declared. That is
 * the ordinary "declares nothing" answer below rather than an error, and it is the trade this
 * service takes in exchange for one reader of one file.
 *
 * <p><b>Absent is not empty-with-an-excuse.</b> A repository declaring no {@link #SLOT_CONFIG} at
 * all publishes nothing and gets no {@code detail} — every SPA on this platform was in that case,
 * and putting a sentence there would turn the ordinary answer into a warning.
 */
@ApplicationScoped
public class ReleaseArtifacts {

  private static final Logger LOG = Logger.getLogger(ReleaseArtifacts.class);

  /**
   * The repository's release configuration, and the only thing looked for at the tag.
   *
   * <p>It is not a pipeline: qits-ci composes the two release triggers from this file and a
   * wrapper-owned archetype recipe, so what is here is declaration alone. Two keys are read —
   * {@code artifacts:} and {@code userflows:}. Everything else it may carry ({@code archetype:},
   * {@code release-request:}, {@code release:}, and a per-artifact {@code sbom:} path) is the
   * composer's business and is passed over here rather than refused: this reader must not become a
   * second schema owner that fails a panel over a key it was never told about.
   */
  static final String SLOT_CONFIG = ".config/qits/release.yml";

  /** The docs scope every userflow bundle on this platform is published under. */
  static final String USERFLOWS_SCOPE = "@userflows/";

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
                  // RELEASED and FINALIZED alike: a tag was cut in both, and what it carries does
                  // not change when it reaches main. Anything else has released nothing to read.
                  if ((row.state != ReleaseRequest.State.RELEASED
                          && row.state != ReleaseRequest.State.FINALIZED)
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
    }
    // The configured file is the whole declaration, and a tag carrying none declared nothing that
    // is readable here: the ordinary answer for a repository that publishes nothing, and — since
    // the legacy recipes were retired — also the answer for a tag whose declaration was one of
    // them. See the class javadoc's "The legacy pair is gone".
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
   * The bundle {@link #SLOT_CONFIG} <b>declares</b>, said out loud instead of left to be found in a
   * shell line.
   *
   * <p><b>It replaced a derivation, and the derivation is gone.</b> The userflow docs site used to
   * be a {@code curl} inside a step of {@code .config/qits/ci-event-release-request.yml}, so that
   * file was read for the {@code @userflows/<site>} coordinate it spelled rather than parsed — the
   * last content read of a legacy recipe anywhere in this estate, retired with the files themselves
   * on 2026-09-18. Everything the hunt was careful about survives in the key: the site is stated
   * rather than composed from the repository's name, because the two genuinely differ.
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
   * <p><b>Its version is the fold's sha and not the calver</b>, because the bundle is published by
   * the pipeline that runs per release request, at {@code $QITS_CI_SHA}. Asking for the calver would
   * 404 on a bundle that is certainly there.
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

  /**
   * The {@code artifacts:} list of a release configuration, as {@link ReleaseArtifactDto}s at the
   * released version.
   *
   * <p>Anything that is not a list of mappings with a {@code type} and a {@code name} is thrown
   * rather than skipped, so the caller can say "the configuration does not parse" instead of quietly
   * answering a shorter list than the repository declares. An entry's <b>other</b> keys are passed
   * over, not refused — a configuration's {@code sbom:} path is a fact about how the pipeline builds
   * the artifact and says nothing about where the artifact went, which is the only question here.
   */
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
