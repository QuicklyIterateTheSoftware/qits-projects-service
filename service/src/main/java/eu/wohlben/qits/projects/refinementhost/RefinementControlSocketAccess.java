package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Binds an agent's own token to its own refinement container's control socket.
 *
 * <p>{@link RefinementControlSocket} admits {@code qits:system} and {@code qits:agent}. A caller
 * that holds {@code qits:agent} and not {@code qits:system} may open only the socket of the
 * refinement whose container was commissioned its client: the token's {@code sub} must be the
 * row's {@code commissionedClientId}. Anything else — another refinement, an unknown row, a row with
 * no commission — is a 403 at the upgrade. A {@code qits:system} caller is judged as before.
 */
@ApplicationScoped
public class RefinementControlSocketAccess implements HttpUpgradeCheck {

  @Inject RefinementRepository store;

  @Override
  public boolean appliesTo(String endpointId) {
    return RefinementControlSocket.ENDPOINT_ID.equals(endpointId);
  }

  @Override
  public Uni<CheckResult> perform(HttpUpgradeContext context) {
    String refinementId = context.pathParam("refinementId");
    return context
        .securityIdentity()
        .flatMap(
            identity -> {
              if (!AgentAccess.isBoundAgent(identity, AgentAccess.SYSTEM_ROLE)) {
                return CheckResult.permitUpgrade();
              }
              // The commission is a row in this database, so it is read off the IO thread.
              return Uni.createFrom()
                  .item(() -> commissionedClientOf(refinementId))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                  .map(client -> decide(identity, client));
            });
  }

  static CheckResult decide(SecurityIdentity identity, String commissionedClientId) {
    return AgentAccess.isClient(identity, commissionedClientId)
        ? CheckResult.permitUpgradeSync()
        : CheckResult.rejectUpgradeSync(403);
  }

  /** The client commissioned for this refinement's container, or null for no row or no client. */
  String commissionedClientOf(String refinementId) {
    long id;
    try {
      id = Long.parseLong(refinementId);
    } catch (NumberFormatException e) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByIdOptional(id).map(row -> row.commissionedClientId).orElse(null));
  }
}
