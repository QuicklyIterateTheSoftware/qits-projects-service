package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * The {@link FrontDeskLifecycleChanged} that does nothing but log, shipped until the front-desk
 * feature (qits-767) provides the implementation that acts on the change. {@code @DefaultBean}, so
 * that implementation — or a suite's recording fake — wins the injection point simply by existing.
 */
@ApplicationScoped
@DefaultBean
public class NoopFrontDeskLifecycleChanged implements FrontDeskLifecycleChanged {

  private static final Logger LOG = Logger.getLogger(NoopFrontDeskLifecycleChanged.class);

  @Override
  public void onChanged(String projectId, FrontDeskLifecycle lifecycle) {
    LOG.debugf(
        "Project %s's front desk lifecycle is now %s; nothing acts on it yet.", projectId, lifecycle);
  }
}
