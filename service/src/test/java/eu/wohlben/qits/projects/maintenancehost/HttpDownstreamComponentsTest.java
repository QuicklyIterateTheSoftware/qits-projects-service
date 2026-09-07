package eu.wohlben.qits.projects.maintenancehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * {@link HttpDownstreamComponents} against a local server standing in for qits-maintenance — plain
 * JUnit over a directly-constructed bean, {@code workspacehost/HttpReleasedBranchWorkspacesTest}'s
 * shape.
 *
 * <p>Two things are under test and they are the two the flow cannot see: the exact wire shape (the
 * one path with the repository's row id interpolated, the method, the bearer and the header pair it
 * falls back to), and that <b>every</b> way this can go wrong answers {@code Optional.empty()}
 * without throwing. The second is the port's whole contract — the fold has already landed when this
 * runs — and the third thing pinned beside it is the distinction the whole feature rests on: a 200
 * carrying an empty array is a PRESENT empty list, not the same answer as "could not ask".
 */
class HttpDownstreamComponentsTest {

  private record Received(String method, String path, String auth, String user, String roles) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>("{\"downstream\":[]}");

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
          exchange.getRequestBody().readAllBytes();
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("X-Qits-User"),
                  exchange.getRequestHeaders().getFirst("X-Qits-Roles")));
          byte[] responseBytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(
              status.get(), responseBytes.length == 0 ? -1 : responseBytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpDownstreamComponents against(String base, Optional<String> authorization) {
    HttpDownstreamComponents components = new HttpDownstreamComponents();
    components.maintenanceUrl = Optional.ofNullable(base);
    components.bearer = () -> authorization;
    return components;
  }

  private HttpDownstreamComponents against(String base) {
    return against(base, Optional.of("Bearer machine-token"));
  }

  @Test
  void theClosureIsReadFromTheOnePathAddressedByTheRepositoryRowId() throws Exception {
    String base = startServer();
    responseBody.set(
        """
        {"repository":"qits-ui-components-jslib","catalogId":"repo-1","downstream":[
          {"repository":"qits-ci-frontend","catalogId":"repo-2","archetype":"FRONTEND",
           "depth":1,"via":["qits-ui-components-jslib"]},
          {"repository":"qits-ci-service","catalogId":"repo-3","archetype":"SERVICE",
           "depth":2,"via":["qits-ci-frontend"]}]}
        """);

    Optional<List<String>> answer =
        against(base).downstreamOf("repo-1", "qits-ui-components-jslib");

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("GET", request.method());
    assertEquals("/maintenance/api/repositories/repo-1/downstream", request.path());
    assertEquals("Bearer machine-token", request.auth());
    assertEquals(
        Optional.of(List.of("qits-ci-frontend", "qits-ci-service")),
        answer,
        "the names alone, in the order the far side sent them — depth ascending is the answer");
  }

  /** No bearer is not a reason to stay silent here: the far side's door is a read. */
  @Test
  void withNoBearerItAsksWithTheForwardedHeaderPair() throws Exception {
    String base = startServer();
    responseBody.set("{\"downstream\":[{\"repository\":\"qits-ci-service\"}]}");

    Optional<List<String>> answer = against(base, Optional.empty()).downstreamOf("repo-1", "lib");

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertNull(request.auth(), "no credential means no Authorization header at all");
    assertEquals("qits-projects", request.user());
    assertEquals("qits:system", request.roles());
    assertEquals(Optional.of(List.of("qits-ci-service")), answer);
  }

  /**
   * The distinction the feature rests on. An empty array is an ANSWER — nothing is built on this
   * repository — and must not arrive at the caller as the same value a failure produces.
   */
  @Test
  void aWellFormedEmptyArrayIsAPresentEmptyListAndNotAnAbsentAnswer() throws Exception {
    String base = startServer();
    responseBody.set("{\"repository\":\"a-leaf\",\"catalogId\":\"repo-1\",\"downstream\":[]}");

    assertEquals(Optional.of(List.of()), against(base).downstreamOf("repo-1", "a-leaf"));
  }

  @Test
  void aRefusalIsCouldNotAskAndNeverThrown() throws Exception {
    String base = startServer();
    status.set(500);
    responseBody.set("{\"message\":\"the graph is not ingested\"}");

    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(() -> against(base).downstreamOf("repo-1", "lib")),
        "a refusal says nothing about what is downstream");
  }

  @Test
  void anAnswerThatWillNotParseIsCouldNotAskAndNeverThrown() throws Exception {
    String base = startServer();
    responseBody.set("not json at all");

    assertEquals(
        Optional.empty(), assertDoesNotThrow(() -> against(base).downstreamOf("repo-1", "lib")));
  }

  /** A 200 that carries no closure at all is a failure, not a leaf: nothing was actually answered. */
  @Test
  void anAnswerWithNoDownstreamMemberIsCouldNotAsk() throws Exception {
    String base = startServer();
    responseBody.set("{\"repository\":\"lib\",\"catalogId\":\"repo-1\"}");

    assertEquals(
        Optional.empty(), assertDoesNotThrow(() -> against(base).downstreamOf("repo-1", "lib")));
  }

  @Test
  void anUnreachableMaintenanceIsCouldNotAskAndNeverThrown() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(() -> against("http://127.0.0.1:1").downstreamOf("repo-1", "lib")));
  }

  @Test
  void anUnsetOrBlankAddressAsksNothingAndAnswersCouldNotAsk() throws Exception {
    String base = startServer();

    assertEquals(Optional.empty(), against(null).downstreamOf("repo-1", "lib"));
    assertEquals(Optional.empty(), against("   ").downstreamOf("repo-1", "lib"));

    assertTrue(received.isEmpty(), "a switched-off hop dials nothing: " + received);
    assertTrue(base.startsWith("http://"), "the server was up, so an attempt would have landed");
  }
}
