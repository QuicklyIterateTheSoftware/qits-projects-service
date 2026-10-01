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
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST /projects/refinement-container/{id}/agents/entity       (through the tunnel)
 *   Authorization: Bearer &lt;qits.projects.refinement-daemon-api-token&gt;
 *   Host: localhost:&lt;qits.projects.refinement-daemon-api-port&gt;
 *
 *   {"title": "…", "status": "REFINED", "blocked": true}
 * </pre>
 *
 * <p><b>A 404 OR A 405 there is retried once on {@code agents/blocked} with {@code {"blocked":
 * …}}</b>: both are what a daemon older than the entity route answers — a refinement container is
 * only re-created at a wake, so one started on an older image stays on it for as long as it runs —
 * and that daemon knows the flag. 405 is the same absence read a different way: measured live
 * 2026-10-01 against workspace-daemon 2026.1001.72420, its {@code /agents/*} router rejects a
 * method it does not recognise for a sub-path before it ever resolves the path, so {@code POST
 * /agents/entity} answers 405 there rather than 404 (qits-617). Treating only 404 as "older than
 * the route" left such a daemon falling through to the generic non-2xx WARN below instead of the
 * fallback. The title and status reach it at its next wake, through the environment below. A 404 or
 * 405 on both is a daemon older than both, and is the one WARN.
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
 * case and costs one indexed read. Anything else — a timeout, a non-2xx, a daemon older than the
 * route answering 404 or 405 — is one WARN. A refinement that missed a signal is corrected at its
 * next wake: {@link RefinementContainerFactory} puts {@code QITS_WORKSPACE_DAEMON_ENTITY_TITLE},
 * {@code _STATUS} and {@code _BLOCKED} on the spec from the row as it then stands.
 *
 * <p>It is synchronous with a short bound rather than fire-and-forget so that a block followed by an
 * unblock — or a transition followed by the next — arrives in that order; the tunnel is loopback to a connected daemon, so the bound is
 * rarely approached.
 */
@ApplicationScoped
public class RefinementAgentEntities {

  private static final Logger LOG = Logger.getLogger(RefinementAgentEntities.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The daemon's route, relative to its proxied base path. */
  static final String ENTITY_PATH = "agents/entity";

  /**
   * The qits-614 route {@link #ENTITY_PATH} widened, asked only when a daemon 404s or 405s the new
   * one (qits-617: a 405 is the same "does not know this route" answer, from a router that rejects
   * the method before it resolves the path).
   */
  static final String BLOCKED_PATH = "agents/blocked";

  @Inject RefinementService refinements;

  @Inject RefinementTunnels tunnels;

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
    try {
      Optional<Refinement> refinement = refinements.findByEntity(entityId);
      if (refinement.isEmpty()) {
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
      send(refinementId, origin.get(), title, status, blocked);
    } catch (RuntimeException e) {
      couldNot("of entity " + entityId, blocked, e.toString());
    }
  }

  /** The POST itself, down an already-open tunnel. Package-private for the wire-shape test. */
  void send(
      long refinementId,
      RefinementTunnels.TunnelOrigin origin,
      String title,
      String status,
      boolean blocked) {
    Map<String, Object> entity = new LinkedHashMap<>();
    entity.put("title", title);
    entity.put("status", status);
    entity.put("blocked", blocked);
    int answer;
    try {
      answer = post(refinementId, origin, ENTITY_PATH, MAPPER.writeValueAsString(entity));
      if (answer == 404 || answer == 405) {
        // A daemon older than the entity route: tell it the one value it knows. 405 is the same
        // absence read a different way — an older router rejecting the method before it resolves
        // the path (qits-617).
        LOG.debugf(
            "Refinement %s's daemon has no %s (status %s); telling it only blocked=%s",
            (Object) refinementId, ENTITY_PATH, Integer.valueOf(answer), Boolean.valueOf(blocked));
        answer = post(refinementId, origin, BLOCKED_PATH, "{\"blocked\":" + blocked + "}");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      couldNot("of refinement " + refinementId, blocked, "interrupted");
      return;
    } catch (Exception e) {
      couldNot("of refinement " + refinementId, blocked, e.toString());
      return;
    }
    if (answer / 100 != 2) {
      couldNot(
          "of refinement " + refinementId,
          blocked,
          "the daemon answered "
              + answer
              + (answer == 404 || answer == 405
                  ? " (a daemon older than " + BLOCKED_PATH + ")"
                  : ""));
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
