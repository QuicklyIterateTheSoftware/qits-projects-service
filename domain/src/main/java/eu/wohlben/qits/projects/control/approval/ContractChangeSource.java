package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.control.DownstreamPins;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * <b>The fold changes the repository's published contract, and another project consumes it</b>
 * (epic qits-1038, task qits-1291 — the owner's cross-project example).
 *
 * <ul>
 *   <li><b>Trigger</b>: the fold changes a path under {@value #DIRECTORY} — the recorded answers and
 *       provider states the platform packages as the golden masters — against the same base every
 *       content source reads ({@link ApprovalSubject#changes()}, the newest release tag). Gitlinks
 *       never count; a rename counts by either path.
 *   <li><b>What is published</b>: {@code contracts.application} in the fold's own {@code
 *       .config/qits/release.yml} names the application, and the packages are {@code
 *       eu.wohlben.qits:<app>-golden-masters} (maven) and {@code @qits/<app without qits->-golden-masters}
 *       (npm). No {@code contracts.application} is no published contract and no requirement.
 *   <li><b>Consumers</b>: qits-maintenance's downstream closure of the repository ({@link
 *       DownstreamPins#downstreamOf}), its direct entries kept where their own manifests pin one of
 *       those packages ({@link DownstreamPins#pinsOf}) — a closure also holds the providers that pin
 *       this repository's consumer pacts, and they are not consumers of its contract. Each
 *       consumer's project comes from this service's own repository rows: by the row id the closure
 *       carries, else by a name exactly one project knows.
 *   <li><b>One requirement per consumer project other than the request's own</b>, {@code
 *       contract:<slug>}, admitting a person and that project's agent, excluding the authors. A
 *       same-project consumer adds nothing: its pact is verified against the provider in CI.
 * </ul>
 *
 * <p><b>Advisory, never fail-closed</b> (the epic's judgement call): qits-maintenance unconfigured,
 * unreachable or refusing, a {@code release.yml} that cannot be read or parsed, a consumer whose
 * project cannot be told — each is a WARN and no requirement for what it hides, because failing
 * closed would hold every provider release during a maintenance outage. A settled request reads
 * nothing here: this is a content source, asked only while the fold is readable.
 */
@ApplicationScoped
public class ContractChangeSource implements ApprovalRequirementSource {

  private static final Logger LOG = Logger.getLogger(ContractChangeSource.class);

  /** The id prefix; the consumer project's slug follows. */
  public static final String ID_PREFIX = "contract:";

  /** The directory the golden masters are packaged from. */
  public static final String DIRECTORY = "golden-masters/";

  /** The file whose {@code contracts.application} names what the golden masters publish under. */
  static final String RELEASE_CONFIG = ".config/qits/release.yml";

  /** How many changed paths a requirement's detail lists, as many as the work-item comment shows. */
  static final int MAX_PATHS = 20;

  static final String MAVEN_GROUP = "eu.wohlben.qits";
  static final String NPM_SCOPE = "@qits/";
  static final String SUFFIX = "-golden-masters";

  @Inject Instance<DownstreamPins> downstream;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  /** A consumer repository's project, as this service's rows know it. */
  record ConsumerProject(String id, String slug) {}

  @Override
  public List<ApprovalRequirement> requirementsFor(ApprovalSubject subject) {
    List<String> paths = changedPaths(subject.changes());
    if (paths.isEmpty()) {
      return List.of();
    }
    String application = application(subject);
    if (application == null) {
      return List.of();
    }
    DownstreamPins pins = pins();
    if (pins == null) {
      LOG.warnf(
          "The fold %s of %s changes its golden masters, and no qits-maintenance client is wired to"
              + " say who consumes them; no contract requirement",
          subject.mergedSha(), subject.repoId());
      return List.of();
    }
    Optional<List<DownstreamPins.Downstream>> closure = pins.downstreamOf(subject.repoId());
    if (closure.isEmpty()) {
      LOG.warnf(
          "The fold %s of %s changes its golden masters, and qits-maintenance could not say who"
              + " consumes them; no contract requirement",
          subject.mergedSha(), subject.repoId());
      return List.of();
    }
    Set<String> packages = packagesOf(application);
    Map<String, ConsumerProject> projects = new LinkedHashMap<>();
    Map<String, Set<String>> consumersByProject = new LinkedHashMap<>();
    for (DownstreamPins.Downstream entry : closure.get()) {
      // A pin on a package the root publishes is a direct edge: anything deeper was reached through
      // somebody else's release, not through this contract.
      if (entry.depth() != 1 || entry.repository() == null || entry.repository().isBlank()) {
        continue;
      }
      Optional<List<DownstreamPins.Pin>> held = pins.pinsOf(entry.repository());
      if (held.isEmpty()) {
        LOG.warnf(
            "Could not read what %s pins from qits-maintenance; it is not counted as a consumer of"
                + " the golden masters of %s",
            entry.repository(), subject.repoId());
        continue;
      }
      if (held.get().stream().noneMatch(pin -> pins(pin, packages))) {
        continue;
      }
      Optional<ConsumerProject> project = projectOf(entry);
      if (project.isEmpty()) {
        LOG.warnf(
            "%s pins the golden masters of %s, and no project here can be told for it; no contract"
                + " requirement for it",
            entry.repository(), subject.repoId());
        continue;
      }
      if (project.get().id().equals(subject.projectId())) {
        continue;
      }
      projects.putIfAbsent(project.get().id(), project.get());
      consumersByProject
          .computeIfAbsent(project.get().id(), key -> new TreeSet<>())
          .add(entry.repository());
    }
    List<ApprovalRequirement> requirements = new ArrayList<>();
    for (Map.Entry<String, ConsumerProject> consumer : projects.entrySet()) {
      requirements.add(
          requirement(consumer.getValue(), consumersByProject.get(consumer.getKey()), paths));
    }
    return requirements;
  }

  @Override
  public int order() {
    return 600;
  }

  /** One consumer project's requirement. */
  static ApprovalRequirement requirement(
      ConsumerProject project, Set<String> consumers, List<String> paths) {
    String slug = project.slug() == null || project.slug().isBlank() ? project.id() : project.slug();
    String shown =
        paths.size() > MAX_PATHS
            ? "; " + MAX_PATHS + " of " + paths.size() + " changed paths listed"
            : "";
    return new ApprovalRequirement(
        ID_PREFIX + slug,
        "Golden masters consumed by project "
            + slug
            + " changed (pinned by "
            + String.join(", ", consumers)
            + shown
            + ")",
        List.of(ApprovalRole.PERSON, ApprovalRole.agentOf(project.id())),
        true,
        String.join(ApprovalRequirement.PATH_SEPARATOR, paths.stream().limit(MAX_PATHS).toList()));
  }

  /** The changed paths under {@link #DIRECTORY}, a rename by both of its paths, gitlinks left out. */
  static List<String> changedPaths(List<CommitFileChangeDto> changes) {
    Set<String> paths = new LinkedHashSet<>();
    for (CommitFileChangeDto file : changes) {
      if (file.touchesGitlink()) {
        continue;
      }
      if (under(file.oldPath())) {
        paths.add(file.oldPath());
      }
      if (under(file.path())) {
        paths.add(file.path());
      }
    }
    return List.copyOf(paths);
  }

  /** The packages the golden masters of {@code application} are published as. */
  static Set<String> packagesOf(String application) {
    String npm = application.startsWith("qits-") ? application.substring(5) : application;
    return Set.of(MAVEN_GROUP + ":" + application + SUFFIX, NPM_SCOPE + npm + SUFFIX);
  }

  private static boolean pins(DownstreamPins.Pin pin, Set<String> packages) {
    return pin != null
        && ("maven".equalsIgnoreCase(pin.ecosystem()) || "npm".equalsIgnoreCase(pin.ecosystem()))
        && packages.contains(pin.name());
  }

  /** {@code contracts.application} of the fold's release.yml, or null where it names none. */
  private static String application(ApprovalSubject subject) {
    String config;
    try {
      config = subject.fileAtFold(RELEASE_CONFIG);
    } catch (FoldUnreadable unreadable) {
      LOG.warnf(
          "The fold %s of %s changes its golden masters, and its %s could not be read: %s; no"
              + " contract requirement",
          subject.mergedSha(), subject.repoId(), RELEASE_CONFIG, unreadable.getMessage());
      return null;
    }
    return application(config);
  }

  /** {@code contracts.application} of a release.yml's text, or null where it names none. */
  static String application(String config) {
    if (config == null || config.isBlank()) {
      return null;
    }
    try {
      Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(config);
      if (root instanceof Map<?, ?> top
          && top.get("contracts") instanceof Map<?, ?> contracts
          && contracts.get("application") instanceof String application
          && !application.isBlank()) {
        return application.trim();
      }
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not parse %s", RELEASE_CONFIG);
    }
    return null;
  }

  /** The wired client, or null where none is. */
  DownstreamPins pins() {
    return downstream != null && downstream.isResolvable() ? downstream.get() : null;
  }

  /**
   * The consumer's project from this service's rows: the row the closure's catalog id names, else
   * the one project that knows a repository by its name. Null where neither tells.
   */
  Optional<ConsumerProject> projectOf(DownstreamPins.Downstream consumer) {
    try {
      if (consumer.catalogId() != null && !consumer.catalogId().isBlank()) {
        Optional<ConsumerProject> byId =
            repositories
                .findByIdOptional(consumer.catalogId())
                .map(repository -> repository.project)
                .map(ContractChangeSource::of);
        if (byId.isPresent()) {
          return byId;
        }
      }
      List<ConsumerProject> owners =
          names.find("name", consumer.repository()).stream()
              .map(alias -> of(alias.project))
              .filter(project -> project != null)
              .distinct()
              .toList();
      return owners.size() == 1 ? Optional.of(owners.get(0)) : Optional.empty();
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not read the project of %s", consumer.repository());
      return Optional.empty();
    }
  }

  private static ConsumerProject of(Project project) {
    return project == null ? null : new ConsumerProject(project.id, project.slug);
  }

  private static boolean under(String path) {
    return path != null && path.startsWith(DIRECTORY);
  }
}
