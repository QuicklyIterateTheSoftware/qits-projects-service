package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Writes the shipped surface configurations in {@link AgentSurfaceDefaults#SURFACES}, once, for a
 * store that has never held them. {@code project.epics} and {@code project.tickets} — the two desks
 * {@link AgentSurfaceDefaults#PROJECT_WORK} replaced — are retired outright (qits-404) and are not
 * seeded: nothing launches with them, no constant names them any more, and their rows are gone.
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
 * <p><b>It runs after V30, never before it, which is what keeps the {@code project.work} copy from
 * being pre-empted.</b> Flyway's {@code migrate-at-start} runs while the datasource is being set up,
 * before {@link StartupEvent} fires, and this seed is started from that event — so on the boot that
 * first carries {@code project.work} in the vocabulary, V30 has already copied the stored {@code
 * project.epics} row onto it, and {@link AgentSurfaceConfigurationService#seedIfAbsent} finds the
 * row and writes nothing. Had the seed run first it would have written the shipped default and
 * V30's {@code where not exists} would have (correctly, for idempotence) declined to overwrite it,
 * silently dropping an operator's epics-desk edits. On a fresh estate V30 finds no {@code
 * project.epics} row to copy and this seed writes {@code project.work} from its shipped default,
 * which is the epics desk's shipped default: the same answer either way.
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
