package eu.wohlben.qits.projects.idphost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentCredentialException;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * qits-idp's commissioned-token API, {@code <auth-server-url>/api/tokens} (qits-767): the third
 * credential qits-idp issues, an opaque {@code qits_tok_…} value the edge introspects. The twin of
 * qits-workspaces-service's {@code wiring/IdpTokens}, written this service's way — the {@link
 * IdpAgentCredentials} shape: an instance {@link HttpClient}, {@code Map}s and never DTOs (no
 * native-image registration owed), HTTP Basic with this service's own {@code qits} client, and
 * absent whenever {@code quarkus.oidc-client.qits.client-enabled} is off. Only a static {@code
 * qits:system} client may commission a token (qits-idp {@code IdpTokensController.commission}), and
 * this service's is one.
 *
 * <p><b>Written for more than one kind.</b> It mints a front-desk runner's registration token
 * ({@link IdpRunnerCommissioner}) today, and the front desk's own token later, which is why {@link
 * #commission(String, String, Map)} takes claims although the runner states none.
 *
 * <p><b>The value is answered once.</b> qits-idp stores a hash; the listing never carries a value,
 * and a caller that loses one deletes the token and commissions again.
 *
 * <p><b>Commissioning throws a classified {@link AgentCredentialException}; deleting and listing
 * never throw.</b> 401, 403 and 5xx are about the moment (an idp cutover) and are retryable;
 * anything else is about the request.
 */
@ApplicationScoped
public class IdpTokens {

  private static final Logger LOG = Logger.getLogger(IdpTokens.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  /** A commissioned token. {@code token} is the value, answered once and never logged. */
  public record Issued(String tokenId, String token, String subject) {
    @Override
    public String toString() {
      return "Issued[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }

  /** One live token of this service's, as the reconcile reads it. Never a value. */
  public record LiveToken(String tokenId, String contextKind, String contextId, Instant createdAt) {}

  @Inject ObjectMapper objectMapper;

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

  /** Whether this process can commission at all: the switch is on and a secret is configured. */
  public boolean enabled() {
    return tokensEnabled && secret().isPresent();
  }

  /**
   * Commission a token of {@code contextKind} for {@code contextId}, stating {@code claims} when
   * there are any. One attempt; the caller holds the patience.
   *
   * @throws AgentCredentialException when not wired, or refused, classified retryable or not
   */
  public Issued commission(String contextKind, String contextId, Map<String, String> claims) {
    return commission(contextKind, contextId, claims, null);
  }

  /**
   * {@link #commission(String, String, Map)} stating the refs the token may push: a non-null {@code
   * gitRefs} is sent as {@code gitRefs} (an empty list is "may push nothing", the front desk's
   * qits-767 commission), null leaves the member out.
   */
  public Issued commission(
      String contextKind, String contextId, Map<String, String> claims, List<String> gitRefs) {
    if (!enabled()) {
      throw new AgentCredentialException(
          "Cannot commission a "
              + contextKind
              + " token for "
              + contextId
              + ": this service has no idp client wired (quarkus.oidc-client.qits.*)",
          false);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contextKind", contextKind);
    body.put("contextId", contextId);
    if (claims != null && !claims.isEmpty()) {
      body.put("claims", claims);
    }
    if (gitRefs != null) {
      body.put("gitRefs", List.copyOf(gitRefs));
    }
    String doing = "commissioning a " + contextKind + " token for " + contextId;
    String json;
    try {
      json = objectMapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new AgentCredentialException("Could not build the token request", false, e);
    }
    HttpResponse<String> response =
        send(
            request(tokensUrl())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            doing);
    if (response.statusCode() != 201 && response.statusCode() != 200) {
      int status = response.statusCode();
      throw new AgentCredentialException(
          "qits-idp answered " + status + " " + doing + ": " + response.body(),
          status == 401 || status == 403 || status >= 500);
    }
    Map<?, ?> answer;
    try {
      answer = objectMapper.readValue(response.body(), Map.class);
    } catch (IOException e) {
      throw new AgentCredentialException("Could not read the token answer from qits-idp", false, e);
    }
    String tokenId = text(answer.get("tokenId"));
    String token = text(answer.get("token"));
    String subject = text(answer.get("subject"));
    if (blank(tokenId) || blank(token) || blank(subject)) {
      throw new AgentCredentialException(
          "qits-idp answered a token commission for " + contextId + " with no usable token", false);
    }
    return new Issued(tokenId, token, subject);
  }

  /**
   * Delete a token. One attempt; 404 is success. Answers whether the token is gone; a failure is
   * left for the reconcile.
   */
  public boolean delete(String tokenId) {
    if (!enabled() || blank(tokenId)) {
      return false;
    }
    try {
      HttpResponse<String> response =
          send(
              request(tokensUrl() + "/" + URLEncoder.encode(tokenId, StandardCharsets.UTF_8))
                  .DELETE()
                  .build(),
              "deleting token " + tokenId);
      int status = response.statusCode();
      if (status == 204 || status == 200 || status == 404) {
        return true;
      }
      LOG.warnf(
          "qits-idp answered %d while deleting token %s; the reconcile will reap it", status, tokenId);
    } catch (AgentCredentialException unreachable) {
      LOG.warnf(
          "Could not reach qits-idp to delete token %s; the reconcile will reap it: %s",
          tokenId, unreachable.getMessage());
    }
    return false;
  }

  /**
   * Every live token this service owns, or EMPTY when it could not be read (or nothing is wired).
   * Empty and an empty list are different answers on purpose: the reconcile deletes from what this
   * returns, so a listing that failed must reap nothing rather than read as "nothing is out there".
   */
  public Optional<List<LiveToken>> list() {
    if (!enabled()) {
      return Optional.empty();
    }
    try {
      HttpResponse<String> response =
          send(request(tokensUrl()).GET().build(), "listing this service's tokens");
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-idp answered %d listing this service's tokens: %s",
            response.statusCode(), response.body());
        return Optional.empty();
      }
      List<?> rows = objectMapper.readValue(response.body(), List.class);
      if (rows == null) {
        return Optional.empty();
      }
      List<LiveToken> live = new ArrayList<>();
      for (Object row : rows) {
        if (!(row instanceof Map<?, ?> fields) || blank(text(fields.get("tokenId")))) {
          continue;
        }
        live.add(
            new LiveToken(
                text(fields.get("tokenId")),
                text(fields.get("contextKind")),
                text(fields.get("contextId")),
                instant(text(fields.get("createdAt")))));
      }
      return Optional.of(List.copyOf(live));
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Could not list this service's tokens at qits-idp: %s", e.toString());
      return Optional.empty();
    }
  }

  private String tokensUrl() {
    String base =
        authServerUrl.endsWith("/")
            ? authServerUrl.substring(0, authServerUrl.length() - 1)
            : authServerUrl;
    return base + "/api/tokens";
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
      throw new AgentCredentialException("qits-idp unreachable " + doing + ": " + e, true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentCredentialException("Interrupted " + doing, false, e);
    }
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static Instant instant(String text) {
    if (blank(text)) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException unparseable) {
      return null;
    }
  }
}
