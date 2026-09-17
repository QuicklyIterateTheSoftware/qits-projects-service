package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.PipelinePhaseReruns;
import eu.wohlben.qits.projects.error.DomainException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpPipelinePhaseReruns} against a local server standing in for qits-ci — plain JUnit over
 * a directly-constructed bean, {@link HttpPublishRunsTest}'s shape.
 *
 * <p><b>Everything here is about one rule that only a wire test can check: the refusal arrives
 * intact.</b> qits-ci's 409 says <em>why</em> a phase cannot be asked again — that its newest run
 * succeeded and the verdict was spent on cutting the tag, that the phase never ran, that it is
 * running right now — and that sentence is the fact the person pressing the button has not got. A
 * port that answered "could not re-run that phase" would be strictly less than what was already
 * known, so the status and the message both cross unchanged.
 */
class HttpPipelinePhaseRerunsTest {

  private record Received(String method, String path, String body, String user) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(202);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"runId\":\"run-99\"}");

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
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  body,
                  exchange.getRequestHeaders().getFirst("X-Qits-User")));
          byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** With the named client off — the shipped default — the hop presents the forwarded pair. */
  private HttpPipelinePhaseReruns against(String base) {
    HttpPipelinePhaseReruns reruns = new HttpPipelinePhaseReruns();
    reruns.ciUrl = Optional.ofNullable(base);
    reruns.bearer = new IdpCiBearer();
    return reruns;
  }

  @Test
  void theQaPhaseIsAskedForByQitsCisOwnWordAndTheNewRunIsNamed() throws Exception {
    String base = startServer();

    assertEquals(
        "run-99", against(base).rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA));

    Received asked = received.get(0);
    assertEquals("POST", asked.method());
    assertEquals("/ci/api/runs/rerun", asked.path());
    assertTrue(asked.body().contains("\"phase\":\"RELEASE_REQUEST\""), asked.body());
    assertTrue(asked.body().contains("\"releaseRequestId\":\"req-1\""), asked.body());
    assertEquals("qits-projects", asked.user(), "the forwarded pair, with no idp configured");
  }

  @Test
  void thePublishPhaseCrossesAsRelease() throws Exception {
    String base = startServer();

    against(base).rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_PUBLISH);

    assertTrue(received.get(0).body().contains("\"phase\":\"RELEASE\""), received.get(0).body());
  }

  /**
   * The load-bearing one. A spent phase is a 409 whose sentence names the run and says what the
   * verdict was spent on — and that is what the caller has to read, not a paraphrase.
   */
  @Test
  void aRefusalKeepsItsStatusAndItsMessage() throws Exception {
    String base = startServer();
    String message =
        "The QA phase of release request req-1 succeeded (CI run run-7), so there is nothing to ask"
            + " again: that verdict was spent on cutting the tag, and the fold it built (branch"
            + " release/req-1) no longer exists.";
    status.set(409);
    responseBody.set("{\"message\":\"" + message + "\"}");

    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> against(base).rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA));

    assertEquals(409, refused.statusCode());
    assertEquals(message, refused.getMessage(), "qits-ci's own words, not a paraphrase");
  }

  @Test
  void anUnknownRepositoryKeepsIts404Too() throws Exception {
    String base = startServer();
    status.set(404);
    responseBody.set("{\"message\":\"No such repository: repo-1\"}");

    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> against(base).rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA));

    assertEquals(404, refused.statusCode());
    assertEquals("No such repository: repo-1", refused.getMessage());
  }

  /** A refusal with nothing readable in it still travels with its status, and never with a guess. */
  @Test
  void aRefusalWithNoMessageGetsANeutralSentenceAndKeepsItsStatus() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set("<html>no</html>");

    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> against(base).rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA));

    assertEquals(409, refused.statusCode());
    assertTrue(refused.getMessage().contains("409"), refused.getMessage());
  }

  /** No address is a configuration fact about this platform, not an outage: 503, at the door. */
  @Test
  void anUnsetAddressIs503AndAsksNobody() {
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> against(null).rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA));
    assertEquals(503, refused.statusCode());
    assertEquals(
        503,
        assertThrows(
                DomainException.class,
                () -> against("  ").rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA))
            .statusCode());
  }

  @Test
  void nobodyListeningIs502() {
    assertEquals(
        502,
        assertThrows(
                DomainException.class,
                () ->
                    against("http://127.0.0.1:1")
                        .rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA))
            .statusCode());
  }

  /** Accepted but naming no run: the ask may well have landed, so it is the exchange that failed. */
  @Test
  void anAcceptedAnswerNamingNoRunIs502() throws Exception {
    String base = startServer();
    responseBody.set("{}");

    assertEquals(
        502,
        assertThrows(
                DomainException.class,
                () -> against(base).rerun("repo-1", "req-1", PipelinePhaseReruns.CI_PHASE_QA))
            .statusCode());
  }
}
