package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.control.FrontDeskLifecycleChanged;
import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.control.ProjectFrontDeskRemoval;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * The front desk's answers to the domain's two project hooks (qits-767):
 *
 * <ul>
 *   <li>{@link FrontDeskLifecycleChanged} — a {@code project.yml} flip: an ALWAYS_ON project gets
 *       its desk (and token) now, and the desk's desired state is recomputed and pushed. The spec
 *       carries the lifecycle too, so the desk is asked to roll ({@code roll_requested_at},
 *       qits-1514) and rolls at once between turns: now when its agent is not busy, otherwise when
 *       the current turn ends (the spec roll's next pass), not after the quiet window a pin bump
 *       waits for. Its delivery half (epic qits-1440): an enabled loop gets the desk and its
 *       delivery token now, a disabled one loses the token, the desk's daemon is sent its session
 *       credentials again (qits-1482: the {@code front-desk} key follows the loop), and the desired
 *       state is recomputed and pushed; the spec carries the loop's env, so the desk rolls the same
 *       way. A
 *       hook fired with unchanged settings composes the applied spec, which clears the request and
 *       restarts nothing.
 *   <li>{@link ProjectFrontDeskRemoval} — a project being deleted takes its desk with it: {@code
 *       remove} to the runner, the token revoked, the row dropped.
 * </ul>
 */
@ApplicationScoped
public class FrontDeskHooks implements FrontDeskLifecycleChanged, ProjectFrontDeskRemoval {

  private static final Logger LOG = Logger.getLogger(FrontDeskHooks.class);

  @Inject FrontDesks desks;

  @Inject FrontDeskSpecRoll specRoll;

  @Inject DeskSessionCredentials sessionCredentials;

  @Override
  public void onChanged(String projectId, FrontDeskLifecycle lifecycle) {
    Instant now = Instant.now();
    if (lifecycle == FrontDeskLifecycle.ALWAYS_ON) {
      desks.ensureRow(projectId);
    }
    desks.reconcileAndPush(projectId, now);
    rollBetweenTurns(projectId, now);
  }

  @Override
  public void onDeliveryChanged(String projectId, DeliverySettings delivery) {
    Instant now = Instant.now();
    if (delivery.enabled()) {
      desks.ensureRow(projectId);
    } else {
      desks.syncDeliveryToken(projectId);
    }
    // The front-desk key follows the loop (qits-1482): sent again even when no token moved, since
    // the delivery token only stands under that key while the loop is on.
    sessionCredentials.push(projectId);
    desks.reconcileAndPush(projectId, now);
    rollBetweenTurns(projectId, now);
  }

  /**
   * Mark the desk's settings changed and roll it now if its agent is between turns (qits-1514). A
   * project with no desk updates no row and an unplaced desk is not rolled; a failure is a WARN and
   * never fails the reconcile that called the hook — the request stays set and the sweep rolls it.
   */
  private void rollBetweenTurns(String projectId, Instant now) {
    try {
      if (desks.requestRoll(projectId, now) > 0) {
        specRoll.roll(projectId, now);
      }
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not roll the front desk of project %s onto its new settings — the spec roll"
              + " retries: %s",
          projectId, e.getMessage());
    }
  }

  @Override
  public void projectDeleting(String projectId) {
    desks.removeDesk(projectId);
  }
}
