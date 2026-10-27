package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.DevelopmentGuideService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Seeds the development guide's rows (qits-1679) and rolls unedited ones forward to the shipped
 * text, the way {@link AgentSurfaceSeed} and {@link DeliveryDeskBriefRollout} do for the surfaces.
 * Never fails or blocks boot: a part with no row reads as its shipped text anyway.
 */
@ApplicationScoped
public class DevelopmentGuideSeed {

  private static final Logger LOG = Logger.getLogger(DevelopmentGuideSeed.class);

  @Inject DevelopmentGuideService guide;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("development-guide-seed").start(this::run);
  }

  /** Seed, then roll forward. The suite calls this directly. */
  public void run() {
    try {
      int seeded = guide.seed();
      int rolled = guide.rollForward();
      LOG.infof("Development guide: seeded %d part(s), rolled %d forward", seeded, rolled);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not seed the development guide; will retry next boot");
    }
  }
}
