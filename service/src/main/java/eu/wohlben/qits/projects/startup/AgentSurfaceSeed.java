package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Writes the eight shipped surface configurations, once, for a store that has never held them.
 *
 * <p><b>Why a boot seed rather than an {@code insert} in V16.</b> The vocabulary has to stay open —
 * adding a ninth surface must be a constant in {@link AgentSurfaceDefaults} and not a migration —
 * and DDL cannot follow a constant. A seed that reads the same constants the fallback reads is what
 * keeps "what a surface ships as" a single answer; a DDL seed would have made the constants and the
 * rows two copies free to disagree, and the whole safety of this feature is that they do not.
 *
 * <p><b>Insert-if-absent, never upsert.</b> An operator's edit must survive every boot after it, so
 * this writes only surfaces the store holds no row for. That also makes it self-healing for a
 * surface added later: the boot after the constant lands seeds it, and nothing else is touched.
 *
 * <p><b>It never fails boot and never blocks it.</b> A missing seed row is not a reason to refuse to
 * serve — a surface with no row reads as its shipped default anyway, which is the same answer the
 * row would have given — so this runs after startup on a virtual thread and swallows everything, the
 * arrangement {@link ProjectAnnounceBackfill}, {@code StartupSelfSeed} and {@link ReservedSlugAudit}
 * already make. One surface's failure costs that surface and no other, and the next boot asks again.
 *
 * <p>The suite drives {@link #seed()} directly rather than through the event, the same way its
 * neighbours here are driven.
 */
@ApplicationScoped
public class AgentSurfaceSeed {

  private static final Logger LOG = Logger.getLogger(AgentSurfaceSeed.class);

  @Inject AgentSurfaceConfigurationService surfaces;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("agent-surface-seed").start(this::seed);
  }

  /** Seed every shipped surface the store does not hold. Answers how many rows were written. */
  public int seed() {
    int written = 0;
    for (String surface : AgentSurfaceDefaults.SURFACES) {
      try {
        if (surfaces.seedIfAbsent(surface)) {
          written++;
          LOG.infof("Seeded agent surface configuration %s from its shipped default", surface);
        }
      } catch (RuntimeException e) {
        // The surface still reads as its shipped default, which is what the row would have said.
        LOG.warnf(e, "Could not seed agent surface configuration %s; will retry next boot", surface);
      }
    }
    return written;
  }
}
