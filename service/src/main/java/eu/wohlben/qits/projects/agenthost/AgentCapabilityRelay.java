package eu.wohlben.qits.projects.agenthost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.api.AgentCapabilityController;
import eu.wohlben.qits.projects.api.AgentCapabilityController.CapabilityReportRequest;
import eu.wohlben.qits.projects.control.AgentCapabilityCatalogueService;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The one call site of {@code PUT /projects/api/agent-capabilities} this service owes: read the
 * project agent's {@code GET /agents/available} once its daemon is reachable, and pass the body into
 * the ingest door.
 *
 * <p>The ingest door was built as a <b>relay</b> and its body is deliberately byte-identical to a
 * daemon's {@code /agents/available} answer, so whoever carries it has no opinion — see {@link
 * AgentCapabilityController}, which carries that argument. This class is qits-projects' carrier for
 * its own agent container; qits-workspaces writes the matching one for a workspace's.
 *
 * <h2>Where it runs, and where it deliberately does not</h2>
 *
 * <p><b>On the daemon's {@code Hello}</b>, from {@link AgentDaemonRegistry}, on a virtual thread.
 * That is the one moment that is both "a container has started" and "its daemon is reachable" —
 * which is exactly the pair the read needs, since the daemon binds loopback and is only addressable
 * through {@link AgentTunnels}. It is also, structurally, off every request path: nothing a person
 * presses reaches this class, so no editor read and no container ensure can be slowed by it, and the
 * process spawns the probe costs happen inside the container at its own boot, not here.
 *
 * <p><b>Not from {@code AgentContainers.ensure}.</b> An ensure returns while the container is still
 * pulling an image and long before the daemon has said anything, so a relay there would either block
 * the browser behind a boot or read nothing. Not from the capability GET either: that route is the
 * editor's and must never wait on a container.
 *
 * <h2>Absent is quiet, malformed is loud</h2>
 *
 * <p>Until both daemons are released, the ordinary case is a daemon that answers {@code
 * /agents/available} with the harness list it has always answered and none of {@code imageVersion},
 * {@code reportedBy} or {@code capabilities}. That is <b>absent</b>: it is logged at DEBUG, nothing
 * is written, and nothing about it is a failure — the catalogue simply keeps answering the shipped
 * fallback, which is a state it is designed to be in. A body that will not parse, or one naming a
 * harness this platform does not know, is <b>broken</b>: a WARN naming what it was, because that is
 * a daemon and a host disagreeing about a contract rather than a version lagging.
 *
 * <p>Nothing here throws into its caller and nothing here fails a container. The worst outcome of
 * every branch is that the editor's dropdowns keep the values they had.
 *
 * <h2>Two blanks are filled, and only two</h2>
 *
 * <p>{@code imageVersion} and {@code reportedBy} are filled from what this host knows — the image
 * pin it created the container from, and the project the container serves — <em>when the daemon
 * named neither</em>. That is not the relay growing an opinion about capabilities: the image version
 * is half the key the catalogue stores under, this service chose the pin, and a report keyed on the
 * empty string is one that cannot be told apart from another build's. A daemon that does name them
 * wins, always.
 */
@ApplicationScoped
public class AgentCapabilityRelay {

  private static final Logger LOG = Logger.getLogger(AgentCapabilityRelay.class);

  /**
   * The daemon route this reads — appended to that container's proxy base path, because no hop in
   * this chain rewrites a path and the daemon serves its API under the address it was told is its
   * own ({@code QITS_PROJECTS_DAEMON_API_BASE_PATH}). Reaching it as a bare {@code /agents/available}
   * would 404 at the daemon.
   */
  static final String AVAILABLE_PATH = "agents/available";

  @Inject AgentTunnels tunnels;

  @Inject AgentContainerFactory factory;

  @Inject ObjectMapper json;

  /**
   * What the ingest door writes through, called in process.
   *
   * <p><b>In process rather than an HTTP round trip to ourselves.</b> A loopback PUT would need this
   * service to hold a machine bearer for one of its own roles, would traverse the whole auth stack to
   * arrive at the same method, and would turn every failure into a status code this class then has to
   * re-interpret — for a background write nobody is waiting on.
   *
   * <p><b>But not around the door's mapping.</b> The ingest body <em>is</em> the daemons' contract,
   * and a second place that turns a report into rows would be the third place it can drift, which is
   * exactly what {@link AgentCapabilityController}'s javadoc forbids. So this class builds the
   * door's own {@link CapabilityReportRequest} and asks it for its rows: {@code reports()} is a pure
   * function on that record and is the one translation, shared with the door.
   *
   * <p>Calling {@link AgentCapabilityController#report} itself is not available and would not be
   * wanted: the class is {@code @RolesAllowed}, the interceptor runs on an in-process call too, and a
   * virtual thread reacting to a control socket carries no identity — measured here as an
   * {@code UnauthorizedException} out of a background relay, which is the door working correctly.
   */
  @Inject AgentCapabilityCatalogueService catalogue;

  /** The bearer the daemon requires — the same value {@link ContainerProxyRoute} presents. */
  @ConfigProperty(name = "qits.projects.daemon-api-token", defaultValue = "qits-projects-daemon")
  String daemonApiToken;

  /**
   * How long the whole read may take before it is abandoned. Short: this is a local loopback hop into
   * a container that has just said hello, and nothing waits on the answer, so a slow one is worth
   * less than the thread holding it.
   */
  @ConfigProperty(name = "qits.projects.agent-capabilities.relay-timeout-ms", defaultValue = "10000")
  long timeoutMs;

