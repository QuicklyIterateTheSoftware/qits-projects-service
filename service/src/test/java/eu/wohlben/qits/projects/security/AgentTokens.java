package eu.wohlben.qits.projects.security;

import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Identities for the agent checks: one with a token and its claims, one without a token.
 *
 * <p>The token is hand-made, as qits-auth-core's own tests make theirs: the checks read a principal
 * that quarkus-oidc has already validated, so a signature here would test the extension.
 */
public final class AgentTokens {

  /** A caller with a token carrying {@code claims}, holding {@code roles}. */
  public static SecurityIdentity token(Map<String, Object> claims, String... roles) {
    Object sub = claims.getOrDefault("sub", "dyn-client");
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new FakeJwt(String.valueOf(sub), claims))
        .addRoles(Set.of(roles))
        .build();
  }

  /** A caller whose roles came from a forwarded header: no token, so no claims. */
  public static SecurityIdentity forwarded(String... roles) {
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal("someone"))
        .addRoles(Set.of(roles))
        .build();
  }

  private record FakeJwt(String name, Map<String, Object> claims) implements JsonWebToken {

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Set<String> getClaimNames() {
      return claims.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return (T) claims.get(claimName);
    }
  }

  private AgentTokens() {}
}
