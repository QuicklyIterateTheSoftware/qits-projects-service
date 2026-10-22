package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.deskhost.FrontDesks;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.function.Function;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Binds an agent's own token to its own container's control socket.
 *
 * <p>{@link AgentControlSocket} admits {@code qits:system} and {@code qits:agent}. A caller that
 * holds {@code qits:agent} and not {@code qits:system} may open only the socket of the project its
 * token names (the {@code project} claim). Any other project is a 403 at the upgrade, before a
 * connection exists. A {@code qits:system} caller is judged as before.
 *
 * <p><b>A front desk's own token is bound to its desk</b> (qits-767). The edge forwards a desk's
 * {@code qits_tok_} as a JWT whose {@code sub} starts with {@link #TOKEN_SUBJECT_PREFIX}; such a
 * bearer must also be the one the project's {@code front_desk.token_subject} names, or it is a 403 —
 * a token of another desk, or one a DELETE revoked and the edge still caches, opens nothing.
 */
@ApplicationScoped
public class AgentControlSocketAccess implements HttpUpgradeCheck {

  /** The {@code sub} prefix of a bearer the edge minted for a {@code qits_tok_}. */
  public static final String TOKEN_SUBJECT_PREFIX = "tok-";

  /** The desks' bound subjects; absent when this check is built by hand. */
  @Inject Instance<FrontDesks> desks;

  @Override
  public boolean appliesTo(String endpointId) {
    return AgentControlSocket.ENDPOINT_ID.equals(endpointId);
  }

  @Override
  public Uni<CheckResult> perform(HttpUpgradeContext context) {
    String projectId = context.pathParam("projectId");
    return context
        .securityIdentity()
        .chain(
            identity -> {
              if (!isTokenSubject(subjectOf(identity))) {
                return Uni.createFrom().item(decide(identity, projectId, p -> null));
              }
              // The bound subject may need a database read: off the event loop.
              return Uni.createFrom()
                  .item(() -> decide(identity, projectId, this::boundSubject))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
            });
  }

  private String boundSubject(String projectId) {
    return desks != null && desks.isResolvable() ? desks.get().tokenSubject(projectId) : null;
  }

  static CheckResult decide(SecurityIdentity identity, String projectId) {
    return decide(identity, projectId, p -> null);
  }

  static CheckResult decide(
      SecurityIdentity identity, String projectId, Function<String, String> boundSubject) {
    if (AgentAccess.isBoundAgent(identity, AgentAccess.SYSTEM_ROLE)
        && !AgentAccess.coversProject(identity, projectId)) {
      return CheckResult.rejectUpgradeSync(403);
    }
    String subject = subjectOf(identity);
    if (isTokenSubject(subject) && !subject.equals(boundSubject.apply(projectId))) {
      return CheckResult.rejectUpgradeSync(403);
    }
    return CheckResult.permitUpgradeSync();
  }

  /** Whether {@code subject} is a {@code qits_tok_} bearer's. */
  public static boolean isTokenSubject(String subject) {
    return subject != null && subject.startsWith(TOKEN_SUBJECT_PREFIX);
  }

  /** The token's {@code sub}, or null for a caller with no token. */
  public static String subjectOf(SecurityIdentity identity) {
    return identity != null && identity.getPrincipal() instanceof JsonWebToken jwt
        ? jwt.getSubject()
        : null;
  }
}
