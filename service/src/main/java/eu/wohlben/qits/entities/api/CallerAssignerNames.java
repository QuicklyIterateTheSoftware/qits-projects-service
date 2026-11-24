package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.AssignerNames;
import io.quarkus.arc.Arc;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The shipped {@link AssignerNames} (qits-2518): when the request on this thread is by {@code
 * changedBy}, the caller as {@link CallerAgents#displayName} names it — {@code "qits-2518's agent"}
 * for a workspace agent's bearer, as a comment's author reads, else its display name. Otherwise
 * (an in-service writer, no request at all) nothing better than {@code changedBy}.
 *
 * <p>Asked inside the write ({@code AssigneeWrites}), where the request and the database are.
 */
@ApplicationScoped
public class CallerAssignerNames implements AssignerNames {

  @Inject Instance<SecurityIdentity> identities;

  @Inject CallerAgents agents;

  @Override
  public String nameOf(String changedBy) {
    try {
      if (changedBy != null
          && Arc.container().requestContext().isActive()
          && identities.isResolvable()) {
        SecurityIdentity identity = identities.get();
        if (changedBy.equals(EntitiesPrincipal.changedBy(identity))) {
          return agents.displayName(identity);
        }
      }
    } catch (RuntimeException e) {
      // No identity to read: the principal name is still a name.
    }
    return null;
  }
}
