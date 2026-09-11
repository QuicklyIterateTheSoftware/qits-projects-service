package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which quality gates a repository's release requests are held by, and the one place that question
 * is answered.
 *
 * <p><b>A gate exists because the repository's configuration says so.</b> Configured means required;
 * a gate the repository does not configure is not waited on, and a repository configuring none is
 * releasable at once — which is not the same as unreviewed, because pressing release is still a
 * person's act.
 *
 * <pre>
 *     .config/qits/ci-event-release-request.yml present  →  the CI gate
 *     .config/qits/deployments.yml present               →  the deployment gate
 *     .config/qits/release-requests.yml manual-review     →  the approval gate
 * </pre>
 *
 * <h2>Read from main, never from the fold</h2>
 *
 * <p>The set is read at the repository's default branch, not at the commit under review. <b>A change
 * cannot alter the rules it is judged by</b>: deleting {@code manual-review: true} is reviewed under
 * the old setting, because the set was read before the fold was considered at all. This is the
 * platform's existing rule for CI recipes — qits-ci discovers and parses a repository's recipes at
 * {@code main}'s head, always — and it carries the same cost in the other direction: turning manual
 * review <em>on</em> takes effect from the next request rather than the current one.
 *
 * <p>{@link ReleaseFinalization#deployability} keeps reading the released tag's <b>tree</b> instead,
 * and that stays. It asks what the repository <em>is</em> and must act on what is about to ship; this
 * asks what the rules <em>are</em>, and rules a change brought with it are not rules it gets to be
 * judged by. Two questions, two readings, both correct.
 *
 * <h2>A tree listing, not a file</h2>
 *
 * <p>{@link ReleaseGitHost#file} answers "failed" for a blob that is absent, one that is binary and a
 * rev that does not resolve alike. {@link ReleaseGitHost#tree} separates them: a successful listing
 * without a path is an answer, an unsuccessful one is not an answer at all. That is
 * {@code deployability}'s reason and here it is sharper — <b>"could not be asked" resolving to "no
 * gates" would release something unreviewed</b>. So an unreadable configuration is {@link
 * GateSet#unknown}, never an empty set, and {@code release-requests.yml} is the one file read after
 * the listing has already said it is there.
 *
 * <h2>Stored nowhere</h2>
 *
 * <p>Resolved when asked. No gate's answer is a column on the request row — {@link EstatePinLedger}
 * states that rule for the estate gate and it holds for all of them: a stored answer would leave the
 * requests that are already open holding a rule that has since changed.
 */
@ApplicationScoped
public class ReleaseGates {

  private static final Logger LOG = Logger.getLogger(ReleaseGates.class);

  /** The fallback default branch, for a repository row that names none. */
  private static final String DEFAULT_MAIN = "main";

  /** The per-release-request CI pipeline. Its presence is the CI gate. */
  static final String CI_RECIPE = ReleaseArtifacts.QA_RECIPE;

  /** The platform's declaration that something deploys this repository. */
  static final String DEPLOYMENTS_MANIFEST = ReleaseFinalization.DEPLOYMENTS_MANIFEST;

  /** The repository's release-request settings. Read for {@code manual-review}. */
  static final String SETTINGS = ReleaseRequestSettingsParser.SETTINGS_PATH;

  @Inject RepositoryRepository repositories;

  @Inject ReleaseRequestSettingsParser settingsParser;

  @Inject Instance<ReleaseGitHost> gitHosts;

  /** How long a successfully read gate configuration is reused for. See {@link #resolve}. */
  @ConfigProperty(name = "qits.projects.release-requests.gate-freshness-ms", defaultValue = "30000")
  long freshnessMs;

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  /** The three gates. A fourth is a new member here and nowhere else. */
  public enum Kind {
    /** Before the tag: a gating {@code BuildSuccessful} for the fold. */
    CI,
    /** Before the tag, and standing alone: a person's yes. */
    APPROVAL,
    /** After the tag: the release is not finished until its deployment is live. */
    DEPLOYMENT
  }

  /** What one gate has to say about one request. */
  public enum State {
    /** Configured, and nothing has answered yet. */
    PENDING,
    /** Satisfied. */
    PASSED,
    /** Red. For CI this has already rejected the request. */
    FAILED,
    /** The configuration could not be read. Never the same word as {@link #PENDING}. */
    UNKNOWN
  }

  /** One gate as a request reports it. */
  public record Gate(Kind kind, State state) {}

  /**
   * The gates a repository configures, or the fact that its configuration could not be read.
   *
   * <p><b>{@code known == false} is not an empty set and must never be read as one.</b> An empty
   * known set is a repository that configured nothing — releasable at once. An unknown set is this
   * service not having been able to ask, which holds the request and says so.
   *
   * @param known whether the configuration was read at all
   * @param kinds the gates configured, empty where the repository configures none; meaningless when
   *     {@code known} is false
   * @param detail why the configuration could not be read, null when it could
   */
  public record GateSet(boolean known, Set<Kind> kinds, String detail) {

    public static GateSet of(Set<Kind> kinds) {
      return new GateSet(true, Set.copyOf(kinds), null);
    }

    /** The configuration could not be read. Everything downstream waits and says why. */
    public static GateSet unknown(String detail) {
      return new GateSet(false, Set.of(), detail);
    }

    /** Whether this repository is gated by {@code kind}. False for an unknown set: see {@link #known}. */
    public boolean requires(Kind kind) {
      return known && kinds.contains(kind);
    }

    /** A repository that configured no gate at all: nothing is waited on in front of the press. */
    public boolean nothingToWaitOn() {
      return known && kinds.isEmpty();
    }
  }

  /**
   * Which gates hold {@code repoId}'s release requests, read from its {@code main}.
   *
   * <p>One tree listing, plus one file read where {@link #SETTINGS} is in it. A repository with no
   * row, and a platform with no git host configured, are both {@link GateSet#unknown}: the honest
   * answer to "which rules apply" when the rules cannot be located is not "none".
   *
   * <p><b>A known answer is held for {@link #freshnessMs} and an unknown one never is.</b> That is a
   * throttle on the <em>read of a repository's configuration</em> and not a stored gate answer —
   * nothing about a request is cached here, the key is the repository, and the entry is the same
   * bytes the git host would answer with again. It exists because this is asked once per distinct
   * repository on every read of the project-wide worklist, which is the busiest read this service
   * has, and {@code main}'s {@code .config/qits/} changes about once a year. {@code RepoMirror}'s
   * own freshness window is the same idea one seam over. An <em>unknown</em> answer is deliberately
   * never held: retrying is exactly what fixes it, and caching "could not ask" would turn a moment's
   * outage into a window of holds.
   */
  public GateSet resolve(String repoId) {
    Cached cached = cache.get(repoId);
    if (cached != null && cached.freshAt(System.currentTimeMillis(), freshnessMs)) {
      return cached.set();
    }
    GateSet resolved = read(repoId);
    if (resolved.known()) {
      cache.put(repoId, new Cached(resolved, System.currentTimeMillis()));
    } else {
      cache.remove(repoId);
    }
    return resolved;
  }

  /** Drop what is remembered about {@code repoId}, so the next resolve asks the git host again. */
  public void forget(String repoId) {
    cache.remove(repoId);
  }

  private record Cached(GateSet set, long readAt) {
    boolean freshAt(long now, long windowMs) {
      return now - readAt < windowMs;
    }
  }

  private GateSet read(String repoId) {
    if (!gitHosts.isResolvable()) {
      return GateSet.unknown("No git host is configured, so this repository's gates cannot be read");
    }
    String main = mainOf(repoId);
    String rev = "refs/heads/" + main;
    ReleaseGitHost host = gitHosts.get();
    ReleaseGitHost.Answer<List<String>> tree;
    try {
      tree = host.tree(repoId, rev);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not be read as an answer.
      LOG.debugf(e, "The git host threw reading the tree of %s at %s", repoId, rev);
      return GateSet.unknown("The git host could not be asked what " + rev + " configures");
    }
    if (!tree.ok()) {
      LOG.debugf(
          "Could not read the tree of %s at %s (%s): %s",
          repoId, rev, tree.retryable() ? "retryable" : "final", tree.detail());
      return GateSet.unknown("The gate configuration at " + rev + " could not be read: " + tree.detail());
    }
    List<String> paths = tree.value();
    EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
    if (paths.contains(CI_RECIPE)) {
      kinds.add(Kind.CI);
    }
    if (paths.contains(DEPLOYMENTS_MANIFEST)) {
      kinds.add(Kind.DEPLOYMENT);
    }
    if (paths.contains(SETTINGS)) {
      // The listing has already said the file is there, so a read that fails is a failure to ask
      // rather than an absence — and an approval gate that vanished because a read failed is the one
      // outcome this whole seam exists to prevent.
      ReleaseGitHost.Answer<String> settings;
      try {
        settings = host.file(repoId, rev, SETTINGS);
      } catch (RuntimeException e) {
        LOG.debugf(e, "The git host threw reading %s of %s at %s", SETTINGS, repoId, rev);
        return GateSet.unknown("The git host could not be asked what " + SETTINGS + " says");
      }
      if (settings == null || !settings.ok()) {
        return GateSet.unknown(
            SETTINGS
                + " is declared at "
                + rev
                + " and could not be read: "
                + (settings == null ? "the git host could not be asked" : settings.detail()));
      }
      try {
        if (settingsParser.parse(settings.value()).manualReview()) {
          kinds.add(Kind.APPROVAL);
        }
      } catch (RuntimeException e) {
        // A settings file that fails open on a typo is a gate that disappears when somebody
        // mistypes it. Unknown holds the request and names the file in the sentence a person reads.
        LOG.warnf(
            "%s at %s of %s does not parse: %s", SETTINGS, rev, repoId, e.getMessage());
        return GateSet.unknown(SETTINGS + " at " + rev + " does not parse: " + e.getMessage());
      }
    }
    return GateSet.of(kinds);
  }

  /**
   * The gate set as a request reports it, one {@link Gate} per member.
   *
   * <p>An unknown set answers <b>every</b> kind at {@link State#UNKNOWN} rather than an empty list,
   * because that is what unknown means here: not which gates apply, not whether any has passed. An
   * empty list would read as "this repository is gated by nothing", which is the one thing an
   * unreadable configuration must never be mistaken for.
   *
   * @param states what the caller knows about each configured gate; a kind it says nothing about is
   *     {@link State#PENDING} — configured, nothing has answered.
   */
  public static List<Gate> report(GateSet set, Map<Kind, State> states) {
    List<Gate> gates = new ArrayList<>();
    if (!set.known()) {
      for (Kind kind : Kind.values()) {
        gates.add(new Gate(kind, State.UNKNOWN));
      }
      return List.copyOf(gates);
    }
    for (Kind kind : Kind.values()) {
      if (set.kinds().contains(kind)) {
        gates.add(new Gate(kind, states.getOrDefault(kind, State.PENDING)));
      }
    }
    return List.copyOf(gates);
  }

  /** The repository's default branch — {@code ReleaseRequests}' reading, which is private there. */
  private String mainOf(String repoId) {
    return repositories
        .findByIdOptional(repoId)
        .map(repository -> repository.mainBranch)
        .filter(branch -> branch != null && !branch.isBlank())
        .orElse(DEFAULT_MAIN);
  }
}
