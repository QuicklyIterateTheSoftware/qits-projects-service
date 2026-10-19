package eu.wohlben.qits.projects.idphost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.Optional;

/**
 * An {@link IdpRunnerCommissioner} as a deployment with an idp has it, pointed at a stub — for a
 * plain test, and for a {@code @QuarkusTest} to install with {@code QuarkusMock} (qits-767).
 */
public final class IdpRunnerCommissionerFixture {

  public static final String OWN_CLIENT = "dev-qits-projects";

  public static final String OWN_SECRET = "own-secret";

  private IdpRunnerCommissionerFixture() {}

  /** Wired, with a patience of {@code patience}. */
  public static IdpRunnerCommissioner pointedAt(String authServerUrl, Duration patience) {
    ObjectMapper json = new ObjectMapper();
    QitsHttpClients clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    IdpTokens tokens = new IdpTokens(clients);
    tokens.objectMapper = json;
    tokens.tokensEnabled = true;
    tokens.authServerUrl = authServerUrl;
    tokens.clientId = OWN_CLIENT;
    tokens.clientSecret = Optional.of(OWN_SECRET);
    tokens.requestTimeout = Duration.ofSeconds(2);
    IdpRunnerCommissioner commissioner = new IdpRunnerCommissioner(clients);
    commissioner.objectMapper = json;
    commissioner.tokens = tokens;
    commissioner.tokensEnabled = true;
    commissioner.authServerUrl = authServerUrl;
    commissioner.clientId = OWN_CLIENT;
    commissioner.clientSecret = Optional.of(OWN_SECRET);
    commissioner.requestTimeout = Duration.ofSeconds(2);
    commissioner.patience = patience;
    return commissioner;
  }

  /** Wired, with no patience: one attempt. */
  public static IdpRunnerCommissioner pointedAt(String authServerUrl) {
    return pointedAt(authServerUrl, Duration.ZERO);
  }

  /**
   * Shuts down both HttpClients a built commissioner holds — its own, and the {@link IdpTokens} it
   * was given — so a suite that builds many of these does not run out of native threads (qits-1149).
   */
  public static void close(IdpRunnerCommissioner commissioner) {
    commissioner.close();
    commissioner.tokens.close();
  }
}
