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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>The platform sets a ticket DONE, and this is where</b> (qits-150). A ticket is DONE only once
 * the wrapper release request carrying its branch is FINALIZED — or once its wrapper branch is found
 * to carry nothing — and no agent or person moves it there: both doors refuse a ticket's DONE for
 * every role ({@code WorkEntityDoors.move}, {@code EntityTransitionService.refuseTicketDone}).
 *
 * <h2>Three ways in, one move</h2>
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
 *       could not be told or could not move the ticket.
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
          settled(ticket).ifPresent(ended -> done(ticket.id, ticket.projectId, sentence(ended)));
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
   * How {@code ticket}'s wrapper branch's delivery ended, read off its ledger row in the project's
   * wrapper repository — or empty where it has not ended, or there is no wrapper, no qualified id or
   * no row.
   */
  Optional<Ended> settled(WorkEntity ticket) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Project project = projects.get(ticket.projectId);
              Optional<Repository> wrapper = projects.findWrapper(project.id);
              if (wrapper.isEmpty() || ticket.number < 1 || project.slug == null) {
                return Optional.<Ended>empty();
              }
              String branch =
                  WorkBranches.of(ticket, QualifiedEntityIds.render(project.slug, ticket.number));
              Optional<BranchDeliveryRepository.Position> held =
                  ledger.position(project.id, wrapper.get().id, branch);
              if (held.isEmpty() || held.get().requestId() == null) {
                return Optional.<Ended>empty();
              }
              ReleaseRequest request = requests.findByIdOptional(held.get().requestId()).orElse(null);
              String qualifiedId = request == null ? held.get().requestId() : request.qualifiedId;
              if (Step.LIVE.name().equals(held.get().step())) {
                return Optional.of(
                    new Ended(
                        project.id,
                        wrapper.get().id,
                        branch,
                        held.get().requestId(),
                        qualifiedId,
                        held.get().version(),
                        Ending.FINALIZED));
              }
              if (Step.WITHDRAWN.name().equals(held.get().step())
                  && request != null
                  && request.state == ReleaseRequest.State.WITHDRAWN
                  && ReleaseRequests.EMPTY_WRAPPER_WITHDRAWN.equals(request.detail)) {
                return Optional.of(
                    new Ended(
                        project.id,
                        wrapper.get().id,
                        branch,
                        held.get().requestId(),
                        qualifiedId,
                        null,
                        Ending.EMPTY));
              }
              return Optional.<Ended>empty();
            });
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
    };
  }
}
