package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.BlockAnnouncer;
import eu.wohlben.qits.entities.control.BlockChange;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Publishes {@link EntityBlockChanged} for the {@code entities} port {@link BlockAnnouncer}
 * (qits-2674), {@link EntityAssignAnnouncer}'s shape. The port is told by {@code BlockWrites} after
 * the transaction committed — so the block door ({@code EntityBlocks.apply}, through {@code
 * WorkEntityService.setBlocked}) and a transition's clear, on either door that moves a status, are
 * all covered without any of them announcing.
 *
 * <p>{@code @DefaultBean}, like every announcer here: the suite's {@code
 * RecordingEntityBlockAnnouncer} overrides {@link #publish} alone, so no test reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class EntityBlockAnnouncer implements BlockAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onBlockChanged(BlockChange change) {
    publish(event(change));
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(EntityBlockChanged event) {
    bus.publish(event);
  }

  static EntityBlockChanged event(BlockChange change) {
    return new EntityBlockChanged(
        null,
        change.entityId(),
        change.projectId(),
        change.archetype() == null ? null : change.archetype().name(),
        change.number(),
        change.qualifiedId(),
        change.status(),
        change.blocked(),
        change.reason(),
        change.changedBy(),
        change.changedAt());
  }
}
