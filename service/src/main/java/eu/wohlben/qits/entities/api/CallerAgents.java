package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>The caller's agent, named by its work</b> (qits-1448, qits-2518): a bearer carrying an {@code
 * agent} claim ({@link EntitiesPrincipal#agentOf}) is the agent of the work item {@link
 * WorkspaceAgentLookup} says it works on, and a reader knows it as {@code "<qualified id>'s
 * agent"} — never by its token subject. The comment's author stamping ({@code authorWorkId}, the
 * turn's {@code who}) and the assignment's {@code changedByName} both resolve it here.
 *
 * <p>Nothing here throws: an absent port, an unknown agent and a lookup that broke its contract all
 * answer empty, and the caller then names the principal as it did before.
 */
@ApplicationScoped
public class CallerAgents {

  private static final Logger LOG = Logger.getLogger(CallerAgents.class);

  @Inject Instance<WorkspaceAgentLookup> agentLookup;

  @Inject EntityCatalogService catalog;

  @Inject QualifiedEntityIds qualifiedIds;

  /** {@code "<qualifiedId>'s agent"}: how a reader names the agent working on that item. */
  public static String agentName(String qualifiedId) {
    return qualifiedId + "'s agent";
  }

  /** The row id of the work item the caller's agent works on; empty for anything but an agent. */
  public Optional<String> workOf(SecurityIdentity identity) {
    Optional<String> agentId = EntitiesPrincipal.agentOf(identity);
    if (agentId.isEmpty() || !agentLookup.isResolvable()) {
      return Optional.empty();
    }
    try {
      Optional<String> work = agentLookup.get().workOf(agentId.get());
      return work == null ? Optional.empty() : work;
    } catch (RuntimeException e) {
      // It promises never to throw; a throw is a bug in it and must not cost the caller's write.
      LOG.warnf(e, "Could not find the work item of agent %s", agentId.get());
      return Optional.empty();
    }
  }

  /** The qualified id of the work item the caller's agent works on: one lookup and one read. */
  public Optional<String> qualifiedWorkOf(SecurityIdentity identity) {
    return workOf(identity).flatMap(this::qualifiedIdOf);
  }

  /**
   * How a reader names the caller: {@code "<qualified id>'s agent"} for an agent whose work item
   * resolves, otherwise {@link CallerDisplayName}.
   */
  public String displayName(SecurityIdentity identity) {
    return qualifiedWorkOf(identity)
        .map(CallerAgents::agentName)
        .orElseGet(() -> CallerDisplayName.of(identity));
  }

  /**
   * The row is read on the epics datasource, inside whatever write the caller is in; the slug on
   * {@code domain}'s, in a transaction of its own — the two are local and non-XA, so one transaction
   * may enlist only one of them ({@code ProjectSlugEntityQualifier}'s rule).
   */
  private Optional<String> qualifiedIdOf(String workId) {
    try {
      TransitionedEntity row = catalog.byIds(List.of(workId)).get(workId);
      if (row == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(
          QuarkusTransaction.requiringNew().call(() -> qualifiedIds.qualify(row).qualifiedId()));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not qualify work item %s", workId);
      return Optional.empty();
    }
  }
}
