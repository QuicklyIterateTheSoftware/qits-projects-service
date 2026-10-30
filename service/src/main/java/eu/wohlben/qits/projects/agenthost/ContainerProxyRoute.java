package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The project-agent reverse proxy: {@code /projects/container/{projectId}/*} forwards verbatim to
 * that project's in-container {@code qits-projects-daemon}. A copy of qits-workspaces'
 * {@code ContainerProxyRoute}, with its one branch removed.
 *
 * <p><b>This is the only way to reach a daemon.</b> Its HTTP API — the commands surface, the coding
 * agent surface and the two interactive websockets — binds {@code 127.0.0.1} from capability 1 and
 * has no address on {@code qits-net} at all. So unlike the reference there is <em>no</em> direct
 * branch: the reverse tunnel is the whole transport, and a project with no live daemon is answered
 * as unreachable rather than dialled at a container name that would refuse the connection anyway.
 *
 * <p><b>Verbatim means verbatim: this route rewrites no path.</b> The daemon receives {@code
 * /projects/container/{projectId}/commands}, not {@code /commands}, and is <em>configured</em> to
 * know that leading part is its own address — {@code deskhost/FrontDeskSpecs} injects {@link
 * ContainerProxyPath#base} as {@code QITS_PROJECTS_DAEMON_API_BASE_PATH} at container creation.
 * That is a deliberate rule rather than an inherited shape: a hop that rewrites a path leaves the
 * two ends disagreeing about the destination's own address, and the disagreement shows up in
 * redirects, generated links and logs, a long way from the rewrite. Stripping here would also have
 * to be done twice — the upgrade path takes the URI straight off the inbound request — so the
 * rewrite that looks like one line is two.
 *
 * <p><b>An upgrade takes a different road through this class, and that is deliberate.</b> {@code WS
 * /terminal/commands/{id}} and {@code WS /chat/commands/{id}} are proxied by {@link #proxyUpgrade}
 * rather than by {@code vertx-http-proxy}, because the library's upgrade path skips its own
 * interceptor chain <em>and</em> pipes the socket with no flow control whatsoever. That method
 * carries the measurement.
 *
 * <p><b>Two interceptors on the ordinary path, and the reason each is there.</b> The bearer is
 * added here rather than being anything the caller supplies — it is peer authentication between
 * qits and the container, so a caller-supplied one would be meaningless and a forwarded one would
 * be a credential leak. The host rewrite pins the authority the daemon sees to a constant, so it
 * does not change under the daemon when the origin does — which is exactly what an ephemeral
 * loopback tunnel port does on every reconnect. The upgrade path arranges both for itself, one
 * mechanism per path.
 *
 * <p><b>Who is admitted, and why the list is this short.</b> The daemon behind this route is the
 * project's delivery desk: its commands, its coding-agent sessions and its last answer, which this
 * service's own REST doors ({@code ProjectFrontDeskController}, {@code AgentContainerController})
 * open only to {@code qits:admin}, {@code qits:admin-agent} and {@code qits:desk-admin}. A raw
 * Vert.x route is reached by no {@code @RolesAllowed}, so it used to forward anyone — and a plain
 * {@code qits:agent} bearer read the desk through here what the doors refused it. The owner's ruling
 * on qits-1575 is that agents do not reach the desk's daemon, so {@link #admit} decides before the
 * tunnel is looked up and before a single byte, upgrade included, goes anywhere: {@code
 * qits:system}, {@code qits:admin} and {@code qits:admin-agent} reach any project's daemon; {@code
 * qits:desk-admin} reaches only the one its token's {@code project} claim names ({@link
 * AgentAccess#coversProject}), the same binding the doors apply to the desk; an anonymous caller is
 * a 401 and everyone else — {@code qits:agent} alone, a plain {@code qits:desk}, {@code qits:ci-run}
 * — a 403. The {@code container-proxy} permission in {@code application.properties} keeps the
 * anonymous out before this class runs; the check here is what judges the authenticated. It mirrors
 * qits-workspaces' admission (qits-1386) minus the agent binding, because no agent is admitted.
 */
@ApplicationScoped
public class ContainerProxyRoute {

  private static final Logger LOG = Logger.getLogger(ContainerProxyRoute.class);

  /** The roles that reach any project's daemon; {@code qits:desk-admin} reaches only its own. */
  private static final List<String> ANY_PROJECT_ROLES =
      List.of(AgentAccess.SYSTEM_ROLE, AgentAccess.ADMIN_ROLE, AgentAccess.ADMIN_AGENT_ROLE);

  @Inject Vertx vertx;

  @Inject AgentTunnels tunnels;

  /** The bearer the daemon requires; the same value {@code deskhost/FrontDeskSpecs} injects. */
  @ConfigProperty(name = "qits.projects.daemon-api-token", defaultValue = "qits-projects-daemon")
  String daemonApiToken;

  /**
   * The daemon's own port — not where the proxy connects, but the authority it presents. Pinning it
   * to a constant is what keeps the daemon's view of who called it identical across reconnects,
   * since the real origin is a loopback port the OS picks afresh each time.
   */
  @ConfigProperty(name = "qits.projects.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  void init(@Observes Router router) {
    router.route(ContainerProxyPath.PREFIX + "*").handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    // `route(PREFIX + "*")` also matches the bare prefix with no trailing slash, one character
    // short of `start` — where the substring below would overflow. No segments, no project.
    int start = ContainerProxyPath.PREFIX.length();
    if (path.length() < start) {
      respond(rc, 404, "No project agent here.");
      return;
    }
    // Limit 2: the id, then the rest. The rest is only ever LOOKED AT here — it is not removed, and
    // the request that goes on the wire still carries the whole path. The daemon knows this prefix
    // is its address because it was told so at container creation; see the class javadoc.
    String[] segments = path.substring(start).split("/", 2);
    if (segments.length < 1 || segments[0].isEmpty()) {
      respond(rc, 404, "No project agent here.");
      return;
    }
    String projectId = segments[0];

    // The request stays untouched while the caller is judged and the lookup runs off the event loop
    // — opening a tunnel awaits a bind on first use. The proxy resumes it when forwarding; a
    // refusal resumes it to drain. Nothing goes on the wire, and no upgrade is answered, before the
    // decision.
    rc.request().pause();
    // The request's own context — in Quarkus a duplicate on the connection's event loop. An upgrade
    // hands the browser socket over on it; see UpgradePipe for the frame that is lost otherwise.
    Context inboundContext = Vertx.currentContext();
    QuarkusHttpUser.getSecurityIdentity(rc, null)
        .subscribe()
        .with(
            identity -> admit(rc, projectId, identity, inboundContext),
            failure -> refuse(rc, 401, "Authentication required.", null, projectId));
  }

  /** Decide who may reach this project's daemon, then look the daemon up. See the class javadoc. */
  private void admit(
      RoutingContext rc, String projectId, SecurityIdentity identity, Context inboundContext) {
    if (identity == null || identity.isAnonymous()) {
      refuse(rc, 401, "Authentication required.", identity, projectId);
      return;
    }
    boolean admitted =
        ANY_PROJECT_ROLES.stream().anyMatch(identity::hasRole)
            || (identity.hasRole(AgentAccess.DESK_ADMIN_ROLE)
                && AgentAccess.coversProject(identity, projectId));
    if (!admitted) {
      refuse(rc, 403, "The project agent is not yours to reach.", identity, projectId);
      return;
    }
    rc.vertx()
        .executeBlocking(() -> tunnels.originFor(projectId).orElse(null))
        .onFailure(e -> respond(rc, 502, "The project agent could not be reached."))
        .onSuccess(origin -> route(rc, origin, inboundContext));
  }

  /** Answer a refusal and say who was refused — subject, roles, project; never a credential. */
  private void refuse(
      RoutingContext rc, int status, String message, SecurityIdentity identity, String projectId) {
    LOG.infof(
        "Refused the project daemon proxy (%d): %s with roles %s for project %s",
        status,
        identity == null || identity.getPrincipal() == null
            ? null
            : identity.getPrincipal().getName(),
        identity == null ? Set.of() : identity.getRoles(),
        projectId);
    // Paused since `handle`; let whatever body follows drain rather than stall the connection.
    rc.request().resume();
    respond(rc, status, message);
  }

  /**
   * One state, one answer. A naive proxy reports "no such project", "container not running" and
   * "daemon never connected" as one indistinguishable 502; here the three collapse honestly into
   * one, because a live control socket is the only evidence this route has or needs — it is
   * stronger than {@code docker inspect} and costs one round-trip less.
   */
  private void route(RoutingContext rc, AgentTunnels.TunnelOrigin origin, Context inboundContext) {
    if (origin == null) {
      respond(
          rc,
          502,
          "The project agent is not running — start it from the project's refinement panel.");
      return;
    }
    // The client is the tunnel's, never a shared one; see AgentTunnels for what sharing would cost.
    forward(rc, origin.client(), origin.port(), inboundContext);
  }

  /**
   * One origin, two transports. An ordinary request goes through {@code vertx-http-proxy}; a
   * WebSocket upgrade does not, and {@link #proxyUpgrade} says why.
   */
  private void forward(RoutingContext rc, HttpClient client, int port, Context inboundContext) {
    if (isWebSocketUpgrade(rc.request())) {
      proxyUpgrade(rc, client, port, inboundContext);
      return;
    }
    HttpProxy.reverseProxy(client)
        .origin(port, "127.0.0.1")
        .addInterceptor(bearer(daemonApiToken))
        .addInterceptor(hostRewrite(daemonApiPort))
        .handle(rc.request());
  }

  private static boolean isWebSocketUpgrade(HttpServerRequest request) {
    return request.headers().contains(HttpHeaders.UPGRADE, "websocket", true);
  }

  /**
   * Carry a WebSocket upgrade to the daemon ourselves, rather than letting {@code vertx-http-proxy}
   * do it.
   *
   * <p><b>{@code vertx-http-proxy} handles an upgrade with no flow control at all.</b> Read out of
   * 4.5.26: {@code ReverseProxy.handle} branches to {@code handleWebSocketUpgrade} and returns
   * before the interceptor iterator is installed — so both interceptors above are dead on this path
   * — and the pipe it then builds is three bare handler installs, {@code
   * serverRequest.handler(clientRequest::write)} before the 101 and {@code a.handler(b::write)}
   * both ways after it. No {@code writeQueueFull}, no {@code pause}, no {@code drainHandler}, and a
   * failure arm that prints {@code "Handle this case"} to {@code System.err}. A fast producer in the
   * container — a chatty build on a terminal socket, a runaway log stream — has nothing telling it
   * to slow down, and the bytes pile up in this process's heap. {@link DaemonStreamRoute} pauses and
   * drains correctly on the tunnel hop; this is the same pipe one hop closer to the browser.
   *
   * <p>So the upgrade is done by hand: the same request, the same headers, the same raw byte pipe —
   * and {@code pause}/{@code drainHandler} in both directions, plus a failure arm that answers the
   * caller. Nothing about the contract moves. The path is still forwarded verbatim, the daemon
   * still authenticates the handshake with the bearer this service presents, and neither end learns
   * it is proxied.
   *
   * <p>The bearer is set on the <em>outbound</em> request here rather than mutated onto the inbound
   * one, which is what the interceptor-skipping defect used to force. It is peer authentication
   * between qits and the container — set, never forwarded — exactly as {@link #bearer} does for an
   * ordinary request. There is still one mechanism per path.
   *
   * <p>{@code Host} is deliberately not copied, so the daemon sees the origin's own authority rather
   * than the browser's — what {@link #hostRewrite} arranges for an ordinary request, and what the
   * client's own default arranges here.
   */
  private void proxyUpgrade(
      RoutingContext rc, HttpClient client, int port, Context inboundContext) {
    HttpServerRequest inbound = rc.request();
    client
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("127.0.0.1")
                .setPort(port)
                .setURI(inbound.uri()))
        .onFailure(
            failure -> {
              LOG.debugf("projects-daemon upgrade could not be opened: %s", String.valueOf(failure));
              inbound.resume();
              respond(rc, 502, "The project agent is not reachable — try restarting it.");
            })
        .onSuccess(outbound -> openUpgrade(rc, inbound, outbound, inboundContext));
  }

  /** Send the handshake on, streaming whatever precedes the 101 with a queue bound on it. */
  private void openUpgrade(
      RoutingContext rc,
      HttpServerRequest inbound,
      HttpClientRequest outbound,
      Context inboundContext) {
    for (Map.Entry<String, String> header : inbound.headers()) {
      if (HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())) {
        outbound.headers().set(HttpHeaders.CONNECTION, HttpHeaders.UPGRADE);
      } else if (!HttpHeaders.HOST.toString().equalsIgnoreCase(header.getKey())) {
        outbound.headers().add(header.getKey(), header.getValue());
      }
    }
    outbound.headers().set("Authorization", "Bearer " + daemonApiToken);

    Future<HttpClientResponse> handshake = outbound.connect();
    inbound.handler(
        buffer -> {
          outbound.write(buffer);
          if (outbound.writeQueueFull()) {
            inbound.pause();
            outbound.drainHandler(drained -> inbound.resume());
          }
        });
    inbound.endHandler(end -> outbound.end());
    // Paused since `handle`, so the tunnel lookup could run off the event loop without losing bytes.
    inbound.resume();

    handshake
        .onFailure(
            failure -> {
              LOG.debugf("projects-daemon refused the upgrade: %s", String.valueOf(failure));
              respond(rc, 502, "The project agent closed the connection.");
            })
        .onSuccess(response -> completeUpgrade(rc, inbound, response, inboundContext));
  }

  /**
   * Marry the two sockets once the daemon has agreed, or pass its refusal on.
   *
   * <p>A refusal is forwarded with its own status and body rather than flattened into a 502: the
   * daemon answers 401 for a bad bearer and 404 for a command that is not running, and those say
   * what to do. {@code vertx-http-proxy} answers the status alone with no body at all.
   *
   * <p>An agreement is handed to {@link UpgradePipe#marry} straight after {@code netSocket()}, on
   * the daemon response's context and before anything else runs there, and the browser's {@code
   * toNetSocket()} is called on {@code inboundContext}, the request's own: each side has a window
   * in which an event reaches a socket with no handler and is dropped, and that class says how the
   * two are closed.
   */
  private void completeUpgrade(
      RoutingContext rc,
      HttpServerRequest inbound,
      HttpClientResponse response,
      Context inboundContext) {
    HttpServerResponse out = inbound.response();
    if (response.statusCode() != 101) {
      out.setStatusCode(response.statusCode());
      for (Map.Entry<String, String> header : response.headers()) {
        // Hop-by-hop framing belongs to this response, not to the origin's.
        if (!HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())
            && !HttpHeaders.TRANSFER_ENCODING.toString().equalsIgnoreCase(header.getKey())) {
          out.headers().add(header.getKey(), header.getValue());
        }
      }
      // pipeTo, not a handler: it carries the backpressure and the end/failure wiring for free.
      response.pipeTo(out);
      return;
    }
    out.setStatusCode(101);
    out.headers().addAll(response.headers());
    NetSocket daemon = response.netSocket();
    UpgradePipe.marry(daemon, inboundContext, inbound::toNetSocket);
  }

  /**
   * Present qits' own credential to the daemon. The daemon's bearer is peer authentication between
   * this service and the container — it says "qits is calling", not "this user is calling", and the
   * daemon has no user identity to check anyway. So it is <em>set</em>, replacing whatever the
   * inbound request carried: forwarding a caller-supplied Authorization would be both meaningless
   * and a way to smuggle a credential into a container.
   */
  public static ProxyInterceptor bearer(String token) {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        context.request().headers().set("Authorization", "Bearer " + token);
        return context.sendRequest();
      }
    };
  }

  /**
   * Pin the authority the daemon sees. Without it the daemon's view of who called it is whatever
   * the origin happens to be, and the origin here is an ephemeral loopback port that changes on
   * every tunnel rebuild. A header that quietly changes meaning between two requests to the same
   * container is worth three lines to prevent. ({@code ProxyInterceptor} has no abstract method —
   * it is not a functional interface — so this must be an explicit implementation, not a lambda.)
   */
  public static ProxyInterceptor hostRewrite(int port) {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        context.request().setAuthority(HostAndPort.create("localhost", port));
        return context.sendRequest();
      }
    };
  }

  /**
   * Errors here answer JSON in the shape the daemon itself answers a failure with ({@code
   * {"message": …}}), so a client's error handling does not fork on which hop failed.
   */
  private void respond(RoutingContext rc, int status, String message) {
    rc.response()
        .setStatusCode(status)
        .putHeader("Content-Type", "application/json")
        .end(new io.vertx.core.json.JsonObject().put("message", message).encode());
  }
}
