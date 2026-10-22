package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.control.FrontDeskLifecycleChanged;
import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.control.ProjectFrontDeskRemoval;
import eu.wohlben.qits.projects.entity.DeskRole;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * The front desk's answers to the domain's two project hooks (qits-767):
 *
 * <ul>
 *   <li>{@link FrontDeskLifecycleChanged} — a {@code project.yml} flip: an ALWAYS_ON project gets
 *       its desk (and token) now, and the desk's desired state is recomputed and pushed. The spec
 *       carries the lifecycle too, so the spec roll recreates the container on its next pass.
 *       Its delivery half (epic qits-1440, qits-1482): an enabled loop gets its DELIVERY desk and
 *       that desk's tokens now, and the desk's desired state is recomputed and pushed; a disabled
 *       one has its DELIVERY desk stopped and removed, tokens revoked. The FRONT desk is not the
 *       loop's any more, so the flip leaves its spec alone; its desired state is recomputed all the
 *       same.
 *   <li>{@link ProjectFrontDeskRemoval} — a project being deleted takes every desk it has with it:
 *       {@code remove} to the runner, the tokens revoked, the rows dropped.
 * </ul>
 */
@ApplicationScoped
public class FrontDeskHooks implements FrontDeskLifecycleChanged, ProjectFrontDeskRemoval {

  @Inject FrontDesks desks;

  @Override
  public void onChanged(String projectId, FrontDeskLifecycle lifecycle) {
    if (lifecycle == FrontDeskLifecycle.ALWAYS_ON) {
      desks.ensureRow(projectId);
    }
    desks.reconcileAndPush(projectId, Instant.now());
  }

  @Override
  public void onDeliveryChanged(String projectId, DeliverySettings delivery) {
    desks.syncDeliveryDesk(projectId);
    desks.syncTokens(DeskRole.FRONT.deskId(projectId));
    desks.reconcileAndPush(DeskRole.FRONT.deskId(projectId), Instant.now());
  }

  @Override
  public void projectDeleting(String projectId) {
    desks.removeProjectDesks(projectId);
  }
}
