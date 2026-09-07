package eu.wohlben.qits.projects.maintenancehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.DownstreamComponents;
import io.quarkus.arc.DefaultBean;
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
 * The shipped {@link DownstreamComponents}: one GET against qits-maintenance's downstream-closure
 * door — a hand-rolled {@code java.net.http} client like every outbound client in this module,
 * because the seam is one read.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   GET {qits.projects.release-requests.maintenance-url}/maintenance/api/repositories/&lt;repoId&gt;/downstream
 *   Authorization: Bearer &lt;machine token, audience qits-platform-maintenance&gt;
 *
 *   -&gt; 200 {
 *        "repository": "qits-ui-components-jslib",
 *        "catalogId":  "&lt;this service's row id&gt;",
 *        "downstream": [
 *          {"repository": "qits-ci-frontend", "catalogId": "…", "archetype": "FRONTEND",
 *           "depth": 1, "via": ["qits-ui-components-jslib"]},
 *          {"repository": "qits-ci-service",  "catalogId": "…", "archetype": "SERVICE",
 *           "depth": 2, "via": ["qits-ci-frontend"]}
 *        ]
 *      }
 * </pre>
 *
 * <p><b>It is addressed by the repository's ROW ID and needs no name lookup.</b> qits-maintenance's
 * {@code catalogId} <em>is</em> this service's repository id — the path segment accepts either
 * spelling, and the one this side always holds is the id. An unknown repository is a 200 with an
 * empty {@code downstream}, deliberately, so a repository qits-maintenance has never catalogued reads
 * as a leaf rather than as a failure of the announce path.
 *
 * <p><b>Only {@code repository} is read, in the order the far side sent.</b> The order IS the answer
 * — depth ascending, then name — so the projection preserves it and never sorts. Depth, archetype,
 * via and the two envelope members are ignored here: what the consumer orders a queue with is the
 * names, and reading more would be this side holding an opinion about another context's model.
 *
 * <h2>Every failure is one behaviour</h2>
 *
 * <p>The address unset or blank, an unreachable or refusing qits-maintenance, a 404 from a version
 * that has not shipped the route yet, a timeout, a body that will not parse, a body with no {@code
 * downstream} array: one WARN and {@link Optional#empty()}. Empty is "could not ask" and the caller
 * turns it into an absent event key, which every consumer already reads as unknown. <b>A 200 with a
 * well-formed empty array is NOT that</b> — it is {@code Optional.of(List.of())}, "asked, and this
 * repository is a leaf", and the two must not be collapsed. Nothing here throws: this runs after a
 * fold has already landed and an enrichment may never fail an announcement.
 *
 * <p>The credential is the {@code maintenance} named OIDC client's bearer ({@link
 * IdpMaintenanceBearer}), assumed to need {@code qits:system} at the far side. While the client is
 * off — the shipped default, and any no-idp topology — the hop falls back to the forwarded {@code
 * X-Qits-*} pair, the posture {@code releasehost/HttpQaRunCancellations} takes for the same reason:
 * this door is a read that decides nothing.
 *
 * <p><b>{@code readTree}, never a bound record</b>, in the {@code wiring/HttpGitHostRepositories}
 * discipline: a record reached through a bare {@link ObjectMapper} needs {@code
 * @RegisterForReflection} to survive a native image and a tree walk needs nothing, so this class adds
 * zero native-image registrations.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static: a static one is built at
 * image-build time and native-image refuses the {@code HttpClientFacade} that lands in the heap.
 * {@code NativeImageContractTest} pins that.
 */
@ApplicationScoped
@DefaultBean
public class HttpDownstreamComponents implements DownstreamComponents {

  private static final Logger LOG = Logger.getLogger(HttpDownstreamComponents.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The one path this adapter knows, and the whole of Contract A as seen from here. {@code %s} is
   * the repository's row id, which is qits-maintenance's {@code catalogId}.
   */
  static final String DOWNSTREAM_PATH = "/maintenance/api/repositories/%s/downstream";

  /** How long a connect may take — qits-maintenance is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** The bound on the whole exchange. An announcement waits on it, so it is deliberately short. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @ConfigProperty(name = "qits.projects.release-requests.maintenance-url")
  Optional<String> maintenanceUrl;

  @Inject MaintenanceBearer bearer;

  @Override
  public Optional<List<String>> downstreamOf(String repoId, String repoName) {
    if (maintenanceUrl.isEmpty() || maintenanceUrl.get().isBlank()) {
      // Not configured is the shipped state and is not worth a line per fold.
      return Optional.empty();
    }
    String url = maintenanceUrl.get() + String.format(DOWNSTREAM_PATH, repoId);
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(url))
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
        return unknown(repoId, repoName, "qits-maintenance answered " + response.statusCode());
      }
      JsonNode downstream = MAPPER.readTree(response.body()).get("downstream");
      if (downstream == null || !downstream.isArray()) {
        return unknown(repoId, repoName, "the answer carries no downstream array");
      }
      List<String> names = new ArrayList<>();
      for (JsonNode entry : downstream) {
        JsonNode repository = entry.get("repository");
        if (repository == null || !repository.isTextual() || repository.asText().isBlank()) {
          return unknown(repoId, repoName, "an entry of the closure names no repository");
        }
        names.add(repository.asText());
      }
      // Order is the answer: nearest first, exactly as the far side sent it.
      return Optional.of(List.copyOf(names));
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this runs on threads a shutdown interrupts.
      Thread.currentThread().interrupt();
      return unknown(repoId, repoName, "interrupted");
    } catch (Exception e) {
      return unknown(repoId, repoName, e.toString());
    }
  }

  /**
   * One WARN and "could not ask". Warn rather than debug because the consequence is silent at every
   * later hop: the event simply carries no closure and qits-ci orders its queue without one.
   */
  private static Optional<List<String>> unknown(String repoId, String repoName, String reason) {
    LOG.warnf(
        "Could not read what is downstream of %s (%s) from qits-maintenance: %s."
            + " The release-request announcement carries no closure and the queue order is"
            + " unconstrained by it.",
        repoName == null ? repoId : repoName, repoId, reason);
    return Optional.empty();
  }
}
