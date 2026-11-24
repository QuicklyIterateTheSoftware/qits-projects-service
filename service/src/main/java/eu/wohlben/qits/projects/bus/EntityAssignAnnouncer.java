package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.AssigneeAnnouncer;
import eu.wohlben.qits.entities.control.AssigneeChange;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Publishes {@link EntityAssigned} for the {@code entities} port {@link AssigneeAnnouncer}
 * (qits-2518). The port is told by {@code AssigneeWrites}, the one writer of the assignee, after the
 * transaction committed — so the create, the PATCH, the PUT-shaped transition and the dispatch are
 * all covered without any of them announcing.
 *
 * <p>{@code changedByName} is the one {@code AssigneeWrites} read inside the write through {@code
 * AssignerNames} ({@code entities/api/CallerAssignerNames}): {@code "<qualified id>'s agent"} for a
 * workspace agent's bearer whose work item resolves, as a comment's author reads (qits-1448); the
 * caller's display name for any other request by {@code changedBy}; {@code changedBy} otherwise.
 *
 * <p>{@code @DefaultBean}, like every announcer here: the suite's {@code
 * RecordingEntityAssignAnnouncer} overrides {@link #publish} alone, so no test reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class EntityAssignAnnouncer implements AssigneeAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onAssigneeChanged(AssigneeChange change) {
    publish(event(change));
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(EntityAssigned event) {
    bus.publish(event);
  }

  static EntityAssigned event(AssigneeChange change) {
    return new EntityAssigned(
        null,
        change.entityId(),
        change.projectId(),
        change.archetype() == null ? null : change.archetype().name(),
        change.number(),
        change.qualifiedId(),
        change.title(),
        change.status(),
        change.assignee(),
        change.previousAssignee(),
        change.claimed(),
        change.changedBy(),
        change.changedByName() == null ? change.changedBy() : change.changedByName(),
        change.changedAt());
  }
}
