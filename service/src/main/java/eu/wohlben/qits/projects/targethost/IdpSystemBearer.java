package eu.wohlben.qits.projects.targethost;

import io.quarkus.arc.DefaultBean;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link SystemBearer}: this service's own {@code qits} oidc client ({@code
 * qits:system}, audience {@code qits-platform}), the one {@code workspacehost/IdpWorkspacesBearer}
 * presents. {@code @DefaultBean}, so a suite's stand-in wins.
 */
@ApplicationScoped
@DefaultBean
public class IdpSystemBearer implements SystemBearer {

  private static final Logger LOG = Logger.getLogger(IdpSystemBearer.class);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @Inject
  @NamedOidcClient("qits")
  OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  @Override
  public Optional<String> authorization() {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank())
          .map(value -> "Bearer " + value);
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for qits-system: %s", e.toString());
      return Optional.empty();
    }
  }
}
