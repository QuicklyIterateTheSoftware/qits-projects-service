package eu.wohlben.qits.projects.workspacehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpWorkspaceAgentBlocks} against a local server standing in for qits-workspaces —
 * {@link HttpWorkspaceAgentTurnsTest}'s shape, one door over (qits-614).
 *
 * <p>The two things the flow cannot see: the exact wire shape (the path beside {@code delivery}
 * under {@code agent-dispatches}, the method, the bearer and the three body members), and that every
 * way this can go wrong — a 404 from a qits-workspaces older than the door first among them —
 * returns normally. The port answers nothing, so "returns normally" is the whole assertion.
 */
class HttpWorkspaceAgentBlocksTest {

  private record Received(String method, String path, String body, String auth) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"workspaceId\":41,\"applied\":true}");

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
          byte[] requestBytes = exchange.getRequestBody().readAllBytes();
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  new String(requestBytes, StandardCharsets.UTF_8),
                  exchange.getRequestHeaders().getFirst("Authorization")));
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

  private HttpWorkspaceAgentBlocks against(
      String base, String fallback, Optional<String> authorization) {
    HttpWorkspaceAgentBlocks blocks = new HttpWorkspaceAgentBlocks();
    blocks.workspacesUrl = Optional.ofNullable(base);
    blocks.releaseWorkspacesUrl = Optional.ofNullable(fallback);
    blocks.bearer = () -> authorization;
    return blocks;
  }

  private HttpWorkspaceAgentBlocks against(String base) {
    return against(base, null, Optional.of("Bearer machine-token"));
  }

  @Test
  void theFlagIsPostedToTheBlockedDoorWithTheBranchOnTheBody() throws Exception {
    String base = startServer();

    against(base).blocked("repo-1", "ticket/puce-button", true);
    against(base).blocked("repo-1", "epic/onboarding", false);

    assertEquals(2, received.size());
    Received request = received.get(0);
    assertEquals("POST", request.method());
    // Beside `delivery`, under agent-dispatches, where the far side's qits:system door is.
    assertEquals("/workspaces/api/agent-dispatches/blocked", request.path());
    assertEquals("Bearer machine-token", request.auth());
    assertEquals(
        Map.of("repositoryId", "repo-1", "branch", "ticket/puce-button", "blocked", true),
        MAPPER.readValue(request.body(), Map.class));
    assertEquals(
        Map.of("repositoryId", "repo-1", "branch", "epic/onboarding", "blocked", false),
        MAPPER.readValue(received.get(1).body(), Map.class));
  }

  /** The ordinary answer for an entity nobody dispatched an agent onto. */
  @Test
  void noWorkspaceOnTheBranchIsAnOrdinaryAnswer() throws Exception {
    String base = startServer();
    responseBody.set("{\"workspaceId\":null,\"applied\":false}");

    assertDoesNotThrow(() -> against(base).blocked("repo-1", "ticket/x", true));
    assertEquals(1, received.size(), "and it was asked exactly once");
  }

  /**
   * A qits-workspaces older than the door answers 404 — the expected state while the two releases
   * are apart. A WARN, never a throw: the block is the same block either way.
   */
  @Test
  void anOlderWorkspacesWithoutTheDoorIsNeverAThrow() throws Exception {
    String base = startServer();
    status.set(404);
    responseBody.set("{\"message\":\"not found\"}");

    assertDoesNotThrow(() -> against(base).blocked("repo-1", "ticket/x", true));
    assertEquals(1, received.size());
  }

  @Test
  void aRefusingOrUnreadableFarSideIsNeverAThrow() throws Exception {
    String base = startServer();
    status.set(500);
    assertDoesNotThrow(() -> against(base).blocked("repo-1", "ticket/x", true));
    status.set(200);
    responseBody.set("not json at all");
    assertDoesNotThrow(() -> against(base).blocked("repo-1", "ticket/x", false));
    assertEquals(2, received.size());
  }

  @Test
  void anUnreachableWorkspacesIsNeverAThrow() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    assertDoesNotThrow(
        () ->
            against("http://127.0.0.1:1", null, Optional.of("Bearer t"))
                .blocked("repo-1", "ticket/x", true));
  }

  /** An unconfigured hop, or one with no machine credential, makes no request at all. */
  @Test
  void noAddressOrNoBearerSendsNothing() throws Exception {
    String base = startServer();

    against(null, null, Optional.of("Bearer t")).blocked("repo-1", "ticket/x", true);
    against("  ", "", Optional.of("Bearer t")).blocked("repo-1", "ticket/x", true);
    against(base, null, Optional.empty()).blocked("repo-1", "ticket/x", true);

    assertTrue(received.isEmpty(), "a switched-off or uncredentialed hop dials nothing: " + received);
  }

  /** The dispatch door's two keys, in its order — no third address and no rename. */
  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    against(null, base, Optional.of("Bearer machine-token")).blocked("repo-1", "ticket/x", true);

    assertEquals(1, received.size(), "an unset own key falls back to the release path's address");
  }
}
