package eu.wohlben.qits.projects.releasehost;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpQaRunCancellations} against a local server standing in for qits-ci — {@link
 * HttpPipelinePhaseRerunsTest}'s shape.
 *
 * <p><b>The one thing worth a wire test here (qits-1264): {@code cancelQaRunsOf} sends exactly
 * {@code cancelRunsOf}'s body plus {@code "phase":"RELEASE_REQUEST"}, and nothing else changes.</b>
 * The two bodies are compared field for field so a future edit to either cannot drift the other —
 * the RELEASED call site depends on the QA-only ask being the narrowing, not a different shape.
 */
class HttpQaRunCancellationsTest {

  private record Received(String method, String path, String body) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();

  /** Every port a test built, shut down after it so the fork keeps no idle client threads. */
  private final List<HttpQaRunCancellations> ports = new CopyOnWriteArrayList<>();

  @AfterEach
  void closeClients() {
    ports.forEach(HttpQaRunCancellations::close);
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(
              new Received(
                  exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
          exchange.sendResponseHeaders(202, -1);
          exchange.getResponseBody().close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** With the named client off — the shipped default — the hop presents the forwarded pair. */
  private HttpQaRunCancellations against(String base) {
    HttpQaRunCancellations cancellations =
        new HttpQaRunCancellations(QitsHttpClients.of(OpenTelemetry.noop(), null));
    cancellations.ciUrl = Optional.ofNullable(base);
    cancellations.bearer = new IdpCiBearer();
    ports.add(cancellations);
    return cancellations;
  }

  @Test
  void cancelRunsOfSendsNoPhaseAtAll() throws Exception {
    String base = startServer();

    against(base).cancelRunsOf("repo-1", "req-1");

    Received asked = received.get(0);
    assertEquals("POST", asked.method());
    assertEquals("/ci/api/runs/cancellations", asked.path());
    assertTrue(asked.body().contains("\"repoId\":\"repo-1\""), asked.body());
    assertTrue(asked.body().contains("\"releaseRequestId\":\"req-1\""), asked.body());
    assertFalse(asked.body().contains("phase"), "the body qits-ci has always been sent: " + asked.body());
  }

  @Test
  void cancelQaRunsOfSendsExactlyThatBodyPlusThePhase() throws Exception {
    String base = startServer();

    against(base).cancelRunsOf("repo-1", "req-1");
    String everything = received.get(0).body();
    received.clear();

    against(base).cancelQaRunsOf("repo-1", "req-1");
    String qaOnly = received.get(0).body();

    assertEquals(
        everything.substring(0, everything.length() - 1) + ",\"phase\":\"RELEASE_REQUEST\"}",
        qaOnly,
        "the QA-only ask is cancelRunsOf's exact body with the phase field appended");
  }

  /** qits-909: the three-argument call appends the reason; a null reason is the two-argument body. */
  @Test
  void cancelQaRunsOfWithAReasonAppendsItAndANullReasonSendsNone() throws Exception {
    String base = startServer();

    against(base).cancelQaRunsOf("repo-1", "req-1");
    String qaOnly = received.get(0).body();
    received.clear();

    against(base).cancelQaRunsOf("repo-1", "req-1", "HELD_BY_PRE_RUN_GATE");
    String held = received.get(0).body();
    received.clear();

    against(base).cancelQaRunsOf("repo-1", "req-1", null);
    String noReason = received.get(0).body();

    assertEquals(
        qaOnly.substring(0, qaOnly.length() - 1) + ",\"reason\":\"HELD_BY_PRE_RUN_GATE\"}",
        held,
        "the QA-only body with the reason appended");
    assertEquals(qaOnly, noReason, "no reason is the two-argument body, byte for byte");
  }

  @Test
  void cancelQaRunsOfWithNoAddressAsksNobody() {
    // Same best-effort contract as cancelRunsOf: no configured ci-url, no attempt, no throw.
    against(null).cancelQaRunsOf("repo-1", "req-1");
    against("  ").cancelQaRunsOf("repo-1", "req-1");
    assertTrue(received.isEmpty());
  }

  @Test
  void cancelQaRunsOfWithNoReleaseRequestIdRefusesRatherThanWidening() throws Exception {
    String base = startServer();

    against(base).cancelQaRunsOf("repo-1", null);
    against(base).cancelQaRunsOf("repo-1", "  ");

    assertTrue(received.isEmpty(), "a repository-wide QA cancellation must never be sent either");
  }
}
