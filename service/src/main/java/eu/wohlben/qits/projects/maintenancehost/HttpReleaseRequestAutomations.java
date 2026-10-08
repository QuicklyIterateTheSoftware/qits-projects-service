package eu.wohlben.qits.projects.maintenancehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.control.ReleaseRequestAutomations;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link ReleaseRequestAutomations}: qits-maintenance's three release-request automation
 * doors (epic qits-978) — a hand-rolled {@code java.net.http} client built to the same shape as
 * {@link HttpDownstreamComponents} rather than folded into it. Two verbs with different directions
 * and different failure meanings do not share a class; what they share is the address, the
 * credential and the idiom, and those are shared by being spelled the same way.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {maintenance-url}/maintenance/api/release-requests/&lt;requestId&gt;/automations
 *   {"repository":"qits-landing-app","foldSha":"&lt;sha&gt;","previousFoldSha":"&lt;sha&gt;"|null,
 *    "changedSincePrevious":["src/app/x.ts"]|null,"sourceBranches":["ticket/x"],"workItem":null}
 *   -&gt; 200 {"requestId":"…","foldSha":"&lt;sha&gt;",
 *           "automations":[{"kind":"screenshot-baselines","label":"Screenshot baselines",
 *                           "state":"RUNNING","detail":"…","bumpId":"…","runIds":["…"],
 *                           "branch":"maintenance/automations/screenshot-baselines/&lt;rr&gt;",
 *                           "resultSha":null,"updatedAt":"…"}]}
 *
 *   GET  {maintenance-url}/maintenance/api/release-requests/&lt;requestId&gt;/automations?foldSha=&lt;sha&gt;
 *   -&gt; 200, the same shape; a request nothing was asked about answers foldSha null and no entries
 *
 *   POST {maintenance-url}/maintenance/api/release-requests/&lt;requestId&gt;/automations/&lt;kind&gt;/runs
 *   {"workItem":null,"repository":"qits-landing-app"}
 *   -&gt; 202 {"id":"&lt;uuid&gt;"}; 404 unknown kind or repository; 409 not open, no fold, one active,
 *      bumping off
 * </pre>
 *
 * <p><b>The repository travels by NAME</b>, because qits-maintenance's catalogue resolves it through
 * its name index — the caller reads one out of the alias table it already owns. The re-run sends it
 * too: the far side needs it for a request's first run of a kind, before anything was asked.
 *
 * <h2>Two failure contracts, by verb</h2>
 *
 * <p>The trigger and the read are asked on the fold path and by the sweep, and nobody waits on them:
 * the address unset or blank, an unreachable or refusing qits-maintenance, a 400, a 404 from a
 * repository it has never catalogued, a timeout, a body that will not parse — one WARN and {@link
 * Optional#empty()}, which leaves the request holding with a sentence (the caller's record is
 * positive, see {@code control/AutomationLedger}), and the next sweep asks again.
 *
 * <p>The re-run is a person's press, so its answer keeps the distinction the person needs: a 2xx
 * with an id is accepted, a 4xx is a refusal whose status and {@code message} are passed through, and
 * everything else — no address, unreachable, 5xx, unparseable — is "could not ask", which the door
 * turns into a 503.
 *
 * <p>Nothing here throws, and an {@link InterruptedException} re-interrupts the thread rather than
 * being swallowed. {@code readTree}, never a bound record, so this class adds zero native-image
 * registrations; the {@link HttpClient} is an <b>instance</b> field, because a static one is built at
 * image-build time and native-image refuses the {@code HttpClientFacade} that lands in the heap.
 */
@ApplicationScoped
@DefaultBean
public class HttpReleaseRequestAutomations implements ReleaseRequestAutomations {

