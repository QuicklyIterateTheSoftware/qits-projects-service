package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.entities.control.EpicService;
import eu.wohlben.qits.entities.control.TicketService;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.entity.Refinement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Resolving an entity tears its refinement down. The assembling layer's join between the two, and
 * the <b>only</b> way an epic's or a ticket's status should be moved from a door: {@code
 * EpicService.transition} or {@code TicketService.transition} alone leaks whatever the entity was
 * still holding. It was {@code EpicResolutions} until qits-395 let a ticket open a refinement too;
 * the rule did not change, only the set of archetypes that can own a room.
 *
 * <p>It lives here, beside the thing it tears down, rather than in the {@code entities} module: that
 * jar depends on {@code domain} nowhere and must keep not depending on it, and the direction is
 * already this way round — {@link RefinementService} depends on the entity services, not the
 * reverse. The seam a caller uses is one method per archetype on this bean, so a door that moves an
 * entity gets the cleanup by construction and not by remembering.
 *
 * <p>Until 2026-09-08 the cleanup was in the browser instead — the refining page discarded before
 * transitioning, and only for what was then {@code ABANDONED} (now {@code DROPPED}). Every other
 * route to a resolved status (the epics board, the REST API, an agent) left the container, its
 * volume, its commissioned credential and the {@code refining/<slug>} branch allocated for good, and
 * the stranded row could not even be adopted back: {@link RefinementService#findOrCreate} refuses an
 * entity that is not {@code REPORTED}.
 *
 * <h2>Order, and why the check comes first</h2>
 *
 * <ol>
 *   <li><b>Plan</b> — {@code planTransition} refuses an illegal move, a target naming no status and
 *       an unknown entity, before anything is touched. A 409 that had already discarded a
 *       refinement would be a worse leak than the one this fixes.
 *   <li><b>Discard</b> — container, volume, credential, branch, row. A failure here throws and the
 *       entity stays where it was, which leaves a still-refining entity with a UI to retry from.
 *   <li><b>Transition</b> — last, so the entity is only made resolved once it owns nothing.
 * </ol>
 *
 * <p>Reversing steps 2 and 3 would leave a resolved entity owning a room nothing can reach. Only a
 * <em>resolving</em> move discards: {@code REPORTED→REFINED} is the scope freeze, and the entity
 * goes on being refined through it. The resolving statuses are IMPLEMENTED, VERIFIED, DONE and
 * DROPPED ({@code EntityLifecycle.resolves}), whichever direction the move comes from, and the rule
 * is the same word for word for both archetypes.
 *
 * <p><b>Not covered, and stated rather than fixed:</b> {@code POST /entities/transition} and the
 * {@code transition_entities} tool write statuses through {@code EntityTransitions}, which does not
 * come through here — for an epic exactly as before qits-395.
 */
@ApplicationScoped
public class EntityResolutions {

  private static final Logger LOG = Logger.getLogger(EntityResolutions.class);

  @Inject EpicService epics;

  @Inject TicketService tickets;

  @Inject RefinementService refinements;

  /** An epic's move, with its refinement torn down first when it resolves the epic. */
  public EpicService.Transition transition(String epicId, String target, String changedBy) {
    EpicService.PlannedTransition planned = epics.planTransition(epicId, target);
    if (planned.resolving()) {
      discardHeldBy(epicId, "Epic", planned.target().name());
    }
    return epics.transition(epicId, target, changedBy);
  }

  /** A ticket's move, with its refinement torn down first when it resolves the ticket (qits-395). */
  public WorkEntity transitionTicket(String ticketId, String target, String changedBy) {
    TicketService.PlannedTransition planned = tickets.planTransition(ticketId, target);
    if (planned.resolving()) {
      discardHeldBy(ticketId, "Ticket", planned.target().name());
    }
    return tickets.transition(ticketId, target, changedBy);
  }

  private void discardHeldBy(String entityId, String noun, String target) {
    Optional<Refinement> refinement = refinements.findByEntity(entityId);
    if (refinement.isPresent()) {
      LOG.infof(
          "%s %s resolves to %s — discarding refinement %s first",
          noun, entityId, target, refinement.get().id);
      refinements.discard(refinement.get().id);
    }
  }
}
