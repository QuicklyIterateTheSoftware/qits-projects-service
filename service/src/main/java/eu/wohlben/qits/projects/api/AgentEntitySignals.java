package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.EntityBlockState;
import eu.wohlben.qits.entities.control.RetitleAnnouncer;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentEntities;
import eu.wohlben.qits.projects.refinementhost.RefinementSessions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * <b>Every agent session working an entity is told the entity as it now stands</b> — its title, its
 * status and its blocked flag — whenever any of the three changes, so the session's name, {@code
 * <status square> <qualified id> <title>} with the square pale while blocked, never reads a stale
 * entity (qits-614 for the flag, qits-617 for the rest). One method, two targets, nothing answered
 * and nothing thrown.
 *
 * <h2>Always all three, from the row</h2>
 *
 * <p>A signal carries the title, the status name and the flag as the <b>row</b> holds them after the
 * write, never the one value the caller changed. So the far side keeps no delta to apply in order,
 * a signal that was lost is repaired by the next one about anything, and the three callers need not
 * agree on what "changed" meant.
 *
 * <h2>The three triggers, each wired once</h2>
 *
 * <ul>
 *   <li><b>A block or an unblock</b> — {@link EntityBlocks#apply}, when the flag actually moved.
 *   <li><b>Every transition</b> — {@code bus/EntityTransitionAnnouncer}, the one implementation of
 *       the {@code TransitionAnnouncer} port both {@code WorkEntityService.transition} and {@code
 *       EntityTransitionService} announce through after their write, calls {@link
 *       #changed(TransitionedEntity)} for each ticket and epic of the batch. Wiring it there and not in the doors is what makes
 *       {@code POST /work/transition} and {@code transition_entities} reach the agents too, which
 *       the door-level call in {@code EntityResolutions} this replaced never did — and what makes a
 *       transition signal exactly once, whichever door it came through. A transition clears the
 *       flag, so this is also how an agent learns a move unblocked it.
 *   <li><b>A retitle</b> — this class is the {@link RetitleAnnouncer} {@code WorkEntityService.update}
 *       announces through when an edit actually changed the title, so the REST patch, the routes and
 *       the {@code update_ticket} / {@code update_epic} tools are all covered at the one layer they
 *       share.
 *   <li><b>A derived block starting or ending</b> (qits-895) — {@link AgentWaiting}, when a
 *       session's wait crosses the debounce (its sweep) or ends after it had.
 * </ul>
 *
 * <h2>The two targets, and why both are asked</h2>
 *
 * <p>An entity's agent stands in one of two places, and nothing here knows which without asking:
 * the <b>agent</b> qits-workspaces runs for the work item, found by its id (qits-1152, {@link
 * WorkspaceAgentEntities}), and the <b>refinement</b> room's session in the project's front desk
 * ({@link RefinementSessions#entityChanged}, epic qits-770). Both are asked, the workspace first: it is
 * where the implement and verify phases run, and a refinement is the rarer case. Asking a target
 * that has nothing standing costs one cheap answer — an {@code agentId: null}, an empty indexed
 * read — and asking only one would leave the other's session reading a stale entity.
 *
 * <h2>Only a ticket and an epic</h2>
 *
 * <p>A campaign has a status and a block too (qits-592), but no agent session works a campaign — its
 * executor dispatches its members, each into a workspace of its own — so there is nobody whose name
 * to change. A feature and a task hold a status of their own since qits-763, and their moves are
 * announced like any other, but no session is named after them — their agent works the epic.
 * The filter is here, once, so no trigger has to know it.
 *
 * <h2>Never throws, never slows materially, and runs after the write</h2>
 *
 * <p>Every trigger fires <b>after the row write has returned</b>: every {@code WorkEntityService}
 * write runs in its own {@code WritePatience} transaction, and both announcement ports are called
 * only once it has committed. So a target is never told about a value that a rollback then undid,
 * and an unreachable target can never undo a value that was written. That is {@link PhaseAdvance}'s
 * ordering, for its reason — and since the transition announcement is made inside the transition
 * call, before any door reaches {@code PhaseAdvance.afterTransition}, the agents hear about the move
 * before a turn is delivered or an agent launched on the same branch.
 *
 * <p>Each target bounds its own exchange to a few seconds and folds every failure into one WARN;
 * this class adds a belt over both, because the port says it must not throw and a port bug must
 * still not turn a recorded write into a 500.
 */
@ApplicationScoped
public class AgentEntitySignals implements RetitleAnnouncer {

  private static final Logger LOG = Logger.getLogger(AgentEntitySignals.class);

  /**
   * Optional, like every port here, and <b>absent is a supported configuration</b>: no workspace is
   * told, and the session keeps the name it had.
   */
  @Inject Instance<WorkspaceAgentEntities> workspaceEntities;

  @Inject RefinementSessions refinementSessions;

  /**
   * Tell every agent working {@code entity} its title, status and blocked flag as they stand on it.
   * A no-op for anything but a ticket or an epic — and a campaign, whose refinement room alone is told. <b>Never throws.</b> The flag is the EFFECTIVE
   * block (qits-895, {@code EntityBlockState}) — the explicit one, or the session itself standing
   * idle past the debounce — so the name pales while either holds; {@link AgentWaiting} calls this
   * when a derived block starts or ends. <b>The block's source rides beside the flag</b> —
   * {@code EXPLICIT}, {@code AGENT_WAITING} or {@code BOTH}, {@code null} while unblocked — so a far
   * side that wants to tell "waiting for a person" apart from "blocked" can, without either target
   * re-deriving the debounce itself. <b>So does the explicit block's author</b> (qits-1511), so
   * qits-workspaces can tell a block the agent set on its own work from one somebody else set; the
   * workspace target alone is told it.
   *
   * @param entity the row <b>after</b> the write — its title, status and block are what is sent,
   *     and its archetype and id address the targets
   */
  public void changed(WorkEntity entity) {
    if (entity == null) {
      return;
    }
    EntityBlockState block = EntityBlockState.of(entity);
    tell(
        entity.id,
        entity.archetype,
        entity.title,
        entity.status,
        block.blocked(),
        block.source(),
        block.blockedBy());
  }

  /**
   * {@link #changed(WorkEntity)} for one row of a transition announcement, which carries the row's
   * post-state as the transition wrote it. <b>Never throws.</b>
   *
   * <p><b>The batch row is used, and the row is deliberately not re-read by id.</b> Outside a
   * transaction a request keeps one persistence context, the door's own read before the move filled
   * it, and the move committed in a transaction of its own — so a {@code find(id)} here answers the
   * row as it stood <em>before</em> the move: the old status, and a flag the move had cleared.
   * Measured, by {@code EntityBlockApiTest}, and the trap {@code TransitionedEntity.edited} records
   * for the same reason. So every value comes off the announcement.
   *
   * <p><b>The block is sent as the announcement states it, never re-derived</b> (qits-1511). The
   * announcement's {@code blocked} is already the EFFECTIVE flag and its {@code blockSource} says
   * why; rebuilding a row from the flag alone and asking {@code EntityBlockState} again answered
   * {@code EXPLICIT} for every block, so a ticket blocked only because its agent stood waiting — and
   * announced in a reshape that kept its status ({@code EntityTransitionService.apply}) — reached
   * qits-workspaces as an explicit block, which stops its idle-agent nudge. A row the announcement
   * carries no author for sends none: a status move clears the explicit block anyway.
   */
  public void changed(TransitionedEntity moved) {
    if (moved == null) {
      return;
    }
    tell(
        moved.id(),
        moved.archetype(),
        moved.title(),
        moved.status(),
        Boolean.TRUE.equals(moved.blocked()),
        moved.blockSource(),
        moved.blockedBy());
  }

  /** The retitle trigger — see the class javadoc. <b>Never throws.</b> */
  @Override
  public void onRetitled(WorkEntity entity) {
    changed(entity);
  }

  /**
   * The one place both triggers' values reach the two targets. The archetype filter is here, once
   * (see the class javadoc), and so is the belt over each target.
   */
  private void tell(
      String id,
      Archetype archetype,
      String title,
      String status,
      boolean blocked,
      String blockSource,
      String blockedBy) {
    if (archetype != Archetype.TICKET
        && archetype != Archetype.EPIC
        && archetype != Archetype.CAMPAIGN) {
      return;
    }
    if (archetype != Archetype.CAMPAIGN) {
      tellTheWorkspace(id, archetype, title, status, blocked, blockSource, blockedBy);
    }
    // A campaign has a refinement room too since epic qits-770, but no dispatched agent of its own.
    try {
      refinementSessions.entityChanged(id, title, status, blocked, blockSource);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not tell the refinement of %s %s its entity changed", archetype, id);
    }
  }

  private void tellTheWorkspace(
      String id,
      Archetype archetype,
      String title,
      String status,
      boolean blocked,
      String blockSource,
      String blockedBy) {
    if (workspaceEntities.isUnsatisfied()) {
      return;
    }
    try {
      // Keyed by the work item (qits-1152): the far side finds its ACTIVE agent, whatever branch it
      // stands on.
      workspaceEntities.get().changed(id, title, status, blocked, blockSource, blockedBy);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug, and it may not touch a write that is
      // already recorded.
      LOG.warnf(e, "Could not tell the agent of %s %s its entity changed", archetype, id);
    }
  }
}
