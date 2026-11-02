package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.entities.control.CampaignStatusRecompute;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * <b>Every campaign's derived status re-derived at boot</b> (qits-650). The status is recomputed in
 * the transaction of every write that can move it; this pass repairs whatever moved it some other
 * way — every campaign set by hand before qits-650, a migration, a manual fix. Each campaign that is
 * neither DONE nor DROPPED and whose stored status differs from its members' floor is fixed as a
 * derived change ({@link CampaignStatusRecompute}: audited as the system, announced as an {@code
 * EntityTransitioned}) and named in one WARN with both statuses.
 *
 * <p>{@link IdeaStatusReconcile}'s shape: on a virtual thread, so boot never waits on it, and in
 * every launch mode — it reads one database and reaches no network. A failure is a WARN; the next
 * boot tries again.
 */
@ApplicationScoped
public class CampaignStatusReconcile {

  private static final Logger LOG = Logger.getLogger(CampaignStatusReconcile.class);

  @Inject CampaignStatusRecompute recompute;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("qits-campaign-status-reconcile").start(this::reconcileQuietly);
  }

  void reconcileQuietly() {
    try {
      reconcile();
    } catch (RuntimeException e) {
      LOG.warn("The campaign status reconcile could not run — retried on the next boot.", e);
    }
  }

  /** Re-derives every campaign still derived, fixes and WARNs each drift, announces; answers how many. */
  public int reconcile() {
    CampaignStatusRecompute.Reconciled reconciled = recompute.reconcile();
    for (CampaignStatusRecompute.Drift drift : reconciled.drifts()) {
      LOG.warnf(
          "Campaign %s (project %s) had status %s stored but its members say %s; fixed to %s.",
          drift.campaignId(), drift.projectId(), drift.stored(), drift.derived(), drift.derived());
    }
    recompute.announce(reconciled.changes());
    if (reconciled.drifts().isEmpty()) {
      LOG.debug("Campaign status reconcile: every campaign's status matches its members.");
    }
    return reconciled.drifts().size();
  }
}
