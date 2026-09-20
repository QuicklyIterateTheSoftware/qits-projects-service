package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.TransitionAnnouncer;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * Turns a multi-entity transition into the platform's {@link EntityTransitioned} event and hands it
 * to the bus.
 *
 * <p>It lives in {@code service/…/bus/} because that package is the whole of this service's bus
 * seams, and the {@code entities} module knows nothing of a bus at all — it depends on {@code domain}
 * nowhere and publishes nothing, so the seam it implements is {@code
 * entities/control/TransitionAnnouncer} and zero implementations is a supported configuration (which is
 * what the {@code entities} module's own suite runs as). The shape is {@code
 * RepositoryRenamedAnnouncer}'s, applied again.
 *
 * <p><b>The cause is left to the bus.</b> {@code QitsEventBus.publish(event)} resolves the parent
 * from {@code CausationScope}, which the REST filter has already restored from the request's {@code
 * X-Qits-Causation-Id} — and a transition is made on the request thread, with no hop in between.
 *
 * <p><b>It blocks, briefly, and never throws.</b> {@code publish} attempts the PUT inline and gives
 * up after the publish timeout, after which the outbox owns delivery — so a qits-events that is down
 * costs a transition a few seconds once and never fails it.
 *
 * <p><b>{@code @DefaultBean}</b>, the posture every adapter here takes: a recording test double then
 * wins the port's injection point simply by existing, so no test reaches the bus and none has to
 * arrange not to.
 */
@ApplicationScoped
@DefaultBean
public class EntityTransitionAnnouncer implements TransitionAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onEntitiesTransitioned(List<TransitionedEntity> entities, Instant transitionedAt) {
    bus.publish(
        new EntityTransitioned(entities.stream().map(this::wire).toList(), transitionedAt));
  }

  /**
   * The control-layer post-state as the wire spells it. The archetype travels as its declared word
   * rather than as an enum of this module's, for the reason every id here is a string: a consumer
   * decodes into a record of its own and must not need this service's types to do it.
   */
  private EntityTransitioned.Entity wire(TransitionedEntity entity) {
    return new EntityTransitioned.Entity(
        entity.id(),
        entity.projectId(),
        entity.archetype() == null ? null : entity.archetype().name(),
        entity.parent(),
        entity.position(),
        entity.slug(),
        entity.title(),
        entity.status());
  }
}
