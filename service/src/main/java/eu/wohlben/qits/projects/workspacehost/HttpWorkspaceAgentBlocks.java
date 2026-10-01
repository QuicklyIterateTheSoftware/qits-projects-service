package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.WorkspaceAgentBlocks;
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
 * The shipped {@link WorkspaceAgentBlocks}: one POST to qits-workspaces' blocked door (qits-614).
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {workspaces-url}/workspaces/api/agent-dispatches/blocked
 *   Authorization: Bearer &lt;machine token, audience qits-workspaces&gt;
 *   Content-Type: application/json
 *
 *   {"repositoryId": "…", "branch": "ticket/&lt;slug&gt;", "blocked": true}
 *
 *   -&gt; 200 {"workspaceId": 41,   "applied": true}
 *   -&gt; 200 {"workspaceId": null, "applied": false}
 * </pre>
 *
 * <p><b>{@code workspaceId: null} is the ordinary answer</b>, for {@link HttpWorkspaceAgentTurns}'
 * reason one door over: no workspace stands on that branch, so there is no session to rename, and
 * the door never creates one. It is a DEBUG line. <b>A 404 is a WARN and nothing else</b>: it is
 * what a qits-workspaces older than this door answers, and the two services release independently,
 * so for a while it is the expected answer — the marker simply does not appear until the far side
 * ships, and the block is the same block.
 *
 * <p><b>The path sits under {@code agent-dispatches}</b>, beside {@code delivery}, for the reason
 * that class gives and paid a release to learn: that is where the far side's {@code qits:system}
 * door is, and the sibling {@code /workspaces/api/workspaces/…} is a person's door that answers this
 * machine bearer 403. Do not tidy it.
 *
 * <h2>Everything else is {@link HttpWorkspaceAgentTurns}' settlement, and deliberately so</h2>
 *
 * <p>The same two address keys in the same order, the same {@link WorkspacesBearer} and no
 * forwarded-header fallback, {@code Map} rather than a DTO on both directions (zero native-image
 * registrations), the {@link HttpClient} an instance field ({@code NativeImageContractTest} pins
 * that), and every failure folded into one WARN and a normal return. Each of those is argued in that
 * class's javadoc and none of them has a different answer for a flag than for a turn. What differs
 * is the timeout: the far side launches nothing here, so the bound is a read's — see {@link
 * #REQUEST_TIMEOUT}.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkspaceAgentBlocks implements WorkspaceAgentBlocks {

  private static final Logger LOG = Logger.getLogger(HttpWorkspaceAgentBlocks.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How long a connect may take — qits-workspaces is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * The bound on the whole exchange. The far side flips a flag and renames a session; it launches
   * nothing, so this is a read's order of magnitude — and it is short because, unlike a turn, the
   * caller here is a person's block press waiting synchronously for a label nobody asked to wait on.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  /** This service's address for qits-workspaces. Unset falls back to the key below. */
  @ConfigProperty(name = "qits.projects.workspaces-url")
  Optional<String> workspacesUrl;

  /** The address the release path already ships set; see {@link HttpWorkspaceAgentTurns}. */
  @ConfigProperty(name = "qits.projects.release-requests.workspaces-url")
  Optional<String> releaseWorkspacesUrl;

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Inject WorkspacesBearer bearer;

  @Override
  public void blocked(String repositoryId, String branch, boolean blocked) {
    Optional<String> base = address();
    if (base.isEmpty()) {
      couldNot(branch, blocked, "no address for qits-workspaces is configured");
      return;
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      couldNot(branch, blocked, "no machine bearer for qits-workspaces is available");
      return;
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("repositoryId", repositoryId);
      body.put("branch", branch);
      body.put("blocked", blocked);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base.get() + "/workspaces/api/agent-dispatches/blocked"))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", authorization.get())
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        couldNot(
            branch,
            blocked,
            "qits-workspaces answered "
                + response.statusCode()
                + (response.statusCode() == 404 ? " (a qits-workspaces older than the door)" : "")
                + ": "
                + response.body());
        return;
      }
      read(branch, blocked, response.body());
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this may run on a worker a shutdown interrupts.
      Thread.currentThread().interrupt();
      couldNot(branch, blocked, "interrupted");
    } catch (Exception e) {
      couldNot(branch, blocked, e.toString());
    }
  }

  /**
   * The answer, read only to say what happened in the log. {@code workspaceId: null} is "nobody
   * stands on that branch" and is DEBUG; an id is INFO; a body that will not parse is the WARN,
   * because this side cannot say the flag landed.
   */
  private static void read(String branch, boolean blocked, String responseBody) {
    Map<?, ?> answer;
    try {
      answer = MAPPER.readValue(responseBody, Map.class);
    } catch (Exception e) {
      couldNot(branch, blocked, "qits-workspaces answered something unreadable: " + e);
      return;
    }
    if (!(answer.get("workspaceId") instanceof Number workspaceId)) {
      LOG.debugf("No workspace stands on %s, so nobody was told it is blocked=%s", branch, blocked);
      return;
    }
    LOG.infof(
        "Told workspace %s on %s that its work is %s (applied: %s)",
        workspaceId.longValue(),
        branch,
        blocked ? "blocked" : "unblocked",
        answer.get("applied"));
  }

  /** The new key, then the release path's — {@link HttpWorkspaceAgentTurns}' order and reason. */
  private Optional<String> address() {
    return workspacesUrl
        .filter(value -> !value.isBlank())
        .or(() -> releaseWorkspacesUrl.filter(value -> !value.isBlank()));
  }

  /** The one WARN. Every way this can go wrong comes through here. */
  private static void couldNot(String branch, boolean blocked, String reason) {
    LOG.warnf(
        "Could not tell the workspace on %s that its work is %s: %s. The block stands as written;"
            + " only the agent session's name misses its marker.",
        branch, blocked ? "blocked" : "unblocked", reason);
  }
}
