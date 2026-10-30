package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.entities.control.IdeaAnnouncements;
import eu.wohlben.qits.entities.control.IdeaStatusRecompute;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * <b>Every idea's stored status re-derived at boot</b> (qits-1387). The status is recomputed in the
 * transaction of every write that can move it; this pass repairs whatever moved it some other way
 * (a migration, a manual fix, a path that forgot). Each idea whose stored status differs is fixed
 * and named in one WARN with both statuses, and announced as an {@code IdeaUpdated}.
 *
 * <p>On a virtual thread, as {@link ReservedSlugAudit} runs, so boot never waits on it, and in
 * every launch mode — it reads one table and reaches no network. A failure is a WARN; the next boot
 * tries again.
 */
@ApplicationScoped
public class IdeaStatusReconcile {

  private static final Logger LOG = Logger.getLogger(IdeaStatusReconcile.class);

  /** Who the fixes are recorded as. */
  static final String CHANGED_BY = "idea-status-reconcile";

  @Inject IdeaStatusRecompute recompute;

  @Inject IdeaAnnouncements announcements;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("qits-idea-status-reconcile").start(this::reconcileQuietly);
  }

  void reconcileQuietly() {
    try {
      reconcile();
    } catch (RuntimeException e) {
      LOG.warn("The idea status reconcile could not run — retried on the next boot.", e);
    }
  }

  /** Re-derives every idea, fixes and WARNs each drift, announces the fixes; answers how many. */
  public int reconcile() {
    IdeaStatusRecompute.Reconciled reconciled = recompute.reconcile(CHANGED_BY);
    for (IdeaStatusRecompute.Drift drift : reconciled.drifts()) {
      LOG.warnf(
          "Idea %s (project %s) had status %s stored but its work says %s; fixed to %s.",
          drift.ideaId(), drift.projectId(), drift.stored(), drift.derived(), drift.derived());
    }
    announcements.announce(reconciled.changes());
    if (reconciled.drifts().isEmpty()) {
      LOG.debug("Idea status reconcile: every stored status matches its work.");
    }
    return reconciled.drifts().size();
  }
}
