package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

/** Which sockets outlive their bearer, and for whom (qits-767). */
class SocketBearerLifetimeTest {

  private static SecurityIdentity bearer(String sub, String... roles) {
    JsonWebToken jwt =
        new JsonWebToken() {
          @Override
          public String getName() {
            return sub;
          }

          @Override
          public Set<String> getClaimNames() {
            return Set.of("sub");
          }

          @Override
          @SuppressWarnings("unchecked")
          public <T> T getClaim(String claimName) {
            return "sub".equals(claimName) ? (T) sub : null;
          }
        };
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(jwt)
        .addRoles(Set.of(roles))
        .addAttributes(Map.of(SocketBearerLifetime.EXPIRE_TIME, 12345L))
        .build();
  }

  private static Object expiry(SecurityIdentity identity, String path) {
    return SocketBearerLifetime.forPath(identity, path).getAttribute(SocketBearerLifetime.EXPIRE_TIME);
  }

  @Test
  void aDesksTokenOutlivesItsBearerOnItsSockets() {
    assertNull(expiry(bearer("tok-1", "qits:agent"), "/projects/daemon/p-1"));
    assertNull(expiry(bearer("tok-1", "qits:agent"), "/projects/daemon/stream/n-1"));
  }

  @Test
  void theRunnerKeepsItsSocket() {
    assertNull(expiry(bearer("dr-1", DeskRunnerSocket.RUNNER_ROLE), "/projects/runners/socket"));
  }

  @Test
  void everythingElseKeepsItsExpiry() {
    assertNotNull(expiry(bearer("tok-1", "qits:agent"), "/projects/api/projects"));
    assertNotNull(expiry(bearer("dyn-client", "qits:agent"), "/projects/daemon/p-1"));
    assertNotNull(expiry(bearer("tok-1", "qits:agent"), "/projects/daemon/"));
  }
}
