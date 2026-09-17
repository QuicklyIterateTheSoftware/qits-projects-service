package eu.wohlben.qits.projects.deploymenthost;

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
 * The machine bearer this service presents to qits-deployments — {@code releasehost/IdpCiBearer}'s
 * sibling on the same {@code qits} named client (service-client-identity-plan.md, C4), asking the
 * one audience this platform has, {@code qits-platform}, and empty on the same terms.
 *
 * <p>A class of its own rather than an injection of the qits-ci one, because a bearer class here is
 * where its far side's posture is written down and the two postures differ. {@link
 * HttpDeploymentRequests} falls back to the forwarded {@code X-Qits-*} pair when this is empty, the
 * way every read hop in this service does; {@link HttpDeploymentRedeploys} <b>does not</b>, and the
 * reason is the far side's door rather than a preference — see that class.
 *
 * <p><b>Empty is a supported answer and the shipped one.</b> With {@code
 * quarkus.oidc-client.qits.client-enabled} false — the default, and any no-idp topology — this
 * process holds no secret and can authenticate to nothing.
 */
@ApplicationScoped
public class IdpDeploymentsBearer {

  private static final Logger LOG = Logger.getLogger(IdpDeploymentsBearer.class);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("qits") OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  /** {@code Bearer <token>}, or empty when this hop has no credential to present. */
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
      LOG.warnf("Could not get a machine token for qits-deployments: %s", e.toString());
      return Optional.empty();
    }
  }
}
