package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkBranches;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.WrapperBranchEndings;
import eu.wohlben.qits.projects.entity.BranchDelivery.Step;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.BranchDeliveryRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import io.quarkus.arc.DefaultBean;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>The platform sets a ticket DONE, and this is where</b> (qits-150). A ticket is DONE only once
 * the wrapper release request carrying its branch is FINALIZED — or once its wrapper branch is found
 * to carry nothing — and no agent or person moves it there: both doors refuse a ticket's DONE for
 * every role ({@code WorkEntityDoors.move}, {@code EntityTransitionService.refuseTicketDone}).
 *
 * <h2>Four ways in, one move</h2>
 *
 * <ul>
 *   <li><b>The ending</b> — {@link #ended}, the {@link WrapperBranchEndings} port: the ledger row of
 *       a branch of the project's wrapper repository went LIVE (its request FINALIZED), or WITHDRAWN
 *       with the request's fold adding nothing to main. Keyed on the ledger row, so a branch
 *       handed on or superseded into a successor closes its ticket when that successor finalizes.
 *   <li><b>The arrival at VERIFIED</b> — {@link #verified}, from {@code PhaseAdvance}: the ending
 *       may have come first (the wrapper went live while the ticket was still being verified, or a
 *       person walked it by hand and nothing was asked for), so the ledger row is read then; and the
 *       move into VERIFIED that finds nothing at all left to release closes the ticket at once.
 *   <li><b>The reconcile</b> — {@link #reconcile}, on {@code
 *       qits.projects.wrapper-done.reconcile-every}: every VERIFIED ticket of every project with a
 *       wrapper is read against its wrapper branch's ledger row, the floor under an ending that
 *       could not be told or could not move the ticket. It also decides the two endings the ledger
 *       cannot state alone ({@link #reconciled}): a wrapper branch deleted with its tip (or, the
 *       tip unknown, its last fold) already in main, and a ticket with no row and no branch at all
 *       past a grace. Its first pass after a deploy is the backfill of the tickets whose requests
 *       ended before qits-150.
 *   <li><b>The deletion</b> — {@code ReleaseRequests.onBranchDeleted}, through {@link #ended}: a
 *       wrapper request withdrawn because its branch was deleted, whose deleted tip — or, with the
 *       tip unknown, last fold — the git host says main contains ({@code Lost.NOTHING}), is
 *       {@link Ending#DELETED_MERGED} at once (qits-1869).
 * </ul>
 *
 * <p>Each closes only a <b>TICKET at VERIFIED</b>: the move is {@link
 * WorkEntityService#transitionFrom} VERIFIED → DONE, which does nothing for a row anywhere else, so
 * the three may race and repeat freely. A ticket not yet VERIFIED is left where it is — its arrival
 * at VERIFIED reads the row again. An epic keeps its manual DONE and is never touched here.
 *
 * <p><b>The wrapper branch is exactly {@code ticket/<qualified id>}</b> ({@link WorkBranches#of}), in
 * the ticket's own project: an agent's submodule branch {@code ticket/<id>-<choice>} lives in a
 * member repository and is not the wrapper's, and a ledger row of another project naming the same
 * string closes nothing. The step's project is checked against the ticket's.
 *
 * <p><b>Who moved it</b> is {@link #MOVER}, a machine, and the thread is told which request — by its
 * qualified id — ended the ticket's delivery. A failure to comment leaves the move standing.
 *
 * <p>{@code @DefaultBean} like every adapter here, so a test may watch what is told.
 */
@ApplicationScoped
@DefaultBean
public class TicketWrapperDone implements WrapperBranchEndings {

  private static final Logger LOG = Logger.getLogger(TicketWrapperDone.class);

  /** Who closes the ticket: this service, never a person or an agent. */
  public static final String MOVER = "qits-projects";

  @Inject WorkEntityService entities;

  @Inject WorkEntityRepository rows;

  @Inject EntityCommentService comments;

  @Inject EntityIdResolver resolver;

  @Inject ProjectService projects;

  @Inject BranchDeliveryRepository ledger;

  @Inject ReleaseRequestRepository requests;

  @Inject ProjectChangePublisher publisher;

  /**
   * Asked whether a wrapper branch is gone and whether main contains its deleted tip (qits-1869)
   * or last fold (qits-150).
   */
  @Inject Instance<ReleaseGitHost> gitHosts;

  /**
   * How long a VERIFIED ticket with no ledger row waits before the reconcile closes it for having
   * no wrapper branch — at least one reconcile interval, so PhaseAdvance's wrapper ask at the
   * arrival has written its row first.
   */
  @ConfigProperty(name = "qits.projects.wrapper-done.no-branch-grace", defaultValue = "PT15M")
  Duration noBranchGrace;

  // ---------------------------------------------------------------------------------------------
  // The ending
  // ---------------------------------------------------------------------------------------------

  @Override
  @ActivateRequestContext
  public void ended(Ended ended) {
    if (ended == null || ended.branch() == null || !ended.branch().startsWith(PREFIX)) {
      return;
    }
    try {
      QuarkusTransaction.suspendingExisting().run(() -> close(ended));
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not close the ticket of wrapper branch %s (%s %s); the reconcile will try again",
          ended.branch(),
          ended.qualifiedId(),
          ended.ending());
    }
  }

  private static final String PREFIX = "ticket/";

  private void close(Ended ended) {
    String qualifiedId = ended.branch().substring(PREFIX.length());
    Optional<CommitSubjectEntities.NamedEntity> named =
        CommitSubjectEntities.parse(qualifiedId)
            .flatMap(resolver::lookup)
            .filter(found -> found.archetype() == Archetype.TICKET)
            .filter(found -> found.qualifiedId().equals(qualifiedId))
            .filter(found -> ended.projectId() == null || ended.projectId().equals(found.projectId()));
    if (named.isEmpty()) {
      LOG.debugf("Wrapper branch %s names no ticket of its project; nothing to close", ended.branch());
      return;
    }
    done(named.get().id(), named.get().projectId(), sentence(ended));
  }

  // ---------------------------------------------------------------------------------------------
  // The arrival at VERIFIED
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code ticket} just moved into VERIFIED. With {@code nothingToRelease} — the move found no
   * request to ask for and none refused, so no wrapper release will ever end for it — it is DONE at
   * once. Otherwise its wrapper branch's ledger row is read, and a delivery that already ended
   * (LIVE, or withdrawn empty) closes it now. Anything else waits for the ending. Never throws.
   */
  @ActivateRequestContext
  public void verified(WorkEntity ticket, boolean nothingToRelease) {
    if (ticket == null || ticket.archetype != Archetype.TICKET) {
      return;
    }
    try {
      QuarkusTransaction.suspendingExisting()
          .run(
              () -> {
                if (nothingToRelease) {
                  done(
                      ticket.id,
                      ticket.projectId,
                      "Nothing was left to release for this ticket, so no wrapper release request"
                          + " will end for it: the platform moves it to **DONE**.");
                  return;
                }
                settled(ticket).ifPresent(ended -> done(ticket.id, ticket.projectId, sentence(ended)));
              });
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "Could not read whether ticket %s's wrapper delivery has ended; the reconcile will", ticket.id);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The reconcile
  // ---------------------------------------------------------------------------------------------

  /**
   * The floor: every VERIFIED ticket of every project with a wrapper, read against its wrapper
   * branch's ledger row, and closed where that delivery has ended. Idempotent — the move is only
   * from VERIFIED — and bounded by the number of VERIFIED tickets, which is small by construction.
   */
  @Scheduled(
      every = "{qits.projects.wrapper-done.reconcile-every}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  @ActivateRequestContext
  public void reconcile() {
    List<Project> all;
    try {
      all = QuarkusTransaction.requiringNew().call(projects::list);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not list the projects to reconcile VERIFIED tickets against");
      return;
    }
    for (Project project : all) {
      try {
        List<WorkEntity> verified =
            QuarkusTransaction.requiringNew()
                .call(
                    () ->
                        rows.listByProjectArchetypeAndStatus(
                            project.id, Archetype.TICKET, EntityStatus.VERIFIED.name()));
        for (WorkEntity ticket : verified) {
          reconciled(ticket).ifPresent(ended -> done(ticket.id, ticket.projectId, sentence(ended)));
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not reconcile the VERIFIED tickets of project %s", project.id);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The pieces
  // ---------------------------------------------------------------------------------------------

  /**
   * What the ledger says of {@code ticket}'s wrapper branch, read in one transaction of the projects
   * database: the branch, the wrapper, the row (or none) and the request the row names (or none).
   */
  private record Facts(
      String projectId,
      Repository wrapper,
      String branch,
      BranchDeliveryRepository.Position held,
      ReleaseRequest request) {

    String qualifiedId() {
      return request == null ? (held == null ? null : held.requestId()) : request.qualifiedId;
    }

    Ended ended(String version, Ending ending) {
      return ended(version, ending, null);
    }

    Ended ended(String version, Ending ending, String deletedSha) {
      return new Ended(
          projectId,
          wrapper.id,
          branch,
          held == null ? null : held.requestId(),
          qualifiedId(),
          version,
          ending,
          deletedSha);
    }
  }

  private Optional<Facts> factsOf(WorkEntity ticket) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Project project = projects.get(ticket.projectId);
              Optional<Repository> wrapper = projects.findWrapper(project.id);
              if (wrapper.isEmpty() || ticket.number < 1 || project.slug == null) {
                return Optional.<Facts>empty();
              }
              String branch =
                  WorkBranches.of(ticket, QualifiedEntityIds.render(project.slug, ticket.number));
              BranchDeliveryRepository.Position held =
                  ledger.position(project.id, wrapper.get().id, branch).orElse(null);
              ReleaseRequest request =
                  held == null || held.requestId() == null
                      ? null
                      : requests.findByIdOptional(held.requestId()).orElse(null);
              return Optional.of(new Facts(project.id, wrapper.get(), branch, held, request));
            });
  }

  /**
   * How {@code ticket}'s wrapper branch's delivery ended, read off its ledger row in the project's
   * wrapper repository — LIVE, or withdrawn empty — or empty where it has not ended, or there is no
   * wrapper, no qualified id or no row. What the arrival at VERIFIED asks.
   */
  Optional<Ended> settled(WorkEntity ticket) {
    return factsOf(ticket).flatMap(TicketWrapperDone::endedOnTheLedger);
  }

  /** The two endings the ledger states by itself: LIVE, and the empty wrapper's withdrawal. */
  private static Optional<Ended> endedOnTheLedger(Facts facts) {
    BranchDeliveryRepository.Position held = facts.held();
    if (held == null || held.requestId() == null) {
      return Optional.empty();
    }
    ReleaseRequest request = facts.request();
    if (Step.LIVE.name().equals(held.step())) {
      return Optional.of(facts.ended(held.version(), Ending.FINALIZED));
    }
    if (Step.WITHDRAWN.name().equals(held.step())
        && request != null
        && request.state == ReleaseRequest.State.WITHDRAWN
        && ReleaseRequests.EMPTY_WRAPPER_WITHDRAWN.equals(request.detail)) {
      return Optional.of(facts.ended(null, Ending.EMPTY));
    }
    return Optional.empty();
  }

  /**
   * <b>What the reconcile asks</b> (qits-150 follow-up): {@link #settled}'s two endings, then the
   * two the ledger cannot state alone, decided from the git host's facts:
   *
   * <ul>
   *   <li><b>{@link Ending#DELETED_MERGED}</b> — the row has ENDED (it is WITHDRAWN, or it names a
   *       request that is WITHDRAWN or OBSOLETE, so the branch never reached a successor: one that
   *       had would name the successor), the wrapper branch is gone from the git host, and the
   *       wrapper's default branch contains the row's {@code deleted_sha} — the tip the deletion
   *       recorded (qits-1869), the branch's own work — or, only where no tip was recorded, the
   *       row's {@code fold_sha} — the last fold that carried the branch, the very fold {@code
   *       ReleaseRequests.onBranchDeleted} asks about — is contained in the wrapper's default
   *       branch ({@link #lastFoldOf}: the request's {@code merged_sha} stands in only for a row
   *       that still names it, never for a WITHDRAWN one, whose request may have folded on
   *       without the branch). A row with neither, a containment the host says NO
   *       to and one it could not answer all close nothing; a known tip's NO is never retried
   *       against the fold, which merges every source of the request and may be a commit no ref
   *       keeps any more (the gap qits-1869 closes).
   *   <li><b>{@link Ending#NO_BRANCH}</b> — no row at all, the wrapper branch is not on the git
   *       host, and the ticket has been VERIFIED longer than {@link #noBranchGrace}, read off its
   *       {@code updatedAt} (which a move into VERIFIED stamps, and any later write only moves
   *       further away — so the grace can only be longer, never shorter). The grace keeps the
   *       reconcile from racing the wrapper ask {@code PhaseAdvance} makes at the arrival.
   * </ul>
   *
   * A row still in flight — PUSHED, or naming an open request — closes nothing, gone branch or not.
   * The git host is asked outside any transaction, and "could not ask" is never "gone".
   */
  Optional<Ended> reconciled(WorkEntity ticket) {
    Optional<Facts> read = factsOf(ticket);
    if (read.isEmpty()) {
      return Optional.empty();
    }
    Facts facts = read.get();
    Optional<Ended> onTheLedger = endedOnTheLedger(facts);
    if (onTheLedger.isPresent() || !gitHosts.isResolvable()) {
      return onTheLedger;
    }
    BranchDeliveryRepository.Position held = facts.held();
    if (held == null) {
      Instant since = ticket.updatedAt;
      if (since == null || since.isAfter(Instant.now().minus(noBranchGrace))) {
        return Optional.empty();
      }
      return branchGone(facts) ? Optional.of(facts.ended(null, Ending.NO_BRANCH)) : Optional.empty();
    }
    if (!rowEnded(facts)) {
      return Optional.empty();
    }
    // qits-1869: the tip the branch was deleted at is the proof when it is known, and then the
    // only one — a NO or an unanswered ask about it is never retried against the fold.
    String tip = held.deletedSha() == null || held.deletedSha().isBlank() ? null : held.deletedSha();
    String asked = tip != null ? tip : lastFoldOf(facts);
    if (asked == null || !branchGone(facts)) {
      return Optional.empty();
    }
    String main =
        facts.wrapper().mainBranch == null || facts.wrapper().mainBranch.isBlank()
            ? "main"
            : facts.wrapper().mainBranch;
    ReleaseGitHost.Answer<Boolean> contained;
    try {
      contained = gitHosts.get().contains(facts.wrapper().id, asked, "refs/heads/" + main);
    } catch (RuntimeException e) {
      contained = ReleaseGitHost.Answer.failedRetryable("git host error: " + e);
    }
    if (!contained.ok() || !Boolean.TRUE.equals(contained.value())) {
      LOG.debugf(
          "The deleted wrapper branch %s: whether %s contains its %s %s is %s; it closes nothing",
          facts.branch(),
          main,
          tip != null ? "deleted tip" : "fold",
          asked,
          contained.ok() ? contained.value() : contained.detail());
      return Optional.empty();
    }
    return Optional.of(facts.ended(null, Ending.DELETED_MERGED, tip));
  }

  /**
   * The last fold that carried the branch: the row's {@code fold_sha} where the step wrote one (a
   * WITHDRAWN step always records the fold it left). A row still naming its request at a step that
   * recorded none — a JOINED written before the first fold — rode that request to its end, so the
   * request's last fold carried it. A WITHDRAWN row without a fold has none: the request may have
   * folded on without the branch.
   */
  private static String lastFoldOf(Facts facts) {
    if (facts.held().foldSha() != null) {
      return facts.held().foldSha();
    }
    if (Step.WITHDRAWN.name().equals(facts.held().step()) || facts.request() == null) {
      return null;
    }
    return facts.request().mergedSha;
  }

  /** WITHDRAWN, or naming a request that ended without carrying the branch on. */
  private static boolean rowEnded(Facts facts) {
    if (Step.WITHDRAWN.name().equals(facts.held().step())) {
      return true;
    }
    ReleaseRequest request = facts.request();
    return request != null
        && (request.state == ReleaseRequest.State.WITHDRAWN
            || request.state == ReleaseRequest.State.OBSOLETE);
  }

  /** True only when the git host says the branch is not there; any failure is "could not ask". */
  private boolean branchGone(Facts facts) {
    try {
      return gitHosts.get().head(facts.wrapper().id, facts.branch()).noSuchBranch();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * VERIFIED → DONE for {@code ticketId}, as {@link #MOVER}, then {@code sentence} on its thread.
   * A ticket anywhere else is left alone and nothing is said.
   */
  private void done(String ticketId, String projectId, String sentence) {
    Optional<WorkEntityService.Transition> moved =
        entities.transitionFrom(
            Archetype.TICKET,
            ticketId,
            EntityStatus.VERIFIED,
            EntityStatus.DONE,
            Mover.machine(MOVER));
    if (moved.isEmpty()) {
      LOG.debugf(
          "Ticket %s is not VERIFIED, so its ended wrapper delivery closes nothing yet; its arrival"
              + " at VERIFIED reads it again",
          ticketId);
      return;
    }
    LOG.infof("Ticket %s is DONE: %s", ticketId, sentence);
    try {
      comments.addComment(ticketId, sentence, MOVER);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Moved ticket %s to DONE but could not say why on its thread", ticketId);
    }
    try {
      publisher.fire(projectId, ProjectChangeHint.Topic.TICKETS);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not fire the TICKETS hint for project %s", projectId);
    }
  }

  private static String shortSha(String sha) {
    return sha.length() > 12 ? sha.substring(0, 12) : sha;
  }

  /** What the ticket's thread is told, naming the request that ended its delivery. */
  static String sentence(Ended ended) {
    String request = "`" + (ended.qualifiedId() == null ? ended.requestId() : ended.qualifiedId()) + "`";
    return switch (ended.ending()) {
      case FINALIZED ->
          "The wrapper release request "
              + request
              + " carrying `"
              + ended.branch()
              + "` is **FINALIZED**"
              + (ended.version() == null ? "" : ": `" + ended.version() + "` reached main")
              + ". The platform moves this ticket to **DONE**.";
      case EMPTY ->
          "The wrapper release request "
              + request
              + " carrying `"
              + ended.branch()
              + "` was withdrawn because its fold adds nothing to main: there is nothing left to"
              + " release. The platform moves this ticket to **DONE**.";
      case DELETED_MERGED ->
          ended.deletedSha() != null
              ? "The wrapper branch `"
                  + ended.branch()
                  + "` was deleted at `"
                  + shortSha(ended.deletedSha())
                  + "`, which main already contains (wrapper release request "
                  + request
                  + "): nothing of it is left to add. The platform moves this ticket to **DONE**."
              : "The wrapper branch `"
                  + ended.branch()
                  + "` was deleted, and the last fold of it, in the wrapper release request "
                  + request
                  + ", is already contained in main: nothing of it is left to add. The platform"
                  + " moves this ticket to **DONE**.";
      case NO_BRANCH ->
          "No wrapper branch `"
              + ended.branch()
              + "` exists on the git host and no release request ever carried one, so nothing"
              + " will be released for this ticket. The platform moves it to **DONE**.";
    };
  }
}
