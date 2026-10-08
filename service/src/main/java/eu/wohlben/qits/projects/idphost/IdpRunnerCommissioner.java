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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The credentials a front-desk runner is commissioned at qits-idp (qits-767): its one-use
 * registration TOKEN, minted when an operator creates the runner (and at every rotation), and its
 * own CLIENT, minted by the register door. Both are given back when the runner is deleted. A copy of
 * qits-workspaces-service's {@code wiring/IdpRunnerCommissioner}; the HTTP is this service's own —
 * {@link IdpTokens} for the token, and the {@link IdpAgentCredentials} shape for the client.
 *
 * <p><b>Two kinds, and qits-idp maps each to one role</b> ({@code CommissionRoles}): a {@link
 * #REGISTRATION_KIND} token grants {@code qits:desk-runner-registration}, which opens the register
 * door and the install script and nothing else; a {@link #RUNNER_KIND} client grants {@code
 * qits:desk-runner}, which opens the runner socket. Both are named by the runner's id as their
 * context, which is what {@code DeskRunnerCommissionReconcile} judges them against. Neither states a
 * project claim or Git refs: a runner acts in no project and pushes nothing. (The kinds are not
 * {@code projects-desk-runner[-registration]}: qits-idp's context kind is at most 32 characters.)
 *
 * <p><b>Commissioning is patient and throws; giving back is one attempt and never throws.</b> A
 * commission is somebody waiting on an answer, so it is asked again across an idp cutover for {@code
 * qits.projects.agent-credentials.commission-patience}; a give-back runs after the row is already
 * gone, so a failure leaves an orphan for the reconcile rather than an error for nobody.
 *
 * <p><b>Wired by the same switch</b> as every commission here, {@code
 * quarkus.oidc-client.qits.client-enabled} and the client's own secret. With either missing {@link
 * #enabled()} is false: commissioning throws {@link CommissionFailedException} (the caller answers
 * 503 before it gets there), and every give-back and listing does nothing.
 */
@ApplicationScoped
public class IdpRunnerCommissioner {

  private static final Logger LOG = Logger.getLogger(IdpRunnerCommissioner.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** How long to wait between two attempts inside the patience window. */
  static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

  /** A front-desk runner's own client, named by the runner's id. */
  public static final String RUNNER_KIND = "desk-runner";

  /** A front-desk runner's one-use registration token, named by the runner's id. */
  public static final String REGISTRATION_KIND = "desk-runner-registration";

  /** A commission that could not be made: not wired, or every attempt the window allowed failed. */
  public static class CommissionFailedException extends IllegalStateException {
    public CommissionFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A runner's commissioned client. The secret is answered once, by qits-idp. */
  public record RunnerClient(String clientId, String secret) {
    @Override
    public String toString() {
      return "RunnerClient[clientId=" + clientId + "]";
    }
  }

  /** One live client of this service's, as the reconcile reads it. Never a secret. */
  public record LiveClient(String clientId, String contextKind, String contextId) {}

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Inject ObjectMapper objectMapper;

  @Inject IdpTokens tokens;

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

  /** The agent credential's window: a runner's commission waits out the same idp cutover. */
  @ConfigProperty(name = "qits.projects.agent-credentials.commission-patience")
  Duration patience;

  /** Whether this process can commission at all. */
  public boolean enabled() {
    return tokensEnabled && secret().isPresent() && tokens.enabled();
  }

  /**
   * Commission the registration token for {@code runnerId}.
   *
   * @throws CommissionFailedException when not wired, or when no attempt landed
   */
  public IdpTokens.Issued registrationToken(UUID runnerId) {
    String contextId = runnerId.toString();
    requireEnabled(REGISTRATION_KIND, contextId);
    return patiently(
        "a " + REGISTRATION_KIND + " token for " + contextId,
        () -> tokens.commission(REGISTRATION_KIND, contextId, null));
  }

  /**
   * Commission the runner's own client: kind {@link #RUNNER_KIND}, context {@code runnerId}, no
   * project claim and no Git refs.
   *
   * @throws CommissionFailedException when not wired, or when no attempt landed
   */
  public RunnerClient runnerClient(UUID runnerId) {
    String contextId = runnerId.toString();
    requireEnabled(RUNNER_KIND, contextId);
    return patiently(
        "a " + RUNNER_KIND + " client for " + contextId, () -> commissionClient(contextId));
  }

  /** Give a runner's client back. One attempt; 404 is success; a failure is the reconcile's. */
  public void decommissionClient(String runnerClientId) {
    if (!enabled() || IdpTokens.blank(runnerClientId)) {
      return;
    }
    try {
      HttpResponse<String> response =
          send(
              request(clientsUrl() + "/" + URLEncoder.encode(runnerClientId, StandardCharsets.UTF_8))
                  .DELETE()
                  .build(),
              "decommissioning runner client " + runnerClientId);
      int status = response.statusCode();
      if (status != 204 && status != 200 && status != 404) {
        LOG.warnf(
            "qits-idp answered %d while decommissioning runner client %s; the reconcile will reap"
                + " it",
            status, runnerClientId);
      }
    } catch (AgentCredentialException unreachable) {
      LOG.warnf(
          "Could not reach qits-idp to decommission runner client %s; the reconcile will reap it:"
              + " %s",
          runnerClientId, unreachable.getMessage());
    }
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
   * Every live client of the {@link #RUNNER_KIND} this service owns, or EMPTY when the listing could
   * not be read (or nothing is wired) — the same "empty is not an empty list" rule as {@link
   * #liveTokens}, for the same reason.
   */
  public Optional<List<LiveClient>> liveRunnerClients() {
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
        String kind = text(fields.get("contextKind"));
        String commissioned = text(fields.get("clientId"));
        if (!RUNNER_KIND.equals(kind) || IdpTokens.blank(commissioned)) {
          continue;
        }
        live.add(new LiveClient(commissioned, kind, text(fields.get("contextId"))));
      }
      return Optional.of(List.copyOf(live));
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Could not list this service's clients at qits-idp: %s", e.toString());
      return Optional.empty();
    }
  }

  private RunnerClient commissionClient(String contextId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contextKind", RUNNER_KIND);
    body.put("contextId", contextId);
    String doing = "commissioning a " + RUNNER_KIND + " client for " + contextId;
    String json;
    try {
      json = objectMapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new AgentCredentialException("Could not build the commission request", false, e);
    }
    HttpResponse<String> response =
        send(
            request(clientsUrl())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            doing);
    int status = response.statusCode();
    if (status != 201 && status != 200) {
      throw new AgentCredentialException(
          "qits-idp answered " + status + " " + doing + ": " + response.body(),
          status == 401 || status == 403 || status >= 500);
    }
    Map<?, ?> answer;
    try {
      answer = objectMapper.readValue(response.body(), Map.class);
    } catch (IOException e) {
      throw new AgentCredentialException("Could not read the commission answer", false, e);
    }
    String commissioned = text(answer.get("clientId"));
    String secret = text(answer.get("secret"));
    if (IdpTokens.blank(commissioned) || IdpTokens.blank(secret)) {
      throw new AgentCredentialException(
          "qits-idp answered a client commission for " + contextId + " with no usable pair", false);
    }
    return new RunnerClient(commissioned, secret);
  }

  /** One commission, asked for again while the answers are about the moment. */
  private <T> T patiently(String what, Supplier<T> attempt) {
    Instant giveUpAt = Instant.now().plus(patience);
    Duration pause = RETRY_PAUSE.compareTo(patience) > 0 ? patience : RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        return attempt.get();
      } catch (AgentCredentialException e) {
        if (!e.retryable() || !Instant.now().isBefore(giveUpAt) || !sleep(pause)) {
          throw new CommissionFailedException(
              "Could not commission " + what + " after " + attempts + " attempt(s): " + e.getMessage(),
              e);
        }
        LOG.infof(
            "Attempt %d to commission %s did not land (%s) — asking again", attempts, what,
            e.getMessage());
      }
    }
  }

  private void requireEnabled(String contextKind, String contextId) {
    if (!enabled()) {
      throw new CommissionFailedException(
          "Cannot commission a "
              + contextKind
              + " credential for "
              + contextId
              + ": this service has no idp client wired (quarkus.oidc-client.qits.*)",
          null);
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
      throw new AgentCredentialException("qits-idp unreachable " + doing + ": " + e, true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentCredentialException("Interrupted " + doing, false, e);
    }
  }

  private static boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
