package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentControlSocketAccess;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
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
 * Keeps a front desk's sockets open past the bearer they were opened with (qits-767).
 *
 * <p><b>Copied from qits-workspaces-service's {@code runnerhost/SocketBearerLifetime}</b> (itself
 * qits-ci-service's, written for qits-545). What it undoes: quarkus-oidc stamps every identity it
 * builds from a bearer with {@code quarkus.identity.expire-time} — the token's {@code exp} — and
 * websockets-next arms a timer on that attribute for every connection it admits and closes it
 * "Authentication expired" when it fires; there is no key to turn that off.
 *
 * <p><b>The front desks' sockets</b>: a desk dials its control socket {@code
 * /projects/daemon/<projectId>} and its dial-backs {@code /projects/daemon/stream/<nonce>} through
 * the edge with its {@code qits_tok_}, which arrives as a 300 s JWT with a {@code tok-} subject. The
 * expiry is dropped on exactly those paths, and only for a {@code tok-} subject; a desk is cut off
 * by its DELETE (lease ended, token revoked) and refused at its next dial by the binding to {@code
 * front_desk.token_subject}. Every other request keeps its expiry exactly as quarkus-oidc stated it.
 *
 * <p>The front-desk runner socket that also took this treatment went with the runners (epic
 * qits-1481, qits-1551).
 */
@ApplicationScoped
public class SocketBearerLifetime implements SecurityIdentityAugmentor {

  /** quarkus-oidc's {@code OidcUtils.QUARKUS_IDENTITY_EXPIRE_TIME}, which websockets-next reads. */
  static final String EXPIRE_TIME = "quarkus.identity.expire-time";

  /**
   * The upgrades whose connections outlive their bearer, by prefix: a desk's control socket {@code
   * /projects/daemon/<projectId>} and its dial-backs {@code /projects/daemon/stream/<nonce>}.
   */
  static final Set<String> SOCKET_PREFIXES =
      Set.of(DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX);

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

  /** Whether {@code path} is one of the sockets that outlive their bearer. */
  static boolean onSocketPath(String path) {
    for (String prefix : SOCKET_PREFIXES) {
      if (path.startsWith(prefix) && path.length() > prefix.length()) {
        return true;
      }
    }
    return false;
  }

  /** The identity as a connection on {@code path} should hold it. */
  static SecurityIdentity forPath(SecurityIdentity identity, String path) {
    if (path == null
        || !onSocketPath(path)
        || identity.isAnonymous()
        || identity.getAttribute(EXPIRE_TIME) == null
        || !AgentControlSocketAccess.isTokenSubject(AgentControlSocketAccess.subjectOf(identity))) {
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
