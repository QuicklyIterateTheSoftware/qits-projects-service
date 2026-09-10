package eu.wohlben.qits.projects.maintenancehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.projects.control.EstatePins;
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
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link EstatePins}: one POST against qits-maintenance's targeted-bump door — a
 * hand-rolled {@code java.net.http} client, the second one in this package and deliberately built to
 * the same shape as {@link HttpDownstreamComponents} rather than folded into it. Two verbs with
 * different directions and different failure meanings do not share a class; what they share is the
 * address, the credential and the idiom, and those are shared by being spelled the same way.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {qits.projects.release-requests.maintenance-url}/maintenance/api/repositories/&lt;name&gt;/branches/bumps
 *   Authorization: Bearer &lt;machine token, audience qits-platform-maintenance&gt;
 *   Content-Type: application/json
 *
 *   {"branch":"work",
 *    "changes":[{"ecosystem":"gitlink",
 *                "manifestPath":"components/qits-ci/qits-ci-frontend",
 *                "name":"qits-ci-frontend",
 *                "from":"&lt;the sha the tree holds&gt;",
 *                "to":"2026.910.180413",
 *                "location":"gitlink:components/qits-ci/qits-ci-frontend"}]}
 *
 *   -&gt; 202 {"id":"&lt;uuid&gt;"}
 * </pre>
 *
 * <p><b>It is addressed by the repository's NAME, and that is the one place it differs from its
 * neighbour.</b> {@link HttpDownstreamComponents} interpolates the repository's row id, because
 * qits-maintenance's {@code catalogId} <em>is</em> this service's id and the {@code /downstream}
 * route accepts it. This route does not: it resolves its path segment through the catalogue's
 * <b>name</b> index alone, so an id here is a 404 from a repository that is very much there. The
 * asymmetry belongs to the far side's two routes rather than to this package, and the honest thing
 * is to say so at each call site rather than to invent a lookup that papers over it — which is why
 * the port takes a name and the caller reads one out of the alias table it already owns.
 *
 * <h2>The six change fields, and why the enum is a string</h2>
 *
 * <p>{@code ecosystem} is {@code "gitlink"} — the far side's {@code Ecosystem.GITLINK} wire name,
 * lowercase — and it travels as a plain string because that is what it is on the wire. Naming the
 * far side's enum here would be this context holding an opinion about another one's model, and the
 * cost of the string is one constant. {@code location} is the far side's own bookkeeping ({@code
 * "gitlink:" + manifestPath} by convention); it is carried and ignored for a gitlink, and it is sent
 * because a hop that drops a field the far side may one day read is a hop that fails later and
 * further away.
 *
 * <p>The body is built with Jackson nodes rather than concatenated, for the reason every hand-rolled
 * client here does it: a manifest path or a version is data, and a client that interpolates data into
 * JSON is a client that will one day send something that will not parse — or worse, something that
 * will.
 *
 * <h2>Every failure is one behaviour, and a 409 is one of them</h2>
 *
 * <p>The address unset or blank, an unreachable or refusing qits-maintenance, a 400 on a change, a
 * 404 from a repository it has never catalogued, a timeout, a body with no {@code id}: one WARN and
 * {@link Optional#empty()}. So is a <b>409</b>, which means a targeted bump is already REQUESTED or
 * RUNNING on that repository and branch — and that is worth stating, because it is the one refusal
 * that means the pins are probably being written right now. It still reads here as "could not ask",
 * and that is safe rather than merely tolerable: the caller's record of a bump is <em>positive</em>
 * and lives in {@code control/EstatePinLedger}, so an ask that could not be made simply leaves the
 * request holding and the next sweep asks again — the far side's own 409 is what keeps a second ask
 * from becoming a second bump.
 *
 * <p>Nothing here throws, and an {@link InterruptedException} re-interrupts the thread rather than
 * being swallowed: this runs on the fold's thread, which a shutdown interrupts.
 *
 * <p>{@code readTree}, never a bound record, in the {@code wiring/HttpGitHostRepositories}
 * discipline — a record reached through a bare {@link ObjectMapper} needs {@code
 * @RegisterForReflection} to survive a native image and a tree walk needs nothing, so this class adds
 * zero native-image registrations. The {@link HttpClient} is an <b>instance</b> field, not static: a
 * static one is built at image-build time and native-image refuses the {@code HttpClientFacade} that
 * lands in the heap. {@code NativeImageContractTest} pins that.
 */
@ApplicationScoped
@DefaultBean
public class HttpEstatePins implements EstatePins {

  private static final Logger LOG = Logger.getLogger(HttpEstatePins.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The one path this adapter knows. {@code %s} is the repository's <b>name</b> in qits-maintenance's
   * catalogue — see the class javadoc for why this route and its neighbour disagree about that.
   */
  static final String BUMPS_PATH = "/maintenance/api/repositories/%s/branches/bumps";

  /** {@code Ecosystem.GITLINK.wireName()} at the far side, and a string on this one. */
  static final String GITLINK = "gitlink";

  /** How long a connect may take — qits-maintenance is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** The bound on the whole exchange. An arming waits on it, so it is deliberately short. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @ConfigProperty(name = "qits.projects.release-requests.maintenance-url")
  Optional<String> maintenanceUrl;

  @Inject MaintenanceBearer bearer;

  @Override
  public Optional<String> bump(String repositoryName, String branch, List<GitlinkChange> changes) {
    if (maintenanceUrl.isEmpty() || maintenanceUrl.get().isBlank()) {
      // Not configured is the shipped state. It is not worth a line per fold — what the operator
      // sees instead is the request itself, holding and saying why.
      return Optional.empty();
    }
    if (repositoryName == null || repositoryName.isBlank()) {
      return couldNotAsk(repositoryName, branch, "this repository answers to no name here");
    }
    String url =
        maintenanceUrl.get()
            + String.format(
                BUMPS_PATH, URLEncoder.encode(repositoryName, StandardCharsets.UTF_8));
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body(branch, changes)));
      Optional<String> authorization = bearer.authorization();
      if (authorization.isPresent()) {
        builder.header("Authorization", authorization.get());
      } else {
        builder.header("X-Qits-User", "qits-projects").header("X-Qits-Roles", "qits:system");
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return couldNotAsk(
            repositoryName,
            branch,
            "qits-maintenance answered " + response.statusCode() + ": " + message(response.body()));
      }
      JsonNode id = MAPPER.readTree(response.body()).get("id");
      if (id == null || !id.isTextual() || id.asText().isBlank()) {
        return couldNotAsk(repositoryName, branch, "the answer names no bump");
      }
      LOG.debugf(
          "Asked qits-maintenance to write %d gitlink pin(s) on %s of %s; bump %s",
          changes.size(), branch, repositoryName, id.asText());
      return Optional.of(id.asText());
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this runs on threads a shutdown interrupts.
      Thread.currentThread().interrupt();
      return couldNotAsk(repositoryName, branch, "interrupted");
    } catch (Exception e) {
      return couldNotAsk(repositoryName, branch, e.toString());
    }
  }

  /** The request body, as nodes — see the class javadoc for why this is not a format string. */
  private static String body(String branch, List<GitlinkChange> changes)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("branch", branch);
    ArrayNode array = root.putArray("changes");
    for (GitlinkChange change : changes) {
      ObjectNode node = array.addObject();
      node.put("ecosystem", GITLINK);
      node.put("manifestPath", change.manifestPath());
      node.put("name", change.name());
      // from is commit-message material and nullable; a null travels as a null rather than being
      // omitted, because the far side reads six fields and an absent one is a shape it never sees.
      node.put("from", change.from());
      node.put("to", change.to());
      node.put("location", GITLINK + ":" + change.manifestPath());
    }
    return MAPPER.writeValueAsString(root);
  }

  /** The far side renders every refusal as {@code {"message":"…"}}; anything else is the body. */
  private static String message(String body) {
    if (body == null || body.isBlank()) {
      return "(no body)";
    }
    try {
      JsonNode node = MAPPER.readTree(body).get("message");
      return node != null && node.isTextual() ? node.asText() : body.trim();
    } catch (Exception e) {
      return body.trim();
    }
  }

  /**
   * One WARN and "could not ask". Warn rather than debug because the consequence is a wrapper
   * release request that will sit PENDING until this succeeds, and the sentence on the request says
   * only that the pins could not be refreshed — this line is where the reason is.
   */
  private static Optional<String> couldNotAsk(String repositoryName, String branch, String reason) {
    LOG.warnf(
        "Could not ask qits-maintenance to write the estate pins on %s of %s: %s."
            + " The wrapper's release request holds until this answers.",
        branch, repositoryName, reason);
    return Optional.empty();
  }
}
