package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Binds an agent's own token to its own container's control socket.
 *
 * <p>{@link AgentControlSocket} admits {@code qits:system} and {@code qits:agent}. A caller that
 * holds {@code qits:agent} and not {@code qits:system} may open only the socket of the project its
 * token names (the {@code project} claim). Any other project is a 403 at the upgrade, before a
 * connection exists. A {@code qits:system} caller is judged as before.
 */
@ApplicationScoped
public class AgentControlSocketAccess implements HttpUpgradeCheck {

  @Override
  public boolean appliesTo(String endpointId) {
    return AgentControlSocket.ENDPOINT_ID.equals(endpointId);
  }

  @Override
  public Uni<CheckResult> perform(HttpUpgradeContext context) {
    String projectId = context.pathParam("projectId");
    return context.securityIdentity().map(identity -> decide(identity, projectId));
  }

  static CheckResult decide(SecurityIdentity identity, String projectId) {
    if (!AgentAccess.isBoundAgent(identity, AgentAccess.SYSTEM_ROLE)) {
      return CheckResult.permitUpgradeSync();
    }
    return AgentAccess.coversProject(identity, projectId)
        ? CheckResult.permitUpgradeSync()
        : CheckResult.rejectUpgradeSync(403);
  }
}
