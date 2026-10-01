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
 * {@link HttpWorkspaceAgentEntities} against a local server standing in for qits-workspaces —
 * {@link HttpWorkspaceAgentTurnsTest}'s shape, one door over (qits-614, qits-617).
 *
 * <p>The three things the flow cannot see: the exact wire shape (the path beside {@code delivery}
 * under {@code agent-dispatches}, the method, the bearer and the five body members), the fall back
 * to the old {@code /blocked} door when a qits-workspaces older than {@code /entity} answers 404,
 * and that every way this can go wrong — a far side older than both doors among them — returns
 * normally. The port answers nothing, so "returns normally" is the whole assertion.
 */
class HttpWorkspaceAgentEntitiesTest {

  private record Received(String method, String path, String body, String auth) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  /** A path this far side does not serve: it answers 404 there, whatever {@link #status} says. */
  private final List<String> unserved = new CopyOnWriteArrayList<>();
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
          int answer = unserved.contains(exchange.getRequestURI().getPath()) ? 404 : status.get();
          exchange.sendResponseHeaders(
              answer, responseBytes.length == 0 ? -1 : responseBytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpWorkspaceAgentEntities against(
      String base, String fallback, Optional<String> authorization) {
    HttpWorkspaceAgentEntities blocks = new HttpWorkspaceAgentEntities();
    blocks.workspacesUrl = Optional.ofNullable(base);
    blocks.releaseWorkspacesUrl = Optional.ofNullable(fallback);
    blocks.bearer = () -> authorization;
    return blocks;
  }

  private HttpWorkspaceAgentEntities against(String base) {
    return against(base, null, Optional.of("Bearer machine-token"));
  }

  @Test
  void titleStatusAndFlagArePostedToTheEntityDoorWithTheBranchOnTheBody() throws Exception {
    String base = startServer();

    against(base).changed("repo-1", "ticket/puce-button", "Puce button", "REFINED", true);
    against(base).changed("repo-1", "epic/onboarding", "Onboarding", "REPORTED", false);

    assertEquals(2, received.size(), "one request each, and no fallback on a 200");
    Received request = received.get(0);
    assertEquals("POST", request.method());
    // Beside `delivery`, under agent-dispatches, where the far side's qits:system door is.
    assertEquals("/workspaces/api/agent-dispatches/entity", request.path());
    assertEquals("Bearer machine-token", request.auth());
    assertEquals(
        Map.of(
            "repositoryId", "repo-1",
            "branch", "ticket/puce-button",
            "title", "Puce button",
            "status", "REFINED",
            "blocked", true),
        MAPPER.readValue(request.body(), Map.class));
    assertEquals(
        Map.of(
            "repositoryId", "repo-1",
            "branch", "epic/onboarding",
            "title", "Onboarding",
            "status", "REPORTED",
            "blocked", false),
        MAPPER.readValue(received.get(1).body(), Map.class));
  }

  /**
   * A qits-workspaces older than {@code /entity} answers it 404 and still serves {@code /blocked}:
   * the flag goes there, alone, in the qits-614 shape.
   */
  @Test
  void aFarSideWithoutTheEntityDoorIsToldTheFlagOnTheBlockedDoor() throws Exception {
    String base = startServer();
    unserved.add("/workspaces/api/agent-dispatches/entity");

    assertDoesNotThrow(() -> against(base).changed("repo-1", "ticket/x", "X", "REFINED", true));

    assertEquals(2, received.size(), "the entity door, then the blocked door: " + received);
    assertEquals("/workspaces/api/agent-dispatches/entity", received.get(0).path());
    Received fallback = received.get(1);
    assertEquals("/workspaces/api/agent-dispatches/blocked", fallback.path());
    assertEquals("Bearer machine-token", fallback.auth());
    assertEquals(
        Map.of("repositoryId", "repo-1", "branch", "ticket/x", "blocked", true),
        MAPPER.readValue(fallback.body(), Map.class));
  }

  /** Only a 404 is "an older far side"; any other refusal is not retried on the old door. */
  @Test
  void aRefusalOtherThan404IsNotRetriedOnTheBlockedDoor() throws Exception {
    String base = startServer();
    status.set(500);

    assertDoesNotThrow(() -> against(base).changed("repo-1", "ticket/x", "X", "REFINED", true));

    assertEquals(1, received.size(), "no fallback: " + received);
  }

  /** The ordinary answer for an entity nobody dispatched an agent onto. */
  @Test
  void noWorkspaceOnTheBranchIsAnOrdinaryAnswer() throws Exception {
    String base = startServer();
    responseBody.set("{\"workspaceId\":null,\"applied\":false}");

    assertDoesNotThrow(() -> against(base).changed("repo-1", "ticket/x", "X", "REFINED", true));
    assertEquals(1, received.size(), "and it was asked exactly once");
  }

  /**
   * A qits-workspaces older than both doors answers 404 twice. A WARN, never a throw: the entity is
   * the same entity either way.
   */
  @Test
  void aWorkspacesOlderThanBothDoorsIsNeverAThrow() throws Exception {
    String base = startServer();
    status.set(404);
    responseBody.set("{\"message\":\"not found\"}");

    assertDoesNotThrow(() -> against(base).changed("repo-1", "ticket/x", "X", "REFINED", true));
    assertEquals(2, received.size(), "the entity door, then the blocked door, and no third ask");
  }

  @Test
  void aRefusingOrUnreadableFarSideIsNeverAThrow() throws Exception {
    String base = startServer();
    status.set(500);
    assertDoesNotThrow(() -> against(base).changed("repo-1", "ticket/x", "X", "REFINED", true));
    status.set(200);
    responseBody.set("not json at all");
    assertDoesNotThrow(() -> against(base).changed("repo-1", "ticket/x", "X", "REFINED", false));
    assertEquals(2, received.size());
  }

  @Test
  void anUnreachableWorkspacesIsNeverAThrow() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    assertDoesNotThrow(
        () ->
            against("http://127.0.0.1:1", null, Optional.of("Bearer t"))
                .changed("repo-1", "ticket/x", "X", "REFINED", true));
  }

  /** An unconfigured hop, or one with no machine credential, makes no request at all. */
  @Test
  void noAddressOrNoBearerSendsNothing() throws Exception {
    String base = startServer();

    against(null, null, Optional.of("Bearer t")).changed("repo-1", "ticket/x", "X", "REFINED", true);
    against("  ", "", Optional.of("Bearer t")).changed("repo-1", "ticket/x", "X", "REFINED", true);
    against(base, null, Optional.empty()).changed("repo-1", "ticket/x", "X", "REFINED", true);

    assertTrue(received.isEmpty(), "a switched-off or uncredentialed hop dials nothing: " + received);
  }

  /** The dispatch door's two keys, in its order — no third address and no rename. */
  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    against(null, base, Optional.of("Bearer machine-token")).changed("repo-1", "ticket/x", "X", "REFINED", true);

    assertEquals(1, received.size(), "an unset own key falls back to the release path's address");
  }
}
