package eu.wohlben.qits.projects.idphost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The clients this service holds at qits-idp, listed and deleted (epic qits-1481): what is left of
 * {@code IdpRunnerCommissioner} once the front-desk runners were retired (qits-1551). It
 * commissions nothing any more. Three boot one-shots give credentials back through it — {@code
 * deskhost/LegacyAgentPlaces} the retired direct path's {@code agent-container} clients, {@code
 * refinementhost/LegacyRefinementContainers} the retired refinement containers' clients, and {@code
 * targethost/LegacyRunnerClients} the retired front-desk runners' clients and tokens. The HTTP is
 * this service's own: {@link IdpTokens} for tokens, the client listing and delete here.
 *
 * <p><b>Giving back is one attempt and never throws</b>: a failure leaves the credential for the
 * next boot.
 *
 * <p><b>Wired by the same switch</b> as every idp call here, {@code
 * quarkus.oidc-client.qits.client-enabled} and the client's own secret. With either missing {@link
 * #enabled()} is false, and every give-back and listing does nothing.
 */
@ApplicationScoped
public class IdpClients {

  private static final Logger LOG = Logger.getLogger(IdpClients.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** One live client of this service's, as the reconcile reads it. Never a secret. */
  public record LiveClient(String clientId, String contextKind, String contextId) {}

  private final HttpClient client;

  @Inject ObjectMapper objectMapper;

  @Inject IdpTokens tokens;

  @Inject
  public IdpClients(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /** Shuts the client down with the bean: a leaked SelectorManager thread per instance (qits-1149). */
  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean tokensEnabled;

  @ConfigProperty(name = "quarkus.oidc-client.qits.auth-server-url")
  String authServerUrl;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-id")
  String clientId;

  @ConfigProperty(name = "quarkus.oidc-client.qits.credentials.secret")
  Optional<String> clientSecret;

  @ConfigProperty(name = "qits.projects.agent-credentials.request-timeout")
  Duration requestTimeout;

  /** Whether this process can ask qits-idp at all. */
  public boolean enabled() {
    return tokensEnabled && secret().isPresent() && tokens.enabled();
  }

  /**
   * Delete any client this service owns. One attempt; 404 is success. Answers whether the client is
   * gone — false when it could not be asked or was refused, which the caller retries later.
   */
  public boolean deleteClient(String clientId) {
    if (!enabled() || IdpTokens.blank(clientId)) {
      return false;
    }
    try {
      HttpResponse<String> response =
          send(
              request(clientsUrl() + "/" + URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                  .DELETE()
                  .build(),
              "deleting client " + clientId);
      int status = response.statusCode();
      if (status == 204 || status == 200 || status == 404) {
        return true;
      }
      LOG.warnf("qits-idp answered %d while deleting client %s", status, clientId);
    } catch (IdpCommissionException unreachable) {
      LOG.warnf("Could not reach qits-idp to delete client %s: %s", clientId, unreachable.getMessage());
    }
    return false;
  }

  /** Delete a token. One attempt; 404 is success. Answers whether the token is gone. */
  public boolean deleteToken(String tokenId) {
    return enabled() && tokens.delete(tokenId);
  }

  /** Every live token this service owns, or EMPTY when it could not be read; see {@link IdpTokens#list}. */
  public Optional<List<IdpTokens.LiveToken>> liveTokens() {
    return enabled() ? tokens.list() : Optional.empty();
  }

  /**
   * Every live client of {@code kind} this service owns, or EMPTY when the listing could not be
   * read (or nothing is wired): how the legacy one-shots find what they give back.
   */
  public Optional<List<LiveClient>> liveClients(String kind) {
    if (!enabled()) {
      return Optional.empty();
    }
    try {
      HttpResponse<String> response =
          send(request(clientsUrl()).GET().build(), "listing this service's clients");
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-idp answered %d listing this service's clients: %s",
            response.statusCode(), response.body());
        return Optional.empty();
      }
      List<?> rows = objectMapper.readValue(response.body(), List.class);
      if (rows == null) {
        return Optional.empty();
      }
      List<LiveClient> live = new ArrayList<>();
      for (Object row : rows) {
        if (!(row instanceof Map<?, ?> fields)) {
          continue;
        }
        String rowKind = text(fields.get("contextKind"));
        String commissioned = text(fields.get("clientId"));
        if (!kind.equals(rowKind) || IdpTokens.blank(commissioned)) {
          continue;
        }
        live.add(new LiveClient(commissioned, rowKind, text(fields.get("contextId"))));
      }
      return Optional.of(List.copyOf(live));
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Could not list this service's clients at qits-idp: %s", e.toString());
      return Optional.empty();
    }
  }

  private String clientsUrl() {
    String base =
        authServerUrl.endsWith("/")
            ? authServerUrl.substring(0, authServerUrl.length() - 1)
            : authServerUrl;
    return base + "/api/clients";
  }

  private HttpRequest.Builder request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(requestTimeout)
        .header("Authorization", basic());
  }

  private String basic() {
    String pair = clientId + ":" + secret().orElse("");
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  private Optional<String> secret() {
    return clientSecret == null ? Optional.empty() : clientSecret.filter(v -> !v.isBlank());
  }

  private HttpResponse<String> send(HttpRequest request, String doing) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IdpCommissionException("qits-idp unreachable " + doing + ": " + e, true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IdpCommissionException("Interrupted " + doing, false, e);
    }
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
