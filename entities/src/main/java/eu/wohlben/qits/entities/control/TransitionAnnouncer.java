package eu.wohlben.qits.entities.control;

import java.time.Instant;
import java.util.List;

/**
 * Tells the rest of the platform that a set of entities moved, all at once.
 *
 * <p><b>A port, because {@code entities} publishes nothing and knows of no bus.</b> That module
 * depends on {@code domain} nowhere and on no auth module, and the only thing qits-eventstream is in
 * its pom for is the causation <em>persistence</em> vocabulary ({@code CausedRow}, {@code
 * CausationStamp}, {@code @Uncaused}) — three jakarta-persistence-shaped types with no publish, no
 * subscribe and no wire in them. The standing rule is that control flow over the bus lives in {@code
 * service/…/bus/} and nowhere else, so the announcement leaves through a seam declared here and
 * implemented there. This mirrors {@code projects/control/RepositoryAnnouncer} exactly, and for its
 * reason.
 *
 * <p><b>Absent is a supported configuration.</b> With no implementation a transition still
 * transitions and simply announces nothing, which is what this module's own suite runs as. Injected
 * as an {@code Instance<T>} for that reason, and the shipped adapter is {@code @DefaultBean} so a
 * recording test double wins the injection point simply by existing.
 *
 * <p><b>Nothing here may throw</b>, and nothing here may be called inside the transaction the write
 * ran in. The second half is not advice: the write seam is {@code WritePatience}, whose body
 * <em>re-runs</em> on a retry, so an announcement made inside it would be made twice. The caller
 * announces after the transaction has committed.
 *
 * @see #onEntitiesTransitioned
 */
public interface TransitionAnnouncer {

  /**
   * A batch of entities is now in the state described. <b>One call per transition, never one per
   * entity</b> — the whole point of the operation is that these entities reached a state no
   * ordering of single writes could have reached legally, so telling the platform about them one at
   * a time would describe a sequence of illegal trees that never existed.
   *
   * @param entities every entity the transition wrote, in the order the caller stated them, each as
   *     its whole post-state
   * @param transitionedAt when the transition committed — the event's {@code occurredAt}, not the
   *     moment the announcement is made
   */
  void onEntitiesTransitioned(List<TransitionedEntity> entities, Instant transitionedAt);
}
