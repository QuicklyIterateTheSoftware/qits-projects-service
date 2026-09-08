package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.projects.entity.Refinement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Resolving an epic tears its refinement down. The assembling layer's join between the two, and the
 * <b>only</b> way an epic's status should be moved from a door: {@code EpicService.transition}
 * alone leaks whatever the epic was still holding.
 *
 * <p>It lives here, beside the thing it tears down, rather than in the {@code epics} module: that
 * jar depends on {@code domain} nowhere and must keep not depending on it, and the direction is
 * already this way round — {@link RefinementService} depends on {@link EpicService}, not the
 * reverse. The seam a caller uses is one method on this bean, so a door that moves an epic gets the
 * cleanup by construction and not by remembering.
 *
 * <p>Until 2026-09-08 the cleanup was in the browser instead — the refining page discarded before
 * transitioning, and only for {@code ABANDONED}. Every other route to a resolved status (the epics
 * board, the REST API, an agent) left the container, its volume, its commissioned credential and
 * the {@code refining/<slug>} branch allocated for good, and the stranded row could not even be
 * adopted back: {@link RefinementService#findOrCreate} refuses an epic that is not {@code
 * REFINING}.
 *
 * <h2>Order, and why the check comes first</h2>
 *
 * <ol>
 *   <li><b>Plan</b> — {@link EpicService#planTransition} refuses an illegal move, a target naming
 *       no status and an unknown epic, before anything is touched. A 409 that had already discarded
 *       a refinement would be a worse leak than the one this fixes.
 *   <li><b>Discard</b> — container, volume, credential, branch, row. A failure here throws and the
 *       epic stays where it was, which leaves a still-refining epic with a UI to retry from.
 *   <li><b>Transition</b> — last, so the epic is only made resolved once it owns nothing.
 * </ol>
 *
 * <p>Reversing steps 2 and 3 would leave a resolved epic owning a workspace nothing can reach. Only
 * a {@linkplain EpicService.PlannedTransition#resolving resolving} move discards: {@code
 * REFINING→IMPLEMENTATION} is the scope freeze, and the epic goes on being refined through it.
 */
@ApplicationScoped
public class EpicResolutions {

  private static final Logger LOG = Logger.getLogger(EpicResolutions.class);

  @Inject EpicService epics;

  @Inject RefinementService refinements;

  /** The move, with the refinement torn down first when it resolves the epic. */
  public EpicService.Transition transition(String epicId, String target, String changedBy) {
    EpicService.PlannedTransition planned = epics.planTransition(epicId, target);
    if (planned.resolving()) {
      Optional<Refinement> refinement = refinements.findByEpic(epicId);
      if (refinement.isPresent()) {
        LOG.infof(
            "Epic %s resolves to %s — discarding refinement %s first",
            epicId, planned.target(), refinement.get().id);
        refinements.discard(refinement.get().id);
      }
    }
    return epics.transition(epicId, target, changedBy);
  }
}