  private static final Logger LOG = Logger.getLogger(HttpReleaseRequestAutomations.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The trigger and the read. {@code %s} is the request id. */
  static final String AUTOMATIONS_PATH = "/maintenance/api/release-requests/%s/automations";

  /** The re-run. {@code %s} is the request id, then the kind. */
  static final String RUNS_PATH = "/maintenance/api/release-requests/%s/automations/%s/runs";

  /** How long a connect may take — qits-maintenance is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * The bound on a trigger or a re-run. Longer than a read because the far side decides each kind's
   * applicability and plan inside it, which is a handful of git-host reads; still one exchange.
   */
  private static final Duration POST_TIMEOUT = Duration.ofSeconds(10);

  /** The bound on a read, which touches nothing but the far side's own rows. */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @ConfigProperty(name = "qits.projects.release-requests.maintenance-url")
  Optional<String> maintenanceUrl;

  @Inject MaintenanceBearer bearer;

  @Override
  public boolean configured() {
    return maintenanceUrl.isPresent() && !maintenanceUrl.get().isBlank();
  }

  @Override
  public Optional<Answer> request(
      String repositoryName,
      String requestId,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches,
      String workItem) {
    if (!configured()) {
      return Optional.empty();
    }
    if (repositoryName == null || repositoryName.isBlank()) {
      return couldNotAsk(
          "settle the automations of " + requestId, "this repository answers to no name here");
    }
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("repository", repositoryName);
      body.put("foldSha", foldSha);
      body.put("previousFoldSha", previousFoldSha);
      if (changedSincePrevious == null) {
        // Null and empty are different answers on the far side — null never carries an outcome
        // over, empty says nothing changed — so null travels as null.
        body.putNull("changedSincePrevious");
      } else {
        ArrayNode paths = body.putArray("changedSincePrevious");
        changedSincePrevious.forEach(paths::add);
      }
      ArrayNode branches = body.putArray("sourceBranches");
      (sourceBranches == null ? List.<String>of() : sourceBranches).forEach(branches::add);
      body.put("workItem", workItem);
      HttpResponse<String> response =
          send(
              base(requestId),
              POST_TIMEOUT,
              HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
      if (response.statusCode() != 200) {
        return couldNotAsk(
            "settle the automations of " + requestId + " at " + foldSha,
            "qits-maintenance answered " + response.statusCode() + ": " + message(response.body()));
      }
      return answer(response.body(), "settle the automations of " + requestId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return couldNotAsk("settle the automations of " + requestId, "interrupted");
    } catch (Exception e) {
      return couldNotAsk("settle the automations of " + requestId, e.toString());
    }
  }

  @Override
  public Optional<Answer> status(String repositoryName, String requestId, String foldSha) {
    if (!configured()) {
      return Optional.empty();
    }
    try {
      String url =
          base(requestId)
              + (foldSha == null ? "" : "?foldSha=" + URLEncoder.encode(foldSha, StandardCharsets.UTF_8));
      HttpResponse<String> response = send(url, READ_TIMEOUT, null);
      if (response.statusCode() != 200) {
        return couldNotAsk(
            "read the automations of " + requestId,
            "qits-maintenance answered " + response.statusCode() + ": " + message(response.body()));
      }
      return answer(response.body(), "read the automations of " + requestId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return couldNotAsk("read the automations of " + requestId, "interrupted");
    } catch (Exception e) {
      return couldNotAsk("read the automations of " + requestId, e.toString());
    }
  }

  @Override
  public Run rerun(String repositoryName, String requestId, String kind) {
    if (!configured()) {
      return Run.unreachable("qits-maintenance has no address here");
    }
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.putNull("workItem");
      body.put("repository", repositoryName);
      String url =
          maintenanceUrl.get()
              + String.format(
                  RUNS_PATH,
                  URLEncoder.encode(requestId, StandardCharsets.UTF_8),
                  URLEncoder.encode(kind, StandardCharsets.UTF_8));
      HttpResponse<String> response =
          send(
              url,
              POST_TIMEOUT,
              HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
      int status = response.statusCode();
      if (status / 100 == 2) {
        JsonNode id = MAPPER.readTree(response.body()).get("id");
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
          LOG.warnf("qits-maintenance accepted a re-run of %s on %s and named no run", kind, requestId);
          return Run.unreachable("the answer names no run");
        }
        return Run.accepted(id.asText());
      }
      if (status / 100 == 4) {
        // A refusal is a fact the person pressing the button has not got: passed through, sentence
        // and all. 401 and 403 are this hop's credential, not the request's, and are not.
        if (status == 401 || status == 403) {
          LOG.warnf(
              "qits-maintenance refused this service's credential re-running %s on %s: %d",
              kind, requestId, status);
          return Run.unreachable("qits-maintenance refused this service's credential");
        }
        return Run.refused(status, message(response.body()));
      }
      LOG.warnf(
          "Could not ask qits-maintenance to re-run %s on %s: it answered %d: %s",
          kind, requestId, status, message(response.body()));
      return Run.unreachable("qits-maintenance answered " + status);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Run.unreachable("interrupted");
    } catch (Exception e) {
      LOG.warnf("Could not ask qits-maintenance to re-run %s on %s: %s", kind, requestId, e);
      return Run.unreachable(e.getClass().getSimpleName());
    }
  }

  private String base(String requestId) {
    return maintenanceUrl.get()
        + String.format(AUTOMATIONS_PATH, URLEncoder.encode(requestId, StandardCharsets.UTF_8));
  }

  /** One exchange, with the credential every hop to qits-maintenance carries. */
  private HttpResponse<String> send(
      String url, Duration timeout, HttpRequest.BodyPublisher post) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json");
    if (post == null) {
      builder.GET();
    } else {
      builder.header("Content-Type", "application/json").POST(post);
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isPresent()) {
      builder.header("Authorization", authorization.get());
    } else {
      builder.header("X-Qits-User", "qits-projects").header("X-Qits-Roles", "qits:system");
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** The far side's answer, as nodes — absent members read as absent, never as a failure. */
  private static Optional<Answer> answer(String body, String what) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    if (root == null || !root.isObject()) {
      return couldNotAsk(what, "the answer is not an object");
    }
    JsonNode entries = root.get("automations");
    if (entries != null && !entries.isNull() && !entries.isArray()) {
      return couldNotAsk(what, "automations is not a list");
    }
    List<Automation> automations = new ArrayList<>();
    if (entries != null && entries.isArray()) {
      for (JsonNode entry : entries) {
        List<String> runIds = new ArrayList<>();
        JsonNode runs = entry.get("runIds");
        if (runs != null && runs.isArray()) {
          runs.forEach(run -> runIds.add(run.asText()));
        }
        String updated = text(entry, "updatedAt");
        automations.add(
            new Automation(
                text(entry, "kind"),
                text(entry, "label"),
                text(entry, "state"),
                text(entry, "detail"),
                text(entry, "bumpId"),
                runIds,
                text(entry, "branch"),
                text(entry, "resultSha"),
                updated == null ? null : parseInstant(updated),
                failure(entry.get("failure"))));
      }
    }
    return Optional.of(
        new Answer(text(root, "requestId"), text(root, "foldSha"), List.copyOf(automations)));
  }

  /**
   * An automation's {@code failure}, or null — absent (an older qits-maintenance that does not send
   * it), null, or not an object all read as "nothing said", never as a parse failure: it is a
   * reason for a person, and the gate decides nothing on it.
   */
  private static AutomationLedger.Failure failure(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode exitCode = node.get("exitCode");
    JsonNode stepIndex = node.get("stepIndex");
    return new AutomationLedger.Failure(
        stepIndex == null || !stepIndex.canConvertToInt() ? 0 : stepIndex.asInt(),
        text(node, "image"),
        exitCode == null || !exitCode.canConvertToInt() ? null : exitCode.asInt(),
        text(node, "excerpt"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Instant parseInstant(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      return null;
    }
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
   * One WARN and "could not ask". Warn rather than debug because the consequence is a release
   * request that holds until this succeeds, and the sentence on the request says only that the
   * automations could not be established — this line is where the reason is.
   */
  private static Optional<Answer> couldNotAsk(String what, String reason) {
    LOG.warnf(
        "Could not ask qits-maintenance to %s: %s. The release request holds until this answers.",
        what, reason);
    return Optional.empty();
  }
}
