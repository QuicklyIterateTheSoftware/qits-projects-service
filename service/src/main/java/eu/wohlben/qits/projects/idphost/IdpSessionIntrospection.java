package eu.wohlben.qits.projects.idphost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.security.SessionIntrospection;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
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
 * {@link SessionIntrospection} over qits-idp's {@code POST <auth-server-url>/api/sessions/introspect}
 * — the same door, body and credential the edge uses to turn the cookie into identity headers, asked
 * again here so that a decision rests on what this service heard from idp rather than on a header.
 *
 * <p><b>HTTP Basic with this service's own client pair</b>, exactly as {@code IdpAgentCredentials} (retired with the direct agent path)
 * presents it and read from the same three {@code quarkus.oidc-client.qits.*} keys: the door admits
 * a static service client holding {@code qits:system}, which is what the deployer's {@code
 * idp:client} resource provisions for {@code <env>-qits-projects}. No new key and no new secret.
 *
 * <p><b>Every "no" is empty</b> ({@link SessionIntrospection}'s contract): 404 is idp's one answer
 * for an unknown, expired or revoked session and is said quietly; any other status, an unreadable
 * answer or nobody answering is one WARN and empty — a person's press then meets a 403 and can press
 * again, which is the honest answer while idp is being redeployed. With the client switched off
 * (%dev, %test) nothing is dialled at all.
 *
 * <p>The value travels in the JSON body and never in a URL or a log line: it is a person's whole
 * session. {@code Map} rather than a DTO, so the native image needs nothing registered — the
 * discipline {@code IdpAgentCredentials} (retired with the direct agent path) keeps.
 */
@ApplicationScoped
@DefaultBean
public class IdpSessionIntrospection implements SessionIntrospection {

  private static final Logger LOG = Logger.getLogger(IdpSessionIntrospection.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** A person is waiting on the press, and idp is a sibling on the same network. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Inject ObjectMapper objectMapper;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean tokensEnabled;

  @ConfigProperty(name = "quarkus.oidc-client.qits.auth-server-url")
  String authServerUrl;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-id")
  String clientId;

  @ConfigProperty(name = "quarkus.oidc-client.qits.credentials.secret")
  Optional<String> clientSecret;

  @Override
  public Optional<Session> introspect(String cookie) {
    if (cookie == null || cookie.isBlank()) {
      return Optional.empty();
    }
    Optional<String> secret = clientSecret.filter(value -> !value.isBlank());
    if (!tokensEnabled || secret.isEmpty()) {
      LOG.debug("no idp client credential here, so no session can be verified");
      return Optional.empty();
    }
    HttpResponse<String> response;
    try {
      String body = objectMapper.writeValueAsString(Map.of("token", cookie));
      String pair = clientId + ":" + secret.get();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(introspectUrl()))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", "Basic " + base64(pair))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      LOG.warnf("qits-idp unreachable introspecting a session: %s", e.toString());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      LOG.warnf(
          "qits-idp answered %d introspecting a session as %s: %s",
          response.statusCode(), clientId, response.body());
      return Optional.empty();
    }
    return read(response.body());
  }

  private Optional<Session> read(String body) {
    Map<?, ?> view;
    try {
      view = objectMapper.readValue(body, Map.class);
    } catch (IOException e) {
      LOG.warnf("Could not read qits-idp's session introspection: %s", e.toString());
      return Optional.empty();
    }
    if (!(view.get("username") instanceof String username) || username.isBlank()) {
      return Optional.empty();
    }
    List<String> roles = new ArrayList<>();
    if (view.get("roles") instanceof List<?> values) {
      for (Object value : values) {
        if (value instanceof String role) {
          roles.add(role);
        }
      }
    }
    return Optional.of(new Session(username, roles));
  }

  /** {@code <auth-server-url>/api/sessions/introspect}, with no double slash. */
  private String introspectUrl() {
    String base =
        authServerUrl.endsWith("/")
            ? authServerUrl.substring(0, authServerUrl.length() - 1)
            : authServerUrl;
    return base + "/api/sessions/introspect";
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
