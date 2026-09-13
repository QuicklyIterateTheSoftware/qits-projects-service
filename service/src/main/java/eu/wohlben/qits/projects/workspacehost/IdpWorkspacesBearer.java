package eu.wohlben.qits.projects.workspacehost;

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
 * The shipped {@link WorkspacesBearer}: the {@code qits} named OIDC client's token
 * (service-client-identity-plan.md, C4 — one audience, {@code qits-platform}) — {@code
 * wiring/IdpGitHostBearer} and {@code releasehost/IdpCiBearer}'s sibling, on the same service
 * identity and secret.
 *
 * <p>Empty on the same three terms they are: the named client disabled (the shipped default, and any
 * no-idp topology), a blank token, or a mint that threw. What the caller does with empty is stricter
 * than theirs, and {@link WorkspacesBearer} says why.
 */
@ApplicationScoped
@DefaultBean
public class IdpWorkspacesBearer implements WorkspacesBearer {

  private static final Logger LOG = Logger.getLogger(IdpWorkspacesBearer.class);
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
      LOG.warnf("Could not get a machine token for qits-workspaces: %s", e.toString());
      return Optional.empty();
    }
  }
}
