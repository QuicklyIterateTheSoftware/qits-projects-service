package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;

/**
 * Tells the rest of this service that an edit changed an entity's title — once, after the write
 * committed, and only when the title is actually different (qits-617).
 *
 * <p><b>Why a port, and why here.</b> The agent sessions working a ticket or an epic are named
 * {@code <status square> <qualified id> <title>}, so a retitle has to reach them; the code that
 * reaches them ({@code projects/api/AgentEntitySignals}) lives in the {@code service} module, which
 * this module knows nothing of. {@link WorkEntityService#update} is the one layer every edit door
 * shares — the REST patch, the archetype routes, {@code update_ticket} and {@code update_epic} — so
 * the announcement is made there, once, rather than remembered by each door. {@link
 * TransitionAnnouncer} is the precedent for both the shape and the reason.
 *
 * <p><b>Absent is a supported configuration</b>, which is what this module's own suite runs as:
 * injected as {@code Instance<T>}, and with no implementation an edit simply announces nothing.
 *
 * <p><b>Nothing here may throw</b>, and nothing here may be called inside the transaction the edit
 * ran in, for {@link TransitionAnnouncer}'s reason: the write seam is {@code WritePatience}, whose
 * body re-runs on a retry. The caller announces after the hold has returned.
 */
public interface RetitleAnnouncer {

  /**
   * {@code entity}'s title was changed by an edit that has committed.
   *
   * @param entity the row as the edit left it — every archetype; an implementation filters
   */
  void onRetitled(WorkEntity entity);
}
