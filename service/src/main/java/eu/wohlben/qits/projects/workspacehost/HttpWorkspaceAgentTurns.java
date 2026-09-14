package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
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
 * The shipped {@link WorkspaceAgentTurns}: one POST to qits-workspaces' delivery door.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {workspaces-url}/workspaces/api/agent-dispatches/delivery
 *   Authorization: Bearer &lt;machine token, audience qits-workspaces&gt;
 *   Content-Type: application/json
 *
 *   {"repositoryId": "…", "branch": "ticket/&lt;slug&gt;", "text": "…", "compactFirst": false}
 *
 *   -&gt; 200 {"workspaceId": 41, "delivered": true,  "launched": false, "detail": "…"}
 *   -&gt; 200 {"workspaceId": 41, "delivered": true,  "launched": true,  "detail": "…"}
 *   -&gt; 200 {"workspaceId": null, "delivered": false, "launched": false, "detail": "…"}
 * </pre>
 *
 * <p><b>A well-formed request is always a 200, and {@code workspaceId: null} is the ordinary
 * answer.</b> It means no workspace stands on that branch — nothing was delivered and nothing was
 * created, because <b>the door never creates a workspace</b>. That is {@link
 * WorkspaceAgentTurns.Outcome#NO_WORKSPACE} here and it is not a failure: standing a workspace up is
 * {@link HttpWorkspaceAgentDispatch}' door, one path over, and a ticket nobody has dispatched an
 * agent onto has nothing to be told.
 *
 * <p><b>The path sits under {@code agent-dispatches} deliberately.</b> That is where the roles are,
 * and it cost a release to learn: the reference read beside it shipped against
 * {@code /workspaces/api/workspaces/…} and answered <b>403</b> to every call, because that far-side
 * class is {@code @RolesAllowed("qits:admin")} — a person's door — while this hop presents a machine
 * bearer carrying {@code qits:system}. Do not tidy this path.
 *
 * <h2>The address: the two keys {@link HttpWorkspaceAgentDispatch} already settled</h2>
 *
 * <p>This hop reads {@code qits.projects.workspaces-url} and falls back to
 * {@code qits.projects.release-requests.workspaces-url} — the <b>same two keys in the same order</b>
 * as the dispatch beside it, and for its reasons rather than for new ones. The honest name is what a
 * hop that has nothing to do with release requests should read; the narrow key already ships set to
 * {@code http://qits-workspaces:8080} and is what every environment injects, so the fallback is what
 * keeps that honesty from costing a redeploy. <b>No third key and no rename</b>: a rename would move
 * a live value under two call sites for a name this door can reach without it, and a third address
 * for the same service would be a third thing to get wrong.
 *
 * <h2>Every failure is one behaviour</h2>
 *
 * <p>No address, no bearer, a timeout, an unreachable or refusing qits-workspaces, an answer that
 * will not parse: <b>one WARN naming the branch and the reason</b>, and a {@link
 * WorkspaceAgentTurns.Outcome#COULD_NOT}. The port's contract is that it never throws — the
 * transition it follows has already been recorded, and a workspace that could not be spoken to must
 * not turn it into a 500. The WARN is at {@code warn} rather than {@code debug} because the visible
 * effect is a phase that silently did not start; the return value says the same thing again, for a
 * caller that has a thread to write it on, and decides nothing.
 *
 * <p><b>There is no forwarded-header fallback</b>, for {@link HttpReleasedBranchWorkspaces}' reason:
 * the two qits-ci hops fall back to the {@code X-Qits-*} pair because a missed read costs a build
 * agent, while this one asks another context to drive an agent in a container, and a call this
 * service cannot authenticate is one it does not make. It would not work anyway — the far side's
 * class takes {@code qits:system}, which a forwarded pair cannot carry honestly.
 *
 * <p><b>{@code Map}, never a DTO</b>, in both directions — {@code wiring/HttpGitHostRepositories}'
 * discipline and for its reason: a record reached through a bare {@link ObjectMapper} needs
 * {@code @RegisterForReflection} to survive a native image and a {@code Map} needs nothing, so this
 * class adds zero native-image registrations and there is still no {@code WorkspacesWireReflection}
 * beside {@code containershost/ContainersWireReflection}.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static: a static one is built at
 * image-build time and native-image refuses the {@code HttpClientFacade} that lands in the heap.
 * {@code NativeImageContractTest} pins that.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkspaceAgentTurns implements WorkspaceAgentTurns {

  private static final Logger LOG = Logger.getLogger(HttpWorkspaceAgentTurns.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How long a connect may take — qits-workspaces is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * The bound on the whole exchange. The far side may have to launch a harness before the text has
   * anywhere to go, so this is the dispatch door's order of magnitude rather than a read's — and it
   * is bounded at all because nobody is waiting: a hop that hung would hold the thread that recorded
   * a transition for no gain.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** This service's address for qits-workspaces. Unset falls back to the key below. */
  @ConfigProperty(name = "qits.projects.workspaces-url")
  Optional<String> workspacesUrl;

  /** The address the release path already ships set; see the class javadoc. */
  @ConfigProperty(name = "qits.projects.release-requests.workspaces-url")
  Optional<String> releaseWorkspacesUrl;

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Inject WorkspacesBearer bearer;

  @Override
  public Turn deliver(String repositoryId, String branch, String text) {
    Optional<String> base = address();
    if (base.isEmpty()) {
      return couldNot(branch, "no address for qits-workspaces is configured");
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      return couldNot(branch, "no machine bearer for qits-workspaces is available");
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("repositoryId", repositoryId);
      body.put("branch", branch);
      body.put("text", text);
      // Whether the far side compacts the session before speaking. False from here: a phase hand-off
      // is told to an agent whose context was just reset anyway, and compaction is the far side's
      // lever, not a decision this port wants an opinion about.
      body.put("compactFirst", false);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base.get() + "/workspaces/api/agent-dispatches/delivery"))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", authorization.get())
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return couldNot(
            branch, "qits-workspaces answered " + response.statusCode() + ": " + response.body());
      }
      return read(branch, response.body());
    } catch (InterruptedException e) {
      // Never swallow the interrupt: this may run on a worker a shutdown interrupts.
      Thread.currentThread().interrupt();
      return couldNot(branch, "interrupted");
    } catch (Exception e) {
      return couldNot(branch, e.toString());
    }
  }

  /**
   * The answer, read as a {@code Map}. {@code workspaceId: null} is the whole of "no workspace
   * stands on that branch" — the far side always 200s a well-formed request, so the absence of an id
   * is information and never a hole. An answer that will not parse, or one carrying an id but
   * claiming neither delivery nor launch, is {@link Outcome#COULD_NOT}: this side cannot say the
   * agent was told, and a hopeful yes on a thread is worse than a warn.
   */
  private Turn read(String branch, String responseBody) {
    Map<?, ?> answer;
    try {
      answer = MAPPER.readValue(responseBody, Map.class);
    } catch (Exception e) {
      return couldNot(branch, "qits-workspaces answered something unreadable: " + e);
    }
    String detail = answer.get("detail") instanceof String sentence ? sentence : "";
    if (!(answer.get("workspaceId") instanceof Number workspaceId)) {
      LOG.debugf("No workspace stands on %s, so the turn was not delivered: %s", branch, detail);
      return new Turn(Outcome.NO_WORKSPACE, detail);
    }
    boolean launched = Boolean.TRUE.equals(answer.get("launched"));
    boolean delivered = Boolean.TRUE.equals(answer.get("delivered"));
    if (!launched && !delivered) {
      return couldNot(branch, "qits-workspaces neither delivered nor launched: " + responseBody);
    }
    LOG.infof(
        "qits-workspaces %s the turn on %s in workspace %s: %s",
        launched ? "launched an agent for" : "delivered", branch, workspaceId.longValue(), detail);
    return new Turn(launched ? Outcome.LAUNCHED : Outcome.DELIVERED, detail);
  }

  /**
   * The new key, then the release path's — {@link HttpWorkspaceAgentDispatch}' order and its reason.
   * Blank counts as unset in both: the release key documents blank as "switch the call off", and a
   * switched-off address is not one to speak through.
   */
  private Optional<String> address() {
    return workspacesUrl
        .filter(value -> !value.isBlank())
        .or(() -> releaseWorkspacesUrl.filter(value -> !value.isBlank()));
  }

  /** The one WARN, and the one failure value. Every way this can go wrong comes through here. */
  private static Turn couldNot(String branch, String reason) {
    LOG.warnf(
        "Could not tell the agent on %s what happened: %s. The transition stands; the next phase"
            + " starts when somebody presses assign agent.",
        branch, reason);
    return new Turn(Outcome.COULD_NOT, reason);
  }
}
