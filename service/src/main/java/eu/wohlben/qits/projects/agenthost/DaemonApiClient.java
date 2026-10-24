package eu.wohlben.qits.projects.agenthost;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>One request to a project agent's daemon API, from this service itself</b>, down the reverse
 * tunnel — the hop {@link AgentCapabilityRelay} has made since qits-1149 and the front desk's
 * prompt and last-answer doors make since qits-1572. Lifted out of the relay so there is one place
 * that knows how to talk to a daemon through {@link AgentTunnels}, rather than one per caller.
 *
 * <p>Three rules, each of which cost a release to learn and each of which is the relay's javadoc's
 * subject — read it there:
 *
 * <ul>
 *   <li><b>The path is the full proxied one</b> ({@link ContainerProxyPath#base} + the route): the
 *       daemon serves its API under the prefix it was told is its own address, and a bare route
 *       would 404 at its router.
 *   <li><b>{@code setServer} is where we connect; {@code setHost}/{@code setPort} are what we claim
 *       to be calling</b> — the tunnel's ephemeral loopback port, presented as {@code
 *       localhost:<daemon api port>}, exactly as {@link ContainerProxyRoute#hostRewrite} pins a
 *       browser's request. The bearer is the daemon's own api token, which says "qits is calling".
 *   <li><b>The exchange is composed and awaited ONCE.</b> Request, send and {@code body()} are one
 *       {@link io.vertx.core.Future} chain, so {@code body()} is attached inside the response
 *       callback on the event loop where it cannot miss a chunk; awaiting the response and then
 *       asking it for its body loses the body (Vert.x 4.5's unpaused inbound buffer).
 * </ul>
 *
 * <p>It classifies nothing: a status and a body come back verbatim, and "could not ask at all" is
 * {@link Unreachable}. What an answer means is the caller's business — the relay retries a 503,
 * the front desk passes it on.
 */
@ApplicationScoped
public class DaemonApiClient {

  @Inject AgentTunnels tunnels;

  /** The bearer the daemon requires — the same value {@link ContainerProxyRoute} presents. */
  @ConfigProperty(name = "qits.projects.daemon-api-token", defaultValue = "qits-projects-daemon")
  String daemonApiToken;

  /**
   * Not where we connect — the authority we claim. The <b>same key</b> {@link ContainerProxyRoute}
   * reads for its {@code hostRewrite}, deliberately and not a second one.
   */
  @ConfigProperty(name = "qits.projects.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  /** One whole answer: status and body together, assembled on the event loop. */
  public record Answer(int status, String body) {}

  /** The daemon could not be asked: no tunnel, or the hop failed or timed out. */
  public static final class Unreachable extends Exception {

    private final boolean noTunnel;

    Unreachable(String message, boolean noTunnel, Throwable cause) {
      super(message, cause);
      this.noTunnel = noTunnel;
    }

    /**
     * True when no tunnel could be opened at all — no daemon holds a control socket for the
     * project, or one that has not said hello yet — as opposed to a hop that failed on the way.
     */
    public boolean noTunnel() {
      return noTunnel;
    }
  }

  /** {@code GET <base>/<route>}. */
  public Answer get(String projectId, String route, long timeoutMs)
      throws Unreachable, InterruptedException {
    return exchange(projectId, HttpMethod.GET, route, null, timeoutMs);
  }

  /** {@code POST <base>/<route>} with a JSON body. */
  public Answer post(String projectId, String route, String json, long timeoutMs)
      throws Unreachable, InterruptedException {
    return exchange(projectId, HttpMethod.POST, route, json, timeoutMs);
  }

  private Answer exchange(
      String projectId, HttpMethod method, String route, String json, long timeoutMs)
      throws Unreachable, InterruptedException {
    AgentTunnels.TunnelOrigin origin = tunnels.originFor(projectId).orElse(null);
    if (origin == null) {
      // The tunnel is opened on demand from the live control socket, so an empty answer here means
      // no daemon is connected (or it has gone away again) rather than anything about the route.
      throw new Unreachable("no tunnel to the project's agent container", true, null);
    }
    RequestOptions options =
        new RequestOptions()
            .setMethod(method)
            .setServer(SocketAddress.inetSocketAddress(origin.port(), "127.0.0.1"))
            .setHost("localhost")
            .setPort(Integer.valueOf(daemonApiPort))
            .setURI(ContainerProxyPath.base(projectId) + route)
            .putHeader("Authorization", "Bearer " + daemonApiToken)
            .setTimeout(timeoutMs);
    if (json != null) {
      options.putHeader("Content-Type", "application/json");
    }
    try {
      return origin
          .client()
          .request(options)
          .compose(request -> json == null ? request.send() : request.send(Buffer.buffer(json)))
          .compose(
              response ->
                  response
                      .body()
                      .map(
                          buffer ->
                              new Answer(
                                  response.statusCode(),
                                  buffer == null ? null : buffer.toString())))
          .toCompletionStage()
          .toCompletableFuture()
          .get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    } catch (Exception e) {
      throw new Unreachable("could not reach the daemon: " + e, false, e);
    }
  }
}
