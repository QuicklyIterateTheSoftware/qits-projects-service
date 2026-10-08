package eu.wohlben.qits.projects.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The {@link ProjectFrontDeskRemoval} of a deployment with no front desks wired: nothing to remove.
 * {@code @DefaultBean}, so the service's implementation wins the injection simply by existing.
 */
@ApplicationScoped
@DefaultBean
public class NoopProjectFrontDeskRemoval implements ProjectFrontDeskRemoval {

  @Override
  public void projectDeleting(String projectId) {
    // No front desk here.
  }
}
