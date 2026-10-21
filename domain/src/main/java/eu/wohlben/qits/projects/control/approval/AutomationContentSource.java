package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.control.AutomationKinds;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>The fold changes what a release-request automation commits, and that kind declares who must
 * acknowledge it</b> (epic qits-1038, task qits-1046 — the owner's "agent acknowledgement" case:
 * regenerated screenshots and docs should not land unnoticed).
 *
 * <ul>
 *   <li><b>The kinds</b> come from qits-maintenance's catalogue ({@link AutomationKinds}, {@code GET
 *       /maintenance/api/automations/kinds}); this service keeps no kind table. A kind with no
 *       {@code approvalRoles} adds nothing ({@code dependency-bump}, {@code estate-pins}).
 *   <li><b>Trigger</b>: a changed path of the fold — against the same base every content source
 *       reads ({@link ApprovalSubject#changes()}), gitlinks left out, a rename by either of its paths
 *       — matches one of the kind's {@code committablePaths} ({@link GitPathspec}).
 *   <li><b>The requirement</b>: {@code automation:<kind>}, reason {@code "<label> changed"}, admitting
 *       the kind's roles as {@link ApprovalRole} reads them, never excluding the authors (the
 *       request's own agent acknowledging is the point), the matching paths as the detail, up to
 *       {@value #MAX_PATHS}.
 * </ul>
 *
 * <p><b>Fail closed, unlike the contract source</b>, because here the requirement is the default and
 * the catalogue only says who may lift it:
 *
 * <ul>
 *   <li>the catalogue <b>configured but unreachable</b>, with no answer ever read: one {@value
 *       #CATALOGUE_ID} requirement admitting a person — thrown as {@link FoldUnreadable}, so the
 *       reading is marked incomplete and never stored as a fact about the fold;
 *   <li>a kind whose {@code committablePaths} use a pathspec form {@link GitPathspec} cannot read, or
 *       whose {@code approvalRoles} name a word {@link ApprovalRole} cannot: {@code
 *       automation:<kind>} admitting a person, where the fold changed anything at all.
 * </ul>
 *
 * <p><b>The cache</b>: the last good answer is served without asking for {@value #FRESH_MINUTES}
 * minutes, then asked for again; while qits-maintenance cannot answer, the last good answer goes on
 * being served, however old (the kinds change with a qits-maintenance release, not by the minute).
 * <b>Unconfigured</b> — {@code qits.projects.release-requests.maintenance-url} blank, which is also
 * what switches the automations gate off, so no automation ran — adds nothing; a deployment ships the
 * address set. A settled request reads nothing here: this is a content source, asked only while the
 * fold is readable.
 */
@ApplicationScoped
public class AutomationContentSource implements ApprovalRequirementSource {

  private static final Logger LOG = Logger.getLogger(AutomationContentSource.class);

  /** The id prefix; the kind follows. */
  public static final String ID_PREFIX = "automation:";

  /** The requirement an unreachable catalogue with nothing cached becomes. */
  public static final String CATALOGUE_ID = ID_PREFIX + "catalogue";

  /** Its sentence. */
  static final String CATALOGUE_REASON =
      "the automation kinds catalogue could not be read from qits-maintenance";

  /** How many changed paths a requirement's detail lists, as many as the work-item comment shows. */
  static final int MAX_PATHS = 20;

  static final long FRESH_MINUTES = 5;

  /** How long the last good answer is served without asking again. */
  static final Duration FRESH = Duration.ofMinutes(FRESH_MINUTES);

  @Inject Instance<AutomationKinds> catalogue;

  @Inject Instance<ApprovalRoles> roles;

  /** What the cache is timed by; a test moves it. */
  Clock clock = Clock.systemUTC();

  /** The last good answer and when it was read. */
  record Cached(List<AutomationKinds.Kind> kinds, Instant readAt) {}

  private volatile Cached cached;

  @Override
  public List<ApprovalRequirement> requirementsFor(ApprovalSubject subject) {
    List<AutomationKinds.Kind> kinds = kinds();
    if (kinds == null) {
      return List.of();
    }
    List<AutomationKinds.Kind> gating =
        kinds.stream().filter(kind -> kind != null && !kind.approvalRoles().isEmpty()).toList();
    if (gating.isEmpty()) {
      return List.of();
    }
    List<String> changed = changedPaths(subject.changes());
    if (changed.isEmpty()) {
      return List.of();
    }
    List<ApprovalRequirement> requirements = new ArrayList<>();
    for (AutomationKinds.Kind kind : gating) {
      requirement(kind, changed).ifPresent(requirements::add);
    }
    return requirements;
  }

  @Override
  public int order() {
    return 500;
  }

  /** Forgets the cached catalogue; the next question asks qits-maintenance. */
  public void forget() {
    cached = null;
  }

  /**
   * The catalogue: fresh from the cache, else asked, else the last good answer. Null where no
   * address is configured.
   *
   * @throws FoldUnreadable where it is configured, cannot be asked and nothing was ever read
   */
  List<AutomationKinds.Kind> kinds() {
    AutomationKinds port = port();
    if (port == null || !port.configured()) {
      LOG.debugf("No automation kinds catalogue is configured; no automation requirement");
      return null;
    }
    Cached known = cached;
    Instant now = clock.instant();
    if (known != null && now.isBefore(known.readAt().plus(FRESH))) {
      return known.kinds();
    }
    Optional<List<AutomationKinds.Kind>> read = port.kinds();
    if (read.isPresent()) {
      cached = new Cached(List.copyOf(read.get()), now);
      return cached.kinds();
    }
    if (known != null) {
      LOG.warnf(
          "Could not read the automation kinds catalogue; serving the answer read at %s",
          known.readAt());
      return known.kinds();
    }
    throw new FoldUnreadable(
        CATALOGUE_ID,
        CATALOGUE_REASON,
        "nothing has been read from it yet, so a person approves until it answers",
        false,
        null);
  }

  /** {@code kind}'s requirement for a fold that changed {@code changed}, if it has one. */
  Optional<ApprovalRequirement> requirement(AutomationKinds.Kind kind, List<String> changed) {
    String id = ID_PREFIX + kind.kind();
    List<GitPathspec> pathspecs = new ArrayList<>();
    for (String spec : kind.committablePaths()) {
      try {
        pathspecs.add(GitPathspec.parse(spec));
      } catch (GitPathspec.Unsupported unsupported) {
        LOG.warnf(
            "The automation kind %s commits %s; a person approves any fold while it does",
            kind.kind(), unsupported.getMessage());
        return Optional.of(
            ApprovalRequirement.person(
                id,
                label(kind) + " may have changed",
                "the catalogue's committable path "
                    + unsupported.getMessage().replaceFirst("^pathspec ", "")
                    + ", which this service cannot read"));
      }
    }
    List<String> matching =
        changed.stream().filter(path -> pathspecs.stream().anyMatch(p -> p.matches(path))).toList();
    if (matching.isEmpty()) {
      return Optional.empty();
    }
    List<ApprovalRole> admits = admits(kind);
    String shown =
        matching.size() > MAX_PATHS
            ? " (" + MAX_PATHS + " of " + matching.size() + " changed paths listed)"
            : "";
    return Optional.of(
        new ApprovalRequirement(
            id,
            label(kind) + " changed" + shown,
            admits,
            false,
            String.join(
                ApprovalRequirement.PATH_SEPARATOR, matching.stream().limit(MAX_PATHS).toList())));
  }

  /** The kind's roles, or a person alone where any word names no role. */
  private List<ApprovalRole> admits(AutomationKinds.Kind kind) {
    List<ApprovalRole> admits = new ArrayList<>();
    for (String word : kind.approvalRoles()) {
      ApprovalRole role = parse(word);
      if (role == null) {
        LOG.warnf(
            "The automation kind %s names the approval role '%s', which is none; a person approves",
            kind.kind(), word);
        return List.of(ApprovalRole.PERSON);
      }
      if (!admits.contains(role)) {
        admits.add(role);
      }
    }
    return admits;
  }

  /** One role word, an {@code agent@<slug>} resolved to the project's id where it can be. */
  ApprovalRole parse(String word) {
    if (word == null || word.isBlank() || word.trim().matches(".*[\\s,].*")) {
      return null;
    }
    ApprovalRoles resolver = roles != null && roles.isResolvable() ? roles.get() : null;
    if (resolver == null) {
      return ApprovalRole.parse(word, slug -> slug);
    }
    List<ApprovalRole> parsed = resolver.parse(word);
    return parsed.isEmpty() ? null : parsed.get(0);
  }

  private static String label(AutomationKinds.Kind kind) {
    return kind.label() == null || kind.label().isBlank() ? kind.kind() : kind.label();
  }

  /** Every changed path of the fold, a rename by both of its paths, gitlinks left out. */
  static List<String> changedPaths(List<CommitFileChangeDto> changes) {
    Set<String> paths = new LinkedHashSet<>();
    for (CommitFileChangeDto file : changes) {
      if (file.touchesGitlink()) {
        continue;
      }
      if (file.oldPath() != null && !file.oldPath().isBlank()) {
        paths.add(file.oldPath());
      }
      if (file.path() != null && !file.path().isBlank()) {
        paths.add(file.path());
      }
    }
    return List.copyOf(paths);
  }

  /** The wired catalogue client, or null where none is. */
  AutomationKinds port() {
    return catalogue != null && catalogue.isResolvable() ? catalogue.get() : null;
  }
}
