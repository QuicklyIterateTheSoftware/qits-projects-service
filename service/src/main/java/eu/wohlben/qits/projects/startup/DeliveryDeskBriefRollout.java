package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Rolls the delivery desk's new brief (qits-1755, before it qits-1748, qits-1714, qits-1679, qits-1621, qits-1618, qits-1604, qits-1597, qits-1601,
 * qits-1596, qits-1568, qits-1571, qits-1569, qits-1560 and qits-1517) onto the stored {@code
 * project.delivery} row, where nobody edited an old one.
 *
 * <p><b>Why a boot step and not the seed.</b> {@link AgentSurfaceSeed} is insert-if-absent, so the
 * row it wrote under qits-1440 keeps {@link AgentSurfaceDefaults#PREVIOUS_DELIVERY_DESK_PROMPT}
 * forever, and the desk would never read the brief that ships. Surface rows are global — one per
 * {@code surface_key}, not one per project — so this is one row to look at.
 *
 * <p><b>Every earlier shipped text rolls, not only the last one</b> ({@link #PREVIOUS_BRIEFS}): a
 * row the qits-1517 rollout never reached still holds qits-1440's brief, one it did reach holds
 * qits-1517's ({@link AgentSurfaceDefaults#QITS_1517_DELIVERY_DESK_PROMPT}), one the qits-1560
 * rollout reached holds qits-1560's ({@link AgentSurfaceDefaults#QITS_1560_DELIVERY_DESK_PROMPT}),
 * one the qits-1569 rollout reached holds qits-1569's ({@link
 * AgentSurfaceDefaults#QITS_1569_DELIVERY_DESK_PROMPT}), one the qits-1571 rollout reached holds
 * qits-1571's ({@link AgentSurfaceDefaults#QITS_1571_DELIVERY_DESK_PROMPT}), one the qits-1568
 * rollout reached holds qits-1568's ({@link AgentSurfaceDefaults#QITS_1568_DELIVERY_DESK_PROMPT}),
 * one the qits-1596 rollout reached holds qits-1596's ({@link
 * AgentSurfaceDefaults#QITS_1596_DELIVERY_DESK_PROMPT}), one the qits-1601 rollout reached holds
 * qits-1601's ({@link AgentSurfaceDefaults#QITS_1601_DELIVERY_DESK_PROMPT}), one the qits-1597
 * rollout reached holds qits-1597's ({@link AgentSurfaceDefaults#QITS_1597_DELIVERY_DESK_PROMPT}),
 * one the qits-1604 rollout reached holds qits-1604's ({@link
 * AgentSurfaceDefaults#QITS_1604_DELIVERY_DESK_PROMPT}), and one the qits-1618 rollout reached
 * holds qits-1618's ({@link AgentSurfaceDefaults#QITS_1618_DELIVERY_DESK_PROMPT}), and one the
 * qits-1621 rollout reached holds qits-1621's ({@link
 * AgentSurfaceDefaults#QITS_1621_DELIVERY_DESK_PROMPT}), one the qits-1679 rollout reached
 * holds qits-1679's ({@link AgentSurfaceDefaults#QITS_1679_DELIVERY_DESK_PROMPT}), one the
 * qits-1714 rollout reached holds the text qits-1724 found shipping ({@link
 * AgentSurfaceDefaults#QITS_1724_DELIVERY_DESK_PROMPT}), one the qits-1748 rollout reached
 * holds qits-1748's ({@link AgentSurfaceDefaults#QITS_1748_DELIVERY_DESK_PROMPT}), and one the
 * qits-1755 rollout reached holds the text qits-1755 found shipping ({@link
 * AgentSurfaceDefaults#QITS_1755_DELIVERY_DESK_PROMPT}).
 * Each is compared in turn, and the first that matches is rewritten straight to the current text.
 *
 * <p><b>Exact equality is the whole safety.</b> A stored prompt that is byte for byte one previous
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

  /** The briefs that shipped before the current one, oldest first; each rolls to the current. */
  static final List<String> PREVIOUS_BRIEFS =
      List.of(
          AgentSurfaceDefaults.PREVIOUS_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1517_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1560_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1569_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1571_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1568_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1596_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1601_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1597_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1604_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1618_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1621_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1679_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1724_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1748_DELIVERY_DESK_PROMPT,
          AgentSurfaceDefaults.QITS_1755_DELIVERY_DESK_PROMPT);

  @Inject AgentSurfaceConfigurationService surfaces;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("delivery-desk-brief-rollout").start(this::rollOut);
  }

  /** Rewrite an unedited stored delivery brief to the shipped one. Answers whether it did. */
  public boolean rollOut() {
    String surface = AgentSurfaceDefaults.PROJECT_DELIVERY;
    try {
      boolean rewritten = false;
      for (String previous : PREVIOUS_BRIEFS) {
        if (surfaces.replaceSystemPromptIfUnedited(
            surface, previous, AgentSurfaceDefaults.DELIVERY_DESK_PROMPT)) {
          rewritten = true;
          break;
        }
      }
      if (rewritten) {
        LOG.infof(
            "Rewrote agent surface configuration %s's system prompt from a previous shipped brief"
                + " to the current one (qits-1755)",
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
