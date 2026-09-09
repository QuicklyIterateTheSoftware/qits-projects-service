package eu.wohlben.qits.projects.confighost;

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
 * The shipped {@link ConfigurationBearer}: the {@code configuration} named OIDC client's token
 * (audience {@code qits-configuration}, that service's own {@code qits.auth.machine.audience}) —
 * a fifth audience on the same service identity as {@code wiring/IdpGitHostBearer}, {@code
 * releasehost/IdpCiBearer}, {@code workspacehost/IdpWorkspacesBearer} and {@code
 * maintenancehost/IdpMaintenanceBearer}.
 *
 * <p>Empty on the same three terms they are: the named client disabled (the shipped default, and any
 * no-idp topology), a blank token, or a mint that threw. Unlike theirs, empty is <b>not</b> softened
 * by a header fallback at the caller — {@link ConfigurationBearer} says why.
 */
@ApplicationScoped
@DefaultBean
public class IdpConfigurationBearer implements ConfigurationBearer {

  private static final Logger LOG = Logger.getLogger(IdpConfigurationBearer.class);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.configuration.client-enabled")
  boolean enabled;

  @Inject
  @NamedOidcClient("configuration")
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
      LOG.warnf("Could not get a machine token for qits-configuration: %s", e.toString());
      return Optional.empty();
    }
  }
}
