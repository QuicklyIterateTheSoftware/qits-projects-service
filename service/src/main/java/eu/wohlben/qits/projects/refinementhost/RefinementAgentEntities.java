package eu.wohlben.qits.projects.refinementhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.entity.Refinement;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The refinement twin of {@code control/WorkspaceAgentEntities}: the agent refining an entity is
 * told the entity's title, status and blocked flag whenever any of them changes, so its session name
 * — {@code <status square> <qualified id> <title>}, the square pale while blocked — keeps reading
 * the entity as it stands (qits-614 for the flag, qits-617 for the rest).
 *
 * <h2>Why a refinement is told here and not through qits-workspaces</h2>
 *
 * <p>A refinement container is this service's own — {@link RefinementService} provisions it, keyed
 * by entity ({@code Refinement.entityId} is unique), and its daemon dials home to this service and
 * not to qits-workspaces. So the workspace port's door would answer "no workspace on that branch"
 * for it every time; the only way in is the tunnel {@link RefinementProxyRoute} already forwards a
 * person's requests through. This class is the host-initiated hop down that same tunnel, the way
 * {@code agenthost/AgentCapabilityRelay} reads a project agent's daemon down its own.
 *
 * <h2>The contract (qits-1285)</h2>
 *
 * <pre>
 *   POST /projects/refinement-container/{id}/agent-worktrees/{entityId}/entity   (through the tunnel)
 *   Authorization: Bearer &lt;qits.projects.refinement-daemon-api-token&gt;
 *   Host: localhost:&lt;qits.projects.refinement-daemon-api-port&gt;
 *
 *   {"title": "…", "status": "REFINED", "blocked": true, "blockSource": "AGENT_WAITING"}
 * </pre>
 *
 * <p><b>{@code entityId} IS the agent id</b>: since qits-1152 the daemon has no container-wide
 * {@code /agents/*} door at all, only this per-agent one. A refinement room starts exactly one
 * agent, and the refining page starts it ({@code POST <proxy base>agent-worktrees}) under {@link
 * Refinement#entityId}, so that is the id this class addresses it by. There is no second id to
 * carry.
 *
 * <p><b>{@code blockSource} (qits-895)</b> is the effective block's source —
 * {@code entities.control.EntityBlockState}'s {@code "EXPLICIT"}, {@code "AGENT_WAITING"} or
 * {@code "BOTH"} — omitted whenever {@code blocked} is false.
 *
 * <p><b>A 404 means no agent has been started in this room yet</b>, and it is the ordinary case
 * rather than an exception: a room's one agent is started by whatever the SPA's refining page does
 * first, which can easily run after the entity's title, status or block flag has already changed
 * once. There is no fallback to retry on — the container-wide routes a 404 used to mean "this daemon
 * predates" are gone — so a 404 is logged at DEBUG and nothing more is asked. The agent that starts
 * later is seeded with the entity as it then stands, by the start request and by the environment
 * {@link RefinementContainerFactory} puts on the spec.
 *
 * <p><b>The path keeps the proxy prefix</b>, and it has to: the daemon was told {@link
 * RefinementPaths#proxyBase} as {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH} at creation and refuses
 * anything outside it, which is the proxy's own no-rewrite rule. The bearer and the {@code Host} are
 * what the proxy's two interceptors put on a forwarded request, set here by hand because nothing is
 * being forwarded.
 *
 * <h2>Best-effort, and on the caller's thread</h2>
 *
 * <p><b>Never throws</b>, for the port's reason: it runs after a block, a transition or a retitle
 * already recorded. No refinement for the entity, or one whose daemon is not connected (a stopped container
 * — {@link RefinementTunnels#originFor} answers empty rather than waking anything), is the ordinary
 * case and costs one indexed read. A 404 — no agent started yet — is DEBUG. Anything else — a
 * timeout, a non-2xx, an exception — is one WARN. A refinement that missed a signal is corrected at
 * its next wake: {@link RefinementContainerFactory} puts {@code QITS_WORKSPACE_DAEMON_ENTITY_TITLE},
 * {@code _STATUS} and {@code _BLOCKED} on the spec from the row as it then stands.
 *
 * <p><b>Only a legacy container room is told here.</b> A session room (epic qits-770) is handed to
 * {@link RefinementSessions#entityChanged}, which sends its project's desk daemon the sessions list
 * again with the new facts, and the daemon renames the session in place.
 *
 * <p>This is qits-1285's stop-gap for a container epic qits-770 is going to delete outright:
 * refinement containers are on the list to go, and this class with them. Nothing here is written to
 * outlive that epic.
 *
 * <p>It is synchronous with a short bound rather than fire-and-forget so that a block followed by an
 * unblock — or a transition followed by the next — arrives in that order; the tunnel is loopback to a connected daemon, so the bound is
 * rarely approached.
 */
@ApplicationScoped
public class RefinementAgentEntities {

  private static final Logger LOG = Logger.getLogger(RefinementAgentEntities.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject RefinementService refinements;

  @Inject RefinementTunnels tunnels;

  /**
   * A session room's entity facts reach its session through the desk's sessions list (epic
   * qits-770), not down a tunnel. Null in a hand-built bean.
   */
  @Inject RefinementSessions sessions;

  /** The bearer the daemon requires; the value {@link RefinementContainerFactory} injects. */
  @ConfigProperty(
      name = "qits.projects.refinement-daemon-api-token",
      defaultValue = "qits-projects-refinement-daemon")
  String daemonApiToken;

  /** The daemon's own port — the authority it is shown, not where the tunnel connects. */
  @ConfigProperty(name = "qits.projects.refinement-daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  /** The bound on the whole exchange; a flag over loopback, so a few seconds is generous. */
  @ConfigProperty(name = "qits.projects.refinement-blocked.timeout-ms", defaultValue = "5000")
  long timeoutMs;

  /**
   * Tell the refinement of {@code entityId}, if one exists and its daemon is connected, the entity's
   * title, status and blocked flag as they now stand. <b>Never throws.</b>
   *
   * @param status the status as stored — the {@code EntityStatus} name, never a rendered label
   */
  public void changed(String entityId, String title, String status, boolean blocked) {
    changed(entityId, title, status, blocked, null);
  }

  /**
   * {@link #changed(String, String, String, boolean)}, carrying the effective block's SOURCE too
   * (qits-895): {@code entities.control.EntityBlockState}'s {@code "EXPLICIT"}, {@code
   * "AGENT_WAITING"} or {@code "BOTH"}, {@code null} while {@code blocked} is false.
   */
  public void changed(
      String entityId, String title, String status, boolean blocked, String blockSource) {
    try {
      Optional<Refinement> refinement = refinements.findByEntity(entityId);
      if (refinement.isEmpty()) {
        return;
      }
      if (!refinement.get().containerRoom()) {
        // A session room (epic qits-770): the desk's daemon renames the session from the list.
        if (sessions != null) {
          sessions.entityChanged(
              refinement.get(),
              new eu.wohlben.qits.projectsdaemon.protocol.Sessions.Entity(
                  null, title, status, blocked, blocked ? blockSource : null));
        }
        return;
      }
      Long refinementId = refinement.get().id;
      Optional<RefinementTunnels.TunnelOrigin> origin = tunnels.originFor(refinementId);
      if (origin.isEmpty()) {
        // Stopped or never started: nothing to tell now, and the next wake reads the row.
        LOG.debugf(
            "Refinement %s of entity %s has no connected daemon; its entity state arrives at the"
                + " next wake",
            refinementId, entityId);
        return;
      }
      send(refinementId, origin.get(), entityId, title, status, blocked, blockSource);
    } catch (RuntimeException e) {
      couldNot("of entity " + entityId, blocked, e.toString());
    }
  }

  /** The POST itself, down an already-open tunnel. Package-private for the wire-shape test. */
  void send(
      long refinementId,
      RefinementTunnels.TunnelOrigin origin,
      String entityId,
      String title,
      String status,
      boolean blocked,
      String blockSource) {
    Map<String, Object> entity = new LinkedHashMap<>();
    entity.put("title", title);
    entity.put("status", status);
    entity.put("blocked", blocked);
    // The effective block's source (qits-895); omitted while not blocked or unknown, exactly as
    // the workspace door's twin omits it.
    if (blockSource != null) {
      entity.put("blockSource", blockSource);
    }
    int answer;
    try {
      answer =
          post(
              refinementId,
              origin,
              "agent-worktrees/" + entityId + "/entity",
              MAPPER.writeValueAsString(entity));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      couldNot("of refinement " + refinementId, blocked, "interrupted");
      return;
    } catch (Exception e) {
      couldNot("of refinement " + refinementId, blocked, e.toString());
      return;
    }
    if (answer == 404) {
      // The ordinary case: no agent has been started in this room yet (qits-1285). There is
      // nothing to retry — the room's title and status reach it once an agent starts.
      LOG.debugf(
          "Refinement %s has no agent started for entity %s yet; its entity state arrives once"
              + " one is",
          (Object) refinementId, entityId);
      return;
    }
    if (answer / 100 != 2) {
      couldNot("of refinement " + refinementId, blocked, "the daemon answered " + answer);
      return;
    }
    LOG.infof(
        "Told refinement %s that its entity is %s, %s, \"%s\"",
        refinementId, status, blocked ? "blocked" : "unblocked", title);
  }

  /** One POST down the tunnel, answering the status code. */
  private int post(
      long refinementId, RefinementTunnels.TunnelOrigin origin, String route, String body)
      throws Exception {
    String path = RefinementPaths.proxyBase(refinementId) + route;
    return origin
        .client()
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(origin.port(), "127.0.0.1"))
                .setHost("localhost")
                .setPort(Integer.valueOf(daemonApiPort))
                .setURI(path)
                .putHeader("Authorization", "Bearer " + daemonApiToken)
                .putHeader("Content-Type", "application/json")
                .setTimeout(timeoutMs))
        .compose(request -> request.send(body))
        // The body is drained so the pooled keep-alive connection is reusable; it says nothing.
        .compose(response -> response.body().map(ignored -> response.statusCode()))
        .toCompletionStage()
        .toCompletableFuture()
        .get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  /** The one WARN. */
  private static void couldNot(String subject, boolean blocked, String reason) {
    LOG.warnf(
        "Could not tell the refinement agent %s that its entity is %s (and its title and status):"
            + " %s. The entity stands as written; the session name catches up at the container's"
            + " next wake.",
        subject, blocked ? "blocked" : "unblocked", reason);
  }
}
