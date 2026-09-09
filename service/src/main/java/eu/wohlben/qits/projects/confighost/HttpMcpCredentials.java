package eu.wohlben.qits.projects.confighost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.AgentMcpCatalog;
import eu.wohlben.qits.projects.control.McpCredentials;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link McpCredentials}: one GET against qits-configuration's entries route — a
 * hand-rolled {@code java.net.http} client like every outbound client in this module.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   GET {qits.projects.agent-mcp.configuration-url}/configuration/api/applications/qits-agent-mcp
 *       /envs/{qits.projects.agent-mcp.configuration-env}/entries
 *   Authorization: Bearer &lt;machine token, audience qits-configuration&gt;
 *
 *   -&gt; 200 { "entries": [ { "key": "env.EXAMPLE_TOKEN", "value": "…", "entryClass": "plain", … } ] }
 * </pre>
 *
 * <p><b>The whole application's entries, not one key, because there is no single-entry route.</b>
 * qits-configuration's surface is a listing, a resolved map and a per-key PUT/DELETE; the read side
 * is by application. That is fine here — this application holds only MCP credentials and a handful
 * of them, the read happens once per document build rather than per request, and asking for a set is
 * what makes {@link #exists} cheap.
 *
 * <p><b>The application segment is the namespace</b>, fixed at {@link
 * AgentMcpCatalog#CREDENTIAL_APPLICATION} and not configurable. It is reserved: nothing deploys
 * under that name, so no deployer ever renders these keys into an application's environment. See
 * {@code control/AgentMcpCatalog} for why the namespace is the application rather than a key prefix
 * — qits-configuration's key grammar is closed and would refuse one.
 *
 * <h2>Nothing here logs a value, at any level</h2>
 *
 * <p>Every log line names the <em>key</em>, the status code or the exception, and never the body and
 * never the value. The body is parsed with {@code readTree} and the value taken as a string that is
 * returned straight to the caller; it is not interpolated into a message, not put into an exception,
 * and not kept. The one exception type this class can produce carries no value either — a failure is
 * an empty {@link Optional} and the caller turns it into a message naming the key.
 *
 * <h2>Unset is not "no credential"</h2>
 *
 * <p>Address unset, env unset, no bearer, unreachable, refused, 404, unparsable, key absent — every
 * one is {@link Optional#empty()} from {@link #value}, and the document build turns that into a
 * failure naming the key. {@link #exists} distinguishes one more case: a <em>successful</em> read
 * that did not hold the key is {@code Optional.of(false)} ("asked, it is not there"), while a read
 * that could not be made at all is empty ("could not ask"). The catalog's write door needs that
 * third answer — it refuses a confirmed-missing reference and accepts an unconfirmable one — and
 * collapsing the two would make an outage of qits-configuration look like a typo.
 *
 * <p>{@code readTree}, never a bound record, in the {@code maintenancehost/HttpDownstreamComponents}
 * discipline: a record reached through a bare {@link ObjectMapper} would need {@code
 * @RegisterForReflection} to survive a native image and a tree walk needs nothing.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static: a static one is built at
 * image-build time and native-image refuses the {@code HttpClientFacade} that lands in the heap.
 */
@ApplicationScoped
@DefaultBean
public class HttpMcpCredentials implements McpCredentials {

  private static final Logger LOG = Logger.getLogger(HttpMcpCredentials.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The one path this adapter knows. {@code %s} is the env; the application is fixed. */
  static final String ENTRIES_PATH = "/configuration/api/applications/%s/envs/%s/entries";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** The bound on the whole exchange. A container provision waits on it, so it is short. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @ConfigProperty(name = "qits.projects.agent-mcp.configuration-url")
  Optional<String> configurationUrl;

  @ConfigProperty(name = "qits.projects.agent-mcp.configuration-env")
  Optional<String> configurationEnv;

  @Inject ConfigurationBearer bearer;

  @Override
  public Optional<String> value(String key) {
    return read(key).map(Lookup::value);
  }

  @Override
  public Optional<Boolean> exists(String key) {
    Optional<Lookup> lookup = read(key);
    return lookup.map(found -> found.value() != null && !found.value().isBlank());
  }

  /**
   * "Asked, and here is what came back for that key" — or empty for every way of not having asked.
   *
   * <p>A successful read that did not hold the key answers a {@link Lookup} with a null value, which
   * is what lets {@link #exists} say {@code false} where {@link #value} says empty.
   */
  private Optional<Lookup> read(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    if (configurationUrl.isEmpty() || configurationUrl.get().isBlank()) {
      LOG.warnf(
          "Cannot read the MCP credential %s: qits.projects.agent-mcp.configuration-url is unset."
              + " Attaching an external MCP server needs this hop configured.",
          key);
      return Optional.empty();
    }
    if (configurationEnv.isEmpty() || configurationEnv.get().isBlank()) {
      // No default is possible and none is guessed: an env is a real tier on this platform and a
      // wrong one would read another environment's credential, which is worse than not reading one.
      LOG.warnf(
          "Cannot read the MCP credential %s: qits.projects.agent-mcp.configuration-env is unset,"
              + " and there is no environment this service could safely assume.",
          key);
      return Optional.empty();
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      LOG.warnf(
          "Cannot read the MCP credential %s: no machine token for qits-configuration. Both of its"
              + " entry routes take qits:admin or qits:system, so there is no honest header"
              + " fallback for a machine-driven read.",
          key);
      return Optional.empty();
    }
    String url =
        configurationUrl.get()
            + String.format(
                ENTRIES_PATH,
                encode(AgentMcpCatalog.CREDENTIAL_APPLICATION),
                encode(configurationEnv.get()));
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(REQUEST_TIMEOUT)
                  .header("Accept", "application/json")
                  .header("Authorization", authorization.get())
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        // The status code and never the body: a body from a configuration service is the last thing
        // to put in a log.
        LOG.warnf(
            "qits-configuration answered %d reading MCP credentials from application %s in env %s.",
            response.statusCode(), AgentMcpCatalog.CREDENTIAL_APPLICATION, configurationEnv.get());
        return Optional.empty();
      }
      JsonNode entries = MAPPER.readTree(response.body()).get("entries");
      if (entries == null || !entries.isArray()) {
        LOG.warnf(
            "qits-configuration's answer for application %s carries no entries array.",
            AgentMcpCatalog.CREDENTIAL_APPLICATION);
        return Optional.empty();
      }
      for (JsonNode entry : entries) {
        JsonNode entryKey = entry.get("key");
        if (entryKey != null && entryKey.isTextual() && entryKey.asText().equals(key)) {
          JsonNode value = entry.get("value");
          return Optional.of(new Lookup(value != null && value.isTextual() ? value.asText() : null));
        }
      }
      // Asked, and it is not there. A real answer, and the one the catalog's write door refuses on.
      return Optional.of(new Lookup(null));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warnf("Interrupted reading the MCP credential %s from qits-configuration.", key);
      return Optional.empty();
    } catch (Exception e) {
      LOG.warnf("Could not read the MCP credential %s from qits-configuration: %s", key, e.toString());
      return Optional.empty();
    }
  }

  /** A successful read's answer for one key: the value, or null when the store did not hold it. */
  private record Lookup(String value) {}

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8);
  }
}
