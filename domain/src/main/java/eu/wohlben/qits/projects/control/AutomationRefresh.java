package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Bring one release request's <b>automations</b> up to date in {@link AutomationLedger}, or say why
 * that could not be established — the writer of the ledger's notes, and the only thing in this
 * service that asks {@link ReleaseRequestAutomations} anything on the fold path (epic qits-978).
 *
 * <h2>What it asks, and of which requests</h2>
 *
 * <p><b>Every open, folded request of every repository</b>, not only the wrapper's. Which kinds
 * apply is decided by qits-maintenance from the repository at the fold — never by an opt-in here —
 * so a repository no kind applies to costs one short exchange that answers an empty list, which is a
 * FRESH note and a request that releases on the same pass it always did.
 *
 * <p>The ask carries what the far side cannot know for itself:
 *
 * <ul>
 *   <li>the repository's <b>name</b>, which is how qits-maintenance's catalogue addresses it;
 *   <li>the <b>source branches</b> — the request's named BRANCH sources, minus the repository's
 *       default branch (a source no release consumes, and protected, so a commit asked of it never
 *       arrives — measured live on {@code qits-qits} with the targeted bump this replaced) and minus
 *       {@code maintenance/automations/**}, the automations' own branches, which are joined to the
 *       request and must never be asked to regenerate themselves;
 *   <li>the <b>previous fold</b> and <b>every path that changed since it</b>, which is what lets the
 *       far side carry an outcome over a re-fold an automation's own commit caused instead of running
 *       it again. The paths come from {@link FoldChanges#pathsBetween} and are null on the first fold
 *       and on a read that failed: null never carries, so the failure direction is one extra run.
 * </ul>
 *
 * <h2>How an answer becomes a note</h2>
 *
 * <p>Every entry FRESH or NOT_APPLICABLE (an empty list included) is {@link
 * AutomationLedger.State#FRESH}; any FAILED
 * is FAILED; any UNKNOWN, or a word this side has never heard of, is UNKNOWN; and anything still
 * moving — REQUESTED, RUNNING, COMMITTED (a commit that re-folds the request, so this fold never
 * ships), SUPERSEDED (the far side saw the fold move on first) — is PENDING. No answer at all is
 * UNKNOWN. FAILED is read before UNKNOWN so that a red run is never reported as an outage.
 *
 * <h2>Idempotence, and what the sweep re-reads</h2>
 *
 * <p>Per {@code (requestId, foldSha)}: a FRESH note is left alone, so the sweep's re-ask every thirty
 * seconds costs a map lookup. An UNKNOWN note, and no note at all, are <b>asked again</b> — the far
 * side's trigger is idempotent, so the re-ask answers the stored outcome rather than running
 * anything twice — which is what makes a qits-maintenance outage heal on the existing sweep.
 *
 * <p>A PENDING or FAILED note is <b>re-read</b> with {@link ReleaseRequestAutomations#status}, not
 * re-posted. That closes the gap the estate gate carried: its PENDING_BUMP was sticky, so a targeted
 * bump whose run went red left the wrapper saying the pins were being written until something
 * re-folded it, and this service never learned the run failed. Now a red run is read, a re-run
 * pressed on a FAILED one is read, and a green run that moved nothing is read as FRESH. The read is
 * trusted only when it lists every kind the note listed — a kind with no row at the fold is not on a
 * status read, which cannot be told apart from "fresh by plan, stored nowhere" — and otherwise the
 * trigger is asked instead. A read that could not be made leaves the note as it is: it holds either
 * way.
 *
 * <p><b>Nothing here throws.</b> It is called from the arming seam, after a fold has already landed,
 * and from the gate's tail. Both belts are the caller's; this one is its own.
 */
@ApplicationScoped
public class AutomationRefresh {

  private static final Logger LOG = Logger.getLogger(AutomationRefresh.class);

  /** What a repository's default branch is when its row does not say — {@code ReleaseRequests}'. */
  private static final String DEFAULT_MAIN = "main";

  /** The automations' own branches. Joined to a request; never a branch they are asked about. */
  static final String AUTOMATION_BRANCHES = "maintenance/automations/";

  @Inject ReleaseRequestRepository requests;

  @Inject ReleaseRequestSourceRepository sources;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject ApprovalPolicy approvalPolicy;

  @Inject AutomationLedger ledger;

  @Inject FoldChanges foldChanges;

  @Inject Instance<ReleaseRequestAutomations> automations;

  /**
   * Everything this refresh needs out of the database, read once.
   *
   * @param repoName the repository as the catalogue names it — the address the port takes, and the
   *     reason this is read here rather than at the adapter; null for a repository with no alias
   * @param wrapper whether this is the estate wrapper, which is gated whether the port is configured
   *     or not
   * @param branches the branches this request releases — its named sources in the order they were
   *     put on it, without the default branch and the automations' own
   */
  private record Facts(
      String requestId,
      String repoId,
      String repoName,
      boolean wrapper,
      String foldSha,
      List<String> branches) {}

  /**
   * Whether the automation gate is on at all: an implementation exists and says it has somewhere to
   * ask. Never throws — a port that cannot say is a port that is not configured.
   */
  public boolean configured() {
    if (!automations.isResolvable()) {
      return false;
    }
    try {
      return automations.get().configured();
    } catch (RuntimeException e) {
      LOG.warnf(e, "The release-request automations port could not say whether it is configured");
      return false;
    }
  }

  /**
   * Establish, or fail to establish, where {@code requestId}'s current fold's automations stand.
   *
   * <p>The only entry point, and it never throws: a port bug, a lazy-loading surprise or anything
   * else costs one WARN and leaves the ledger as it was — which is a hold, because absence is the
   * hold.
   *
   * @param previousFoldSha the fold the current one replaced, when the caller is the fold seam and
   *     knows it; null from the sweep, which then reuses what the note already carries
   */
  public void refresh(String requestId, String previousFoldSha) {
    try {
      attempt(requestId, previousFoldSha);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not refresh the automations of release request %s; it holds until the next sweep",
          requestId);
    }
  }

  private void attempt(String requestId, String previousFoldSha) {
    Facts facts = QuarkusTransaction.requiringNew().call(() -> gather(requestId));
    if (facts == null) {
      // Not open or not folded yet: there is no question to answer, and a note about a request that
      // never needed one would be a note nothing ever drops.
      return;
    }
    AutomationLedger.Note held =
        ledger.noteFor(requestId).filter(note -> facts.foldSha().equals(note.foldSha())).orElse(null);
    String previous =
        previousFoldSha != null ? previousFoldSha : held == null ? null : held.previousFoldSha();
    if (!configured()) {
      if (facts.wrapper()) {
        // The one repository that is gated with no port at all: holding, and saying why, is the
        // estate gate's behaviour before automations were generalised and it is kept exactly.
        unknown(facts, previous, "no qits-maintenance is configured to run the automations");
      }
      return;
    }
    if (held != null && held.state() == AutomationLedger.State.FRESH) {
      return;
    }
    // A repository with no alias is asked about anyway, with a null name: the shipped adapter
    // addresses qits-maintenance by name and answers "could not ask" for none, which holds the
    // request — and whether a name is needed at all is the implementation's to say, not this seam's.
    ReleaseRequestAutomations port = automations.get();
    if (held != null
        && (held.state() == AutomationLedger.State.PENDING
            || held.state() == AutomationLedger.State.FAILED)) {
      // RE-READ, NOT RE-POST: see the class javadoc. A read that could not be made leaves the note.
      Optional<ReleaseRequestAutomations.Answer> read =
          port.status(facts.repoName(), facts.requestId(), facts.foldSha());
      if (read.isEmpty()) {
        return;
      }
      if (covers(read.get(), held) && read.get().foldSha() != null) {
        AutomationLedger.Note note = noteOf(facts, previous, read.get());
        if (note.state() != AutomationLedger.State.UNKNOWN) {
          record(facts, note);
          return;
        }
      }
      // The read cannot stand on its own — a kind missing from it, or a word that says nothing — so
      // the idempotent trigger is asked instead, below.
    }
    List<String> changed = previous == null ? null : changedSince(facts, previous);
    Optional<ReleaseRequestAutomations.Answer> answer =
        port.request(
            facts.repoName(),
            facts.requestId(),
            facts.foldSha(),
            previous,
            changed,
            facts.branches(),
            null);
    if (answer.isEmpty()) {
      unknown(facts, previous, "qits-maintenance could not be asked about them");
      return;
    }
    if (answer.get().foldSha() != null && !facts.foldSha().equals(answer.get().foldSha())) {
      unknown(facts, previous, "qits-maintenance answered about another fold");
      return;
    }
    record(facts, noteOf(facts, previous, answer.get()));
  }

  /** Whether a status read lists every kind the note it would replace listed. */
  private static boolean covers(
      ReleaseRequestAutomations.Answer read, AutomationLedger.Note held) {
    Set<String> listed = new HashSet<>();
    read.automations().forEach(entry -> listed.add(entry.kind()));
    return !read.automations().isEmpty()
        && held.automations().stream().allMatch(entry -> listed.contains(entry.kind()));
  }

  /**
   * The paths the fold changed since the one before it, or null when they could not be read — and
   * null is the safe answer, because it never carries an outcome over and costs at most a run.
   */
  private List<String> changedSince(Facts facts, String previous) {
    try {
      return foldChanges.pathsBetween(facts.repoId(), facts.foldSha(), previous);
    } catch (RuntimeException e) {
      LOG.debugf(
          e,
          "Release request %s: what changed between %s and %s could not be read; asking without it",
          facts.requestId(),
          previous,
          facts.foldSha());
      return null;
    }
  }

  /** The answer as the ledger keeps it — see the class javadoc for how the states read together. */
  private static AutomationLedger.Note noteOf(
      Facts facts, String previous, ReleaseRequestAutomations.Answer answer) {
    List<AutomationLedger.Automation> entries =
        answer.automations().stream()
            .map(
                entry ->
                    new AutomationLedger.Automation(
                        entry.kind(),
                        entry.label() == null ? entry.kind() : entry.label(),
                        entry.state() == null ? "UNKNOWN" : entry.state().toUpperCase(Locale.ROOT),
                        entry.detail(),
                        entry.runIds(),
                        entry.branch(),
                        entry.resultSha(),
                        entry.updatedAt(),
                        entry.failure()))
            .toList();
    return new AutomationLedger.Note(facts.foldSha(), stateOf(entries), entries, null, previous);
  }

  /** The words several kinds' states read as together. */
  static AutomationLedger.State stateOf(List<AutomationLedger.Automation> entries) {
    boolean failed = false;
    boolean unknown = false;
    boolean pending = false;
    for (AutomationLedger.Automation entry : entries) {
      switch (entry.state()) {
        case "FRESH", AutomationLedger.NOT_APPLICABLE -> {}
        case "FAILED" -> failed = true;
        case "REQUESTED", "RUNNING", "COMMITTED", "SUPERSEDED" -> pending = true;
        default -> unknown = true;
      }
    }
    if (failed) {
      return AutomationLedger.State.FAILED;
    }
    if (unknown) {
      return AutomationLedger.State.UNKNOWN;
    }
    return pending ? AutomationLedger.State.PENDING : AutomationLedger.State.FRESH;
  }

  private void record(Facts facts, AutomationLedger.Note note) {
    ledger.record(facts.requestId(), note);
    LOG.debugf(
        "Release request %s: the automations at %s are %s (%d kind(s))",
        facts.requestId(), facts.foldSha(), note.state(), note.automations().size());
  }

  private void unknown(Facts facts, String previous, String reason) {
    LOG.warnf(
        "Release request %s holds: its automations at %s could not be established — %s",
        facts.requestId(), facts.foldSha(), reason);
    // What was known about kinds at this fold is kept beside the reason: an outage is not news that
    // the kinds stopped applying.
    List<AutomationLedger.Automation> known =
        ledger
            .noteFor(facts.requestId())
            .filter(note -> facts.foldSha().equals(note.foldSha()))
            .map(AutomationLedger.Note::automations)
            .orElse(List.of());
    ledger.record(
        facts.requestId(),
        new AutomationLedger.Note(
            facts.foldSha(), AutomationLedger.State.UNKNOWN, known, reason, previous));
  }

  /**
   * The database half, in one short transaction. Null for every request this feature has no opinion
   * about — one that is gone, settled or not folded yet — and returning null rather than an empty
   * answer matters: nothing is written to the ledger for those, so nothing has to be cleaned up for
   * them either.
   */
  private Facts gather(String requestId) {
    ReleaseRequest row = requests.findByIdOptional(requestId).orElse(null);
    if (row == null || row.state != ReleaseRequest.State.PENDING || row.mergedSha == null) {
      return null;
    }
    Repository repository = repositories.findByIdOptional(row.repoId).orElse(null);
    if (repository == null) {
      return null;
    }
    // Null is carried rather than turned into a null Facts: an unnamed repository is one this gate
    // still applies to, and the port decides what asking about it means.
    String repoName = names.nameFor(repository).orElse(null);
    // THE DEFAULT BRANCH IS A SOURCE AND IS NOT A TARGET — the class javadoc has the whole of why.
    // Read the way ReleaseRequests reads it, blank included, so the two cannot disagree about which
    // branch this is.
    String main =
        repository.mainBranch == null || repository.mainBranch.isBlank()
            ? DEFAULT_MAIN
            : repository.mainBranch;
    List<String> branches =
        sources.listByRequest(requestId).stream()
            .filter(source -> source.kind == ReleaseRequestSource.Kind.BRANCH)
            .map(source -> source.name)
            .filter(name -> !main.equals(name))
            .filter(name -> !name.startsWith(AUTOMATION_BRANCHES))
            .toList();
    return new Facts(
        row.id,
        row.repoId,
        repoName,
        approvalPolicy.isEstateWrapper(row.repoId),
        row.mergedSha,
        branches);
  }
}
