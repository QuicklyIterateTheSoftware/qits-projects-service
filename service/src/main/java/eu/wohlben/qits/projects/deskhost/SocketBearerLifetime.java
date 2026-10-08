package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the front-desk runner socket open past the bearer it was opened with (qits-767).
 *
 * <p><b>Copied from qits-workspaces-service's {@code runnerhost/SocketBearerLifetime}</b> (itself
 * qits-ci-service's, written for qits-545); this service had no equivalent — its agent and
 * refinement control sockets carry none. What it undoes: quarkus-oidc stamps every identity it
 * builds from a bearer with {@code quarkus.identity.expire-time} — the token's {@code exp} — and
 * websockets-next arms a timer on that attribute for every connection it admits and closes it
 * "Authentication expired" when it fires; there is no key to turn that off. The edge forwards a
 * runner's dial with a JWT it minted for 300 s, so without this every runner would lose its socket
 * five minutes after each connect.
 *
 * <p><b>Why dropping the attribute is right rather than refreshing the token.</b> The socket is
 * authenticated at the upgrade and nowhere after it, by design: a runner is cut off by its deletion
 * ({@code DeskRunnerRegistry.deleted} — a {@code retire} and a 1008 close, whatever its bearer says)
 * and refused at its next dial by the missing row. So the attribute is removed for {@link
 * #SOCKET_PATHS} and the {@link #ROLES} that dial them only, and every other request keeps its
 * expiry exactly as quarkus-oidc stated it. The front-desk task extends both sets to the daemon
 * control paths it moves under {@code /projects/daemon/*}.
 */
@ApplicationScoped
public class SocketBearerLifetime implements SecurityIdentityAugmentor {

  /** quarkus-oidc's {@code OidcUtils.QUARKUS_IDENTITY_EXPIRE_TIME}, which websockets-next reads. */
  static final String EXPIRE_TIME = "quarkus.identity.expire-time";

  /** The upgrades whose connections outlive their bearer. */
  static final Set<String> SOCKET_PATHS = Set.of(DeskRunnerProtocol.SOCKET_PATH);

  /** The roles whose connections on {@link #SOCKET_PATHS} outlive their bearer. */
  static final Set<String> ROLES = Set.of(DeskRunnerSocket.RUNNER_ROLE);

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity, AuthenticationRequestContext context) {
    return Uni.createFrom().item(identity);
  }

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity,
      AuthenticationRequestContext context,
      Map<String, Object> attributes) {
    RoutingContext request = HttpSecurityUtils.getRoutingContextAttribute(attributes);
    String path = request == null ? null : request.normalizedPath();
    return Uni.createFrom().item(forPath(identity, path));
  }

  /** The identity as a connection on {@code path} should hold it. */
  static SecurityIdentity forPath(SecurityIdentity identity, String path) {
    if (path == null
        || !SOCKET_PATHS.contains(path)
        || identity.isAnonymous()
        || identity.getAttribute(EXPIRE_TIME) == null
        || ROLES.stream().noneMatch(identity::hasRole)) {
      return identity;
    }
    Map<String, Object> kept = new HashMap<>(identity.getAttributes());
    kept.remove(EXPIRE_TIME);
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(identity.getPrincipal())
        .addRoles(identity.getRoles())
        .addCredentials(identity.getCredentials())
        .addPermissions(identity.getPermissions())
        .addAttributes(kept)
        .addPermissionChecker(identity::checkPermission)
        .build();
  }
}
