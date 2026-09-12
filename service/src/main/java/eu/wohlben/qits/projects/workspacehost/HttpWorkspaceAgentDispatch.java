package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link WorkspaceAgentDispatch}: one POST to qits-workspaces' agent-dispatch door.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {workspaces-url}/workspaces/api/agent-dispatches
 *   Authorization: Bearer &lt;machine token, audience qits-workspaces&gt;
 *   Content-Type: application/json
 *
 *   {"repositoryId": "…", "branch": "ticket/&lt;slug&gt;",
 *    "gitRefs": ["refs/heads/ticket/&lt;slug&gt;"], "branchTree": true,
 *    "ticketId": "…", "instruction": "&lt;the agent's first turn&gt;"}
 *
 *   -&gt; 200 {"workspace": {"id": 41, …}, "fresh": true,
 *           "agentLaunch": "SCHEDULED"|"SKIPPED_RUNNING", "technicalProcessId": "…"|null}
 * </pre>
 *
 * <p>Anything that is not a 200 is a dispatch that did not happen.
 *
 * <h2>A NEW class, not a second verb on {@link HttpReleasedBranchWorkspaces}</h2>
 *
 * <p>The package doc forbids growing that class a second verb, and the reason survives this change
 * intact: what travels through it is a workspace-lifecycle <em>fact</em>, announced after a release
 * that already happened, and its whole contract is that it never throws. This is a <em>request</em>
 * whose outcome a person is waiting on and whose failure has to reach them. Two verbs with opposite
 * failure contracts do not belong in one class. What is shared is the discipline, and it is copied
 * rather than inherited: {@code Map} in both directions, an instance {@link HttpClient}, the bearer
 * through {@link WorkspacesBearer}, no DTO and therefore no native-image registration.
 *
 * <h2>The address, and why there is a new key for it</h2>
 *
 * <p>{@code qits.projects.workspaces-url} is <b>this service's address for qits-workspaces</b>, and
 * it is what this hop reads. When it is unset or blank the hop falls back to
 * {@code qits.projects.release-requests.workspaces-url} — the key that already ships set, and the
 * one the deployed platform already injects — so a deployment that has configured qits-workspaces
 * once has configured it for this door too, and nothing had to be redeployed for the dispatch to
 * work.
 *
 * <p>The alternative was to read the old key and be done. It was rejected on its name: that key is
 * spelled {@code release-requests} and its whole documented job is one lifecycle call made after a
 * release, and a ticket dispatch reading it would put a second, unrelated flow behind a name that
 * lies about both. The fallback is what keeps that honesty from costing a configuration change: one
 * value to set today, and a correct name to move to. When every environment names the new key, the
 * old one narrows back to the release path it is named for.
 *
 * <h2>Failure is an answer here, not a WARN</h2>
 *
 * <p>The port's javadoc is the rule and this adapter implements exactly it: <b>503</b> when the hop
 * is not configured — no address, or no machine credential, which is a platform that cannot reach
 * qits-workspaces at all — and <b>502</b> for everything about the exchange: a non-200, an
 * unreachable or timing-out far side, an answer that will not parse or carries no workspace id.
 * There is no forwarded-header fallback, for {@link HttpReleasedBranchWorkspaces}' reason: this door
 * creates a container, and a call this service cannot authenticate is one it does not make.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static, so a native image does not get
 * an {@code HttpClientFacade} in its build-time heap; {@code NativeImageContractTest} pins that.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkspaceAgentDispatch implements WorkspaceAgentDispatch {

  private static final Logger LOG = Logger.getLogger(HttpWorkspaceAgentDispatch.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How long a connect may take — qits-workspaces is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * The bound on the whole exchange, and it is generous on purpose: the far side cuts the branch and
   * <b>pushes every submodule's branch synchronously</b> before it answers, so a wide estate is tens
   * of seconds of git and not a hung request.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  /**
   * The bound on a reference lookup, and it is short where the dispatch's is generous: this one sits
   * inside a page being drawn, so a far side that is slow must cost a second and a missing link —
   * never the listing itself.
   */
  private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  /** This service's address for qits-workspaces. Unset falls back to the key below. */
  @ConfigProperty(name = "qits.projects.workspaces-url")
  Optional<String> workspacesUrl;

  /** The address the release path already ships set; see the class javadoc. */
  @ConfigProperty(name = "qits.projects.release-requests.workspaces-url")
  Optional<String> releaseWorkspacesUrl;

  @Inject WorkspacesBearer bearer;

  @Override
  public Dispatch dispatchAgent(
      String repositoryId,
      String branch,
      List<String> gitRefs,
      boolean branchTree,
      Subject subject,
      String instruction) {
    String base =
        address()
            .orElseThrow(
                () ->
                    new DomainException(
                        503,
                        "No address for qits-workspaces is configured"
                            + " (qits.projects.workspaces-url), so no agent can be dispatched."));
    String authorization =
        bearer
            .authorization()
            .orElseThrow(
                () ->
                    new DomainException(
                        503,
                        "No machine credential for qits-workspaces is available, so no agent can"
                            + " be dispatched."));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repositoryId", repositoryId);
    body.put("branch", branch);
    // The refs an agent in this workspace may push (plan contract C4). Left off when unset: then
    // qits-workspaces allows only the workspace's own branch. An older qits-workspaces ignores it.
    if (gitRefs != null) {
      body.put("gitRefs", gitRefs);
    }
    body.put("branchTree", branchTree);
    // What the workspace is for, and no goal: a dispatch has no prose of its own to state, and the
    // far side leaves a `preamble` it is not sent alone. Only the member that is set is sent —
    // an explicit null would be this hop stating a subject it does not have.
    if (subject != null && subject.ticketId() != null) {
      body.put("ticketId", subject.ticketId());
    }
    if (subject != null && subject.epicId() != null) {
      body.put("epicId", subject.epicId());
    }
    body.put("instruction", instruction);

    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base + "/workspaces/api/agent-dispatches"))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", authorization)
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      // Never swallow the interrupt, even though this one runs on a request thread.
      Thread.currentThread().interrupt();
      throw failed(branch, "interrupted while waiting for qits-workspaces");
    } catch (Exception e) {
      throw failed(branch, "could not reach qits-workspaces: " + e);
    }
    if (response.statusCode() != 200) {
      throw failed(
          branch, "qits-workspaces answered " + response.statusCode() + ": " + response.body());
    }
    return read(branch, response.body());
  }

  /**
   * The read back: {@code GET /workspaces/api/agent-dispatches/references?ticketId=…&epicId=…}, both
   * parameters repeating, answering {@code {"entries":[{"workspace":{…}}]}}.
   *
   * <p><b>The path is under {@code agent-dispatches} and that is load-bearing, not tidy.</b> It sat
   * under {@code /workspaces/api/workspaces/references} for one release and answered **403** to
   * every call this class made: that far-side class is {@code @RolesAllowed("qits:admin")}, a
   * person's door, and this hop presents a machine bearer, which carries {@code qits:system}. The
   * contract below turned each 403 into an empty list — correctly, by its own rules — so the feature
   * was deployed, inert, and silent. The dispatch door's class states both roles, so the reference
   * it writes is read back through it.
   *
   * <p><b>Nothing here throws, and that is the port's contract rather than this class being
   * lenient.</b> A missing address, a missing credential, a far side that is away, a non-200 and an
   * answer that will not parse all come out the same way: one WARN and an empty list. The caller is
   * a tickets panel being drawn, and the degraded answer — every button live, no link — is exactly
   * what the platform did before this read existed.
   */
  @Override
  public List<Reference> workspacesReferencing(
      Collection<String> ticketIds, Collection<String> epicIds) {
    String query = query(ticketIds, epicIds);
    if (query.isEmpty()) {
      return List.of(); // asked about no rows — no call to make
    }
    Optional<String> base = address();
    Optional<String> authorization = bearer.authorization();
    if (base.isEmpty() || authorization.isEmpty()) {
      LOG.debugf(
          "No %s for qits-workspaces, so no workspace references are read",
          base.isEmpty() ? "address" : "machine credential");
      return List.of();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(base.get() + "/workspaces/api/agent-dispatches/references?" + query))
              .timeout(LOOKUP_TIMEOUT)
              .header("Accept", "application/json")
              .header("Authorization", authorization.get())
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-workspaces answered %s to a workspace-reference lookup; reporting none: %s",
            response.statusCode(), response.body());
        return List.of();
      }
      return references(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while reading workspace references; reporting none");
      return List.of();
    } catch (Exception e) {
      LOG.warnf("Could not read workspace references, so none are reported: %s", e.toString());
      return List.of();
    }
  }

  /**
   * The query string, with every blank and duplicate id dropped — a blank would ask about a row that
   * cannot exist, and the same id twice would ask the same question twice. Empty means there is
   * nothing to ask.
   */
  private static String query(Collection<String> ticketIds, Collection<String> epicIds) {
    return Stream.concat(
            named("ticketId", ticketIds).stream(), named("epicId", epicIds).stream())
        .collect(Collectors.joining("&"));
  }

  private static List<String> named(String parameter, Collection<String> ids) {
    if (ids == null) {
      return List.of();
    }
    return ids.stream()
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .map(id -> parameter + "=" + URLEncoder.encode(id, StandardCharsets.UTF_8))
        .toList();
  }

  /**
   * The entries, read as {@code Map}s like everything else this class exchanges. An entry missing
   * the pair a link is composed from is skipped rather than carried as a half-row: a reference the
   * browser cannot turn into an address is worse than one it never heard about.
   */
  private static List<Reference> references(String responseBody) throws Exception {
    Map<?, ?> answer = MAPPER.readValue(responseBody, Map.class);
    if (!(answer.get("entries") instanceof List<?> entries)) {
      return List.of();
    }
    List<Reference> references = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> wrapper)
          || !(wrapper.get("workspace") instanceof Map<?, ?> row)
          || !(row.get("workspaceRowId") instanceof Number rowId)
          || !(row.get("repositoryId") instanceof String repositoryId)) {
        continue;
      }
      references.add(
          new Reference(
              rowId.longValue(),
              repositoryId,
              string(row.get("workspaceId")),
              string(row.get("branch")),
              string(row.get("ticketId")),
              string(row.get("epicId"))));
    }
    return List.copyOf(references);
  }

  private static String string(Object value) {
    return value instanceof String text ? text : null;
  }

  /**
   * The new key, then the release path's. Blank counts as unset in both: the release key documents
   * blank as "switch the call off", and a switched-off address is not one to dispatch against.
   */
  private Optional<String> address() {
    return workspacesUrl
        .filter(value -> !value.isBlank())
        .or(() -> releaseWorkspacesUrl.filter(value -> !value.isBlank()));
  }

  /**
   * The answer, read as a {@code Map}. The workspace's row id is the one member the caller cannot do
   * without — the SPA's link is composed from it — so an answer carrying no readable id is a failed
   * dispatch and not a dispatch with a hole in it.
   */
  private Dispatch read(String branch, String responseBody) {
    Map<?, ?> answer;
    try {
      answer = MAPPER.readValue(responseBody, Map.class);
    } catch (Exception e) {
      throw failed(branch, "qits-workspaces answered something unreadable: " + e);
    }
    Object workspace = answer.get("workspace");
    if (!(workspace instanceof Map<?, ?> row) || !(row.get("id") instanceof Number id)) {
      throw failed(branch, "qits-workspaces answered no workspace id: " + responseBody);
    }
    boolean fresh = Boolean.TRUE.equals(answer.get("fresh"));
    Object launch = answer.get("agentLaunch");
    LOG.infof(
        "qits-workspaces dispatched an agent onto %s in workspace %s (fresh=%s, launch=%s)",
        branch, id.longValue(), fresh, launch);
    return new Dispatch(id.longValue(), fresh, launch == null ? null : launch.toString());
  }

  private static DomainException failed(String branch, String reason) {
    return new DomainException(502, "Could not dispatch an agent onto " + branch + ": " + reason);
  }
}
