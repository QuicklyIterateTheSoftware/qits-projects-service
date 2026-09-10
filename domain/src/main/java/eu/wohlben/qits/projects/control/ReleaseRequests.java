package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.MergeConflictDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestCommitsDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceDto;
import eu.wohlben.qits.projects.entity.ReleasePriority;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.ReleaseRequestApprovalRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The release-request state machine: N sources folded onto a backing branch, settled by quality
 * gates, executed when they pass. The reason the build-status ledger lives in this service — a
 * verdict arrives, the ledger row is written, and the matching requests resolve in the same
 * consumption.
 *
 * <h2>A request is an octopus merge</h2>
 *
 * <p>Its participants are the <b>named branches</b> on it ({@code main} is implied by every create,
 * and more are added later) plus the <b>implicit tag sources</b>: every released tag of the
 * repository not yet merged to {@code main}. The implicit set is what makes each release a superset
 * of the releases still in flight — a release is a tag and {@code main} is finalized only after the
 * deployment, so between those two moments a request that did not fold the tag in would be a step
 * backwards from what is already shipping.
 *
 * <p>{@link #remerge} folds them into {@code refs/heads/release/<id>} through {@link
 * BackingBranchMerger} and stores the tip as {@code mergedSha}. <b>That fold is the re-arm</b>:
 * a new tip invalidates the gates and puts the request back to PENDING, exactly as a new branch head
 * used to. Four things trigger it, and each is per-request — a shared trigger is never a shared
 * merge, and one request's fold never touches a sibling's backing branch:
 *
 * <ol>
 *   <li>the request being created, and a source being added to it;
 *   <li>a push touching any of its named branches ({@link #onBranchMoved}, off {@code
 *       SCMPublishCommit});
 *   <li>a sibling release JOINING the implicit set — every open request of that repository re-folds,
 *       and it is a real change;
 *   <li>a pending tag LEAVING the set on reaching {@code main} ({@link #onReleasedTagMerged}) —
 *       content-idempotent, so the fold usually answers {@code unchanged} and nothing is announced.
 * </ol>
 *
 * <p><b>{@code unchanged} is not a change.</b> The git host answers it when every head is already
 * contained in the target: same sha, no new commit. Nothing is re-armed and no {@code
 * ReleaseRequestChanged} is dispatched, which is what makes a trigger with no content behind it
 * free. A fold that cannot be made at all is {@code CONFLICTED} — no ref moved, the conflict stored
 * for a person to act on, and <b>no event</b>; the next fold that succeeds clears it and dispatches,
 * <em>including</em> one answering {@code unchanged}, unless that sha already carries a gating
 * verdict the gate can read.
 *
 * <h2>The build gate</h2>
 *
 * <p><b>Exactly one thing meets it: a gating {@code BuildSuccessful} whose commit is this request's
 * CURRENT fold.</b> A PENDING request becomes READY when, for its {@code mergedSha}:
 *
 * <ol>
 *   <li><b>No gating verdict is red.</b> One red gating run is a REJECTED request, immediately —
 *       nothing to wait for. Non-gating verdicts (the userflow pipelines) are read and ignored.
 *   <li><b>A gating verdict is green.</b> That is the vouch and there is no second way to earn it.
 *       <b>No verdict is not a pass</b>: the request stays PENDING and the sweep asks again, for as
 *       long as it takes. A repository whose pipeline never materializes therefore cannot release,
 *       and that is what a release gate is <em>for</em>.
 *   <li><b>No run is still queued or running</b>, where qits-ci can be asked. The ledger cannot see
 *       those (only terminal runs announce), so {@link ActiveBuilds} asks; a second gating pipeline
 *       still grinding on the same fold could still come back red, and a positive count holds the
 *       request. An answer that cannot be had does <b>not</b> hold a vouched sha back — the green
 *       gating verdict is the gate, the probe only ever narrows it.
 * </ol>
 *
 * <p><b>There was a fourth arm and it was a hole.</b> A sha nothing vouched for used to pass
 * vacuously once a settle window had lapsed, on the theory that a repository with no CI must still
 * be releasable. What it actually did — QA runs are created over the bus and executed by one serial
 * runner, so the active-runs probe answers 0 while a run is still being accepted — was wave releases
 * through in under two minutes, before their QA runs had executed at all (measured 2026-09-04). The
 * window is gone, with the property that configured it.
 *
 * <p>A request with no {@code mergedSha} yet is gated on nothing and stays PENDING: the fold has not
 * been computed, so there is no content to have an opinion about.
 *
 * <p><b>The merged sha is the correlation key, in both directions.</b> Only a verdict naming the
 * request's <em>current</em> fold can flip it — one for a superseded merge matches no request and is
 * simply not read — and the ledger rows of superseded merges are kept rather than deleted, because
 * they are the record of what was built. That is what makes the gate safe without anybody stopping
 * the runs, which in turn is what lets the run cancellation a re-fold asks for be best effort: it
 * frees a build agent, it does not decide anything. See {@link #evaluate(String, String)} and {@link
 * QaRunCancellations}.
 *
 * <h2>The approval gate</h2>
 *
 * <p><b>Some releases need a person to say yes, and that is a second gate rather than a clause of
 * the first.</b> Where {@link ApprovalPolicy} says this repository's releases have to be approved —
 * today that is the wrapper, whose release is the estate's own version moving — a request that has
 * passed every build-gate arm still does not become READY until somebody has decided about it. The
 * decision is a {@link eu.wohlben.qits.projects.entity.ReleaseRequestApproval} row: APPROVED lets it
 * through, DECLINED rejects it with the decider's own sentence, and no row at all holds it PENDING
 * saying so.
 *
 * <p><b>It is correlated to {@code mergedSha}, exactly as the build gate is, and that is what makes
 * it cost nothing to invalidate.</b> A decision names the fold it was made about, so anything that
 * re-folds this request lands a new sha and the old decision stops matching — no column to clear, no
 * path that has to remember to clear it, and no way for an approval of one content to authorise
 * another. Pushing a fix onto a declined request is the ordinary way to answer it, the same move
 * that answers a red build.
 *
 * <p><b>Nothing about it is stored on the request.</b> Whether approval is required is asked of
 * {@link ApprovalPolicy} on every evaluation and on every read, and the decision is read out of the
 * approval table at the current sha. That is deliberate rather than incidental: the policy is a seam
 * whose rule is going to change, and a stored answer would leave the requests that are already open
 * holding the old one. What the API answers is derived the same way, for the same reason — see
 * {@link ReleaseRequestDto}.
 *
 * <p><b>Its rule is a placeholder and its position is not.</b> {@link ApprovalPolicy} tests the
 * archetype today; what replaces it is the primary quality gate — the step that asks whether the
 * change did what it set out to do — after which a person is asked when that gate cannot vouch for
 * the work, whatever the repository is. So this gate is the escalation seam, and the policy in front
 * of it is the <em>primary</em> gate in the sense that matters here: it decides whether the question
 * is asked at all, and everything downstream of it is unchanged by the day it starts answering
 * differently.
 *
 * <p><b>Why it sits AFTER the build gate.</b> A red gating verdict rejects the request before
 * anybody is asked, so nobody is ever put in front of a fold CI has already failed — the most
 * expensive thing a gate can waste is a person's attention, and asking for a sign-off on a broken
 * build spends it on a decision that cannot matter. The same order is why a request waiting for CI
 * says so rather than asking for an approval it would have to re-ask after the next fold. A
 * <b>decline is not an unattended-gate ticket</b> for the mirror-image reason: a person just said
 * no, so by definition somebody is watching, and {@link UnattendedGateTickets} stays wired to red
 * builds alone.
 *
 * <h2>The release</h2>
 *
 * <p><b>Execution happens off every other thread.</b> A READY request is handed to the one-thread
 * {@code release-request-worker}: the resolution runs under the bus consumption and the sweep on a
 * scheduler thread, and the release is several HTTP round trips neither may sit on. The worker
 * re-reads the row, so a request executed twice over is settled by the first arrival.
 *
 * <p>What {@link ReleaseExecutor} does is stamp a calver, rewrite the manifests at the fold, commit
 * them onto the backing branch, <b>tag</b> that commit, delete the branches the release consumed and
 * announce {@code SCMRelease}. There is no push to {@code main} in it: a release is a tag, and
 * {@code main} is finalized after the deployment. This class's own share of that is the bookkeeping
 * — {@link #recordReleasedTag} puts the new tag in the repository's implicit source set and re-folds
 * every <em>other</em> open request, so each of them is a superset of what is already shipping.
 */
@ApplicationScoped
public class ReleaseRequests {

  private static final Logger LOG = Logger.getLogger(ReleaseRequests.class);

  /** The fallback default branch, for a repository row that names none. */
  private static final String DEFAULT_MAIN = "main";

  @Inject ReleaseRequestRepository requests;

  @Inject ReleaseRequestSourceRepository sources;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  /**
   * The approval gate's two halves — the policy that says whether a person has to be asked, and the
   * rows that say what one answered. Both are read on every evaluation and on every read of a
   * request, and neither is cached: see the approval gate in this class's javadoc for why nothing
   * about it may be stored on the request row.
   */
  @Inject ApprovalPolicy approvalPolicy;

  @Inject ReleaseRequestApprovalRepository approvals;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject BuildStatusLedger ledger;

  /**
   * The mirror-backed git reader, for the one question this class answers out of a repository rather
   * than out of its own tables: what a fold brought in. Not an {@code Instance} — it is this
   * module's own bean and not a port, and the mirror it reads is cloned on first use.
   */
  @Inject CommitService commits;

  @Inject ObjectMapper json;

  @Inject Instance<ActiveBuilds> activeBuilds;

  @Inject Instance<ReleaseExecutor> executors;

  @Inject Instance<BackingBranchMerger> mergers;

  @Inject Instance<ReleaseRequestAnnouncer> announcers;

  @Inject Instance<QaRunCancellations> cancellations;

  @Inject Instance<ReleasedBranchWorkspaces> releasedBranchWorkspaces;

  @Inject Instance<DownstreamComponents> downstreamComponents;

  @Inject Instance<UnattendedGateTickets> gateTickets;

  /**
   * The requesters that are <b>machines</b>, and therefore the requests nobody is waiting on. A
   * request carries the forwarded identity of whoever asked; qits-maintenance deliberately sends
   * none, so its bumps are attributed to its own machine principal and land here. Comma-separated,
   * matched exactly, and a platform that names none simply files no tickets.
   *
   * <p>Configuration rather than a constant because the identity is an OIDC client name and a
   * platform may run a second robot; it is not a switch for turning the behaviour off, which is what
   * leaving the port unimplemented does.
   */
  @ConfigProperty(
      name = "qits.projects.release-requests.unattended-requesters",
      defaultValue = "qits-platform-maintenance")
  List<String> unattendedRequesters;

  /**
   * The publish phase, called on the worker the instant a release lands: a repository that declares
   * no deployment has nothing to wait for and its tag is merged to {@code main} there and then.
   *
   * <p>The reverse edge — {@link ReleaseFinalization} injects this class to clear a tag out of the
   * implicit source set — makes this a cycle between two {@code @ApplicationScoped} beans, which
   * CDI's client proxies resolve. It is the honest shape: a release and its finalization are two
   * halves of one lifecycle and each has to be able to start the other.
   */
  @Inject ReleaseFinalization finalization;

  private ExecutorService worker;

  @PostConstruct
  void start() {
    worker =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "release-request-worker");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  // ---------------------------------------------------------------------------------------------
  // The caller-facing arms
  // ---------------------------------------------------------------------------------------------

  /**
   * Create (or converge on) the request the named branch participates in. The fresh request's named
   * sources are the repository's default branch and {@code branch} — {@code main} is <b>implied</b>
   * rather than asked for, because a release that does not contain what is already on main is not a
   * release anybody wants; naming the default branch itself simply makes a main-only request.
   *
   * <p>At most one open request per named branch, the merge-request shape kept: asking again for a
   * branch that already participates in an open request answers that request rather than opening a
   * second one, and adds nothing to it.
   *
   * @param priority how urgently the named branch wants to be released, or null/blank for {@code
   *     MEDIUM}. The implied {@code main} row takes the default rather than the caller's word: the
   *     caller asked about their branch and said nothing about main. On the <b>converge</b> arm an
   *     absent priority changes nothing — re-asking for a branch must never silently downgrade the
   *     urgency somebody escalated it to — and a stated one updates the branch's row.
   */
  public ReleaseRequestDto request(
      String repoId, String branch, String summary, String requester, String priority) {
    String named = requireBranch(branch);
    ReleasePriority stated = statedPriority(priority);
    if (summary == null || summary.isBlank()) {
      throw new BadRequestException("A release request carries a summary");
    }
    Repository repository =
        repositories
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    String projectId = repository.project == null ? null : repository.project.id;
    String repoName = names.nameFor(repository).orElse(null);
    String main =
        repository.mainBranch == null || repository.mainBranch.isBlank()
            ? DEFAULT_MAIN
            : repository.mainBranch;

    String id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest open = requests.findOpenByBranch(repoId, named).orElse(null);
                  if (open != null) {
                    open.summary = summary.trim();
                    if (requester != null) {
                      open.requester = requester;
                    }
                    if (stated != null) {
                      sources
                          .find(open.id, ReleaseRequestSource.Kind.BRANCH, named)
                          .ifPresent(source -> source.priority = stated);
                    }
                    open.updatedAt = Instant.now();
                    return open.id;
                  }
                  ReleaseRequest fresh = new ReleaseRequest();
                  fresh.id = UUID.randomUUID().toString();
                  fresh.repoId = repoId;
                  fresh.projectId = projectId;
                  fresh.repoName = repoName;
                  fresh.summary = summary.trim();
                  fresh.requester = requester;
                  fresh.state = ReleaseRequest.State.PENDING;
                  fresh.createdAt = Instant.now();
                  fresh.armedAt = fresh.createdAt;
                  fresh.updatedAt = fresh.createdAt;
                  requests.persist(fresh);
                  // main first: it is the head the fold starts from, and the order sources are
                  // added in is the order they become parents. It is IMPLIED rather than asked
                  // for, so it takes the default priority — unless the caller named it, in which
                  // case that one row is the branch they spoke about.
                  boolean mainWasNamed = main.equals(named);
                  addSourceRow(
                      fresh.id,
                      main,
                      null,
                      mainWasNamed ? orDefault(stated) : ReleasePriority.DEFAULT);
                  if (!mainWasNamed) {
                    addSourceRow(fresh.id, named, requester, orDefault(stated));
                  }
                  return fresh.id;
                });
    remerge(id, "created");
    return get(id);
  }

  /**
   * Put another branch on an open request. Idempotent — a branch already named is not added twice
   * and the request is answered unchanged — and a settled request refuses with a 409, because
   * widening what has already been released or withdrawn would rewrite a record.
   *
   * <p>Only <b>named</b> sources are caller-managed. The implicit tag sources are derived from what
   * the repository has in flight and an API that let a caller drop one would let somebody release a
   * step backwards from what is already shipping.
   *
   * @param priority how urgently this branch wants to be released, or null/blank for {@code
   *     MEDIUM}. On the idempotent arm — the branch is already a source — a stated priority updates
   *     the row it names and an absent one leaves it exactly where it was, so a retried add never
   *     downgrades an escalation. Neither re-folds: a priority is not content.
   */
  public ReleaseRequestDto addSource(
      String requestId, String branch, String actor, String priority) {
    String named = requireBranch(branch);
    ReleasePriority stated = statedPriority(priority);
    boolean added =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requireOpenForChange(requestId);
                  ReleaseRequestSource existing =
                      sources.find(row.id, ReleaseRequestSource.Kind.BRANCH, named).orElse(null);
                  if (existing != null) {
                    if (stated != null && stated != existing.priority) {
                      existing.priority = stated;
                      row.updatedAt = Instant.now();
                    }
                    return false;
                  }
                  addSourceRow(row.id, named, actor, orDefault(stated));
                  row.updatedAt = Instant.now();
                  return true;
                });
    if (added) {
      remerge(requestId, "a source was added: " + named);
    }
    return get(requestId);
  }

  /**
   * Re-state how urgently one named branch of an open request wants to be released. The request's
   * effective priority — the max over its named sources — follows on the next read.
   *
   * <p><b>Nothing is re-folded and nothing is announced.</b> The fold did not move: the same
   * branches are folded onto the same backing branch and the sha is the one the gates are already
   * evaluating, so asking the git host again would be a call with no content behind it and a {@code
   * ReleaseRequestChanged} would ask qits-ci for a second build of a sha it has already built. The
   * value reaches the platform on the next event that carries it anyway — {@code SCMRelease} reads
   * the sources live at release time, so an escalation made while a request waits on its gate still
   * arrives with the release. <b>The queue-ordering feature revisits this</b>: the moment something
   * downstream orders by the value, a priority-only change becomes news and this arm needs a
   * statement of its own.
   *
   * <p>Refusals, in the order they are met: a request that does not exist is a 404; a RELEASED or
   * WITHDRAWN one is a 409 ({@link #requireOpenForChange}, because re-pricing what already
   * concluded would rewrite a record); a branch this request does not name is a 404 naming it; and
   * a word naming no priority is a 400 naming the word. Implicit tag sources are not addressable
   * here — they have no row, and their urgency is the release they came from.
   */
  public ReleaseRequestDto updateSourcePriority(
      String requestId, String branch, String priority, String actor) {
    String named = requireBranch(branch);
    ReleasePriority wanted = requirePriority(priority);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = requireOpenForChange(requestId);
              ReleaseRequestSource source =
                  sources
                      .find(row.id, ReleaseRequestSource.Kind.BRANCH, named)
                      .orElseThrow(
                          () ->
                              new NotFoundException(
                                  "Release request "
                                      + requestId
                                      + " has no named source "
                                      + named));
              if (source.priority == wanted) {
                return;
              }
              LOG.infof(
                  "Release request %s: source %s is %s now (was %s), asked by %s",
                  requestId, named, wanted, source.priority, actor == null ? "an operator" : actor);
              source.priority = wanted;
              row.updatedAt = Instant.now();
            });
    return get(requestId);
  }

  /**
   * Withdraw an open request: the ask is moot and a person (or the branch's deletion) said so.
   * WITHDRAWN is terminal and leaves its branches free — the next ask mints a fresh request rather
   * than reviving this one, and a push no longer re-merges it. A request already settled (RELEASED,
   * WITHDRAWN) refuses with a 409 naming its state: withdrawing what already concluded would rewrite
   * a record.
   */
  public ReleaseRequestDto withdraw(String id, String reason, String actor) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = requireOpenForChange(id);
              row.state = ReleaseRequest.State.WITHDRAWN;
              row.detail =
                  (reason == null || reason.isBlank())
                      ? "Withdrawn by " + (actor == null ? "an operator" : actor)
                      : reason.trim();
              row.retryable = false;
              row.updatedAt = Instant.now();
            });
    return get(id);
  }

  /** One request, as the API answers it. */
  public ReleaseRequestDto get(String id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row =
                  requests
                      .findByIdOptional(id)
                      .orElseThrow(
                          () -> new NotFoundException("Release request not found: " + id));
              return dto(
                  row,
                  row.repoName,
                  sources.listByRequest(id),
                  implicitFor(row.repoId),
                  pendingTags.findByRequest(id).orElse(null),
                  approvalOf(row));
            });
  }

  /**
   * One repository's requests, newest first — <b>the open ones plus the last {@value
   * #RECENT_RELEASED} releases</b> when nobody names a state, and exactly what {@link #statesFor}
   * makes of the word when somebody does.
   *
   * <p>Ordered by {@code createdAt} because this list is the repository's own record, read as "what
   * has been asked for here" — the project-wide list is the worklist and orders by what moved last.
   * The two reads are merged in memory rather than in SQL, which is what lets the tail be a page:
   * the open set is bounded by the flow and the tail by {@value #RECENT_RELEASED}.
   */
  public List<ReleaseRequestDto> listByRepo(String repoId, String state) {
    Selection selection = statesFor(state);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<ReleaseRequest> rows = new ArrayList<>(requests.listByRepo(repoId, selection.states()));
              if (selection.recentReleased()) {
                rows.addAll(requests.listRecentReleased(repoId, RECENT_RELEASED));
              }
              rows.sort(Comparator.comparing((ReleaseRequest row) -> row.createdAt).reversed());
              return decorate(rows, Map.of());
            });
  }

  /**
   * A whole project's requests, across every repository it owns, most recently moved first — the
   * one read that answers "what is waiting on me here" without walking the repositories.
   *
   * <p><b>Open by default, plus what has just landed.</b> With no {@code state} the answer is {@link
   * ReleaseRequestRepository#OPEN} — the requests that can still move — followed by the project's
   * last {@value #RECENT_RELEASED} releases, so that a release leaving the worklist does not also
   * leave the page. {@code all} answers every state, and a state's own name narrows to it. A word
   * naming none is a {@link BadRequestException} rather than an empty list, so a typo in the filter
   * never reads as "nothing is pending" — the same posture the epic board's status filter takes.
   *
   * <p>Each row is named with the repository's <b>current</b> alias rather than the one recorded
   * when the request was made: a rename moves the name and leaves the row's snapshot behind, and a
   * list that spans repositories is exactly where a stale name would mislead. The map is one query
   * for the project, so naming the rows costs nothing per row. A repository with no alias keeps its
   * snapshot, and then null — the caller shows the id.
   */
  public List<ReleaseRequestDto> listByProject(String projectId, String state) {
    Selection selection = statesFor(state);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<ReleaseRequest> rows =
                  new ArrayList<>(requests.listByProject(projectId, selection.states()));
              if (selection.recentReleased()) {
                rows.addAll(requests.listRecentReleasedByProject(projectId, RECENT_RELEASED));
              }
              rows.sort(Comparator.comparing((ReleaseRequest row) -> row.updatedAt).reversed());
              return decorate(rows, names.namesByRepository(projectId));
            });
  }

  /**
   * What this request's fold brought in — the commits of {@code mergedSha^1..mergedSha}, which is
   * the octopus's own range and therefore exactly what the participants contributed over the branch
   * it was folded onto.
   *
   * <p>Scoped by repository as well as by id, so a request read through the wrong repository's route
   * is a 404 rather than somebody else's answer. (The bare {@link #get} is deliberately left as it
   * is: it is the machine peers' read and has always been keyed on the id alone.)
   *
   * <p><b>Three empties, three sentences, and no error among them.</b> A request that has not been
   * folded yet has nothing to list; a fold the repository no longer holds — a withdrawn request's
   * backing branch is deleted, and history predating the mirror was never there — is a fact about
   * the repository and not a failure of this read; and a fold that added nothing over its target is
   * a real, if unusual, release. Every one of them answers 200 with the reason on {@code detail}.
   */
  public ReleaseRequestCommitsDto mergedCommits(String repoId, String requestId) {
    ReleaseRequest row =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests
                        .findByIdOptional(requestId)
                        .filter(candidate -> candidate.repoId.equals(repoId))
                        .orElseThrow(
                            () ->
                                new NotFoundException(
                                    "Release request not found: " + requestId)));
    if (row.mergedSha == null) {
      return new ReleaseRequestCommitsDto(null, List.of(), "Nothing has been folded yet");
    }
    CommitService.MergeRange range = commits.listMergeRange(repoId, row.mergedSha);
    if (!range.present()) {
      return new ReleaseRequestCommitsDto(
          row.mergedSha, List.of(), "The fold is no longer in the repository's history");
    }
    if (range.commits().isEmpty()) {
      return new ReleaseRequestCommitsDto(
          row.mergedSha,
          List.of(),
          "The fold brought nothing in: every source was already contained in what it was folded"
              + " onto");
    }
    return new ReleaseRequestCommitsDto(row.mergedSha, range.commits(), null);
  }

  // ---------------------------------------------------------------------------------------------
  // The triggers
  // ---------------------------------------------------------------------------------------------

  /**
   * A branch moved: every open request of the repository that names it re-folds, whatever state it
   * was in — a PENDING request re-gates the new fold, a READY-but-unexecuted one must not land the
   * old one, a REJECTED one comes back to life because the fix it asked for is exactly what a new
   * push is, a FAILED one retries against reality, and a CONFLICTED one is cleared by a push that
   * makes the fold succeed. Called from the {@code SCMPublishCommit} consumption; a branch no open
   * request names is a no-op.
   *
   * <p><b>Per request, never per branch.</b> A push to {@code main} participates in every open
   * request of the repository and each gets its own fold onto its own backing branch.
   */
  public void onBranchMoved(String repoId, String branch, String sha) {
    List<String> affected =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests.findOpenByBranches(repoId, List.of(branch)).stream()
                        .map(row -> row.id)
                        .toList());
    for (String id : affected) {
      remerge(id, "a push moved " + branch + " to " + shortSha(sha));
    }
  }

  /**
   * A branch is gone, so it participates in nothing: it is dropped from every open request naming
   * it. A request left with nothing but the repository's default branch is <b>withdrawn</b> — there
   * is no work in it any more and the row would otherwise stand open forever (three did,
   * 2026-09-01) — and every other affected request simply re-folds without it.
   */
  public void onBranchDeleted(String repoId, String branch) {
    record Affected(String id, boolean withdrawn) {}
    List<Affected> affected =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Affected> touched = new ArrayList<>();
                  for (ReleaseRequest open : requests.findOpenByBranches(repoId, List.of(branch))) {
                    sources
                        .find(open.id, ReleaseRequestSource.Kind.BRANCH, branch)
                        .ifPresent(sources::delete);
                    List<ReleaseRequestSource> left = sources.listByRequest(open.id);
                    boolean empty =
                        left.isEmpty()
                            || left.stream().allMatch(s -> s.name.equals(mainOf(repoId)));
                    if (empty) {
                      open.state = ReleaseRequest.State.WITHDRAWN;
                      open.detail = "Withdrawn: the branch was deleted";
                      open.retryable = false;
                      open.updatedAt = Instant.now();
                      LOG.infof(
                          "Release request %s withdrawn: branch %s of %s was deleted",
                          open.id, branch, repoId);
                    } else {
                      open.updatedAt = Instant.now();
                    }
                    touched.add(new Affected(open.id, empty));
                  }
                  return touched;
                });
    for (Affected row : affected) {
      if (!row.withdrawn()) {
        remerge(row.id(), "the source branch " + branch + " was deleted");
      }
    }
  }

  /**
   * A released tag reached {@code main}, so it LEAVES the implicit source set — the post-deployment
   * merge's half of the bookkeeping, called by {@link ReleaseFinalization} once the git host has
   * applied that merge. Every open request of the repository re-folds without it.
   *
   * <p><b>This is the only writer of {@code merged_at}</b>, which is what keeps "the tag is on
   * {@code main}" and "the open requests no longer fold it in" one step rather than two.
   *
   * <p><b>Content-idempotent, and that is the point.</b> A tag on {@code main} is already contained
   * in the fold through {@code main} itself, so dropping it changes nothing the git host can see: the
   * merge answers {@code unchanged}, no request is re-armed and no event is dispatched. A tag
   * nothing has a pending row for is a no-op.
   */
  public void onReleasedTagMerged(String repoId, String tagName) {
    boolean cleared =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .find(repoId, tagName)
                        .filter(row -> row.mergedAt == null)
                        .map(
                            row -> {
                              row.mergedAt = Instant.now();
                              return true;
                            })
                        .orElse(false));
    if (!cleared) {
      return;
    }
    remergeOpenOf(repoId, null, "the released tag " + tagName + " reached main");
  }

  /**
   * A verdict landed for {@code (repoId, commitSha)} — re-evaluate what it may settle. Called by
   * the bus consumption right after the ledger write; the evaluation opens transactions of its own,
   * so the claim never spans this datasource.
   *
   * <p><b>The commit is a MERGED sha</b>, which is what makes this read a filter rather than a fan-
   * out: {@code findPendingByCommit} matches requests whose current fold <em>is</em> that commit, so
   * a verdict for a superseded merge names no request and settles nothing. {@link #evaluate(String,
   * String)} re-checks the same equality inside its transaction, because the fold can move between
   * the two.
   */
  public void onVerdict(String repoId, String commitSha) {
    List<String> pending =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests.findPendingByCommit(repoId, commitSha).stream()
                        .map(row -> row.id)
                        .toList());
    pending.forEach(id -> evaluate(id, commitSha));
  }

  /**
   * The safety net under the event-driven path: re-evaluates every open request. It is what turns
   * "could not ask qits-ci" and "the verdict has not landed yet" into delays instead of stalls, what
   * retries a FAILED execution — a <b>retryable</b> one only — and what re-folds a request whose
   * very first merge could not be made because the git host was unreachable.
   *
   * <p><b>It is also the only thing that will ever release a request whose verdict arrived while
   * this service was down.</b> The gate now passes on a verdict and nothing else, so a missed
   * consumption is a request that sits PENDING until something asks again — which is this.
   *
   * <p>A CONFLICTED request is deliberately <b>not</b> re-folded here. A conflict is a fact about
   * content that answers the same on every knock, and knocking anyway is the unbounded-loop defect
   * this file already paid for once (measured 2026-09-01). Something has to change first, and the
   * things that change it are all triggers of their own.
   */
  public void sweep() {
    List<ReleaseRequest> open =
        QuarkusTransaction.requiringNew().call(() -> List.copyOf(requests.listOpen()));
    for (ReleaseRequest row : open) {
      if (row.state != ReleaseRequest.State.CONFLICTED && row.mergedSha == null) {
        remerge(row.id, "the first fold had not been made yet");
        continue;
      }
      switch (row.state) {
        case PENDING -> evaluate(row.id);
        case READY -> enqueueExecution(row.id);
        case FAILED -> {
          if (row.retryable) {
            enqueueExecution(row.id);
          }
        }
        default -> {}
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The fold
  // ---------------------------------------------------------------------------------------------

  /**
   * A fold that produced something new, carried out of its transaction so the announcement is made
   * after it — never inside, the rule {@code RepositoryRenamedAnnouncer} states. Null stands for
   * "nothing to announce", which is every other outcome.
   */
  private record Folded(
      String releaseRequestId,
      String projectId,
      String repoId,
      String repoName,
      String backingBranch,
      String mergedSha,
      String supersededSha,
      Instant changedAt,
      String priority) {}

  /**
   * Fold one request's sources onto its backing branch, and act on what came back. Package-private
   * so the suite can drive a single fold without a trigger.
   *
   * <p>The git-host call is made <b>outside</b> every transaction, the shape the door call already
   * takes: the read that assembles the sources and the write that applies the answer are two short
   * transactions with an HTTP round trip between them.
   */
  void remerge(String id, String why) {
    record Ask(String repoId, List<String> refs, String summary, boolean stillOpen) {}
    Ask ask =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
                  if (row == null || !ReleaseRequestRepository.OPEN.contains(row.state)) {
                    return new Ask(null, List.of(), null, false);
                  }
                  return new Ask(
                      row.repoId,
                      refsOf(sources.listByRequest(id), implicitFor(row.repoId)),
                      row.summary,
                      true);
                });
    if (!ask.stillOpen()) {
      return;
    }
    String target = "refs/heads/" + ReleaseRequest.backingBranchOf(id);
    if (!mergers.isResolvable()) {
      note(id, "No git host is configured; the sources for this request cannot be folded");
      return;
    }
    BackingBranchMerger.Outcome outcome;
    try {
      outcome =
          mergers
              .get()
              .merge(
                  ask.repoId(),
                  target,
                  ask.refs(),
                  "Release request " + id + ": " + ask.summary());
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not lose the request.
      LOG.warnf(e, "The backing-branch merger threw for release request %s", id);
      outcome = BackingBranchMerger.Outcome.unreachable("merger error: " + e.getMessage());
    }
    Folded folded = apply(id, target, why, outcome);
    if (folded != null) {
      if (folded.supersededSha() != null) {
        // A fold that REPLACED a sha, not the first one: whatever qits-ci is still running for this
        // request is grinding on content nobody will accept. See cancel() for why this is best
        // effort and why it is scoped to this request alone.
        cancel(folded.repoId(), folded.releaseRequestId(), folded.supersededSha());
      }
      announce(folded);
    }
    if (outcome.folded()) {
      evaluate(id);
    }
  }

  /**
   * Ask qits-ci to stop this request's in-flight runs, because the fold they were started for has
   * been superseded.
   *
   * <p><b>Best effort, and never able to fail a fold.</b> The gate is correlated by sha — a verdict
   * naming a merge this request has already moved past matches nothing and settles nothing — so the
   * cancellation buys a build agent rather than correctness. An unreachable qits-ci, a refusal and
   * no implementation at all are one answer: carry on. That is also why it is called <b>after</b>
   * the fold's write transaction and outside every transaction, beside the announcement.
   *
   * <p><b>Scoped to this request and never to the repository.</b> A sibling request folds its own
   * sources onto its own backing branch and its runs are none of this one's business; cancelling by
   * repository would take a neighbour's green build away seconds before it settled them.
   */
  private void cancel(String repoId, String requestId, String supersededSha) {
    if (!cancellations.isResolvable()) {
      return;
    }
    try {
      LOG.debugf(
          "Release request %s superseded %s; asking qits-ci to cancel its runs",
          requestId, shortSha(supersededSha));
      cancellations.get().cancelRunsOf(repoId, requestId);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not cost the fold.
      LOG.warnf(e, "Could not ask qits-ci to cancel the runs of release request %s", requestId);
    }
  }

  /** The write half of a fold: the row is re-read, so a request settled mid-call is left alone. */
  private Folded apply(
      String id, String target, String why, BackingBranchMerger.Outcome outcome) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
              if (row == null || !ReleaseRequestRepository.OPEN.contains(row.state)) {
                return null;
              }
              Instant now = Instant.now();
              row.updatedAt = now;
              switch (outcome.result()) {
                case CONFLICT -> {
                  row.state = ReleaseRequest.State.CONFLICTED;
                  row.detail =
                      "The sources could not be folded ("
                          + why
                          + "): "
                          + outcome.conflicts().stream()
                              .map(BackingBranchMerger.Conflict::path)
                              .distinct()
                              .collect(Collectors.joining(", "));
                  row.conflictDetail = conflictJson(target, outcome.conflicts());
                  row.retryable = false;
                  return null;
                }
                case UNREACHABLE -> {
                  // A fact about the moment, not about the request: the state is left where it was
                  // and the sweep folds again.
                  row.detail = "The sources could not be folded: " + outcome.detail();
                  return null;
                }
                case UNCHANGED -> {
                  boolean wasConflicted = row.state == ReleaseRequest.State.CONFLICTED;
                  row.conflictDetail = null;
                  if (!wasConflicted) {
                    return null;
                  }
                  // The fold is possible again and the tip is the answer, but it is the SAME tip —
                  // nothing new to build. Back to PENDING against what is already there.
                  row.state = ReleaseRequest.State.PENDING;
                  row.detail = "The conflict is resolved; the fold is unchanged";
                  row.mergedSha = outcome.sha();
                  if (ledger.verdictsOf(row.repoId, row.mergedSha).stream()
                      .anyMatch(CommitBuildStatusDto::gating)) {
                    // A gating run has already answered for this exact sha, so evaluate() — which
                    // remerge calls the moment this returns — reads it, and announcing would ask
                    // for a second build of content already built. Same-datasource read, so it
                    // costs this transaction nothing.
                    return null;
                  }
                  // Nothing has EVER built this sha and nothing will: a request that went
                  // CONFLICTED on creation never armed a run, and since the vacuous settle-window
                  // pass went (2026-09-04) no verdict is no release, for ever. "Clears it and
                  // dispatches" is the promise, and this is the half of it that was missing —
                  // superseded is null because nothing moved, so no run is cancelled either.
                  return new Folded(
                      row.id,
                      row.projectId,
                      row.repoId,
                      row.repoName,
                      row.backingBranch(),
                      row.mergedSha,
                      null,
                      now,
                      effectivePriorityOf(row.id).name());
                }
                default -> {
                  String superseded = row.mergedSha;
                  boolean moved = !outcome.sha().equals(superseded);
                  row.mergedSha = outcome.sha();
                  row.conflictDetail = null;
                  if (!moved) {
                    return null;
                  }
                  rearm(row, why);
                  return new Folded(
                      row.id,
                      row.projectId,
                      row.repoId,
                      row.repoName,
                      row.backingBranch(),
                      row.mergedSha,
                      superseded,
                      now,
                      effectivePriorityOf(row.id).name());
                }
              }
            });
  }

  /**
   * Say why a PENDING request is still pending, <b>only when the sentence changed</b>. The sweep
   * re-evaluates every open request every 30 seconds and a request can wait for a whole pipeline,
   * so stamping {@code updatedAt} on every tick would keep re-sorting the board (which is ordered by
   * it) and write a row per request per tick for no news at all.
   */
  private static void waiting(ReleaseRequest row, String detail) {
    if (detail.equals(row.detail)) {
      return;
    }
    row.detail = detail;
    row.updatedAt = Instant.now();
  }

  /** The one place a request changes sha: gates invalidated, PENDING again, the window restarted. */
  private static void rearm(ReleaseRequest open, String why) {
    open.state = ReleaseRequest.State.PENDING;
    open.detail = "Re-armed onto " + shortSha(open.mergedSha) + " (" + why + ")";
    open.version = null;
    open.retryable = false;
    open.armedAt = Instant.now();
    open.updatedAt = open.armedAt;
  }

  /** Fire and forget, outside every transaction and never able to fail a fold. */
  private void announce(Folded folded) {
    if (!announcers.isResolvable()) {
      return;
    }
    try {
      announcers
          .get()
          .onReleaseRequestChanged(
              folded.projectId(),
              folded.repoId(),
              folded.repoName(),
              folded.releaseRequestId(),
              folded.backingBranch(),
              folded.mergedSha(),
              folded.changedAt(),
              folded.priority(),
              downstreamOf(folded));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not announce the change to release request %s", folded.releaseRequestId());
    }
  }

  /**
   * What is built on top of this repository, for the announcement to carry — asked here rather than
   * inside the fold's transaction, beside the run cancellation and for that call's reasons: it is an
   * HTTP round trip into another context, made after the merge has already landed.
   *
   * <p><b>Null is a first-class answer and is not the same as an empty list.</b> No implementation,
   * a port that answered empty ("could not ask") and a port that threw all produce null, which the
   * event turns into an absent key — exactly the shape every announcement made before this field had
   * — and the consumer reads as "unknown". An empty list means the question was asked and this
   * repository is a leaf, which is real information and travels as such.
   *
   * <p>Its own try/catch rather than the announcement's, so a port bug costs the enrichment and never
   * the event: the announcement is the thing that makes qits-ci build the fold at all, and it must go
   * out whatever the closure lookup did.
   */
  private List<String> downstreamOf(Folded folded) {
    if (!downstreamComponents.isResolvable()) {
      return null;
    }
    try {
      return downstreamComponents
          .get()
          .downstreamOf(folded.repoId(), folded.repoName())
          .orElse(null);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not cost the announcement.
      LOG.warnf(
          e,
          "Could not read what is downstream of %s for release request %s",
          folded.repoId(),
          folded.releaseRequestId());
      return null;
    }
  }

  /** Re-fold every open request of a repository, optionally skipping one. */
  private void remergeOpenOf(String repoId, String skipId, String why) {
    List<String> ids =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests.listOpenByRepo(repoId).stream()
                        .map(row -> row.id)
                        .filter(id -> !id.equals(skipId))
                        .toList());
    ids.forEach(id -> remerge(id, why));
  }

  // ---------------------------------------------------------------------------------------------
  // The gate and the execution
  // ---------------------------------------------------------------------------------------------

  /** Package-private so the suite can drive one evaluation without the worker or the sweep. */
  void evaluate(String id) {
    evaluate(id, null);
  }

  /**
   * Re-decide one request's gate.
   *
   * <p><b>The correlation key is the merged sha, and it is checked twice.</b> {@code verdictSha} is
   * the commit a verdict just landed for, or null when nothing in particular prompted this (the
   * sweep, a fold). A verdict naming anything other than the request's <b>current</b> {@code
   * mergedSha} settles nothing and returns here: it is an answer about a fold this request has moved
   * past, and the run that produced it was started for content nobody will accept any more. The
   * ledger rows for it are <b>kept</b> — they are the record of what was built and are read again if
   * that sha ever comes back — and this is also why a re-fold asks qits-ci to cancel the runs it
   * superseded: the gate is already safe without the cancellation, which is why the cancellation is
   * allowed to fail.
   *
   * <p>The verdicts read below are then read <em>at</em> {@code mergedSha} for the same reason, so a
   * request whose fold moved between the two reads is simply evaluated against the newer one.
   */
  void evaluate(String id, String verdictSha) {
    // Filled INSIDE the gate transaction and acted on outside it: a ticket write is another module's
    // database and belongs nowhere near the transaction that decides a gate. Null on every
    // evaluation that does not reject an unattended request, which is nearly all of them.
    AtomicReference<UnattendedGateTickets.Rejection> unattended = new AtomicReference<>();
    boolean ready =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
                  if (row == null || row.state != ReleaseRequest.State.PENDING) {
                    return false;
                  }
                  if (row.mergedSha == null) {
                    // Nothing has been folded yet: there is no content to have an opinion about.
                    return false;
                  }
                  if (verdictSha != null && !verdictSha.equals(row.mergedSha)) {
                    LOG.debugf(
                        "Release request %s ignores a verdict for %s; it is gating %s now",
                        id, shortSha(verdictSha), shortSha(row.mergedSha));
                    return false;
                  }
                  List<CommitBuildStatusDto> verdicts =
                      ledger.verdictsOf(row.repoId, row.mergedSha);
                  CommitBuildStatusDto redGating =
                      verdicts.stream()
                          .filter(v -> v.gating() && !"SUCCESS".equals(v.status()))
                          .findFirst()
                          .orElse(null);
                  if (redGating != null) {
                    row.state = ReleaseRequest.State.REJECTED;
                    row.detail =
                        "Gating run "
                            + redGating.runId()
                            + " finished "
                            + redGating.status()
                            + " for "
                            + row.mergedSha;
                    row.updatedAt = Instant.now();
                    if (isUnattended(row) && row.projectId != null) {
                      unattended.set(
                          new UnattendedGateTickets.Rejection(
                              row.id,
                              row.projectId,
                              row.repoId,
                              row.repoName,
                              sources.listByRequest(row.id).stream().map(s -> s.name).toList(),
                              row.mergedSha,
                              redGating.runId(),
                              redGating.status(),
                              row.detail,
                              row.requester,
                              row.gateTicketId));
                    }
                    return false;
                  }
                  boolean vouched =
                      verdicts.stream().anyMatch(v -> v.gating() && "SUCCESS".equals(v.status()));
                  if (!vouched) {
                    // THE GATE. Nothing has vouched for this fold, so it does not pass — not after
                    // a window, not because CI looks idle, not ever until a gating run says SUCCESS
                    // for this exact commit. The sweep asks again; a repository whose pipeline never
                    // materializes stays PENDING, which is the correct answer and not a stall.
                    waiting(row, "Waiting for a gating CI verdict for " + shortSha(row.mergedSha));
                    return false;
                  }
                  Integer active =
                      activeBuilds.isResolvable()
                          ? activeBuilds.get().activeFor(row.repoId, row.mergedSha).orElse(null)
                          : null;
                  if (active != null && active > 0) {
                    // Vouched, but qits-ci still has runs on this very fold: a second gating
                    // pipeline can still come back red. Only a POSITIVE count holds — "could not
                    // ask" (no probe, unreachable, unreadable) never overrides the vouch, or a
                    // platform with no probe configured could never release a green commit.
                    waiting(
                        row,
                        active + " CI run(s) are still in flight for " + shortSha(row.mergedSha));
                    return false;
                  }
                  // THE SECOND GATE, and it is deliberately the last thing asked. Everything above
                  // is the build gate; a red verdict has already rejected, so nobody is ever asked
                  // to sign off a fold CI has failed. Same transaction, same thread — this is two
                  // reads of tables this service owns, and approving is itself a re-evaluation
                  // trigger, so a door that records a decision calls evaluate() after its write and
                  // the request settles here on the next pass.
                  if (approvalPolicy.requiresApproval(row.repoId)) {
                    ReleaseRequestApproval decision =
                        approvals.latestFor(row.id, row.mergedSha).orElse(null);
                    if (decision == null) {
                      // No decision about THIS fold. A decision about a superseded one is the same
                      // answer: the re-arm carries the invalidation, so there is nothing to clear.
                      waiting(
                          row, "Waiting for a person to approve " + shortSha(row.mergedSha));
                      return false;
                    }
                    if (decision.decision == ReleaseRequestApproval.Decision.DECLINED) {
                      row.state = ReleaseRequest.State.REJECTED;
                      row.detail = declinedDetail(decision);
                      row.updatedAt = Instant.now();
                      // NO unattended-gate ticket, and that is the whole difference from a red
                      // build: a person just said no, so somebody is demonstrably watching. Filing
                      // them a ticket about their own decision is noise on the one repository where
                      // a human is already engaged.
                      return false;
                    }
                    // APPROVED: fall through, exactly as a green gating verdict does.
                  }
                  row.state = ReleaseRequest.State.READY;
                  row.detail = null;
                  row.updatedAt = Instant.now();
                  return true;
                });
    if (unattended.get() != null) {
      fileUnattendedGateTicket(unattended.get());
    }
    if (ready) {
      enqueueExecution(id);
    }
  }

  /**
   * Is this a request <b>nobody is waiting on</b>? The whole test is who asked: a person's forwarded
   * identity is a person who watches their request, and a machine principal is the tail of an
   * automated night with no reader at the end of it.
   *
   * <p>A request with no requester at all is <b>not</b> unattended. It is an unattributed call, made
   * by something this service could not name, and inventing an owner for it either way is a guess —
   * so it keeps the behaviour it always had.
   */
  private boolean isUnattended(ReleaseRequest row) {
    return row.requester != null && unattendedRequesters.contains(row.requester);
  }

  /**
   * Put the red gate in front of a person, and remember the ticket it went to.
   *
   * <p>Two things are load-bearing here and both are about not making it worse. The port is
   * <b>optional</b> — with no implementation the gate has still rejected and the API still says the
   * request is unattended, which is exactly what this platform did before — and the whole call is
   * wrapped, because a ticket store having a bad day must never turn into an exception on the bus
   * consumption or the sweep thread that decided the gate.
   *
   * <p>An empty answer leaves the stored link alone rather than clearing it: "could not file" and
   * "there is no ticket" are different facts, and clearing on the first would file a duplicate the
   * moment the store came back.
   */
  private void fileUnattendedGateTicket(UnattendedGateTickets.Rejection rejection) {
    if (!gateTickets.isResolvable()) {
      LOG.infof(
          "Release request %s was rejected with nobody watching it (%s); no ticket sink is"
              + " configured, so it is only on the request: %s",
          rejection.requestId(), rejection.requester(), rejection.detail());
      return;
    }
    String ticketId;
    try {
      ticketId = gateTickets.get().rejected(rejection).orElse(null);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not reach the gate.
      LOG.warnf(e, "Could not file the gate-failure ticket for release request %s",
          rejection.requestId());
      return;
    }
    if (ticketId == null || ticketId.equals(rejection.existingTicketId())) {
      return;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                requests
                    .findByIdOptional(rejection.requestId())
                    .ifPresent(row -> row.gateTicketId = ticketId));
    LOG.infof(
        "Release request %s was rejected with nobody watching it; filed ticket %s",
        rejection.requestId(), ticketId);
  }

  private void enqueueExecution(String id) {
    worker.submit(() -> execute(id));
  }

  /**
   * The release itself, on the worker. The row is re-read first, so of two enqueues the second finds
   * a settled request and does nothing; a failure is FAILED with the executor's own words, and
   * whether the sweep retries it is the executor's classification.
   *
   * <p>What is released is the <b>backing branch</b> at the <b>merged sha</b> — the fold, not any
   * one participant — and what the release <em>is</em> is a tag: the executor stamps a calver,
   * rewrites the manifests at the fold, commits them onto the backing branch, tags that commit and
   * deletes the branches the release consumed. {@code main} is finalized after the deployment, which
   * is why the bookkeeping below exists: the tag joins the repository's implicit source set until
   * something merges it, so that every other open request is a superset of what is already shipping.
   *
   * <p>The read below assembles the whole ask — the named sources and the repository's default
   * branch included, because the executor deletes the first and must never delete the second — in
   * <b>one</b> short transaction, and the release happens outside it.
   */
  private void execute(String id) {
    ReleaseExecutor.Release ask =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  ReleaseRequest row = requests.findByIdOptional(id).orElse(null);
                  if (row == null
                      || row.mergedSha == null
                      || (row.state != ReleaseRequest.State.READY
                          && row.state != ReleaseRequest.State.FAILED)) {
                    return null;
                  }
                  List<ReleaseRequestSource> named = sources.listByRequest(row.id);
                  return new ReleaseExecutor.Release(
                      row.id,
                      row.repoId,
                      row.projectId,
                      row.repoName,
                      row.backingBranch(),
                      row.mergedSha,
                      row.summary,
                      row.requester,
                      named.stream().map(source -> source.name).toList(),
                      mainOf(row.repoId),
                      wrapperCatalogOf(row.repoId, row.projectId),
                      // Read LIVE, here and not at the fold: a request can wait a whole pipeline on
                      // its gate, and an escalation made in that window has to reach the tag.
                      effectivePriorityOf(named).name());
                });
    if (ask == null) {
      return;
    }
    if (!executors.isResolvable()) {
      settle(
          id,
          ReleaseRequest.State.FAILED,
          "No release executor is configured; the request stays and the sweep will retry",
          null,
          true);
      return;
    }
    ReleaseExecutor.Outcome outcome;
    try {
      outcome = executors.get().release(ask);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not kill the worker.
      LOG.warnf(e, "Release executor threw for request %s", id);
      outcome = ReleaseExecutor.Outcome.refused("executor error: " + e.getMessage());
    }
    if (outcome.released()) {
      // The tag is tracked BEFORE the request is settled, so that a reader who sees RELEASED can
      // rely on the release being in the in-flight set. Only the siblings' re-fold is left after.
      recordReleasedTag(ask.repoId(), id, outcome.version(), outcome.releasedSha());
      settle(id, ReleaseRequest.State.RELEASED, null, outcome.version(), false);
      LOG.infof(
          "Release request %s released %s@%s as %s",
          id,
          ask.repoName() != null ? ask.repoName() : ask.repoId(),
          ask.backingBranch(),
          outcome.version());
      remergeOpenOf(ask.repoId(), id, "the sibling release " + outcome.version() + " is in flight");
      // THE PUBLISH PHASE'S FORK, on the release's own thread and the moment it lands: a repository
      // that declares no deployment has nothing to wait for and its tag goes to main now. One that
      // does deploy is left to DeploymentActive. Last, after the row exists and the request is
      // RELEASED, because the fork reads that row; and never able to fail a release that already
      // happened — a process that dies here leaves an ungated pending row, which is exactly what
      // ReleaseFinalization's catch-up sweep is for.
      finalization.onReleased(ask.repoId(), outcome.version());
      resolveWorkspacesOnReleasedBranches(ask, outcome);
      sayItHealed(ask, outcome.version());
    } else {
      settle(id, ReleaseRequest.State.FAILED, outcome.detail(), null, outcome.retryable());
      LOG.warnf(
          "Release request %s was not released (%s): %s",
          id, outcome.retryable() ? "will retry" : "final until re-armed", outcome.detail());
    }
  }

  /**
   * The branches this release just deleted, told to whoever may have a workspace standing on one.
   *
   * <p><b>Exactly the set the executor deleted</b>, read from the same two fields it reads: the
   * request's named sources <em>minus</em> the repository's default branch, which nothing deletes
   * and no release consumes. The backing branch is deliberately not among them — it is this flow's
   * own scratch ref, created by the fold, and no workspace was ever made on one.
   *
   * <p>Last, after the row is RELEASED and after {@link ReleaseFinalization#onReleased}, and wrapped
   * so that <b>nothing here can reach the release path</b>. The port promises not to throw; the belt
   * is the same one round {@link ReleaseExecutor} above and states the same thing — a throw is a
   * port bug, and a workspace that was not reaped must never turn a release that happened into a
   * failure or settle it a second time. See {@link ReleasedBranchWorkspaces} for the absence this
   * call exists to cover.
   */
  private void resolveWorkspacesOnReleasedBranches(
      ReleaseExecutor.Release ask, ReleaseExecutor.Outcome outcome) {
    if (!releasedBranchWorkspaces.isResolvable()) {
      return;
    }
    try {
      ReleasedBranchWorkspaces workspaces = releasedBranchWorkspaces.get();
      Set<String> released = new LinkedHashSet<>(ask.namedSources());
      released.remove(ask.defaultBranch());
      for (String branch : released) {
        workspaces.branchReleased(
            ask.repoId(), branch, outcome.version(), outcome.releasedSha());
      }
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not touch a settled release.
      LOG.warnf(
          e, "Could not resolve the workspaces of the branches released by request %s",
          ask.requestId());
    }
  }

  /**
   * The request that a ticket was filed about has released after all — said on that ticket's thread,
   * and nothing more.
   *
   * <p><b>The ticket is deliberately left OPEN.</b> A green build says this fold passes now; it does
   * not say that whatever a person added to the thread in the meantime is handled, and a machine
   * that files a report is a much cheaper thing to be wrong about than a machine that closes one.
   * Somebody reading "this released as 2026.910.104616" and resolving it costs one press; a ticket
   * auto-resolved over a discussion nobody finished costs the discussion.
   *
   * <p>Last on the release path, after the row is RELEASED, and wrapped like every other call out
   * here: a release that has already happened must never be failed by a ticket store.
   */
  private void sayItHealed(ReleaseExecutor.Release ask, String version) {
    if (!gateTickets.isResolvable()) {
      return;
    }
    String ticketId =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    requests
                        .findByIdOptional(ask.requestId())
                        .map(row -> row.gateTicketId)
                        .orElse(null));
    if (ticketId == null) {
      return;
    }
    try {
      gateTickets.get().released(ticketId, ask.requestId(), ask.repoName(), version);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not touch a settled release.
      LOG.warnf(
          e, "Could not close the loop on ticket %s for released request %s",
          ticketId, ask.requestId());
    }
  }

  /**
   * A release landed: the tag it produced JOINS the repository's implicit source set until the
   * post-deployment merge puts it on {@code main}, and every <b>other</b> open request of that
   * repository re-folds so that it is a superset of the release now in flight.
   *
   * <p>Populated going forward only. A platform that released before this table existed has tags
   * with no row, which is the honest answer: nothing here can know whether they reached main.
   */
  private void recordReleasedTag(
      String repoId, String requestId, String version, String releasedSha) {
    if (version == null || version.isBlank()) {
      return;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (pendingTags.find(repoId, version).isPresent()) {
                return;
              }
              ReleasedTagPendingMerge row = new ReleasedTagPendingMerge();
              row.id = UUID.randomUUID().toString();
              row.repoId = repoId;
              row.tagName = version;
              row.releasedSha = releasedSha;
              row.releaseRequestId = requestId;
              row.releasedAt = Instant.now();
              pendingTags.persist(row);
            });
  }

  private void settle(
      String id, ReleaseRequest.State state, String detail, String version, boolean retryable) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                requests
                    .findByIdOptional(id)
                    .ifPresent(
                        row -> {
                          row.state = state;
                          row.detail = detail;
                          row.retryable = retryable;
                          if (version != null) {
                            row.version = version;
                          }
                          row.updatedAt = Instant.now();
                        }));
  }

  /** A sentence on an open request that changes nothing else — the unreachable-git-host arm. */
  private void note(String id, String detail) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                requests
                    .findByIdOptional(id)
                    .filter(row -> ReleaseRequestRepository.OPEN.contains(row.state))
                    .ifPresent(
                        row -> {
                          row.detail = detail;
                          row.updatedAt = Instant.now();
                        }));
  }

  // ---------------------------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------------------------

  /** The word "all", spelled once — every other value is a state name or a mistake. */
  private static final String ALL_STATES = "all";

  /**
   * How many releases the default reading carries behind the open ones. Ten, because the tail is
   * context and not history: it says what has just landed on the page whose question is what has
   * not, and a number that grew with the repository would turn a worklist back into a log.
   */
  static final int RECENT_RELEASED = 10;

  /**
   * What a {@code state} query selects: a set of states, and whether the recently released tail
   * rides along behind them.
   *
   * <p>The tail is a second query rather than a state in the set, and it has to be: "the last ten
   * RELEASED" is a page, not a predicate, and folding it into the {@code state in (…)} read would
   * answer every release the repository has ever made.
   */
  private record Selection(List<ReleaseRequest.State> states, boolean recentReleased) {}

  /**
   * The states a {@code state} query means, and what else rides with them. <b>Absent or blank is the
   * open set plus the last {@value #RECENT_RELEASED} releases</b> — the question both lists exist to
   * answer is "what is happening here", and a page that dropped a release the moment it landed made
   * the most interesting event in the flow the one thing it never showed. {@code all} is every state
   * (spelled as the full set rather than as "no filter", so one query shape serves both); a state
   * name, in any case, is itself, and narrows to exactly it.
   *
   * <p><b>WITHDRAWN left the default reading when the tail arrived</b>, and that is the intended
   * trade: it was never in the open set, so it only ever appeared on the repository list because
   * that list had no filter at all. It is one {@code state=WITHDRAWN} or one {@code state=all} away.
   */
  private static Selection statesFor(String state) {
    if (state == null || state.isBlank()) {
      return new Selection(ReleaseRequestRepository.OPEN, true);
    }
    String wanted = state.trim();
    if (ALL_STATES.equalsIgnoreCase(wanted)) {
      return new Selection(List.of(ReleaseRequest.State.values()), false);
    }
    for (ReleaseRequest.State candidate : ReleaseRequest.State.values()) {
      if (candidate.name().equalsIgnoreCase(wanted)) {
        return new Selection(List.of(candidate), false);
      }
    }
    throw new BadRequestException(
        "Unknown release-request state: "
            + state
            + ". Name one of "
            + Arrays.stream(ReleaseRequest.State.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "))
            + ", or '"
            + ALL_STATES
            + "' for every state; leaving it off answers the open ones plus the last "
            + RECENT_RELEASED
            + " released.");
  }

  /**
   * Names a list of rows without a query per row: one read of every named source in the page, one
   * read of each distinct repository's pending tags, and one read of the released tags the page's
   * own requests produced — which is where {@code releasedSha} and {@code mergedToMainAt} both come
   * from.
   *
   * <p>That last read is kept <b>unfiltered</b>: a row whose {@code mergedAt} is still null is the
   * release in flight, and it carries the sha the tag points at just as an already-merged one does.
   * Filtering it out is what used to make the answer say "there is no such release" for exactly the
   * releases somebody is watching.
   *
   * <p><b>The approval gate is answered in the same batched shape and for the same reason.</b> It is
   * derived per read rather than stored, so it is two more questions per row and would be two more
   * queries per row if it were asked naively — on the project-wide worklist, which is the busiest
   * read this class has. So the policy is asked once per <b>distinct repository</b> (the page's rows
   * are usually a handful of repositories, and the answer cannot differ within one) and the
   * decisions come back in one query, keyed on each request's own current fold — see {@link
   * ReleaseRequestApprovalRepository#currentForEach}.
   */
  private List<ReleaseRequestDto> decorate(
      List<ReleaseRequest> rows, Map<String, String> currentNames) {
    List<String> ids = rows.stream().map(row -> row.id).toList();
    Map<String, List<ReleaseRequestSource>> named =
        sources.listByRequests(ids).stream()
            .collect(Collectors.groupingBy(source -> source.requestId));
    Map<String, List<ReleasedTagPendingMerge>> implicit =
        rows.stream()
            .map(row -> row.repoId)
            .distinct()
            .collect(Collectors.toMap(repoId -> repoId, this::implicitFor));
    Map<String, ReleasedTagPendingMerge> released =
        pendingTags.listByRequests(ids).stream()
            .collect(Collectors.toMap(tag -> tag.releaseRequestId, tag -> tag, (a, b) -> a));
    Map<String, Boolean> approvalRequired =
        rows.stream()
            .map(row -> row.repoId)
            .distinct()
            .collect(
                Collectors.toMap(repoId -> repoId, repoId -> approvalPolicy.requiresApproval(repoId)));
    Map<String, ReleaseRequestApproval> decisions =
        approvals.currentForEach(
            rows.stream()
                .filter(row -> row.mergedSha != null)
                .collect(
                    Collectors.toMap(row -> row.id, row -> row.mergedSha, (a, b) -> a)));
    return rows.stream()
        .map(
            row ->
                dto(
                    row,
                    currentNames.getOrDefault(row.repoId, row.repoName),
                    named.getOrDefault(row.id, List.of()),
                    implicit.getOrDefault(row.repoId, List.of()),
                    released.get(row.id),
                    ApprovalView.of(
                        approvalRequired.getOrDefault(row.repoId, false), decisions.get(row.id))))
        .toList();
  }

  /**
   * The approval gate as a <b>read</b> answers it: whether a person had to be asked, what the newest
   * decision at this request's current fold was, and who made it. Derived on every read out of the
   * policy and the approval table, never out of the request row, because there is nothing about it
   * on the request row — see the approval gate in this class's javadoc.
   *
   * <p>It is a record rather than five parameters threaded through {@link #dto} because both callers
   * compute the same five things and only the number of queries differs, and because the invariant
   * worth holding in one place is that {@code state} and the three decision fields can never
   * disagree: they are made from the one decision or from its absence, together.
   */
  private record ApprovalView(
      boolean required,
      ReleaseRequest.ApprovalState state,
      String actor,
      Instant decidedAt,
      String note) {

    /**
     * @param decision the newest decision at the request's <b>current</b> {@code mergedSha}, or null
     *     where there is none — a fold nobody has judged, and one whose decisions were all made
     *     against a sha the request has moved past, which are one answer on purpose.
     */
    static ApprovalView of(boolean required, ReleaseRequestApproval decision) {
      if (!required) {
        // Not asked, which is deliberately not the same word as approved. The decision fields stay
        // null even where a row exists: a policy that stopped requiring approval must not leave the
        // answer looking like somebody is still gating on it.
        return new ApprovalView(false, ReleaseRequest.ApprovalState.NOT_REQUIRED, null, null, null);
      }
      if (decision == null) {
        return new ApprovalView(true, ReleaseRequest.ApprovalState.WAITING, null, null, null);
      }
      ReleaseRequest.ApprovalState state =
          decision.decision == ReleaseRequestApproval.Decision.DECLINED
              ? ReleaseRequest.ApprovalState.DECLINED
              : ReleaseRequest.ApprovalState.APPROVED;
      return new ApprovalView(true, state, decision.actor, decision.decidedAt, decision.note);
    }
  }

  /**
   * The same derivation for a <b>single</b> request, where a batched read would be one query with
   * one element in it. Called inside the caller's already-open transaction, like {@link
   * #effectivePriorityOf(String)} beside it.
   */
  private ApprovalView approvalOf(ReleaseRequest row) {
    boolean required = approvalPolicy.requiresApproval(row.repoId);
    return ApprovalView.of(
        required,
        required && row.mergedSha != null
            ? approvals.latestFor(row.id, row.mergedSha).orElse(null)
            : null);
  }

  /**
   * What a DECLINED request says, and it names the person: a machine gate's sentence is about a run,
   * and this one is about somebody's judgement, so the actor is the first thing in it. The note is
   * appended only where there is one — a decline with nothing said reads as the bare fact rather
   * than as a sentence with an empty tail.
   */
  private static String declinedDetail(ReleaseRequestApproval decision) {
    String note = decision.note == null ? null : decision.note.trim();
    return "Declined by "
        + decision.actor
        + (note == null || note.isEmpty() ? "" : ": " + note);
  }

  private List<ReleasedTagPendingMerge> implicitFor(String repoId) {
    return pendingTags.listPending(repoId);
  }

  /**
   * @param released this request's own released tag, or null where it produced none — an unreleased
   *     request, and a release made before {@code released_tag_pending_merge} existed. Both of its
   *     fields on the answer are therefore null together with it.
   * @param approval the approval gate as of this read, already derived — passed in rather than
   *     computed here so that the list path can answer a whole page in a fixed number of queries.
   */
  private ReleaseRequestDto dto(
      ReleaseRequest row,
      String repoName,
      List<ReleaseRequestSource> named,
      List<ReleasedTagPendingMerge> implicit,
      ReleasedTagPendingMerge released,
      ApprovalView approval) {
    List<ReleaseRequestSourceDto> all = new ArrayList<>();
    for (ReleaseRequestSource source : named) {
      all.add(
          new ReleaseRequestSourceDto(
              source.kind.name(),
              source.name,
              "refs/heads/" + source.name,
              false,
              source.priority == null ? null : source.priority.name()));
    }
    for (ReleasedTagPendingMerge tag : implicit) {
      all.add(
          new ReleaseRequestSourceDto(
              ReleaseRequestSource.Kind.RELEASED_TAG.name(),
              tag.tagName,
              "refs/tags/" + tag.tagName,
              true,
              // An implicit source has no row and therefore no priority of its own: its urgency
              // was the release it came from, and it counts towards no max.
              null));
    }
    return new ReleaseRequestDto(
        row.id,
        row.repoId,
        repoName,
        row.backingBranch(),
        List.copyOf(all),
        effectivePriorityOf(named).name(),
        row.mergedSha,
        row.state.name(),
        row.summary,
        row.requester,
        isUnattended(row),
        row.gateTicketId,
        row.detail,
        approval.required(),
        approval.state().name(),
        approval.actor(),
        approval.decidedAt(),
        approval.note(),
        conflictOf(row),
        row.version,
        released == null ? null : released.releasedSha,
        released == null ? null : released.mergedAt,
        row.retryable,
        row.createdAt,
        row.updatedAt);
  }

  // ---------------------------------------------------------------------------------------------
  // Small things
  // ---------------------------------------------------------------------------------------------

  /**
   * The refs the git host is handed, in the order they become parents: the named branches as they
   * were added ({@code main} first), then the released tags still in flight, oldest first. Two
   * sources naming one commit are one head at the far side, so a duplicate costs nothing.
   */
  private static List<String> refsOf(
      List<ReleaseRequestSource> named, List<ReleasedTagPendingMerge> implicit) {
    Set<String> refs = new LinkedHashSet<>();
    named.forEach(source -> refs.add("refs/heads/" + source.name));
    implicit.forEach(tag -> refs.add("refs/tags/" + tag.tagName));
    return List.copyOf(refs);
  }

  private void addSourceRow(
      String requestId, String branch, String actor, ReleasePriority priority) {
    ReleaseRequestSource source = new ReleaseRequestSource();
    source.id = UUID.randomUUID().toString();
    source.requestId = requestId;
    source.kind = ReleaseRequestSource.Kind.BRANCH;
    source.name = branch;
    source.addedAt = Instant.now();
    source.addedBy = actor;
    source.priority = priority;
    sources.persist(source);
  }

  /**
   * The request's effective priority: the max over its <b>named</b> sources, {@code MEDIUM} where
   * it has none. Read inside whatever transaction is already open — it is one query on the same
   * datasource — and never stored, so it cannot disagree with the rows it is a max of.
   */
  private ReleasePriority effectivePriorityOf(String requestId) {
    return effectivePriorityOf(sources.listByRequest(requestId));
  }

  /** The same max over rows already in hand — the read paths, which fetch them anyway. */
  private static ReleasePriority effectivePriorityOf(List<ReleaseRequestSource> named) {
    return ReleasePriority.max(named.stream().map(source -> source.priority).toList());
  }

  /**
   * What a caller said about priority, or null where they said nothing. Null is not {@code MEDIUM}
   * here on purpose: the converge and idempotent arms have to tell "did not say" from "said
   * MEDIUM", because only the second may move a stored value.
   */
  private static ReleasePriority statedPriority(String priority) {
    if (priority == null || priority.isBlank()) {
      return null;
    }
    return requirePriority(priority);
  }

  /** A priority the caller must have named — the update arm, where absent is nothing to do. */
  private static ReleasePriority requirePriority(String priority) {
    return ReleasePriority.of(priority == null ? null : priority.trim())
        .orElseThrow(
            () ->
                new BadRequestException(
                    "Unknown release priority: "
                        + priority
                        + ". Name one of "
                        + Arrays.stream(ReleasePriority.values())
                            .map(Enum::name)
                            .collect(Collectors.joining(", "))
                        + "."));
  }

  private static ReleasePriority orDefault(ReleasePriority stated) {
    return stated == null ? ReleasePriority.DEFAULT : stated;
  }

  private ReleaseRequest requireOpenForChange(String id) {
    ReleaseRequest row =
        requests
            .findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Release request not found: " + id));
    if (row.state == ReleaseRequest.State.RELEASED
        || row.state == ReleaseRequest.State.WITHDRAWN) {
      throw new DomainException(409, "Release request " + id + " is already " + row.state);
    }
    return row;
  }

  private static String requireBranch(String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("A release request names a branch");
    }
    return branch.trim();
  }

  /** The repository's default branch, for the "nothing but main is left" reading. */
  private String mainOf(String repoId) {
    return repositories
        .findByIdOptional(repoId)
        .map(repository -> repository.mainBranch)
        .filter(branch -> branch != null && !branch.isBlank())
        .orElse(DEFAULT_MAIN);
  }

  /**
   * The project's other repositories by registered name, and only for a WRAPPER release — the
   * catalog the executor banks the wrapper's gitlink pins from. Empty for every ordinary
   * repository, which is what turns the banking arm off without a flag: an estate of nothing is
   * nothing to pin.
   *
   * <p>The wrapper itself is excluded — a superproject does not pin itself — and a repository the
   * name table has no row for is simply absent, because a gitlink needs the name {@code
   * .gitmodules} declares and an unnamed row cannot be matched to one.
   */
  private Map<String, ReleaseExecutor.Submodule> wrapperCatalogOf(String repoId, String projectId) {
    boolean wrapper =
        repositories
            .findByIdOptional(repoId)
            .map(repository -> repository.archetype == RepositoryArchetype.PROJECT)
            .orElse(false);
    if (!wrapper || projectId == null) {
      return Map.of();
    }
    Map<String, ReleaseExecutor.Submodule> catalog = new LinkedHashMap<>();
    names
        .namesByRepository(projectId)
        .forEach(
            (siblingId, name) -> {
              if (siblingId.equals(repoId)) {
                return;
              }
              catalog.put(name, new ReleaseExecutor.Submodule(siblingId, mainOf(siblingId)));
            });
    return Map.copyOf(catalog);
  }

  private String conflictJson(String target, List<BackingBranchMerger.Conflict> conflicts) {
    try {
      return json.writeValueAsString(
          new MergeConflictDto(
              target,
              conflicts.stream()
                  .map(
                      c ->
                          new MergeConflictDto.ConflictedPath(
                              c.path(), c.head(), c.headSha(), c.reason()))
                  .toList()));
    } catch (Exception e) {
      // A conflict that cannot be written down is still a conflict: the state stands and the
      // detail sentence carries the paths.
      LOG.warnf("Could not record the conflict detail: %s", e.toString());
      return null;
    }
  }

  private MergeConflictDto conflictOf(ReleaseRequest row) {
    if (row.conflictDetail == null || row.conflictDetail.isBlank()) {
      return null;
    }
    try {
      return json.readValue(row.conflictDetail, MergeConflictDto.class);
    } catch (Exception e) {
      LOG.warnf("Release request %s has an unreadable conflict detail: %s", row.id, e.toString());
      return null;
    }
  }

  private static String shortSha(String sha) {
    if (sha == null) {
      return "(nothing)";
    }
    return sha.length() <= 10 ? sha : sha.substring(0, 10);
  }
}
