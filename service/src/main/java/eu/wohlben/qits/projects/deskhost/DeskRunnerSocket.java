package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.projects.control.DeskRunners;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthChecked;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginState;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The endpoint a registered front-desk runner holds open for as long as its host is up (qits-767).
 * It owns the WebSocket lifecycle and the framing; {@link DeskRunnerRegistry} owns the sessions —
 * qits-workspaces-service's {@code WorkspaceRunnerSocket}, in the desk spelling.
 *
 * <p><b>Identity is the bearer, and only the bearer.</b> {@code @RolesAllowed("qits:desk-runner")}
 * — the role qits-idp grants a {@code desk-runner} client — is enforced at the HTTP upgrade, so a
 * dial without it never reaches {@link #onOpen}. There the token's {@code sub}, which qits-idp sets
 * to the client id of a {@code client_credentials} token, is looked up against {@code
 * desk_runner.client_id}. No row is a 1008 {@link RunnerWire.CloseReason#RUNNER_DELETED}, which a
 * runner reads as its deletion and decommissions itself on. Nothing on the wire names a runner.
 *
 * <p><b>The subject is read off the validated token and nowhere else</b>, so the machine gate
 * ({@code qits.auth.machine.required}) must be on for a runner to connect at all. A dial with no
 * subject is closed {@link #UNKNOWN_RUNNER}, which never makes a runner remove itself.
 *
 * <p><b>The path literal carries {@code /projects} itself</b>: a {@code @WebSocket} path does not
 * follow {@code quarkus.rest.path}. It is the protocol jar's {@link DeskRunnerProtocol#SOCKET_PATH},
 * the one spelling the register door hands out; {@code quarkus.quinoa.ignored-path-prefixes} already
 * keeps the SPA fallback off it. {@link SocketBearerLifetime} keeps the connection past its bearer's
 * {@code exp}.
 *
 * <p>Frames are handled on virtual threads, and an undecodable one is dropped and logged rather than
 * allowed to close the socket. Every frame stamps the runner as seen.
 */
@WebSocket(path = DeskRunnerProtocol.SOCKET_PATH)
@RolesAllowed(DeskRunnerSocket.RUNNER_ROLE)
public class DeskRunnerSocket {

  private static final Logger LOG = Logger.getLogger(DeskRunnerSocket.class);

  /** The role qits-idp grants a {@code desk-runner} client, and the only one admitted. */
  public static final String RUNNER_ROLE = "qits:desk-runner";

  /** A dial whose bearer carries no subject: a host that cannot read identity. */
  public static final String UNKNOWN_RUNNER = "UNKNOWN_RUNNER";

  /** A runner speaking another capability version at the pinned version. */
  public static final String CAPABILITY_MISMATCH = "CAPABILITY_MISMATCH";

  private static final UserData.TypedKey<DeskRunnerRegistry.Session> SESSION =
      new UserData.TypedKey<>("deskRunnerSession");

  @Inject DeskRunnerRegistry registry;

  @Inject DeskRunnerMessageCodec codec;

  @Inject DeskRunners runners;

  @Inject SecurityIdentity identity;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    Optional<String> subject = MachineIdentity.claim(identity, "sub");
    Optional<DeskRunner> runner = subject.flatMap(runners::findByClientId);
    if (runner.isEmpty()) {
      LOG.warnf(
          "Refused a desk runner dial from %s: subject %s is no registered runner",
          connection.handshakeRequest().remoteAddress(), subject.orElse("(none)"));
      refuse(
          connection, subject.isPresent() ? RunnerWire.CloseReason.RUNNER_DELETED : UNKNOWN_RUNNER);
      return;
    }
    connection.userData().put(SESSION, registry.admit(runner.orElseThrow(), connection));
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    DeskRunnerRegistry.Session session = connection.userData().get(SESSION);
    if (session == null) {
      return;
    }
    RunnerMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable frame from runner %s: %s", session.runnerName(), e.getMessage());
      return;
    }
    switch (decoded) {
      case Hello hello -> {
        switch (registry.onHello(session, hello)) {
          case GREETED -> {}
          case VERSION_MISMATCH -> refuse(connection, CAPABILITY_MISMATCH);
          case RUNNER_GONE -> refuse(connection, RunnerWire.CloseReason.RUNNER_DELETED);
        }
      }
      case Heartbeat ignored -> registry.onFrame(session);
      case Reserve ignored -> {
        registry.onFrame(session);
        registry.onReserve(session);
      }
      case LoginState login -> registry.onLoginState(session, login);
      case HealthChecked checked -> {
        registry.onFrame(session);
        registry.onHealthChecked(session, checked);
      }
      case DeskRunnerMessage desk -> {
        registry.onFrame(session);
        registry.onDeskFrame(session, desk);
      }
      default -> {
        registry.onFrame(session);
        // Host → runner lifecycle frames are never received here; ignored rather than trusted.
        LOG.debugf(
            "Runner %s sent a host frame %s — ignored",
            session.runnerName(), decoded.getClass().getSimpleName());
      }
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    DeskRunnerRegistry.Session session = connection.userData().get(SESSION);
    if (session != null) {
      registry.onClose(session);
    }
  }

  private void refuse(WebSocketConnection connection, String reason) {
    DeskRunnerRegistry.closeBounded(
        connection,
        new CloseReason(DeskRunnerRegistry.CLOSE_POLICY, reason),
        "a refused desk runner dial");
  }
}