  /** Off switch, for a daemon whose route misbehaves; the catalogue then answers its fallback. */
  @ConfigProperty(name = "qits.projects.agent-capabilities.relay-enabled", defaultValue = "true")
  boolean enabled;

  /**
   * Projects with a relay in flight. A guard and not a cache: the far side is an upsert per
   * {@code (harness, image version)}, so relaying twice writes the same row twice and costs nothing
   * but the round trip — what would actually hurt is a flapping daemon stacking reads on a container
   * that is already struggling.
   */
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /**
   * A daemon has said hello for {@code projectId}: go and ask it what its harnesses can do.
   *
   * <p>Returns immediately. The caller is a WebSocket frame handler on an event loop and must not be
   * given anything to wait for; the work runs on a virtual thread, the way this repo's other
   * off-thread boot work does.
   */
  public void onDaemonHello(String projectId) {
    if (!enabled || projectId == null || projectId.isBlank()) {
      return;
    }
    if (!inFlight.add(projectId)) {
      return;
    }
    Thread.ofVirtual()
        .name("agent-capability-relay-" + projectId)
        .start(
            () -> {
              try {
                readAndIngest(projectId);
              } catch (RuntimeException e) {
                // A background read of an advisory catalogue. Nothing downstream of here is worth a
                // stack trace on a container start.
                LOG.warnf(
                    "Could not relay the harness capability report for project %s: %s",
                    projectId, e.toString());
              } finally {
                inFlight.remove(projectId);
              }
            });
  }

  /**
   * Read, decode, record. Every arm ends in a log line and none of them in an exception.
   *
   * <p>Package-private and synchronous, so a suite that already stands a fake daemon behind a real
   * tunnel can drive the whole hop — {@link AgentTunnelProxyTest} — without waiting on a virtual
   * thread and without the {@link #enabled} gate that keeps the automatic firing dark under test.
   */
  void readAndIngest(String projectId) {
    AgentTunnels.TunnelOrigin origin = tunnels.originFor(projectId).orElse(null);
    if (origin == null) {
      LOG.debugf("No tunnel to project %s; no capability report read", projectId);
      return;
    }
    String body = read(projectId, origin);
    if (body != null) {
      ingest(projectId, body);
    }
  }

  /**
   * Decode one {@code /agents/available} body and record it. Package-private so the three answers
   * this has to tell apart — a full report, an older daemon's, and something that is not this
   * contract at all — are testable without standing a container and a tunnel up to produce them.
   */
  void ingest(String projectId, String body) {
    CapabilityReportRequest report;
    try {
      report = json.readValue(body, CapabilityReportRequest.class);
    } catch (Exception malformed) {
      // Broken, not absent: the daemon answered something that is not this contract at all.
      LOG.warnf(
          "project %s answered %s with a body this service cannot read as a capability report: %s",
          projectId, AVAILABLE_PATH, malformed.toString());
      return;
    }
    if (report == null || report.capabilities() == null || report.capabilities().isEmpty()) {
      // The ordinary case until the daemons are released: an older /agents/available answers the
      // harness list and nothing else. Absent, and absent is fine.
      LOG.debugf(
          "project %s reports no harness capabilities yet (a daemon older than the probe);"
              + " the catalogue keeps what it has",
          projectId);
      return;
    }
    CapabilityReportRequest filled =
        new CapabilityReportRequest(
            blank(report.reportedBy()) ? "project-agent/" + projectId : report.reportedBy(),
            blank(report.imageVersion()) ? factory.imageVersion() : report.imageVersion(),
            report.capabilities());
    try {
      int recorded =
          catalogue.record(filled.reportedBy(), filled.imageVersion(), filled.reports());
      LOG.infof(
          "Recorded %d harness capability report(s) from project %s's agent container",
          Integer.valueOf(recorded), projectId);
    } catch (RuntimeException refused) {
      // The door refuses an unknown harness. That is the two sides disagreeing about a vocabulary,
      // which is loud by the same rule that makes an unparseable body loud.
      LOG.warnf(
          "project %s's capability report was refused by the ingest door: %s",
          projectId, refused.toString());
    }
  }

  /**
   * One GET through the tunnel, or null when there is nothing to read.
   *
   * <p>The client is the tunnel's own and must be — {@link AgentTunnels} carries what sharing one
   * would cost. A non-2xx is treated as absence rather than as a failure: a daemon that does not
   * serve this route at all is exactly the state this relay is written to tolerate, and there is no
   * caller to report a status code to.
   */
  private String read(String projectId, AgentTunnels.TunnelOrigin origin) {
    String path = ContainerProxyPath.base(projectId) + AVAILABLE_PATH;
    try {
      HttpClientResponse response =
          origin
              .client()
              .request(
                  new RequestOptions()
                      .setMethod(HttpMethod.GET)
                      .setHost("127.0.0.1")
                      .setPort(origin.port())
                      .setURI(path)
                      .putHeader("Authorization", "Bearer " + daemonApiToken)
                      .setTimeout(timeoutMs))
              .compose(request -> request.send())
              .toCompletionStage()
              .toCompletableFuture()
              .get(timeoutMs, TimeUnit.MILLISECONDS);
      Buffer buffer =
          response
              .body()
              .toCompletionStage()
              .toCompletableFuture()
              .get(timeoutMs, TimeUnit.MILLISECONDS);
      if (response.statusCode() / 100 != 2) {
        LOG.debugf(
            "project %s answered %d for %s; no capability report to record",
            projectId, Integer.valueOf(response.statusCode()), AVAILABLE_PATH);
        return null;
      }
      return buffer == null ? null : buffer.toString();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      LOG.debugf("Could not read %s from project %s: %s", AVAILABLE_PATH, projectId, e.toString());
      return null;
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
