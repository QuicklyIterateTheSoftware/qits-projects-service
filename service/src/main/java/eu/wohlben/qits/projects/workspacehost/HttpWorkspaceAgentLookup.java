package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link WorkspaceAgentLookup}: one GET to qits-workspaces' agent read (qits-1448).
 *
 * <pre>
 *   GET {workspaces-url}/workspaces/api/agents/{agentId}
 *   Authorization: Bearer &lt;machine token, audience qits-workspaces&gt;
 *
 *   -&gt; 200 {"agentId": "…", "workId": "…", …}
 *   -&gt; 404 no such agent
 * </pre>
 *
 * <p>The far side's {@code AgentController.get} admits {@code qits:system}, the role this hop's
 * {@link WorkspacesBearer} carries.
 *
 * <p><b>{@link HttpWorkspaceAgentTurns}' shape and contract, for its reasons</b>: the same two
 * address keys in the same order and no third, the same bearer and no forwarded-header fallback, a
 * {@code Map} and never a DTO (nothing to register for the native image), and an <b>instance</b>
 * {@link HttpClient} wrapped with {@link QitsHttpClients} ({@code NativeImageContractTest}). It
 * never throws: a 404 is an unknown agent and a DEBUG, every other failure is one WARN naming the
 * agent, and both answer empty. The bounds are a read's (2 s to connect, 5 s in all), because a
 * comment POST waits on it.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkspaceAgentLookup implements WorkspaceAgentLookup {

  private static final Logger LOG = Logger.getLogger(HttpWorkspaceAgentLookup.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** The bound on the whole exchange: a comment's write waits on this read. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  /** This service's address for qits-workspaces. Unset falls back to the key below. */
  @ConfigProperty(name = "qits.projects.workspaces-url")
  Optional<String> workspacesUrl;

  /** The address the release path already ships set; see {@link HttpWorkspaceAgentTurns}. */
  @ConfigProperty(name = "qits.projects.release-requests.workspaces-url")
  Optional<String> releaseWorkspacesUrl;

  private final HttpClient client;

  @Inject WorkspacesBearer bearer;

  @Inject
  public HttpWorkspaceAgentLookup(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /**
   * Shuts the client down with the bean: a JDK {@link HttpClient} holds a SelectorManager thread
   * until it is closed or collected, and a suite that boots the application many times runs out of
   * native threads on clients nobody closed (qits-1149).
   */
  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  @Override
  public Optional<String> workOf(String agentId) {
    if (agentId == null || agentId.isBlank()) {
      return Optional.empty();
    }
    Optional<String> base = address();
    if (base.isEmpty()) {
      return couldNot(agentId, "no address for qits-workspaces is configured");
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      return couldNot(agentId, "no machine bearer for qits-workspaces is available");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      base.get()
                          + "/workspaces/api/agents/"
                          + URLEncoder.encode(agentId, StandardCharsets.UTF_8)))
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json")
              .header("Authorization", authorization.get())
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        LOG.debugf("qits-workspaces knows no agent %s, so its comment names no work item", agentId);
        return Optional.empty();
      }
      if (response.statusCode() / 100 != 2) {
        return couldNot(
            agentId, "qits-workspaces answered " + response.statusCode() + ": " + response.body());
      }
      Map<?, ?> answer;
      try {
        answer = MAPPER.readValue(response.body(), Map.class);
      } catch (Exception e) {
        return couldNot(agentId, "qits-workspaces answered something unreadable: " + e);
      }
      if (answer == null
          || !(answer.get("workId") instanceof String workId)
          || workId.isBlank()) {
        return couldNot(agentId, "qits-workspaces answered no workId: " + response.body());
      }
      return Optional.of(workId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return couldNot(agentId, "interrupted");
    } catch (Exception e) {
      return couldNot(agentId, e.toString());
    }
  }

  /**
   * {@code GET /workspaces/api/agents?state=ACTIVE} (qits-1672), read as a {@code Map} for each
   * entry's {@code agentId} and {@code workId}. Same contract as {@link #workOf}: never throws, one
   * WARN and empty on every failure.
   */
  @Override
  public Optional<List<ActiveAgent>> active() {
    Optional<String> base = address();
    if (base.isEmpty()) {
      return couldNotList("no address for qits-workspaces is configured");
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      return couldNotList("no machine bearer for qits-workspaces is available");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base.get() + "/workspaces/api/agents?state=ACTIVE"))
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json")
              .header("Authorization", authorization.get())
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return couldNotList(
            "qits-workspaces answered " + response.statusCode() + ": " + response.body());
      }
      Map<?, ?> answer;
      try {
        answer = MAPPER.readValue(response.body(), Map.class);
      } catch (Exception e) {
        return couldNotList("qits-workspaces answered something unreadable: " + e);
      }
      if (answer == null || !(answer.get("agents") instanceof List<?> entries)) {
        return couldNotList("qits-workspaces answered no agents: " + response.body());
      }
      List<ActiveAgent> agents = new ArrayList<>();
      for (Object entry : entries) {
        if (entry instanceof Map<?, ?> agent
            && agent.get("agentId") instanceof String agentId
            && agent.get("workId") instanceof String workId) {
          agents.add(new ActiveAgent(agentId, workId));
        }
      }
      return Optional.of(List.copyOf(agents));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return couldNotList("interrupted");
    } catch (Exception e) {
      return couldNotList(e.toString());
    }
  }

  private static Optional<List<ActiveAgent>> couldNotList(String reason) {
    LOG.warnf("Could not list the ACTIVE agents of qits-workspaces: %s", reason);
    return Optional.empty();
  }

  /** The new key, then the release path's — {@link HttpWorkspaceAgentTurns}' order. */
  private Optional<String> address() {
    return workspacesUrl
        .filter(value -> !value.isBlank())
        .or(() -> releaseWorkspacesUrl.filter(value -> !value.isBlank()));
  }

  /** The one WARN, and the one failure value. */
  private static Optional<String> couldNot(String agentId, String reason) {
    LOG.warnf(
        "Could not find the work item of agent %s: %s. Its comment is written naming none.",
        agentId, reason);
    return Optional.empty();
  }
}
