package eu.wohlben.qits.projects.workspacehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpWorkspaceAgentLookup} against a local server standing in for qits-workspaces — plain
 * JUnit over a directly-constructed bean, {@link HttpWorkspaceAgentTurnsTest}'s shape.
 *
 * <p>The wire (one GET of {@code /workspaces/api/agents/{agentId}} with the machine bearer) and that
 * every way it can go wrong answers empty rather than throwing: a comment is written whatever this
 * says.
 */
class HttpWorkspaceAgentLookupTest {

  private record Received(String method, String path, String auth) {}

  private static final String AGENT = "6f1c2a8e-3b7d-4e0a-9c55-1d2e3f405162";

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicLong delayMs = new AtomicLong(0);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"agentId\":\"" + AGENT + "\",\"workId\":\"w-1402\"}");

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
                  exchange.getRequestHeaders().getFirst("Authorization")));
          try {
            Thread.sleep(delayMs.get());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          } catch (IOException e) {
            // The client gave up first: the timeout case.
          }
        });
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpWorkspaceAgentLookup against(
      String base, String fallback, Optional<String> authorization) {
    HttpWorkspaceAgentLookup lookup =
        new HttpWorkspaceAgentLookup(QitsHttpClients.of(OpenTelemetry.noop(), null));
    lookup.workspacesUrl = Optional.ofNullable(base);
    lookup.releaseWorkspacesUrl = Optional.ofNullable(fallback);
    lookup.bearer = () -> authorization;
    return lookup;
  }

  private HttpWorkspaceAgentLookup against(String base) {
    return against(base, null, Optional.of("Bearer machine-token"));
  }

  @Test
  void theAgentsWorkItemIsReadOffTheAgentRead() throws Exception {
    String base = startServer();

    assertEquals(Optional.of("w-1402"), against(base).workOf(AGENT));

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("GET", request.method());
    assertEquals("/workspaces/api/agents/" + AGENT, request.path());
    assertEquals("Bearer machine-token", request.auth());
  }

  @Test
  void anUnknownAgentIsEmpty() throws Exception {
    String base = startServer();
    status.set(404);
    responseBody.set("{\"message\":\"no such agent\"}");

    assertEquals(Optional.empty(), assertDoesNotThrow(() -> against(base).workOf(AGENT)));
  }

  @Test
  void aRefusingFarSideIsEmptyAndNeverAThrow() throws Exception {
    String base = startServer();
    status.set(500);
    responseBody.set("{\"message\":\"broken\"}");

    assertEquals(Optional.empty(), assertDoesNotThrow(() -> against(base).workOf(AGENT)));
  }

  @Test
  void garbageIsEmptyAndNeverAThrow() throws Exception {
    String base = startServer();

    responseBody.set("not json at all");
    assertEquals(Optional.empty(), assertDoesNotThrow(() -> against(base).workOf(AGENT)));

    responseBody.set("{\"agentId\":\"" + AGENT + "\",\"workId\":\"  \"}");
    assertEquals(Optional.empty(), against(base).workOf(AGENT), "a blank workId is no answer");

    responseBody.set("[1,2,3]");
    assertEquals(Optional.empty(), assertDoesNotThrow(() -> against(base).workOf(AGENT)));
  }

  /** The request timeout is 5 s: a far side that takes longer is "could not say". */
  @Test
  void aTimeoutIsEmptyAndNeverAThrow() throws Exception {
    String base = startServer();
    delayMs.set(7_000);

    long started = System.nanoTime();
    assertEquals(Optional.empty(), assertDoesNotThrow(() -> against(base).workOf(AGENT)));
    long tookMs = (System.nanoTime() - started) / 1_000_000;
    assertTrue(tookMs < 6_500, "bounded by the request timeout, took " + tookMs + " ms");
  }

  @Test
  void anUnreachableWorkspacesIsEmpty() {
    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(
            () -> against("http://127.0.0.1:1", null, Optional.of("Bearer t")).workOf(AGENT)));
  }

  @Test
  void noAddressOrNoBearerDialsNothing() throws Exception {
    String base = startServer();

    assertEquals(Optional.empty(), against(null, " ", Optional.of("Bearer t")).workOf(AGENT));
    assertEquals(Optional.empty(), against(base, null, Optional.empty()).workOf(AGENT));

    assertTrue(received.isEmpty(), "nothing dialled: " + received);
  }

  /** The two keys of every hop to qits-workspaces, in their order — no third address. */
  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    assertEquals(
        Optional.of("w-1402"),
        against(null, base, Optional.of("Bearer machine-token")).workOf(AGENT));
  }
}
