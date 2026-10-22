package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Rolls the delivery desk's new brief (qits-1517) onto the stored {@code project.delivery} row,
 * where nobody edited the old one.
 *
 * <p><b>Why a boot step and not the seed.</b> {@link AgentSurfaceSeed} is insert-if-absent, so the
 * row it wrote under qits-1440 keeps {@link AgentSurfaceDefaults#PREVIOUS_DELIVERY_DESK_PROMPT}
 * forever, and the desk would never read the brief that ships. Surface rows are global — one per
 * {@code surface_key}, not one per project — so this is one row to look at.
 *
 * <p><b>Exact equality is the whole safety.</b> A stored prompt that is byte for byte the previous
 * shipped text was never edited, and is rewritten to {@link
 * AgentSurfaceDefaults#DELIVERY_DESK_PROMPT}; anything else is an owner's edit and is left alone; a
 * row already at the new text, and no row at all (the seed then writes the new text), are no-ops. So
 * it is idempotent: the boot after the one that rewrote the row finds the new text and writes
 * nothing.
 *
 * <p>Like the seed it never fails boot and never blocks it — a virtual thread after startup, every
 * failure one WARN, retried on the next boot. The suite drives {@link #rollOut()} directly.
 */
@ApplicationScoped
public class DeliveryDeskBriefRollout {

  private static final Logger LOG = Logger.getLogger(DeliveryDeskBriefRollout.class);

  @Inject AgentSurfaceConfigurationService surfaces;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("delivery-desk-brief-rollout").start(this::rollOut);
  }

  /** Rewrite an unedited stored delivery brief to the shipped one. Answers whether it did. */
  public boolean rollOut() {
    String surface = AgentSurfaceDefaults.PROJECT_DELIVERY;
    try {
      boolean rewritten =
          surfaces.replaceSystemPromptIfUnedited(
              surface,
              AgentSurfaceDefaults.PREVIOUS_DELIVERY_DESK_PROMPT,
              AgentSurfaceDefaults.DELIVERY_DESK_PROMPT);
      if (rewritten) {
        LOG.infof(
            "Rewrote agent surface configuration %s's system prompt from the previous shipped brief"
                + " to the current one (qits-1517)",
            surface);
      } else {
        LOG.infof(
            "Left agent surface configuration %s's system prompt as stored: absent, already current"
                + " or edited by its owner",
            surface);
      }
      return rewritten;
    } catch (RuntimeException e) {
      // The row keeps the brief it had, which is what it held before this boot.
      LOG.warnf(e, "Could not roll out the delivery desk's brief to %s; will retry next boot", surface);
      return false;
    }
  }
}
