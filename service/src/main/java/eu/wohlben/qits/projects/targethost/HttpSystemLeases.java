package eu.wohlben.qits.projects.targethost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The shipped {@link SystemLeases}: qits-system's doors at {@code qits.system.url} (scheme, host and
 * port, no path — qits-system serves its own {@code /system/api} segment), with this service's own
 * bearer ({@link SystemBearer}).
 *
 * <p><b>{@code Map} and {@code JsonNode}, never a DTO</b>, in both directions — {@code
 * workspacehost/HttpReleasedBranchWorkspaces}' discipline: a record reached through a bare {@link
 * ObjectMapper} needs {@code @RegisterForReflection} to survive a native image, a tree needs
 * nothing, so this class owes native-image no registration.
 *
 * <p><b>Every failure is a {@link LeaseRefused}</b>, with qits-system's status and {@code code}
 * where it answered ({@code ErrorBody{code, message}}) and -1 where nothing did. A 404 to a read or
 * an end is no lease, the same outcome, and answers empty.
 *
 * <p>The {@link HttpClient} is an instance field (a static one lands in the image heap, which
 * native-image refuses) and is wrapped with {@link QitsHttpClients}.
 */
@ApplicationScoped
@DefaultBean
public class HttpSystemLeases implements SystemLeases {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient client;

  @ConfigProperty(name = "qits.system.url")
  String systemUrl;

  @Inject SystemBearer bearer;

  @Inject
  public HttpSystemLeases(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /** Shuts the client down with the bean: a leaked SelectorManager thread per instance (qits-1149). */
  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  @Override
  public Lease create(LeaseRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slotType", SLOT_TYPE);
    body.put("ownerRef", request.projectId());
    body.put("workKey", SystemLeases.workKey(request.projectId()));
    body.put("priority", 0);
    body.put("spec", spec(request.spec()));
    if (request.pinTargetId() != null) {
      body.put("pinTargetId", request.pinTargetId().toString());
    }
    if (request.adopt() != null) {
      Map<String, String> adopt = new LinkedHashMap<>();
      adopt.put("container", request.adopt().container());
      adopt.put("volume", request.adopt().volume());
      body.put("adopt", adopt);
    }
    return lease(
        exchange(
            "POST", "/leases", body, "ask for a lease for the desk of " + request.projectId()));
  }

  @Override
  public Optional<Lease> get(UUID leaseId) {
    return optional("GET", "/leases/" + leaseId, null, "read lease " + leaseId);
  }

  @Override
  public List<Lease> list() {
    JsonNode listed = exchange("GET", "/leases", null, "list this service's leases");
    List<Lease> leases = new ArrayList<>();
    if (listed != null && listed.isArray()) {
      listed.forEach(node -> leases.add(lease(node)));
    }
    return List.copyOf(leases);
  }

  @Override
  public Lease replaceSpec(UUID leaseId, LeaseSpec spec) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("spec", spec(spec));
    return lease(exchange("PUT", "/leases/" + leaseId + "/spec", body, "roll lease " + leaseId));
  }

  @Override
  public Optional<Lease> end(UUID leaseId) {
    return optional("DELETE", "/leases/" + leaseId, null, "end lease " + leaseId);
  }

  @Override
  public boolean purgeWorkVolume(String workKey) {
    try {
      exchange(
          "DELETE",
          "/work-volumes/" + URLEncoder.encode(workKey, StandardCharsets.UTF_8),
          null,
          "purge work volume " + workKey);
      return true;
    } catch (LeaseRefused refused) {
      if (refused.status() == 404) {
        return false;
      }
      throw refused;
    }
  }

  @Override
  public List<Target> targets() {
    JsonNode listed = exchange("GET", "/targets", null, "list the targets");
    List<Target> targets = new ArrayList<>();
    if (listed != null && listed.isArray()) {
      for (JsonNode t : listed) {
        targets.add(
            new Target(
                uuid(text(t, "id")),
                text(t, "name"),
                blankless(text(t, "engineId")),
                blankless(text(t, "hostname")),
                t.path("minimums").path("desk").asInt(0)));
      }
    }
    return List.copyOf(targets);
  }

  // --- internals --------------------------------------------------------------------------------

  /** The wire form of a spec: unset limits left out. */
  static Map<String, Object> spec(LeaseSpec spec) {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("image", spec.image());
    wire.put("env", spec.env() == null ? Map.of() : spec.env());
    if (spec.user() != null) {
      wire.put("user", spec.user());
    }
    Limits limits = spec.limits();
    if (limits != null) {
      Map<String, Object> l = new LinkedHashMap<>();
      putIfSet(l, "memory", limits.memory());
      putIfSet(l, "memorySwap", limits.memorySwap());
      putIfSet(l, "pidsLimit", limits.pidsLimit());
      putIfSet(l, "cpus", limits.cpus());
      putIfSet(l, "oomScoreAdj", limits.oomScoreAdj());
      if (!l.isEmpty()) {
        wire.put("limits", l);
      }
    }
    return wire;
  }

  private static void putIfSet(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private Optional<Lease> optional(String method, String path, Object body, String what) {
    try {
      JsonNode answer = exchange(method, path, body, what);
      return Optional.ofNullable(answer == null ? null : lease(answer));
    } catch (LeaseRefused refused) {
      if (refused.status() == 404) {
        return Optional.empty();
      }
      throw refused;
    }
  }

  private JsonNode exchange(String method, String path, Object body, String what) {
    if (systemUrl == null || systemUrl.isBlank()) {
      throw new LeaseRefused(-1, null, "qits.system.url is unset, so nothing can " + what, null);
    }
    String base =
        systemUrl.endsWith("/") ? systemUrl.substring(0, systemUrl.length() - 1) : systemUrl;
    HttpResponse<String> response;
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(base + "/system/api" + path))
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json");
      bearer.authorization().ifPresent(value -> request.header("Authorization", value));
      if (body == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      }
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LeaseRefused(-1, null, "interrupted while asked to " + what, e);
    } catch (Exception transport) {
      throw new LeaseRefused(
          -1, null, "qits-system could not be asked to " + what + ": " + transport, transport);
    }
    int status = response.statusCode();
    String text = response.body();
    if (status / 100 != 2) {
      String code = null;
      String message = null;
      try {
        JsonNode error = text == null || text.isBlank() ? null : JSON.readTree(text);
        code = text(error, "code");
        message = text(error, "message");
      } catch (Exception unreadable) {
        message = null;
      }
      throw new LeaseRefused(
          status,
          code,
          "qits-system answered " + status + " when asked to " + what
              + (message == null ? "" : ": " + message),
          null);
    }
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return JSON.readTree(text);
    } catch (Exception unreadable) {
      throw new LeaseRefused(
          status, null, "qits-system's answer when asked to " + what + " is not JSON", unreadable);
    }
  }

  static Lease lease(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode exit = node.path("exitCode");
    return new Lease(
        uuid(text(node, "id")),
        text(node, "ownerRef"),
        text(node, "slotType"),
        LeaseState.parse(text(node, "state")),
        uuid(text(node, "targetId")),
        exit.isNumber() ? exit.asInt() : null,
        text(node, "detail"),
        text(node, "specHash"),
        instant(text(node, "requestedAt")));
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.path(field).isTextual() ? node.path(field).asText() : null;
  }

  private static String blankless(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static UUID uuid(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(text.trim());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  private static Instant instant(String text) {
    if (text == null) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (RuntimeException unparsable) {
      return null;
    }
  }
}
