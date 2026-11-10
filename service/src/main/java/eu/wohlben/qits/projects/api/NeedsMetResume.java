package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.NeedsMet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>A move that meets an item's last need resumes the run its needs held</b> (qits-2026). The
 * {@code entities} module fires {@link NeedsMet} on the CDI bus inside the read of who waits on the
 * moved items ({@code NeedService.announceMet}, after the transition committed); {@link #onMet},
 * {@code during = AFTER_SUCCESS} like {@code bus/WorkNeedAnnouncer} beside it, hands every waiting
 * item whose needs are now all met to {@link PhaseAdvance#resumeHeld} once that read has committed —
 * which does nothing unless the item is a pre-approved FLOW run held at REFINED, and otherwise
 * schedules it on its pre-approval and hands implement on, as the refine landing would have.
 *
 * <p><b>It runs on the committing thread, synchronously</b>, the way {@code
 * control/GitHostNameSync} observes its own event: the move that met the need is already recorded
 * and answered for, the resume suspends whatever transaction is still associated with the thread
 * and opens its own, and nothing waits on it but the request that made the move — which waits on
 * that move's own {@code PhaseAdvance} hand-off just the same. {@code allMet} is only the filter:
 * the resume re-reads the needs itself, so an item that gained a need meanwhile stays held.
 *
 * <p>The name it stamps is {@link PhaseAdvance#PLATFORM}: the event names no mover, and the
 * scheduling itself is made as whoever pre-approved it. <b>Never throws into the caller.</b>
 */
@ApplicationScoped
public class NeedsMetResume {

  private static final Logger LOG = Logger.getLogger(NeedsMetResume.class);

  @Inject PhaseAdvance advance;

  /** The needs a transition met, once the read that found them has committed. */
  void onMet(@Observes(during = TransactionPhase.AFTER_SUCCESS) NeedsMet met) {
    resume(met);
  }

  /** Resumes each item {@code met} left with no unmet need, once per item; never throws. */
  public void resume(NeedsMet met) {
    if (met == null) {
      return;
    }
    Set<String> released = new LinkedHashSet<>();
    for (NeedsMet.Met need : met.met()) {
      if (need.allMet() && need.entityId() != null) {
        released.add(need.entityId());
      }
    }
    for (String entityId : released) {
      try {
        advance.resumeHeld(entityId, PhaseAdvance.PLATFORM);
      } catch (RuntimeException e) {
        // resumeHeld says it never throws; a throw is a bug in it and must not reach the move.
        LOG.warnf(e, "Could not resume %s, whose needs were all met", entityId);
      }
    }
  }
}
