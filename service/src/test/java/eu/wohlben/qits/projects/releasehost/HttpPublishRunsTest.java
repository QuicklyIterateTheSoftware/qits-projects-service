package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
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
 * {@link HttpPublishRuns} against a local server standing in for qits-ci — plain JUnit over a
 * directly-constructed bean, {@link HttpBackingBranchMergerTest}'s shape.
 *
 * <p>Everything here is about ONE rule that only a wire test can check: <b>no failure is ever {@code
 * false}</b>. A 503, any other status, a body with no {@code declared}, an unset address and nobody
 * listening are all "could not ask", because the one thing the caller may never do is finalize a
 * release whose publish was never checked.
 */
class HttpPublishRunsTest {

  private record Received(String method, String path, String query, String user) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"repositoryId\":\"repo-1\",\"rev\":\"refs/tags/v\",\"declared\":true}");

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
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestURI().getQuery(),
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

  /** With the named client off — the shipped default — the read presents the forwarded pair. */
  private HttpPublishRuns against(String base) {
    HttpPublishRuns runs = new HttpPublishRuns();
    runs.ciUrl = Optional.ofNullable(base);
    runs.bearer = new IdpCiBearer();
    return runs;
  }

  @Test
  void aDeclaredReleaseIsReadOffTheTagsOwnRev() throws Exception {
    String base = startServer();

    assertEquals(Optional.of(true), against(base).declaredFor("repo-1", "refs/tags/2026.916.1"));

    Received asked = received.get(0);
    assertEquals("GET", asked.method());
    assertEquals("/ci/api/repositories/repo-1/release-phase", asked.path());
    assertTrue(asked.query().contains("refs/tags/2026.916.1"), asked.query());
    assertEquals("qits-projects", asked.user(), "the forwarded pair, with no idp configured");
  }

  @Test
  void aRepositoryWithNoReleaseRunAnswersFalseAndNotEmpty() throws Exception {
    String base = startServer();
    responseBody.set("{\"repositoryId\":\"repo-1\",\"declared\":false,\"detail\":\"no release slot\"}");

    assertEquals(Optional.of(false), against(base).declaredFor("repo-1", "refs/tags/v"));
  }

  @Test
  void theFarSidesOwnCouldNotAnswerIsCouldNotAsk() throws Exception {
    String base = startServer();
    status.set(503);
    responseBody.set("{\"message\":\"the wrapper's archetypes could not be read\"}");

    assertEquals(Optional.empty(), against(base).declaredFor("repo-1", "refs/tags/v"));
  }

  @Test
  void aRefusalIsCouldNotAskToo() throws Exception {
    String base = startServer();
    status.set(403);
    responseBody.set("{\"message\":\"no\"}");

    assertEquals(Optional.empty(), against(base).declaredFor("repo-1", "refs/tags/v"));
  }

  @Test
  void aBodyWithoutADeclaredBooleanIsNotAnAnswer() throws Exception {
    String base = startServer();
    responseBody.set("{\"repositoryId\":\"repo-1\"}");

    assertEquals(Optional.empty(), against(base).declaredFor("repo-1", "refs/tags/v"));
  }

  @Test
  void anUnsetAddressAsksNobodyAndAnswersCouldNotAsk() {
    assertEquals(Optional.empty(), against(null).declaredFor("repo-1", "refs/tags/v"));
    assertEquals(Optional.empty(), against("  ").declaredFor("repo-1", "refs/tags/v"));
  }

  @Test
  void nobodyListeningIsCouldNotAskAndNeverAThrow() {
    assertEquals(
        Optional.empty(), against("http://127.0.0.1:1").declaredFor("repo-1", "refs/tags/v"));
  }
}
