package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.TransitionAnnouncer;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.AgentEntitySignals;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

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
 * arrange not to. The suite's double {@code extends} this class and overrides {@link #publish}
 * alone, so the agent signals below run in every test exactly as they run here.
 *
 * <h2>It also tells the agents working each moved ticket and epic (qits-617)</h2>
 *
 * <p>The agent sessions working a ticket or an epic are named {@code <status square> <qualified id>
 * <title>}, so every transition has to reach them — and this is the one place every transition
 * passes: {@code WorkEntityService.transition} and {@code EntityTransitionService} (the bulk {@code
 * POST /work/transition} and {@code transition_entities}) both announce through this port after
 * their write commits, and the port is resolved with {@code Instance.get()}, so it has exactly one
 * implementation and this is it. After the publish, {@link
 * AgentEntitySignals#changed(TransitionedEntity)} is called once for each ticket and epic in the
 * batch, with the batch row itself — the post-state the transition wrote, so what is sent is the new
 * title, status and flag (a transition clears the flag). Not a re-read by id: that answers the row
 * as the request's persistence context last saw it, which is before the move. The publish is first and the
 * signals can never fail it: they are each wrapped, and the signals themselves never throw.
 */
@ApplicationScoped
@DefaultBean
public class EntityTransitionAnnouncer implements TransitionAnnouncer {

  private static final Logger LOG = Logger.getLogger(EntityTransitionAnnouncer.class);

  @Inject QitsEventBus bus;

  /** The agents working each moved ticket and epic — see the class javadoc. */
  @Inject AgentEntitySignals agents;

  @Override
  public void onEntitiesTransitioned(List<TransitionedEntity> entities, Instant transitionedAt) {
    try {
      publish(event(entities, transitionedAt));
    } finally {
      signal(entities);
    }
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(EntityTransitioned event) {
    bus.publish(event);
  }

  /**
   * The agents half: one signal per ticket and epic in the batch, in batch order. A feature, a task
   * and a campaign have no session named after them and are skipped here, before any read.
   */
  private void signal(List<TransitionedEntity> entities) {
    for (TransitionedEntity entity : entities) {
      if (entity.archetype() != Archetype.TICKET && entity.archetype() != Archetype.EPIC) {
        continue;
      }
      try {
        agents.changed(entity);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not tell the agents of %s %s it moved", entity.archetype(), entity.id());
      }
    }
  }

  /**
   * The event this adapter publishes for a batch — static and package-visible so the suite's
   * recording double can state what <em>would</em> have crossed the wire without reaching the bus.
   */
  static EntityTransitioned event(List<TransitionedEntity> entities, Instant transitionedAt) {
    return new EntityTransitioned(
        entities.stream().map(EntityTransitionAnnouncer::wire).toList(), transitionedAt);
  }

  /**
   * The control-layer post-state as the wire spells it. The archetype travels as its declared word
   * rather than as an enum of this module's, for the reason every id here is a string: a consumer
   * decodes into a record of its own and must not need this service's types to do it.
   */
  private static EntityTransitioned.Entity wire(TransitionedEntity entity) {
    return new EntityTransitioned.Entity(
        entity.id(),
        entity.projectId(),
        entity.archetype() == null ? null : entity.archetype().name(),
        entity.parent(),
        entity.position(),
        entity.slug(),
        entity.title(),
        entity.status(),
        entity.statusBefore(),
        entity.changedBy(),
        entity.number());
  }
}
