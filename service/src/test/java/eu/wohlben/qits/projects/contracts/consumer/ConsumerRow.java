package eu.wohlben.qits.projects.contracts.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>One REST call this service makes, as a consumer pact row</b> (ticket qits-1149).
 *
 * <p>A row names the provider (by REPOSITORY name, which is what a pact names), the provider's
 * operation, the provider state whose recorded answer it is bound to, the request exactly as this
 * service sends it, and the response paths this service reads ({@link #consumes}). Empty {@code
 * consumes} means the call reads nothing but the status, so the pact binds the status alone.
 *
 * <p>The request is this consumer's OWN expectation. A {@code {name}} anywhere in the path, a query
 * value or a request-body string is a provider-state parameter: the state's recorded example in the
 * consumer test, a provider-state expression for the verifier.
 *
 * <p>{@link #call} runs the real client against the mock server, with the state's params, and
 * asserts what the client made of the answer.
 *
 * @param provider the provider repository, e.g. {@code qits-ci-service}
 * @param providerApp the provider application, as its golden-master index names it
 * @param needs what the provider's recording must hold for this row, for the inventory and the
 *     skip reason while the state is not recorded yet
 */
public record ConsumerRow(
    String provider,
    String providerApp,
    String operationId,
    String state,
    String method,
    String path,
    Map<String, String> query,
    JsonNode requestBody,
    List<String> consumes,
    int status,
    Trigger trigger,
    Call call,
    String needs) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ConsumerRow {
    Objects.requireNonNull(trigger, "trigger: every row names the entry point that makes the call");
    query = query == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(query));
    consumes = consumes == null ? List.of() : List.copyOf(consumes);
  }

  /** What the row does with the real client, given the mock server and the state's params. */
  @FunctionalInterface
  public interface Call {
    void run(String baseUrl, Map<String, String> params) throws Exception;
  }

  /**
   * What made this service make the call: the {@code qits-trigger} reference. {@code operation}
   * names this service's own operationId (or, for a door the OpenAPI document does not list, its
   * JAX-RS method), {@code event} a bus event, {@code schedule} a scheduled or boot-time method.
   */
  public record Trigger(String kind, String app, String key, String value) {

    public static final String CONSUMER = "qits-projects-service";

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(app, "app");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    public static Trigger operation(String operationId) {
      return new Trigger("operation", CONSUMER, "operationId", operationId);
    }

    public static Trigger event(String event) {
      return new Trigger("event", CONSUMER, "event", event);
    }

    public static Trigger schedule(String schedule) {
      return new Trigger("schedule", CONSUMER, "schedule", schedule);
    }

    /** The {@code qits-trigger} group, every value a string, in a fixed key order. */
    public Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", app);
      ref.put(key, value);
      return ref;
    }
  }

  /** The interaction's description: the trigger first, so (description, state) stays unique. */
  public String description() {
    return trigger.value() + ": " + operationId;
  }

  /** The recording this row is bound to, when the provider's published jar holds it. */
  public Optional<ConsumerGoldenMasters.Recorded> recorded(ConsumerGoldenMasters masters) {
    return masters.find(providerApp, state, operationId);
  }

  /** Why the row cannot run yet, or empty when it can. */
  public Optional<String> pending(ConsumerGoldenMasters masters) {
    if (recorded(masters).isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        "needs provider state '" + state + "' for " + operationId + " in " + provider + " (" + needs
            + ")");
  }

  /** A request body from JSON text: the tables' spelling. */
  public static JsonNode json(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException("not JSON: " + text, e);
    }
  }
}
