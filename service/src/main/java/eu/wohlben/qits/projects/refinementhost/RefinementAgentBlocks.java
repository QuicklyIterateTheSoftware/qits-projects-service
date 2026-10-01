package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.Refinement;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The refinement twin of {@code control/WorkspaceAgentBlocks}: the agent refining an entity is told
 * that the entity was blocked or unblocked, so its session name gains or loses the {@code ❗ }
 * marker (qits-614).
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
 *   POST /projects/refinement-container/{id}/agents/blocked      (through the tunnel)
 *   Authorization: Bearer &lt;qits.projects.refinement-daemon-api-token&gt;
 *   Host: localhost:&lt;qits.projects.refinement-daemon-api-port&gt;
 *
 *   {"blocked": true}
 * </pre>
 *
 * <p><b>The path keeps the proxy prefix</b>, and it has to: the daemon was told {@link
 * RefinementPaths#proxyBase} as {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH} at creation and refuses
 * anything outside it, which is the proxy's own no-rewrite rule. The bearer and the {@code Host} are
 * what the proxy's two interceptors put on a forwarded request, set here by hand because nothing is
 * being forwarded.
 *
 * <h2>Best-effort, and on the caller's thread</h2>
 *
 * <p><b>Never throws</b>, for the port's reason: it runs after a block or a transition already
 * recorded. No refinement for the entity, or one whose daemon is not connected (a stopped container
 * — {@link RefinementTunnels#originFor} answers empty rather than waking anything), is the ordinary
 * case and costs one indexed read. Anything else — a timeout, a non-2xx, a daemon older than the
 * route answering 404 — is one WARN. A refinement that missed the flag is corrected at its next
 * wake: {@link RefinementContainerFactory} puts {@code QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED} on the
 * spec from the row as it then stands.
 *
 * <p>It is synchronous with a short bound rather than fire-and-forget so that a block followed by an
 * unblock arrives in that order; the tunnel is loopback to a connected daemon, so the bound is
 * rarely approached.
 */
@ApplicationScoped
public class RefinementAgentBlocks {

  private static final Logger LOG = Logger.getLogger(RefinementAgentBlocks.class);

  /** The daemon's route, relative to its proxied base path. */
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
   * Tell the refinement of {@code entityId}, if one exists and its daemon is connected, that the
   * entity is now {@code blocked} or not. <b>Never throws.</b>
   */
  public void blocked(String entityId, boolean blocked) {
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
            "Refinement %s of entity %s has no connected daemon; its blocked=%s arrives at the next"
                + " wake",
            (Object) refinementId, entityId, blocked);
        return;
      }
      send(refinementId, origin.get(), blocked);
    } catch (RuntimeException e) {
      couldNot("of entity " + entityId, blocked, e.toString());
    }
  }

  /** The POST itself, down an already-open tunnel. Package-private for the wire-shape test. */
  void send(long refinementId, RefinementTunnels.TunnelOrigin origin, boolean blocked) {
    String path = RefinementPaths.proxyBase(refinementId) + BLOCKED_PATH;
    String body = "{\"blocked\":" + blocked + "}";
    int status;
    try {
      status =
          origin
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
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      couldNot("of refinement " + refinementId, blocked, "interrupted");
      return;
    } catch (Exception e) {
      couldNot("of refinement " + refinementId, blocked, e.toString());
      return;
    }
    if (status / 100 != 2) {
      couldNot(
          "of refinement " + refinementId,
          blocked,
          "the daemon answered "
              + status
              + (status == 404 ? " (a daemon older than " + BLOCKED_PATH + ")" : ""));
      return;
    }
    LOG.infof(
        "Told refinement %s that its entity is %s", refinementId, blocked ? "blocked" : "unblocked");
  }

  /** The one WARN. */
  private static void couldNot(String subject, boolean blocked, String reason) {
    LOG.warnf(
        "Could not tell the refinement agent %s that its entity is %s: %s. The block stands as"
            + " written; the session name catches up at the container's next wake.",
        subject, blocked ? "blocked" : "unblocked", reason);
  }
}
