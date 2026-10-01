package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.AgentBlockSignals;
import eu.wohlben.qits.projects.entity.Refinement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Resolving an entity tears its refinement down. The assembling layer's join between the two, and
 * the <b>only</b> way an epic's or a ticket's status should be moved from a door: {@code
 * WorkEntityService.transition} alone leaks whatever the entity was still holding. It was {@code EpicResolutions} until qits-395 let a ticket open a refinement too;
 * the rule did not change, only the set of archetypes that can own a room.
 *
 * <p>It lives here, beside the thing it tears down, rather than in the {@code entities} module: that
 * jar depends on {@code domain} nowhere and must keep not depending on it, and the direction is
 * already this way round — {@link RefinementService} depends on the entity services, not the
 * reverse. The seam a caller uses is the one method on this bean, taking the archetype as data like
 * the service under it (qits-399), so a door that moves an entity gets the cleanup by construction
 * and not by remembering.
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
 * <h2>A move off a block tells the entity's agents, before the next phase starts (qits-614)</h2>
 *
 * <p>Every transition clears {@code blocked} ({@code WorkEntityService.transition}), and nothing
 * else would tell the agent sessions working the entity that their {@code ❗ } marker is stale. So
 * the flag is read off the <b>plan</b> — the row as it stood before the move — and, only when it was
 * set, {@link AgentBlockSignals} is told {@code false} once the transition has returned. Three
 * properties follow from where that line sits:
 *
 * <ul>
 *   <li><b>After the commit.</b> The transition runs in its own {@code WritePatience} transaction
 *       and this bean is not transactional, so a move that is refused or rolled back has told
 *       nobody anything, and a target that cannot be reached cannot undo the move.
 *   <li><b>Before {@code PhaseAdvance}.</b> Every door that delivers the next phase's turn calls
 *       {@code PhaseAdvance.afterTransition} <em>after</em> this method returns, so the unblock is
 *       on the far side before a turn is delivered or an agent launched on the same branch — the
 *       ordering holds by construction, without either door having to remember it.
 *   <li><b>Not after a resolving move's discard alone.</b> A move that resolves the entity has
 *       already torn its refinement down, so that half finds no room and asks nothing; the
 *       workspace is still told, because a resolved entity's workspace may well still stand.
 * </ul>
 *
 * <p>The flag is copied to a local before anything else runs: within an enclosing transaction the
 * planned row could be the managed instance the move writes, and reading it afterwards would always
 * answer {@code false}.
 *
 * <p><b>Not covered, and stated rather than fixed:</b> {@code POST /entities/transition} and the
 * {@code transition_entities} tool write statuses through {@code EntityTransitions}, which does not
 * come through here — for an epic exactly as before qits-395.
 */
@ApplicationScoped
public class EntityResolutions {

  private static final Logger LOG = Logger.getLogger(EntityResolutions.class);

  @Inject WorkEntityService entities;

  @Inject RefinementService refinements;

  /** The agents told that a move cleared the block — see the class javadoc. */
  @Inject AgentBlockSignals agents;

  /**
   * A move of any lifecycle archetype, with its refinement torn down first when it resolves it, and
   * the entity's agents told it is no longer blocked when it was.
   */
  public WorkEntityService.Transition transition(
      Archetype archetype, String id, String target, String changedBy) {
    WorkEntityService.PlannedTransition planned = entities.planTransition(archetype, id, target);
    boolean wasBlocked = planned.entity().blocked;
    if (planned.resolving()) {
      discardHeldBy(id, noun(archetype), planned.target().name());
    }
    WorkEntityService.Transition moved = entities.transition(archetype, id, target, changedBy);
    if (wasBlocked) {
      // Ticket and epic only, never throws — AgentBlockSignals filters and swallows both.
      agents.blocked(moved.entity(), false);
    }
    return moved;
  }

  /** "Epic" / "Ticket", for the log line. */
  private static String noun(Archetype archetype) {
    String name = archetype.name();
    return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
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
