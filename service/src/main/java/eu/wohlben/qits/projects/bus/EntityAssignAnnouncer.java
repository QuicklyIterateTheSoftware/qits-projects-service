package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.api.CallerDisplayName;
import eu.wohlben.qits.entities.api.EntitiesPrincipal;
import eu.wohlben.qits.entities.control.AssigneeAnnouncer;
import eu.wohlben.qits.entities.control.AssigneeChange;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.arc.Arc;
import io.quarkus.arc.DefaultBean;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Publishes {@link EntityAssigned} for the {@code entities} port {@link AssigneeAnnouncer}
 * (qits-2518). The port is told by {@code AssigneeWrites}, the one writer of the assignee, after the
 * transaction committed — so the create, the PATCH, the PUT-shaped transition and the dispatch are
 * all covered without any of them announcing.
 *
 * <p>{@code changedByName} is the caller's display name ({@link CallerDisplayName}) when the
 * request on this thread is by {@code changedBy} — the after-completion runs on the writer's thread,
 * inside its request — and {@code changedBy} otherwise (an in-service writer, or no request at all).
 *
 * <p>{@code @DefaultBean}, like every announcer here: the suite's {@code
 * RecordingEntityAssignAnnouncer} overrides {@link #publish} alone, so no test reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class EntityAssignAnnouncer implements AssigneeAnnouncer {

  @Inject QitsEventBus bus;

  @Inject Instance<SecurityIdentity> identities;

  @Override
  public void onAssigneeChanged(AssigneeChange change) {
    publish(event(change, nameOf(change.changedBy())));
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(EntityAssigned event) {
    bus.publish(event);
  }

  static EntityAssigned event(AssigneeChange change, String changedByName) {
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
        changedByName,
        change.changedAt());
  }

  /** The request's display name when the request is by {@code changedBy}; else {@code changedBy}. */
  private String nameOf(String changedBy) {
    try {
      if (changedBy != null
          && Arc.container().requestContext().isActive()
          && identities.isResolvable()) {
        SecurityIdentity identity = identities.get();
        if (changedBy.equals(EntitiesPrincipal.changedBy(identity))) {
          return CallerDisplayName.of(identity);
        }
      }
    } catch (RuntimeException e) {
      // No identity to read: the principal name is still a name.
    }
    return changedBy;
  }
}
