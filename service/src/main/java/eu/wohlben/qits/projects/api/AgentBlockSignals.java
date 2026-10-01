package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentBlocks;
import eu.wohlben.qits.projects.refinementhost.RefinementAgentBlocks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>Every agent session working an entity is told when the entity's block flag changes</b>, so
 * its Claude session name gains or loses the {@code ❗ } marker (qits-614). One method, two
 * targets, nothing answered and nothing thrown.
 *
 * <h2>The two targets, and why both are asked</h2>
 *
 * <p>An entity's agent stands in one of two places, and nothing here knows which without asking:
 * the <b>workspace</b> on its {@code ticket/<slug>} or {@code epic/<slug>} branch, which
 * qits-workspaces runs ({@link WorkspaceAgentBlocks}), and the <b>refinement</b> container this
 * service runs for it ({@link RefinementAgentBlocks}). Both are asked, the workspace first: it is
 * where the implement and verify phases run, and a refinement is the rarer case. Asking a target
 * that has nothing standing costs one cheap answer — a {@code workspaceId: null}, an empty indexed
 * read — and asking only one would leave the other's session reading as working.
 *
 * <h2>Only a ticket and an epic</h2>
 *
 * <p>A campaign is blocked too (qits-592), but no agent session works a campaign — its executor
 * dispatches its members, each into a workspace of its own — so there is nobody whose name to mark.
 * A feature and a task cannot be blocked at all. The filter is here, once, so neither call site has
 * to know it.
 *
 * <h2>Never throws, never slows materially, and runs after the write</h2>
 *
 * <p>Both call sites — {@link EntityBlocks#apply} and {@code EntityResolutions.transition} — call
 * this <b>after the row write has returned</b>. Every {@code WorkEntityService} write runs in its
 * own {@code WritePatience} transaction and neither call site is {@code @Transactional}, so by then
 * the flag is committed: a target is never told about a value that a rollback then undid, and an
 * unreachable target can never undo a value that was written. That is {@link PhaseAdvance}'s
 * ordering, for its reason.
 *
 * <p>Each target bounds its own exchange to a few seconds and folds every failure into one WARN;
 * this class adds a belt over both, because the port says it must not throw and a port bug must
 * still not turn a recorded block into a 500. A project with no wrapper has no workspace branch to
 * address and asks only the refinement.
 */
@ApplicationScoped
public class AgentBlockSignals {

  private static final Logger LOG = Logger.getLogger(AgentBlockSignals.class);

  @Inject EntityWorkspaces workspaces;

  /**
   * Optional, like every port here, and <b>absent is a supported configuration</b>: no workspace is
   * told, and the session keeps the name it had.
   */
  @Inject Instance<WorkspaceAgentBlocks> workspaceBlocks;

  @Inject RefinementAgentBlocks refinementBlocks;

  /**
   * Tell every agent working {@code entity} that its block flag is now {@code blocked}. A no-op for
   * anything but a ticket or an epic. <b>Never throws.</b>
   *
   * @param entity the entity, as either side of the write — only its archetype, id, project and
   *     slug are read, none of which a block or a transition changes
   * @param blocked the flag as it now stands on the row
   */
  public void blocked(WorkEntity entity, boolean blocked) {
    if (entity == null
        || (entity.archetype != Archetype.TICKET && entity.archetype != Archetype.EPIC)) {
      return;
    }
    tellTheWorkspace(entity, blocked);
    try {
      refinementBlocks.blocked(entity.id, blocked);
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "Could not tell the refinement of %s %s it is blocked=%s", entity.archetype, entity.id,
          blocked);
    }
  }

  private void tellTheWorkspace(WorkEntity entity, boolean blocked) {
    if (workspaceBlocks.isUnsatisfied()) {
      return;
    }
    try {
      Optional<EntityWorkspaces.Target> target = workspaces.find(entity);
      if (target.isEmpty()) {
        LOG.debugf(
            "%s %s's project has no wrapper repository, so no workspace is told it is blocked=%s",
            entity.archetype, entity.id, blocked);
        return;
      }
      workspaceBlocks.get().blocked(target.get().repositoryId(), target.get().branch(), blocked);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug (or a catalog read that failed), and
      // neither may touch a block that is already recorded.
      LOG.warnf(
          e, "Could not tell the workspace of %s %s it is blocked=%s", entity.archetype, entity.id,
          blocked);
    }
  }
}
