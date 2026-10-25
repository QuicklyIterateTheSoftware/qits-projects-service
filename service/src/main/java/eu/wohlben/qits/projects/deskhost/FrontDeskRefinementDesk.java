package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.refinementhost.RefinementDesk;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * {@link RefinementDesk} over {@link FrontDesks#status}: the desk state a session room's answer
 * carries (epic qits-770). Never throws — a project with no slug, or a read that fails, is empty.
 */
@ApplicationScoped
public class FrontDeskRefinementDesk implements RefinementDesk {

  private static final Logger LOG = Logger.getLogger(FrontDeskRefinementDesk.class);

  @Inject FrontDesks desks;

  @Override
  public Optional<DeskStatus> of(String projectId) {
    if (projectId == null) {
      return Optional.empty();
    }
    try {
      FrontDeskState state = desks.status(projectId);
      return Optional.of(
          new DeskStatus(
              state.runtimeStatus() == null ? null : state.runtimeStatus().name(),
              state.failureDetail()));
    } catch (RuntimeException e) {
      LOG.debugf("Could not read the front desk of project %s: %s", projectId, e.getMessage());
      return Optional.empty();
    }
  }
}
