package eu.wohlben.qits.projects.agenthost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.api.AgentCapabilityController;
import eu.wohlben.qits.projects.api.AgentCapabilityController.CapabilityReportRequest;
import eu.wohlben.qits.projects.control.AgentCapabilityCatalogueService;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
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
 * That is the first moment the container is reachable at all — the daemon binds loopback and is only
 * addressable through {@link AgentTunnels}, so a control socket is the earliest evidence there is
 * anything to ask. It is also, structurally, off every request path: nothing a person presses reaches
 * this class, so no editor read and no container ensure can be slowed by it, and the process spawns
 * the probe costs happen inside the container at its own boot, not here.
 *
 * <p><b>Not from {@code AgentContainers.ensure}.</b> An ensure returns while the container is still
 * pulling an image and long before the daemon has said anything, so a relay there would either block
 * the browser behind a boot or read nothing. Not from the capability GET either: that route is the
 * editor's and must never wait on a container.
 *
 * <h2>{@code Hello} is not "the daemon can answer this", and that is the defect this retry fixes</h2>
 *
 * <p>Measured live on 2026-09-09 (qits-projects-service 2026.909.115344, qits-projects-daemon
 * 2026.909.114544): the daemon's probe answered a complete report through the tunnel, the ingest door
 * recorded it, and yet a genuine fresh {@code Hello} filled nothing — the catalogue kept answering
 * the shipped fallback for minutes. The reason is on the other side of the socket and is structural
 * rather than a race in the ordinary sense. {@code ControlSocket.start()} kicks the boot self-clone
 * onto a worker and dials home <em>in parallel</em>, and it is the worker that, when the clone
 * finishes, calls {@code wireCapabilities()} — which probes the harnesses <b>and only then</b> calls
 * {@code projectsApi.start()}, the loopback bind. So at the instant of {@code Hello} the daemon's API
 * is not listening at all: the tunnel opens, the stream is requested, the daemon's own dial-back
 * finds nothing on {@code 127.0.0.1:13338}, and the read fails. One attempt, at the one moment the
 * daemon guarantees it cannot answer, and every later moment — when the answer is complete and
 * correct — nobody asks again.
 *
 * <p><b>So the read is retried, and the retry is condition-driven rather than a poll.</b> The
 * daemon offers no frame that says "my API is up and my harnesses are probed" — {@code Provisioned}
 * is sent from <em>inside</em> the clone, still ahead of the bind and the probe, and it is not sent
 * at all on a reconnect — so there is no better moment to fire at, and inventing one is a protocol
 * change in another repository that would strand every container already running. What there is
 * instead is an answer that distinguishes the two states: an unwired agent surface answers
 * <b>503</b>, an unbound one answers nothing at all, and a wired one answers the report. Those are
 * retried; every other outcome is terminal on the first attempt, so a warm container (whose
 * {@code /workspace} is already populated, and whose API is therefore up almost at once) still
 * records on attempt one and an older daemon is still quiet exactly once.
 *
 * <p><b>The window is bounded and the give-up is loud.</b> {@code relay-attempts} reads with an
 * exponential backoff capped at {@code relay-retry-max-ms} — about four minutes shipped — and a
 * window that ends without an answer is a <b>WARN naming the project and what the last attempt
 * saw</b>. That line is the whole point of the bound: a relay that silently found no tunnel for ever
 * is the green-while-dead shape this feature exists to remove, and it is what let this defect run
 * unseen with every arm below DEBUG. The {@link #inFlight} guard is held for the whole window, so a
 * flapping daemon reconnecting six times in a minute still has exactly one read in flight.
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
   * How many times the read may be attempted before the window is given up on, the first included.
   * Twelve with the shipped backoff is roughly four minutes, which is a boot self-clone of a wrapper
   * and its submodules with room to spare. One turns the retry off without turning the relay off.
   */
  @ConfigProperty(name = "qits.projects.agent-capabilities.relay-attempts", defaultValue = "12")
  int attempts;

  /** The first wait after a "not ready" answer; it doubles from here. */
  @ConfigProperty(
      name = "qits.projects.agent-capabilities.relay-retry-initial-ms",
      defaultValue = "2000")
  long retryInitialMs;

  /** The ceiling the doubling stops at, so a long clone is polled at a steady slow rate. */
  @ConfigProperty(name = "qits.projects.agent-capabilities.relay-retry-max-ms", defaultValue = "30000")
  long retryMaxMs;

  /**
   * Projects with a relay in flight — <b>for the whole retry window</b>, not for one read. A guard
   * and not a cache: the far side is an upsert per {@code (harness, image version)}, so relaying
   * twice writes the same row twice and costs nothing but the round trip — what would actually hurt
   * is a flapping daemon stacking reads, and stacking *windows* is how that would happen now.
   */
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /** Set at shutdown so a sleeping window stops rather than reading into a closing container set. */
  private volatile boolean stopped;

  @PreDestroy
  void stop() {
    stopped = true;
  }

  /**
   * What one attempt produced. Three of the four are terminal; only {@link Kind#NOT_READY} is worth
   * asking again, and separating it from {@link Kind#ABSENT} is what keeps an older daemon quiet on
   * the first attempt while a daemon that has not finished booting is waited for.
   */
  record Outcome(Kind kind, String detail) {

    enum Kind {
      /** A report was decoded and written. Done. */
      RECORDED,
      /** The daemon serves the route and named no capabilities: an older one. Quiet, and done. */
      ABSENT,
      /** Not this contract, or a harness the platform does not know. Loud, and done. */
      BROKEN,
      /** Nothing answered, or the answer said "not yet". Ask again. */
      NOT_READY
    }

    static Outcome recorded(String detail) {
      return new Outcome(Kind.RECORDED, detail);
    }

    static Outcome absent(String detail) {
      return new Outcome(Kind.ABSENT, detail);
    }

    static Outcome broken(String detail) {
      return new Outcome(Kind.BROKEN, detail);
    }

    static Outcome notReady(String detail) {
      return new Outcome(Kind.NOT_READY, detail);
    }
  }

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
                relay(projectId);
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
   * The whole window: read, and keep reading while the daemon says "not yet", up to
   * {@link #attempts}.
   *
   * <p>Package-private and synchronous so a suite standing a fake daemon behind a real tunnel can
   * drive it — {@link AgentTunnelProxyTest} — without the virtual thread and without the
   * {@link #enabled} gate that keeps the automatic firing dark under test.
   *
   * <p>It sleeps on a <b>virtual</b> thread, which parks rather than holding a carrier, so the cost
   * of a window that never succeeds is one continuation and twelve loopback GETs. Nothing waits on
   * it and nothing it does can fail a container start.
   */
  void relay(String projectId) {
    int limit = Math.max(1, attempts);
    long wait = Math.max(1L, retryInitialMs);
    Outcome last = Outcome.notReady("no attempt was made");
    for (int attempt = 1; attempt <= limit && !stopped; attempt++) {
      last = readAndIngest(projectId);
      if (last.kind() != Outcome.Kind.NOT_READY) {
        return;
      }
      if (attempt == limit || !pause(wait)) {
        break;
      }
      wait = Math.min(Math.max(1L, retryMaxMs), wait * 2);
    }
    if (stopped) {
      return;
    }
    // The one line that makes a future occurrence of this defect visible. Every arm of this relay is
    // deliberately quiet, which is exactly how it came to fill nothing for a whole release without
    // leaving a trace above DEBUG; a bounded window that ends unanswered is not quiet.
    LOG.warnf(
        "Gave up reading the harness capability report from project %s after %d attempt(s): %s."
            + " The catalogue keeps what it has (the shipped fallback, if this container is the"
            + " first). The next Hello from this project relays again.",
        projectId, Integer.valueOf(limit), last.detail());
  }

  /** Sleep, answering whether the window may continue. */
  private boolean pause(long millis) {
    try {
      Thread.sleep(Duration.ofMillis(millis));
      return !stopped;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * One attempt: read, decode, record. Every arm ends in a log line and none of them in an
   * exception.
   *
   * <p>Package-private and synchronous for {@link AgentTunnelProxyTest}, which drives a single
   * attempt where the retry would only slow the assertion down.
   */
  Outcome readAndIngest(String projectId) {
    AgentTunnels.TunnelOrigin origin = tunnels.originFor(projectId).orElse(null);
    if (origin == null) {
      // Retryable, and it is the arm that most needs to be: the tunnel is opened on demand from the
      // live control socket, so an empty answer here means the daemon has gone away again rather
      // than that this project has no capabilities to report.
      LOG.debugf("No tunnel to project %s; no capability report read", projectId);
      return Outcome.notReady("no tunnel to the project's agent container");
    }
    return read(projectId, origin);
  }

  /**
   * Decode one {@code /agents/available} body and record it. Package-private so the three answers
   * this has to tell apart — a full report, an older daemon's, and something that is not this
   * contract at all — are testable without standing a container and a tunnel up to produce them.
   */
  Outcome ingest(String projectId, String body) {
    CapabilityReportRequest report;
    try {
      report = json.readValue(body, CapabilityReportRequest.class);
    } catch (Exception malformed) {
      // Broken, not absent: the daemon answered something that is not this contract at all.
      LOG.warnf(
          "project %s answered %s with a body this service cannot read as a capability report: %s",
          projectId, AVAILABLE_PATH, malformed.toString());
      return Outcome.broken("the body is not a capability report: " + malformed);
    }
    if (report == null || report.capabilities() == null || report.capabilities().isEmpty()) {
      // The ordinary case until the daemons are released: an older /agents/available answers the
      // harness list and nothing else. Absent, and absent is fine — and terminal, because this is a
      // daemon that answered rather than one that is not up yet.
      LOG.debugf(
          "project %s reports no harness capabilities yet (a daemon older than the probe);"
              + " the catalogue keeps what it has",
          projectId);
      return Outcome.absent("the daemon named no capabilities");
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
      return Outcome.recorded("recorded " + recorded + " report(s)");
    } catch (RuntimeException refused) {
      // The door refuses an unknown harness. That is the two sides disagreeing about a vocabulary,
      // which is loud by the same rule that makes an unparseable body loud.
      LOG.warnf(
          "project %s's capability report was refused by the ingest door: %s",
          projectId, refused.toString());
      return Outcome.broken("the ingest door refused the report: " + refused);
    }
  }

  /**
   * One GET through the tunnel, classified.
   *
   * <p>The client is the tunnel's own and must be — {@link AgentTunnels} carries what sharing one
   * would cost.
   *
   * <p><b>The status codes are read, and the split is the fix.</b> A <b>404</b> is a daemon that does
   * not serve this route at all, which is absence and terminal — asking a second time gets the same
   * answer for ever. Anything else non-2xx is <b>not ready</b>: the daemon's own API answers 503
   * ("Coding agents are not available yet") for the whole window between its bind and its harness
   * probe finishing, and treating that as absence is precisely what made this relay fill nothing. A
   * hop that failed outright — which is what an unbound loopback API looks like from here, since the
   * daemon's dial-back finds nothing to pipe to — is not ready for the same reason.
   */
  private Outcome read(String projectId, AgentTunnels.TunnelOrigin origin) {
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
      int status = response.statusCode();
      if (status == 404) {
        LOG.debugf(
            "project %s does not serve %s (404); no capability report to record",
            projectId, AVAILABLE_PATH);
        return Outcome.absent("the daemon answered 404 for " + AVAILABLE_PATH);
      }
      if (status / 100 != 2) {
        LOG.debugf(
            "project %s answered %d for %s; its agent surface is not up yet",
            projectId, Integer.valueOf(status), AVAILABLE_PATH);
        return Outcome.notReady("the daemon answered " + status + " for " + AVAILABLE_PATH);
      }
      if (buffer == null) {
        return Outcome.notReady("the daemon answered " + status + " with no body");
      }
      return ingest(projectId, buffer.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.notReady("interrupted");
    } catch (Exception e) {
      LOG.debugf("Could not read %s from project %s: %s", AVAILABLE_PATH, projectId, e.toString());
      return Outcome.notReady("could not reach the daemon: " + e);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
