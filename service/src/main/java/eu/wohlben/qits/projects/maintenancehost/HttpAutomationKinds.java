package eu.wohlben.qits.projects.maintenancehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.AutomationKinds;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link AutomationKinds}: one GET against qits-maintenance (task qits-1046), on the
 * address and the credential {@link HttpDownstreamPins} uses — {@code
 * qits.projects.release-requests.maintenance-url} and the {@link MaintenanceBearer}, falling back to
 * the forwarded {@code X-Qits-*} pair while the named client is off. The door admits {@code
 * qits:system} and {@code qits:agent}.
 *
 * <pre>
 *   GET {maintenance-url}/maintenance/api/automations/kinds
 *   -&gt; 200 {"kinds": [{"kind", "label", "committablePaths": [], "approvalRoles": []}]}
 * </pre>
 *
 * <p>No cache here — {@code approval/AutomationContentSource} keeps the last good answer. Every
 * failure — an unreachable or refusing far side, a timeout, a body that will not parse — is one WARN
 * and {@link Optional#empty()}; nothing here throws. {@code readTree}, never a bound record, and an
 * instance {@link HttpClient}, for {@link HttpDownstreamComponents}' native-image reasons.
 */
@ApplicationScoped
@DefaultBean
public class HttpAutomationKinds implements AutomationKinds {

  private static final Logger LOG = Logger.getLogger(HttpAutomationKinds.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final String KINDS_PATH = "/maintenance/api/automations/kinds";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** The bound on one exchange; an approval read waits on it, at most once per five minutes. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient client;

  @ConfigProperty(name = "qits.projects.release-requests.maintenance-url")
  Optional<String> maintenanceUrl;

  @Inject MaintenanceBearer bearer;

  @Inject
  public HttpAutomationKinds(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  @Override
  public boolean configured() {
    return maintenanceUrl.isPresent() && !maintenanceUrl.get().isBlank();
  }

  @Override
  public Optional<List<Kind>> kinds() {
    if (!configured()) {
      return Optional.empty();
    }
    JsonNode body;
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(maintenanceUrl.get() + KINDS_PATH))
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
      if (response.statusCode() / 100 != 2) {
        return unknown("qits-maintenance answered " + response.statusCode());
      }
      body = MAPPER.readTree(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unknown("interrupted");
    } catch (Exception e) {
      return unknown(e.toString());
    }
    JsonNode kinds = body == null ? null : body.get("kinds");
    if (kinds == null || !kinds.isArray()) {
      return unknown("the answer carries no kinds array");
    }
    List<Kind> out = new ArrayList<>();
    for (JsonNode entry : kinds) {
      String kind = text(entry, "kind");
      if (kind == null) {
        return unknown("an entry names no kind");
      }
      Optional<List<String>> paths = strings(entry, "committablePaths");
      Optional<List<String>> roles = strings(entry, "approvalRoles");
      if (paths.isEmpty() || roles.isEmpty()) {
        // An absent role list must not read as "needs no approval".
        return unknown("the kind " + kind + " carries no committablePaths or approvalRoles array");
      }
      out.add(new Kind(kind, text(entry, "label"), paths.get(), roles.get()));
    }
    return Optional.of(List.copyOf(out));
  }

  private static Optional<List<String>> strings(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      return Optional.empty();
    }
    List<String> out = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isTextual()) {
        return Optional.empty();
      }
      out.add(item.asText());
    }
    return Optional.of(out);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }

  private static <T> Optional<T> unknown(String reason) {
    LOG.warnf("Could not read the automation kinds catalogue from qits-maintenance: %s", reason);
    return Optional.empty();
  }
}
