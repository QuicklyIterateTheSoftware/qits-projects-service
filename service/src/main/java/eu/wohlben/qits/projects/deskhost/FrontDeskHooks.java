package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.control.FrontDeskLifecycleChanged;
import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.control.ProjectFrontDeskRemoval;
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
 *       Its delivery half (epic qits-1440): an enabled loop gets the desk and its delivery token
 *       now, a disabled one loses the token, and the desired state is recomputed and pushed; the
 *       spec carries the loop's env, so the roll recreates the container here too.
 *   <li>{@link ProjectFrontDeskRemoval} — a project being deleted takes its desk with it: {@code
 *       remove} to the runner, the token revoked, the row dropped.
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
    if (delivery.enabled()) {
      desks.ensureRow(projectId);
    } else {
      desks.syncDeliveryToken(projectId);
    }
    desks.reconcileAndPush(projectId, Instant.now());
  }

  @Override
  public void projectDeleting(String projectId) {
    desks.removeDesk(projectId);
  }
}
