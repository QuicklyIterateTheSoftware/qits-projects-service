package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.WorkspaceAgentEntities;
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
 * The shipped {@link WorkspaceAgentEntities}: one POST to qits-workspaces' entity door (qits-617),
 * falling back to the blocked door it replaced (qits-614) when the far side is older than it.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {workspaces-url}/workspaces/api/agent-dispatches/entity
 *   Authorization: Bearer &lt;machine token, audience qits-workspaces&gt;
 *   Content-Type: application/json
 *
 *   {"repositoryId": "…", "branch": "ticket/&lt;slug&gt;",
 *    "title": "…", "status": "REFINED", "blocked": true}
 *
 *   -&gt; 200 {"workspaceId": 41,   "applied": true}
 *   -&gt; 200 {"workspaceId": null, "applied": false}
 * </pre>
 *
 * <p><b>{@code workspaceId: null} is the ordinary answer</b>, for {@link HttpWorkspaceAgentTurns}'
 * reason one door over: no workspace stands on that branch, so there is no session to rename, and
 * the door never creates one. It is a DEBUG line.
 *
 * <h2>A 404 OR A 405 on {@code /entity} is retried once, on {@code /blocked}</h2>
 *
 * <p>qits-workspaces releases before this service and serves {@code /entity} by then, so on a
 * converged estate the fallback never runs. It is here because the two release independently and a
 * rollback of the far side is still a far side without the door: then {@code /entity} answers 404,
 * and the same exchange goes to {@code /blocked} with {@code {"repositoryId", "branch", "blocked"}}
 * — the flag, which is the half of the name that door ever knew. <b>405 counts the same as 404</b>:
 * measured live 2026-10-01 against a workspace-daemon container on 2026.1001.72420, a daemon older
 * than {@code /agents/entity} answers a method it does not recognise for that sub-path with 405
 * rather than 404, because its router rejects the method before it ever resolves the path
 * (qits-617) — treating only 404 as "the far side predates this door" left that answer falling
 * through to the generic non-2xx WARN below instead of the fallback. The title and status are lost
 * on that path and only that path, and the next signal against a current far side restores them. A
 * 404 or 405 on {@code /blocked} too is a far side older than both, and is the one WARN.
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
 * class's javadoc and none of them has a different answer for a label than for a turn. What differs
 * is the timeout: the far side launches nothing here, so the bound is a read's — see {@link
 * #REQUEST_TIMEOUT}.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkspaceAgentEntities implements WorkspaceAgentEntities {

  private static final Logger LOG = Logger.getLogger(HttpWorkspaceAgentEntities.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How long a connect may take — qits-workspaces is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * The bound on each exchange. The far side records three values and renames a session; it
   * launches nothing, so this is a read's order of magnitude — and it is short because, unlike a
   * turn, the caller here is a person's block press, transition or edit waiting synchronously for a
   * label nobody asked to wait on.
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

  /** The door this adapter speaks to, relative to the address. */
  static final String ENTITY_PATH = "/workspaces/api/agent-dispatches/entity";

  /**
   * The qits-614 door {@link #ENTITY_PATH} replaced, asked only when the far side 404s or 405s the
   * new one (qits-617: 405 is the same "does not know this door" answer, from a router that rejects
   * the method before it resolves the path).
   */
  static final String BLOCKED_PATH = "/workspaces/api/agent-dispatches/blocked";

  @Override
  public void changed(
      String repositoryId, String branch, String title, String status, boolean blocked) {
    String what = describe(title, status, blocked);
    Optional<String> base = address();
    if (base.isEmpty()) {
      couldNot(branch, what, "no address for qits-workspaces is configured");
      return;
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      couldNot(branch, what, "no machine bearer for qits-workspaces is available");
      return;
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("repositoryId", repositoryId);
      body.put("branch", branch);
      body.put("title", title);
      body.put("status", status);
      body.put("blocked", blocked);
      HttpResponse<String> response = post(base.get() + ENTITY_PATH, authorization.get(), body);
      if (response.statusCode() == 404 || response.statusCode() == 405) {
        // A qits-workspaces older than the entity door: tell it the one value it knows. 405 is the
        // same absence read a different way — an older router rejecting the method before it
        // resolves the path (qits-617).
        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("repositoryId", repositoryId);
        flag.put("branch", branch);
        flag.put("blocked", blocked);
        LOG.debugf(
            "qits-workspaces has no %s yet (status %s); telling %s only that it is blocked=%s",
            ENTITY_PATH, response.statusCode(), branch, blocked);
        response = post(base.get() + BLOCKED_PATH, authorization.get(), flag);
      }
      if (response.statusCode() / 100 != 2) {
        couldNot(
            branch,
            what,
            "qits-workspaces answered "
                + response.statusCode()
                + (response.statusCode() == 404 || response.statusCode() == 405
                    ? " (a qits-workspaces older than both the entity and the blocked door)"
                    : "")
                + ": "
                + response.body());
        return;
      }
      read(branch, what, response.body());
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this may run on a worker a shutdown interrupts.
      Thread.currentThread().interrupt();
      couldNot(branch, what, "interrupted");
    } catch (Exception e) {
      couldNot(branch, what, e.toString());
    }
  }

  private HttpResponse<String> post(String url, String authorization, Map<String, Object> body)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** What was being said, for the log: {@code REFINED, blocked, "the title"}. */
  private static String describe(String title, String status, boolean blocked) {
    return status + ", " + (blocked ? "blocked" : "unblocked") + ", \"" + title + "\"";
  }

  /**
   * The answer, read only to say what happened in the log. {@code workspaceId: null} is "nobody
   * stands on that branch" and is DEBUG; an id is INFO; a body that will not parse is the WARN,
   * because this side cannot say the values landed.
   */
  private static void read(String branch, String what, String responseBody) {
    Map<?, ?> answer;
    try {
      answer = MAPPER.readValue(responseBody, Map.class);
    } catch (Exception e) {
      couldNot(branch, what, "qits-workspaces answered something unreadable: " + e);
      return;
    }
    if (!(answer.get("workspaceId") instanceof Number workspaceId)) {
      LOG.debugf("No workspace stands on %s, so nobody was told its work is %s", branch, what);
      return;
    }
    LOG.infof(
        "Told workspace %s on %s that its work is %s (applied: %s)",
        workspaceId.longValue(), branch, what, answer.get("applied"));
  }

  /** The new key, then the release path's — {@link HttpWorkspaceAgentTurns}' order and reason. */
  private Optional<String> address() {
    return workspacesUrl
        .filter(value -> !value.isBlank())
        .or(() -> releaseWorkspacesUrl.filter(value -> !value.isBlank()));
  }

  /** The one WARN. Every way this can go wrong comes through here. */
  private static void couldNot(String branch, String what, String reason) {
    LOG.warnf(
        "Could not tell the workspace on %s that its work is %s: %s. The entity stands as written;"
            + " only the agent session's name lags behind it.",
        branch, what, reason);
  }
}
