package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.RetitleAnnouncer;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentEntities;
import eu.wohlben.qits.projects.refinementhost.RefinementAgentEntities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
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
 *       {@code POST /entities/transition} and {@code transition_entities} reach the agents too, which
 *       the door-level call in {@code EntityResolutions} this replaced never did — and what makes a
 *       transition signal exactly once, whichever door it came through. A transition clears the
 *       flag, so this is also how an agent learns a move unblocked it.
 *   <li><b>A retitle</b> — this class is the {@link RetitleAnnouncer} {@code WorkEntityService.update}
 *       announces through when an edit actually changed the title, so the REST patch, the routes and
 *       the {@code update_ticket} / {@code update_epic} tools are all covered at the one layer they
 *       share.
 * </ul>
 *
 * <h2>The two targets, and why both are asked</h2>
 *
 * <p>An entity's agent stands in one of two places, and nothing here knows which without asking:
 * the <b>workspace</b> on its {@code ticket/<slug>} or {@code epic/<slug>} branch, which
 * qits-workspaces runs ({@link WorkspaceAgentEntities}), and the <b>refinement</b> container this
 * service runs for it ({@link RefinementAgentEntities}). Both are asked, the workspace first: it is
 * where the implement and verify phases run, and a refinement is the rarer case. Asking a target
 * that has nothing standing costs one cheap answer — a {@code workspaceId: null}, an empty indexed
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
 * still not turn a recorded write into a 500. A project with no wrapper has no workspace branch to
 * address and asks only the refinement.
 */
@ApplicationScoped
public class AgentEntitySignals implements RetitleAnnouncer {

  private static final Logger LOG = Logger.getLogger(AgentEntitySignals.class);

  @Inject EntityWorkspaces workspaces;

  /**
   * Optional, like every port here, and <b>absent is a supported configuration</b>: no workspace is
   * told, and the session keeps the name it had.
   */
  @Inject Instance<WorkspaceAgentEntities> workspaceEntities;

  @Inject RefinementAgentEntities refinementEntities;

  /**
   * Tell every agent working {@code entity} its title, status and blocked flag as they stand on it.
   * A no-op for anything but a ticket or an epic. <b>Never throws.</b>
   *
   * @param entity the row <b>after</b> the write — its title, status and flag are what is sent, and
   *     its archetype, id, project and slug address the targets
   */
  public void changed(WorkEntity entity) {
    if (entity == null
        || (entity.archetype != Archetype.TICKET && entity.archetype != Archetype.EPIC)) {
      return;
    }
    tellTheWorkspace(entity);
    try {
      refinementEntities.changed(entity.id, entity.title, entity.status, entity.blocked);
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "Could not tell the refinement of %s %s its entity changed", entity.archetype,
          entity.id);
    }
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
   * for the same reason. So the values come off the announcement, and the transient row built here
   * only carries them and the four fields the targets are addressed by — id, archetype, project and
   * slug; it is never persisted.
   */
  public void changed(TransitionedEntity moved) {
    if (moved == null) {
      return;
    }
    WorkEntity row = new WorkEntity();
    row.id = moved.id();
    row.archetype = moved.archetype();
    row.projectId = moved.projectId();
    row.number = moved.number();
    row.slug = moved.slug();
    row.slugScope = moved.slugScope();
    row.title = moved.title();
    row.status = moved.status();
    row.blocked = Boolean.TRUE.equals(moved.blocked());
    changed(row);
  }

  /** The retitle trigger — see the class javadoc. <b>Never throws.</b> */
  @Override
  public void onRetitled(WorkEntity entity) {
    changed(entity);
  }

  private void tellTheWorkspace(WorkEntity entity) {
    if (workspaceEntities.isUnsatisfied()) {
      return;
    }
    try {
      Optional<EntityWorkspaces.Target> target = workspaces.find(entity);
      if (target.isEmpty()) {
        LOG.debugf(
            "%s %s's project has no wrapper repository, so no workspace is told it changed",
            entity.archetype, entity.id);
        return;
      }
      workspaceEntities
          .get()
          .changed(
              entity.id,
              target.get().repositoryId(),
              target.get().branch(),
              entity.title,
              entity.status,
              entity.blocked);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug (or a catalog read that failed), and
      // neither may touch a write that is already recorded.
      LOG.warnf(
          e, "Could not tell the workspace of %s %s its entity changed", entity.archetype,
          entity.id);
    }
  }
}
