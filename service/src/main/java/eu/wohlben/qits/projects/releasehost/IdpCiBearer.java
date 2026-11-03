package eu.wohlben.qits.projects.releasehost;

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
 * The machine bearer this service presents to qits-ci — {@code wiring/IdpGitHostBearer}'s sibling on
 * the {@code qits} named client (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4), asking one audience,
 * {@code qits-platform}, empty on the same terms.
 *
 * <p>Every hop to qits-ci holds it — {@link HttpActiveBuilds}, {@link HttpCiRunLineage}, {@link
 * HttpPipelinePhaseReruns}, {@link HttpPublishRuns}, {@link HttpQaRunCancellations} and {@link
 * HttpReleaseDecisions} — and it is the only credential they send (owner ruling 2026-10-18, epic
 * qits-543: a service calls a peer with its own bearer and no forwarded {@code X-Qits-*} pair). With
 * the named client disabled, or a mint that fails, it is empty and the hop <b>fails closed</b>: it
 * sends nothing, logs {@link #MISSING}, and answers its own failure contract, as {@code
 * wiring/HttpGitHostRepositories} does.
 */
@ApplicationScoped
public class IdpCiBearer {

  /** What a hop logs when it has no bearer to present and therefore does not call. */
  public static final String MISSING =
      "no machine bearer for qits-ci: the named OIDC client quarkus.oidc-client.qits is disabled"
          + " or minted no token, and this call is never sent without one";

  private static final Logger LOG = Logger.getLogger(IdpCiBearer.class);
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
      LOG.warnf("Could not get a machine token for qits-ci: %s", e.toString());
      return Optional.empty();
    }
  }
}
