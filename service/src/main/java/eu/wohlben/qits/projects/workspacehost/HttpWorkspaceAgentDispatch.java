package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
 *   {"repositoryId": "…", "branch": "ticket/&lt;slug&gt;", "branchTree": true,
 *    "preamble": "&lt;markdown&gt;", "instruction": "&lt;the agent's first turn&gt;"}
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
      boolean branchTree,
      String preamble,
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
    body.put("branchTree", branchTree);
    body.put("preamble", preamble);
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
