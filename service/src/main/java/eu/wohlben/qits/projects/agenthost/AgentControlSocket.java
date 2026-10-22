package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The endpoint each project's in-container {@code qits-projects-daemon} dials on boot. It owns only
 * the WebSocket lifecycle and JSON framing — {@link AgentDaemonRegistry} owns the state.
 *
 * <p><b>The path is an append-only cross-repo contract.</b> {@code deskhost/FrontDeskSpecs} injects
 * {@code ws://<host>:<port>/projects/daemon/<projectId>} as {@code QITS_PROJECTS_DAEMON_URL} into
 * every container it creates, and the daemon dials exactly that, verbatim, parsing no path out of
 * it. Only a container recreate re-injects the value, so changing this literal breaks every
 * container already running. It is spelled once, in {@link
 * DaemonProtocol#CONTROL_SOCKET_PATH_PREFIX}, and both repos read it from there.
 *
 * <p>Note a {@code @WebSocket} path is a literal that does <b>not</b> follow {@code
 * quarkus.rest.path}, so it carries the {@code /projects} segment itself — and, being outside
 * {@code /projects/api}, it needs its own entry in {@code quarkus.quinoa.ignored-path-prefixes} or
 * a plain GET on it answers the SPA's {@code index.html} with a 200.
 *
 * <p>Unlike qits-workspaces' equivalent the path parameter needs no parsing: a project id is
 * already a String, which is the only type websockets-next accepts for a {@code @PathParam}.
 *
 * <p><b>The path parameter is a desk id</b> (qits-1482): a FRONT desk's is its project id, so every
 * front desk dials exactly the path it always did; a DELIVERY desk dials {@code
 * /projects/daemon/<projectId>-delivery}, and its {@code hello} still carries the real project id.
 * The registry keys every daemon by its desk id.
 *
 * <p>The socket requires {@code qits:system} or {@code qits:agent} during the upgrade. Its daemon
 * callers present their commissioned machine tokens; the desk path parameter selects a target and is
 * not accepted as authentication by itself. A {@code qits:agent} caller may open only a desk of its
 * own project — {@link AgentControlSocketAccess} answers 403 for any other, and a desk's own token
 * opens only that desk. The reverse tunnel's nonce remains a second, connection-local guard.
 */
@WebSocket(
    path = DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX + "{deskId}",
    endpointId = AgentControlSocket.ENDPOINT_ID)
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
public class AgentControlSocket {

  /** Stated, so {@link AgentControlSocketAccess} names this endpoint and no other. */
  public static final String ENDPOINT_ID = "projects-agent-control-socket";

  private static final Logger LOG = Logger.getLogger(AgentControlSocket.class);

  @Inject AgentDaemonRegistry registry;

  @Inject DaemonMessageCodec codec;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("deskId") String deskId, WebSocketConnection connection) {
    registry.register(deskId, connection);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(
      String message, @PathParam("deskId") String deskId, WebSocketConnection connection) {
    DaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable projects-daemon frame for desk %s: %s", deskId, e.getMessage());
      return;
    }
    registry.onMessage(deskId, connection, decoded);
  }

  @OnClose
  public void onClose(@PathParam("deskId") String deskId, WebSocketConnection connection) {
    registry.unregister(deskId, connection);
  }
}
