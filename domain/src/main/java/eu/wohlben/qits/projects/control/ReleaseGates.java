package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.projects.entity.StoredGateSet;
import eu.wohlben.qits.projects.gitmirror.GitProcessSpans;
import eu.wohlben.qits.projects.gitmirror.GitTracing;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.persistence.StoredGateSetRepository;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
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
 * <h2>The four kinds are the gates BETWEEN a release's phases</h2>
 *
 * <p>A release is one pipeline of three phases, and each of these four kinds stands between two of
 * them. <b>A phase is a unit of work with a state and a rerun</b> — the QA run at {@code
 * release/<id>@mergedSha}, the publish run at {@code <version>@commitSha}, and the deployment of
 * that version — and nothing else is one: a <em>step</em> inside a run is not, and neither is any
 * other part a run happens to be split into, because it has no state and no rerun of its own. <b>A
 * gate is the condition between two phases.</b>
 *
 * <pre>
 *     P1 . QA        {@link Kind#CI}, {@link Kind#APPROVAL}   ->   P2 . Publish
 *     P2 . Publish   {@link Kind#PUBLISH}                     ->   P3 . Deploy
 *     P3 . Deploy    {@link Kind#DEPLOYMENT}                  ->   FINALIZED
 * </pre>
 *
 * <p><b>Reading them that way changed nothing about them.</b> The same four kinds are resolved from
 * the same files at the same revs and answered by the same paths; what the pipeline reading adds is
 * <em>placement</em>, which lives in {@code ReleasePipelineAssembler} and re-decides none of this.
 * In particular the publish gate's "read at the tag, never at {@code main}" rule below is unchanged
 * and is not a consequence of the phase model — it is a consequence of which content the gate is
 * about.
 *
 * <p><b>And a gate DELAYS rather than fails.</b> An unmet gate holds the release where it is: the
 * next phase does not start and the request stays open. That is why a red {@link Kind#PUBLISH}
 * verdict leaves the request {@code RELEASED} rather than moving it anywhere — the tag cannot be
 * un-cut, so it is a failed gate on an open request, retried by {@code qits ci retry} — and why
 * there is no release-request state for "the pipeline failed". The only thing that ends a release is
 * every configured gate having passed and the tag reaching {@code main}.
 *
 * <pre>
 *     .config/qits/release.yml declaring a QA pipeline
 *       (an archetype:, or a release-request: slot)      →  the CI gate
 *     .config/qits/deployments.yml present               →  the deployment gate
 *     .config/qits/release-requests.yml manual-review    →  the approval gate
 *
 *     AT THE RELEASED TAG, not at main:
 *     .config/qits/release.yml for which qits-ci says
 *       it runs a release                                →  the publish gate
 * </pre>
 *
 * <h2>Configured at the tag: the publish gate</h2>
 *
 * <p><b>{@link Kind#PUBLISH} is not in the set this class resolves</b>, and it cannot be: it is
 * configured by the <em>released tag's</em> tree rather than by {@code main}'s, so there is no
 * answer to give until a release has happened. {@code ReleaseFinalization} reads it, off the same
 * single tree listing it already makes for {@code deployability}, and a caller that has a released
 * tag in hand puts the kind into the set with {@link GateSet#with} before reporting.
 *
 * <p><b>The two gates read {@link ReleaseArtifacts#SLOT_CONFIG} differently, and that is a
 * correction rather than an inconsistency</b> (2026-09-16). This class asks whether the file
 * declares a QA pipeline — an {@code archetype:}, whose composed {@code release-request:} slot every
 * archetype declares, or a {@code release-request:} slot of the repository's own. {@code
 * ReleaseFinalization} is about the {@code release:} slot, which {@code spa-frontend} and {@code
 * cli} deliberately declare <b>not</b> — so an archetype's name was the wrong test there, and the
 * publish gate asks qits-ci itself through {@code control/PublishRuns} instead. Neither reading may
 * be copied onto the other.
 *
 * <p><b>Why that direction is right here and wrong for the other three.</b> The rule "read from
 * main, never from the fold" exists so a change cannot loosen the rules it is judged by — and every
 * gate it governs is asked <em>before</em> the tag, about content nobody has accepted yet. The
 * publish gate is asked after: the run that must go green is the release run of the released commit,
 * composed from what <em>that commit</em> declared, and asking {@code main} instead would gate a
 * release on a pipeline it does not have (a repository that added a release declaration after the
 * tag) or wave one through that does (a repository that removed it since). There is no loophole to
 * open, because the content is already tagged and immutable. It is {@link
 * ReleaseFinalization#deployability}'s reasoning exactly, for the same half of the lifecycle.
 *
 * <h2>The CI gate is composed, not local</h2>
 *
 * <p>{@link ReleaseArtifacts#SLOT_CONFIG} is the whole of what turns this gate on. A repository
 * carries no QA <em>recipe</em> of its own any more; qits-ci composes one, either from the wrapper's
 * {@code .config/qits/release-archetypes/<archetype>.yml} or from the {@code release-request:} slot
 * the repository declares in that same file, and the composed pipeline reports its verdict exactly
 * as an in-repository recipe used to.
 *
 * <p><b>Reading the composed source is what closed the 2026-09-13 bug</b>, and reading it is now the
 * only reading there is. This class used to look first for a repository's own
 * {@code .config/qits/ci-event-release-request.yml} and treat its presence as the CI gate — so a
 * repository that migrated away from that file lost its CI gate entirely, skipped straight to
 * {@code READY} and released before its QA run had even started. The archetype reading was added
 * beside the file check then; the file check itself went on 2026-09-18, when no repository in the
 * estate carried the file any more and the branch could only ever have answered for a repository
 * that has since been migrated.
 *
 * <p><b>The rule is presence, not composition.</b> {@link #read} asks {@link ReleaseArchetypeParser}
 * one question — does {@link ReleaseArtifacts#SLOT_CONFIG} declare a QA pipeline, meaning a non-blank
 * {@code archetype:} or a non-empty {@code release-request:} — and never opens the archetype file,
 * nor reads a single step out of either slot. That is deliberate rather than a shortcut: the slot
 * schema belongs to qits-ci, which composes it, and a second reader of that schema here is a second
 * place a future change has to be kept in step, for a fact a presence check already answers. Every
 * archetype this platform ships declares a {@code release-request:} slot today (checked by hand
 * across all six on 2026-09-13), so the two readings agree on every repository that exists; if a
 * future archetype ever shipped without one, a repository naming it would still hold its CI gate
 * here (see below) and wait on a verdict that never arrives — recoverable by fixing the archetype or
 * the repository's declaration, and the one outcome this class must never produce instead is
 * releasing ungated.
 *
 * <p><b>Naming an archetype is not the only way to be composed, and reading it as though it were
 * released repositories ungated</b> (fixed 2026-09-22). The predicate asked for {@code archetype:}
 * alone, on the reasoning that a file declaring only {@code artifacts:} or {@code userflows:}
 * composes nothing at qits-ci. A repository may instead <em>inline</em> its slots, and qits-ci
 * honours that ahead of any archetype — {@code CiReleaseComposer.choose} takes the repository's own
 * slot list outright when it is present, archetype or not — so such a repository is composed a QA
 * run and simply got no gate for it. Measured on {@code qits-landing-app}, the one repository of 49
 * on the estate with slots and no archetype: three requests went {@code READY} within a second of
 * creation, the tag was cut and the backing {@code release/<id>} branch deleted, and the QA run
 * qits-ci had composed then died with {@code CLONE_FAILED} on the branch that release had just
 * removed. Its QA steps had never run once.
 *
 * <p><b>Two edge cases both resolve to "the CI gate applies", never to "no gate".</b> A {@link
 * ReleaseArtifacts#SLOT_CONFIG} that will not parse, and one naming an archetype with no {@code
 * release-request:} slot, are indistinguishable from here without becoming that second schema
 * reader — so both are read the same conservative way as "an archetype might be composing a
 * pipeline for this repository" and the CI gate is added. The alternative, treating either as "no
 * gate", would be a release configured to skip its own QA run and never say so; the alternative of
 * marking the whole {@link GateSet} {@link GateSet#unknown} — the way an unparseable {@code
 * release-requests.yml} does two paragraphs down — would be truthful but wider than the fact
 * actually in doubt, since the deployment and approval gates are unaffected by whether {@link
 * ReleaseArtifacts#SLOT_CONFIG} parses. A WARN names the repository either way, because a file that
 * will not parse is something a person has to fix.
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
 * <h2>Stored per repository, and never on a request</h2>
 *
 * <p>Resolved when asked. No gate's answer is a column on the request row — {@link AutomationLedger}
 * states that rule for the automations gate and it holds for all of them: a stored answer would leave the
 * requests that are already open holding a rule that has since changed.
 *
 * <p>What <em>is</em> stored (qits-1201, V44) is the repository's configuration as last read from its
 * {@code main} — {@link StoredGateSet}, one row per repository, written whenever a read comes back
 * known. It is what {@link #forDisplay} shows, so the release-request list asks the git host nothing
 * even on a cold process or right after {@code main} moved; nothing that decides ever reads it.
 */
@ApplicationScoped
public class ReleaseGates {

  private static final Logger LOG = Logger.getLogger(ReleaseGates.class);

  /** The fallback default branch, for a repository row that names none. */
  private static final String DEFAULT_MAIN = "main";

  /**
   * A repository's release declaration. Read for {@code archetype:} and {@code release-request:} —
   * see the class javadoc's "The CI gate is composed, not local".
   */
  static final String RELEASE_CONFIG = ReleaseArtifacts.SLOT_CONFIG;

  /** The platform's declaration that something deploys this repository. */
  static final String DEPLOYMENTS_MANIFEST = ReleaseFinalization.DEPLOYMENTS_MANIFEST;

  /** The repository's release-request settings. Read for {@code manual-review}. */
  static final String SETTINGS = ReleaseRequestSettingsParser.SETTINGS_PATH;

  @Inject RepositoryRepository repositories;

  @Inject ReleaseRequestSettingsParser settingsParser;

  @Inject ReleaseArchetypeParser archetypeParser;

  @Inject Instance<ReleaseGitHost> gitHosts;

  /**
   * Where every known gate set is written down for {@link #forDisplay} (qits-1201). Null when a test
   * builds this class with {@code new}, and then nothing is stored.
   */
  @Inject StoredGateSetRepository storedSets;

  /** Trees only — {@link #toJson} and {@link #fromJson} need nothing registered for a native image. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** What a display shows for a repository whose configuration has never been read. */
  public static final String NOT_READ_YET =
      "The gate configuration of this repository has not been read yet";

  /**
   * Where every span this class opens goes — {@value #RESOLVE_SPAN}, {@value #FOR_DISPLAY_SPAN}
   * and {@value #READ_SPAN} alike (qits-1202). Borrowed from {@code gitmirror} rather than declared
   * twice: it is this repository's one tracing port, and its shape — open, attribute, close, nested
   * under whatever is current — is exactly what a resolution, a display read or a git-host read
   * needs. Optional like every port here, and null when a test builds this class with {@code new};
   * both read as {@link GitTracing#NONE} through {@link #tracer}.
   */
  @Inject Instance<GitTracing> tracing;

  /** The span around {@link #resolve}; on a miss the git host's HTTP spans nest under it. */
  static final String RESOLVE_SPAN = "release-gates.resolve";

  /** {@code hit} or {@code miss}: whether {@link #resolve} answered from {@link #cache}. */
  static final String CACHE_ATTRIBUTE = "qits.release_gates.cache";

  /** The span around {@link #forDisplay}; every read it starts nests under it. */
  static final String FOR_DISPLAY_SPAN = "release-gates.for-display";

  /** How many repositories {@link #forDisplay} was asked about, as a long attribute. */
  static final String REPOSITORIES_ATTRIBUTE = "qits.release_gates.repositories";

  /** How many of them {@link #forDisplay} answered fresh from {@link #cache}, as a long attribute. */
  static final String HITS_ATTRIBUTE = "qits.release_gates.hits";

  /**
   * How many of them {@link #forDisplay} served stale while starting a background refresh, as a
   * long attribute.
   */
  static final String STALE_ATTRIBUTE = "qits.release_gates.stale";

  /** How many of them {@link #forDisplay} read synchronously, as a long attribute. */
  static final String MISSES_ATTRIBUTE = "qits.release_gates.misses";

  /**
   * The span around {@link #read(String, String)} — the one git-host read every path shares, so a
   * miss of {@link #resolve}, a concurrent read or a background refresh of {@link #forDisplay} is
   * its own span, with the git host's own HTTP spans nested under it.
   */
  static final String READ_SPAN = "release-gates.read";

  /** How long a successfully read gate configuration is reused for. See {@link #resolve}. */
  @ConfigProperty(name = "qits.projects.release-requests.gate-freshness-ms", defaultValue = "30000")
  long freshnessMs;

  /**
   * How many gate configurations are read from the git host at once — by one display read that
   * meets several repositories it has nothing cached for, and by the background refreshes {@link
   * #forDisplay} starts. Bounded so that a project with fifty repositories is not fifty concurrent
   * requests at qits-githost.
   */
  @ConfigProperty(name = "qits.projects.release-requests.gate-read-concurrency", defaultValue = "8")
  int readConcurrency;

  /**
   * <b>The suite's switch, never a deployment's</b> (qits-1201): {@link #forDisplay} reads every
   * repository it has nothing fresh in memory for on the caller's thread, as it did before the gate
   * sets were stored, and {@link #mainMoved} starts no background read. The suite rewrites a
   * repository's main and lists right after, with a freshness of zero, and must see what it staged.
   */
  @ConfigProperty(
      name = "qits.projects.release-requests.display-reads-synchronously",
      defaultValue = "false")
  boolean displayReadsSynchronously;

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  /** The repositories a background refresh is in flight for — at most one each. */
  private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

  /**
   * Bumped by every {@link #forget}: a read that began before it must not be remembered or stored,
   * and a refresh that finds it moved reads once more.
   */
  private final Map<String, Long> generations = new ConcurrentHashMap<>();

  /**
   * When each repository was last {@link #forget forgotten}: a stored set read before that is from
   * before the move, and a display treats it as stale however young it is.
   */
  private final Map<String, Long> forgottenAt = new ConcurrentHashMap<>();

  /**
   * Where gate configurations are read off the caller's thread. No CDI, transaction or security
   * context is carried: a read needs none of them ({@link #read(String, String)} is handed the main
   * branch rather than looking it up), and a background refresh outlives the request that started
   * it. The trace is carried explicitly instead, see {@link #traced}.
   */
  private ManagedExecutor readers;

  @PostConstruct
  void startReaders() {
    readers =
        ManagedExecutor.builder()
            .maxAsync(Math.max(1, readConcurrency))
            .propagated(ThreadContext.ALL_REMAINING)
            .cleared(ThreadContext.CDI, ThreadContext.TRANSACTION, ThreadContext.SECURITY)
            .build();
  }

  @PreDestroy
  void stopReaders() {
    if (readers != null) {
      readers.shutdownNow();
    }
  }

  /** The five gates. A sixth is a new member here and nowhere else. */
  public enum Kind {
    /**
     * Before the tag, and FIRST — the pre-run (qits-1133): every release-request automation that
     * applies (dependency bumps, estate pins, the entity diagram, screenshot baselines, …) is fresh
     * for the fold, or a person waived that fold. QA is not asked for until it passes, which is why
     * it is ahead of {@link #CI} here and in every list made in this order. Not configured from
     * {@code main} like the others — it is on wherever {@link AutomationRefresh#configured()
     * qits-maintenance is configured}, and for the estate wrapper always; {@code ReleaseRequests} is
     * what puts it in a reported set. See {@link AutomationLedger}.
     */
    AUTOMATIONS,
    /** Before the tag, once the pre-run is done: a {@code BuildSuccessful} for the fold. */
    CI,
    /** Before the tag, and standing alone: a person's yes. */
    APPROVAL,
    /**
     * After the tag: the tag's own release run — the <b>publish</b> run — must be green. See
     * "Configured at the tag" in this class's javadoc for why this one is not read from {@code
     * main}, and {@code ReleaseFinalization} for who answers it.
     */
    PUBLISH,
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

    /**
     * The same set with one more gate in it — the seam {@link Kind#PUBLISH} arrives through, since
     * that one is configured at the released tag and this set is read at {@code main}.
     *
     * <p><b>An unknown set is returned unchanged</b>, and that is the whole care this method needs:
     * unknown does not mean "no gates yet", it means the configuration could not be read, and a set
     * that gained a member would start claiming to know one gate out of four. See {@link #known}.
     */
    public GateSet with(Kind kind) {
      if (!known || kinds.contains(kind)) {
        return this;
      }
      EnumSet<Kind> widened = EnumSet.copyOf(kinds.isEmpty() ? EnumSet.noneOf(Kind.class) : kinds);
      widened.add(kind);
      return GateSet.of(widened);
    }

    /** A repository that configured no gate at all: nothing is waited on in front of the press. */
    public boolean nothingToWaitOn() {
      return known && kinds.isEmpty();
    }
  }

  /**
   * Which gates hold {@code repoId}'s release requests, read from its {@code main}.
   *
   * <p>One tree listing, plus one file read where {@link #RELEASE_CONFIG} is in the tree, plus one
   * file read where {@link #SETTINGS} is in it. A repository with no
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
   *
   * <p><b>Traced as {@value #RESOLVE_SPAN}</b> (qits-1202) with {@value #CACHE_ATTRIBUTE} {@code
   * hit} or {@code miss}, because this is asked once per repository on the busiest read this
   * service has, and a slow worklist is either many misses or one slow git host — the span says
   * which, and on a miss the git host's own HTTP spans sit inside it.
   */
  public GateSet resolve(String repoId) {
    GitTracing tracer = tracer();
    try (GitTracing.Span span =
        tracer.start(
            RESOLVE_SPAN, repoId == null ? Map.of() : Map.of(GitProcessSpans.REPO_ID, repoId))) {
      Cached cached = cache.get(repoId);
      if (cached != null && cached.freshAt(System.currentTimeMillis(), freshnessMs)) {
        span.attribute(CACHE_ATTRIBUTE, "hit");
        return cached.set();
      }
      span.attribute(CACHE_ATTRIBUTE, "miss");
      long generation = generation(repoId);
      long startedAt = System.currentTimeMillis();
      return remember(repoId, read(repoId), generation, startedAt);
    }
  }

  /** A stored gate set and when the read behind it began — see {@link StoredGateSet}. */
  public record Stored(GateSet set, Instant readAt) {}

  /**
   * The stored gate sets of {@code repoIds}, in one query — what a page's {@code loadPage} hands
   * {@link #forDisplay}. Must be called inside a transaction; a row that will not parse is left out,
   * which a display reads as "not read yet".
   */
  public Map<String, Stored> stored(Collection<String> repoIds) {
    if (storedSets == null || repoIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Stored> out = new HashMap<>();
    for (StoredGateSet row : storedSets.listByRepos(repoIds)) {
      GateSet set = fromJson(row.gateSet);
      if (set != null && row.readAt != null) {
        out.put(row.repoId, new Stored(set, row.readAt));
      }
    }
    return out;
  }

  /**
   * <b>The gate sets a read-only display shows</b> — the release-request list and single read, and
   * nothing that decides anything. Keyed by repository id, each with the main branch and the stored
   * set the caller has already read inside its own transaction, so nothing here touches the database.
   *
   * <p><b>It asks the git host nothing</b> (qits-1201). Each repository is answered from what is
   * already known — a set in memory inside {@link #freshnessMs}, else the stored one inside it, else
   * the newer of the two however old, else {@link GateSet#unknown} with {@link #NOT_READ_YET} — and a
   * repository answered from anything but a fresh set gets <b>one background refresh</b>, at most
   * one in flight per repository however many pages arrive meanwhile. A stored set read before the
   * repository was last {@link #forget forgotten} is from before its main moved and counts as stale.
   * So a cold process, and the first page after a push to main, show the configuration last read and
   * a page later the one read now; before qits-1201 both read the git host on the page's own thread.
   *
   * <p>That trade is safe for a display and for nothing else: the configuration changes about once a
   * year, and the worst a stale answer does on a page is draw a gate a few seconds late. Every caller
   * that <em>decides</em> — the executor, gating, finalization, the approval question asked for a
   * release — keeps {@link #resolve}, fresh within the window or read synchronously.
   *
   * <p><b>The suite reads synchronously instead</b> ({@link #displayReadsSynchronously}): every
   * repository with nothing fresh in memory is read on the spot — concurrently, on a bounded pool —
   * so a test that rewrites main and lists right after sees what it staged, with no background
   * refresh racing its assertions.
   *
   * <p><b>Traced as {@value #FOR_DISPLAY_SPAN}</b> (qits-1202), with {@value #REPOSITORIES_ATTRIBUTE},
   * {@value #HITS_ATTRIBUTE} (answered fresh, from memory or the store), {@value #STALE_ATTRIBUTE}
   * (answered from something older while a refresh starts) and {@value #MISSES_ATTRIBUTE} (read
   * synchronously — only ever in the suite) as long attributes. The span stays open and current
   * across the synchronous reads, so {@link #traced} carries it onto each reader thread and every
   * {@value #READ_SPAN} nests under it.
   */
  public Map<String, GateSet> forDisplay(
      Map<String, String> mainBranchByRepo, Map<String, Stored> stored) {
    GitTracing tracer = tracer();
    try (GitTracing.Span span = tracer.start(FOR_DISPLAY_SPAN, Map.of())) {
      Map<String, GateSet> answered = new HashMap<>();
      Map<String, String> toRead = new LinkedHashMap<>();
      long now = System.currentTimeMillis();
      int[] hits = {0};
      int[] stale = {0};
      mainBranchByRepo.forEach(
          (repoId, main) -> {
            Cached cached = cache.get(repoId);
            Stored kept = stored.get(repoId);
            long forgot = forgottenAt.getOrDefault(repoId, 0L);
            long keptAt = kept == null ? Long.MIN_VALUE : kept.readAt().toEpochMilli();
            if (cached != null && cached.freshAt(now, freshnessMs)) {
              answered.put(repoId, cached.set());
              hits[0]++;
            } else if (displayReadsSynchronously) {
              toRead.put(repoId, main);
            } else if (kept != null && keptAt >= forgot && now - keptAt < freshnessMs) {
              answered.put(repoId, kept.set());
              hits[0]++;
            } else {
              GateSet shown;
              if (cached != null && (kept == null || cached.readAt() >= keptAt)) {
                shown = cached.set();
              } else if (kept != null) {
                shown = kept.set();
              } else {
                shown = GateSet.unknown(NOT_READ_YET);
              }
              answered.put(repoId, shown);
              stale[0]++;
              refreshInBackground(repoId, main);
            }
          });
      span.attribute(REPOSITORIES_ATTRIBUTE, (long) mainBranchByRepo.size());
      span.attribute(HITS_ATTRIBUTE, (long) hits[0]);
      span.attribute(STALE_ATTRIBUTE, (long) stale[0]);
      span.attribute(MISSES_ATTRIBUTE, (long) toRead.size());
      if (toRead.isEmpty()) {
        return answered;
      }
      if (toRead.size() == 1) {
        // One read is no reason to change threads.
        Map.Entry<String, String> only = toRead.entrySet().iterator().next();
        long generation = generation(only.getKey());
        long startedAt = System.currentTimeMillis();
        answered.put(
            only.getKey(),
            remember(only.getKey(), read(only.getKey(), only.getValue()), generation, startedAt));
        return answered;
      }
      Map<String, CompletableFuture<GateSet>> reading = new LinkedHashMap<>();
      Map<String, Long> generationAtStart = new HashMap<>();
      long startedAt = System.currentTimeMillis();
      toRead.forEach(
          (repoId, main) -> {
            generationAtStart.put(repoId, generation(repoId));
            reading.put(repoId, readers.supplyAsync(traced(() -> read(repoId, main))));
          });
      reading.forEach(
          (repoId, future) -> {
            GateSet set;
            try {
              set = future.join();
            } catch (RuntimeException e) {
              // read() answers every failure as unknown; a throw here is the pool's, not the host's.
              LOG.debugf(e, "Reading the gate configuration of %s did not complete", repoId);
              set = GateSet.unknown("The gate configuration of this repository could not be read");
            }
            answered.put(repoId, remember(repoId, set, generationAtStart.get(repoId), startedAt));
          });
      return answered;
    }
  }

  /**
   * {@code repoId}'s {@code main} moved: drop what is remembered about it and read it again in the
   * background (qits-1201), so the next page shows the configuration from after the move rather than
   * waiting for one to notice it is stale. In the suite ({@link #displayReadsSynchronously}) only the
   * forget: its next display reads synchronously anyway.
   */
  public void mainMoved(String repoId) {
    forget(repoId);
    if (!displayReadsSynchronously) {
      refreshInBackground(repoId, null);
    }
  }

  /**
   * One refresh of {@code repoId} off the caller's thread, unless one is already in flight. Its
   * answer is remembered and stored only if the repository was not {@link #forget forgotten} while it
   * read — a read from before a move must not put the configuration from before the move back — and
   * a refresh that finds it was forgotten reads once more, since the {@link #mainMoved} that forgot it
   * found this one in flight and started nothing.
   *
   * @param main the main branch, or null to look it up in a transaction of the refresh's own
   */
  private void refreshInBackground(String repoId, String main) {
    if (readers == null || repoId == null || !refreshing.add(repoId)) {
      return;
    }
    long generation = generation(repoId);
    try {
      readers.supplyAsync(
          traced(
              () -> {
                try {
                  long startedAt = System.currentTimeMillis();
                  String branch = main != null ? main : inTransaction(() -> mainOf(repoId));
                  GateSet set = read(repoId, branch);
                  if (set.known()) {
                    // An unknown answer is never held, as in resolve(): what is shown stays shown
                    // and the next page that finds it stale asks again.
                    remember(repoId, set, generation, startedAt);
                  }
                } catch (RuntimeException e) {
                  LOG.debugf(e, "Refreshing the gate configuration of %s failed", repoId);
                } finally {
                  refreshing.remove(repoId);
                }
                if (generation(repoId) != generation) {
                  refreshInBackground(repoId, null);
                }
                return null;
              }));
    } catch (RuntimeException e) {
      // A pool that refuses the task (shutting down) leaves what is shown standing.
      refreshing.remove(repoId);
      LOG.debugf(e, "Could not start a refresh of the gate configuration of %s", repoId);
    }
  }

  /**
   * The tracer every span in this class opens through (qits-1202): the optional {@link #tracing}
   * port where it resolves, {@link GitTracing#NONE} where it is absent or a test built this class
   * with {@code new} and never set it.
   */
  private GitTracing tracer() {
    return tracing != null && tracing.isResolvable() ? tracing.get() : GitTracing.NONE;
  }

  /** The caller's trace, carried onto the reader thread so the git host calls are its children. */
  private static <T> java.util.function.Supplier<T> traced(java.util.function.Supplier<T> task) {
    io.opentelemetry.context.Context context = io.opentelemetry.context.Context.current();
    return () -> {
      try (io.opentelemetry.context.Scope ignored = context.makeCurrent()) {
        return task.get();
      }
    };
  }

  /**
   * A known answer is remembered for the window and stored for {@link #forDisplay}; an unknown one
   * drops whatever was remembered. Neither happens to an answer read before the repository was last
   * {@link #forget forgotten} ({@code generation}): main moved under that read.
   *
   * @param startedAt when the read began — the stored set's {@code read_at}, so a set read across a
   *     {@link #forget} counts as older than it
   */
  private GateSet remember(String repoId, GateSet resolved, long generation, long startedAt) {
    if (repoId == null) {
      return resolved;
    }
    if (resolved.known()) {
      Cached fresh = new Cached(resolved, startedAt);
      boolean[] kept = {false};
      cache.compute(
          repoId,
          (key, current) -> {
            if (generation(repoId) != generation) {
              return current;
            }
            kept[0] = true;
            return fresh;
          });
      if (kept[0]) {
        store(repoId, resolved, startedAt);
      }
    } else {
      cache.remove(repoId);
    }
    return resolved;
  }

  /**
   * Write {@code set} down for {@link #forDisplay}, off the caller's thread: a decision that resolved
   * it may be holding a row lock, and must neither wait for nor fail on a display's bookkeeping. Best
   * effort — a lost write is a background refresh on the next page.
   */
  private void store(String repoId, GateSet set, long readAt) {
    if (storedSets == null || readers == null) {
      return;
    }
    String json = toJson(set);
    try {
      readers.execute(
          () -> {
            try {
              inTransaction(
                  () -> {
                    storedSets.upsert(repoId, json, Instant.ofEpochMilli(readAt));
                    return null;
                  });
            } catch (RuntimeException e) {
              LOG.debugf(e, "Could not store the gate set of %s", repoId);
            }
          });
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not hand the gate set of %s to be stored", repoId);
    }
  }

  /**
   * Drop what is remembered about {@code repoId}, so the next resolve asks the git host again —
   * called whenever the repository's main moves (a finalization merge, or any push seen by the head
   * listener through {@link ReleaseRequests#onMainMoved}, by way of {@link #mainMoved}), so neither a
   * decision nor a display reads the configuration from before the move. The stored set stays, and a
   * display keeps showing it until the re-read lands.
   */
  public void forget(String repoId) {
    if (repoId == null) {
      return;
    }
    generations.merge(repoId, 1L, Long::sum);
    forgottenAt.put(repoId, System.currentTimeMillis());
    cache.remove(repoId);
  }

  private long generation(String repoId) {
    return repoId == null ? 0L : generations.getOrDefault(repoId, 0L);
  }

  private record Cached(GateSet set, long readAt) {
    boolean freshAt(long now, long windowMs) {
      return now - readAt < windowMs;
    }
  }

  /**
   * The stored form of a set: {@code {"known":true,"kinds":["CI","APPROVAL"],"detail":null}}. Built
   * as a tree, so neither direction needs a class registered for reflection in the native image.
   */
  static String toJson(GateSet set) {
    ObjectNode node = JSON.createObjectNode();
    node.put("known", set.known());
    var kinds = node.putArray("kinds");
    for (Kind kind : Kind.values()) {
      if (set.kinds().contains(kind)) {
        kinds.add(kind.name());
      }
    }
    if (set.detail() == null) {
      node.putNull("detail");
    } else {
      node.put("detail", set.detail());
    }
    return node.toString();
  }

  /** {@link #toJson} read back; null for anything that does not parse, including a kind unheard of. */
  static GateSet fromJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(json);
      if (node == null || !node.isObject()) {
        return null;
      }
      JsonNode detail = node.get("detail");
      String why = detail == null || detail.isNull() ? null : detail.asText();
      if (!node.path("known").asBoolean(false)) {
        return GateSet.unknown(why);
      }
      EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
      for (JsonNode kind : node.path("kinds")) {
        kinds.add(Kind.valueOf(kind.asText()));
      }
      return GateSet.of(kinds);
    } catch (Exception e) {
      LOG.debugf(e, "A stored gate set does not parse: %s", json);
      return null;
    }
  }

  /**
   * Runs {@code work} in a transaction of its own under a request context — a reader thread carries
   * neither.
   */
  private static <T> T inTransaction(java.util.function.Supplier<T> work) {
    ManagedContext context = Arc.container().requestContext();
    boolean activated = !context.isActive();
    if (activated) {
      context.activate();
    }
    try {
      return QuarkusTransaction.requiringNew().call(work::get);
    } finally {
      if (activated) {
        context.terminate();
      }
    }
  }

  private GateSet read(String repoId) {
    if (!gitHosts.isResolvable()) {
      return GateSet.unknown("No git host is configured, so this repository's gates cannot be read");
    }
    return read(repoId, mainOf(repoId));
  }

  /**
   * {@link #read(String)} with the main branch already known: no database, any thread.
   *
   * <p><b>Traced as {@value #READ_SPAN}</b> (qits-1202) with {@value GitProcessSpans#REPO_ID}, so
   * every git-host read this class makes — {@link #resolve}'s miss, one of {@link #forDisplay}'s
   * concurrent reads, one of its background refreshes — is its own span, with the git host's own
   * HTTP spans nested under it.
   */
  private GateSet read(String repoId, String main) {
    GitTracing tracer = tracer();
    try (GitTracing.Span span =
        tracer.start(READ_SPAN, repoId == null ? Map.of() : Map.of(GitProcessSpans.REPO_ID, repoId))) {
      if (!gitHosts.isResolvable()) {
        return GateSet.unknown("No git host is configured, so this repository's gates cannot be read");
      }
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
        return GateSet.unknown(
            "The gate configuration at " + rev + " could not be read: " + tree.detail());
      }
      List<String> paths = tree.value();
      EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
      if (paths.contains(RELEASE_CONFIG)) {
        // One file read on every repository that declares a release at all, and the file is exactly
        // the one ReleaseArtifacts already fetches. The tree listing alone cannot answer this gate:
        // a release.yml is present for every migrated repository and what says whether qits-ci
        // composes a QA pipeline to be gated by is inside it — an archetype: to compose one from, or
        // a release-request: slot the repository declares itself.
        ReleaseGitHost.Answer<String> config;
        try {
          config = host.file(repoId, rev, RELEASE_CONFIG);
        } catch (RuntimeException e) {
          LOG.debugf(e, "The git host threw reading %s of %s at %s", RELEASE_CONFIG, repoId, rev);
          return GateSet.unknown("The git host could not be asked what " + RELEASE_CONFIG + " says");
        }
        if (config == null || !config.ok()) {
          return GateSet.unknown(
              RELEASE_CONFIG
                  + " is declared at "
                  + rev
                  + " and could not be read: "
                  + (config == null ? "the git host could not be asked" : config.detail()));
        }
        try {
          if (archetypeParser.declaresQaPipeline(config.value())) {
            kinds.add(Kind.CI);
          }
        } catch (RuntimeException e) {
          // Unlike the settings-file failure below, this does NOT widen to GateSet.unknown: the one
          // fact in doubt is whether this repository's CI gate applies, the deployment and approval
          // gates read from other files entirely, and the safe answer to "cannot say" is the same
          // one an archetype with no release-request slot gets — hold the CI gate rather than skip
          // it. See the class javadoc's "Two edge cases both resolve to 'the CI gate applies'".
          LOG.warnf(
              "%s at %s of %s does not parse; treating %s as CI-gated rather than ungated: %s",
              RELEASE_CONFIG, rev, repoId, repoId, e.getMessage());
          kinds.add(Kind.CI);
        }
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
          LOG.warnf("%s at %s of %s does not parse: %s", SETTINGS, rev, repoId, e.getMessage());
          return GateSet.unknown(SETTINGS + " at " + rev + " does not parse: " + e.getMessage());
        }
      }
      return GateSet.of(kinds);
    }
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
