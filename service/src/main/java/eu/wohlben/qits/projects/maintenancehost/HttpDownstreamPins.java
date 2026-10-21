package eu.wohlben.qits.projects.maintenancehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.DownstreamPins;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link DownstreamPins}: two GETs against qits-maintenance, on the address and the
 * credential {@link HttpDownstreamComponents} already uses — {@code
 * qits.projects.release-requests.maintenance-url} and the {@link MaintenanceBearer}, falling back to
 * the forwarded {@code X-Qits-*} pair while the named client is off. Both doors admit {@code
 * qits:system} and {@code qits:agent}.
 *
 * <pre>
 *   GET {maintenance-url}/maintenance/api/repositories/&lt;repoId&gt;/downstream
 *   -&gt; 200 {"downstream": [{"repository", "catalogId", "depth", …}]}
 *
 *   GET {maintenance-url}/maintenance/api/repositories/&lt;name&gt;
 *   -&gt; 200 {"pins": [{"ecosystem", "name", …}], …}    404: no such repository = pins nothing
 * </pre>
 *
 * <p>Every failure — unset address, an unreachable or refusing far side, a timeout, a body that
 * will not parse — is one WARN and {@link Optional#empty()}; nothing here throws. {@code readTree},
 * never a bound record, and an instance {@link HttpClient}, for {@link HttpDownstreamComponents}'
 * native-image reasons.
 */
@ApplicationScoped
@DefaultBean
public class HttpDownstreamPins implements DownstreamPins {

  private static final Logger LOG = Logger.getLogger(HttpDownstreamPins.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final String DOWNSTREAM_PATH = HttpDownstreamComponents.DOWNSTREAM_PATH;

  static final String REPOSITORY_PATH = "/maintenance/api/repositories/%s";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** The bound on one exchange; an approval read waits on it. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient client;

  @ConfigProperty(name = "qits.projects.release-requests.maintenance-url")
  Optional<String> maintenanceUrl;

  @Inject MaintenanceBearer bearer;

  @Inject
  public HttpDownstreamPins(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  @Override
  public Optional<List<Downstream>> downstreamOf(String repoId) {
    Optional<JsonNode> body = get(String.format(DOWNSTREAM_PATH, segment(repoId)), false);
    if (body.isEmpty()) {
      return Optional.empty();
    }
    JsonNode downstream = body.get().get("downstream");
    if (downstream == null || !downstream.isArray()) {
      return unknown("the downstream of " + repoId, "the answer carries no downstream array");
    }
    List<Downstream> entries = new ArrayList<>();
    for (JsonNode entry : downstream) {
      String repository = text(entry, "repository");
      if (repository == null) {
        return unknown("the downstream of " + repoId, "an entry names no repository");
      }
      JsonNode depth = entry.get("depth");
      entries.add(
          new Downstream(
              repository, text(entry, "catalogId"), depth != null && depth.isInt() ? depth.asInt() : 0));
    }
    return Optional.of(List.copyOf(entries));
  }

  @Override
  public Optional<List<Pin>> pinsOf(String repository) {
    Optional<JsonNode> body = get(String.format(REPOSITORY_PATH, segment(repository)), true);
    if (body.isEmpty()) {
      return Optional.empty();
    }
    if (body.get().isNull()) {
      return Optional.of(List.of());
    }
    JsonNode pins = body.get().get("pins");
    if (pins == null || !pins.isArray()) {
      return unknown("the pins of " + repository, "the answer carries no pins array");
    }
    List<Pin> out = new ArrayList<>();
    for (JsonNode pin : pins) {
      String name = text(pin, "name");
      if (name != null) {
        out.add(new Pin(text(pin, "ecosystem"), name));
      }
    }
    return Optional.of(List.copyOf(out));
  }

  /**
   * One GET; the parsed body, a JSON null for a 404 where {@code notFoundIsEmpty}, or empty for
   * every failure.
   */
  private Optional<JsonNode> get(String path, boolean notFoundIsEmpty) {
    if (maintenanceUrl.isEmpty() || maintenanceUrl.get().isBlank()) {
      // Not configured: the caller's WARN says what it costs.
      return Optional.empty();
    }
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(maintenanceUrl.get() + path))
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json")
              .GET();
      Optional<String> authorization = bearer.authorization();
      if (authorization.isPresent()) {
        builder.header("Authorization", authorization.get());
      } else {
        builder.header("X-Qits-User", "qits-projects").header("X-Qits-Roles", "qits:system");
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (notFoundIsEmpty && response.statusCode() == 404) {
        return Optional.of(MAPPER.nullNode());
      }
      if (response.statusCode() / 100 != 2) {
        return unknown(path, "qits-maintenance answered " + response.statusCode());
      }
      return Optional.of(MAPPER.readTree(response.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unknown(path, "interrupted");
    } catch (Exception e) {
      return unknown(path, e.toString());
    }
  }

  private static String segment(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }

  private static <T> Optional<T> unknown(String what, String reason) {
    LOG.warnf("Could not read %s from qits-maintenance: %s", what, reason);
    return Optional.empty();
  }
}
