package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  /**
   * A path whose method this far side's router rejects: it answers 405 there, whatever
   * {@link #status} says. Plays an older qits-workspaces whose {@code /agents/*} router answers 405
   * rather than 404 for a sub-path it does not know (qits-617).
   */
  private final List<String> methodNotAllowed = new CopyOnWriteArrayList<>();
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"agentId\":\"a-41\",\"applied\":true}");

  /** Every port a test built, shut down after it so the fork keeps no idle client threads. */
  private final List<HttpWorkspaceAgentEntities> ports = new CopyOnWriteArrayList<>();

  @AfterEach
  void closeClients() {
    ports.forEach(HttpWorkspaceAgentEntities::close);
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
          byte[] requestBytes = exchange.getRequestBody().readAllBytes();
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  new String(requestBytes, StandardCharsets.UTF_8),
                  exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] responseBytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          String path = exchange.getRequestURI().getPath();
          int answer =
              unserved.contains(path)
                  ? 404
                  : methodNotAllowed.contains(path) ? 405 : status.get();
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
    HttpWorkspaceAgentEntities blocks = new HttpWorkspaceAgentEntities(QitsHttpClients.of(OpenTelemetry.noop(), null));
    blocks.workspacesUrl = Optional.ofNullable(base);
    blocks.releaseWorkspacesUrl = Optional.ofNullable(fallback);
    blocks.bearer = () -> authorization;
    ports.add(blocks);
    return blocks;
  }

  private HttpWorkspaceAgentEntities against(String base) {
    return against(base, null, Optional.of("Bearer machine-token"));
  }

  @Test
  void titleStatusAndFlagArePostedToTheEntityDoorWithTheWorkIdOnTheBody() throws Exception {
    String base = startServer();

    against(base).changed("t-1", "Puce button", "REFINED", true, "EXPLICIT");
    against(base).changed("e-1", "Onboarding", "REPORTED", false, null);

    assertEquals(2, received.size(), "one request each, and no fallback on a 200");
    Received request = received.get(0);
    assertEquals("POST", request.method());
    // Beside `delivery`, under agent-dispatches, where the far side's qits:system door is.
    assertEquals("/workspaces/api/agent-dispatches/entity", request.path());
    assertEquals("Bearer machine-token", request.auth());
    assertEquals(
        Map.of(
            "workId", "t-1",
            "title", "Puce button",
            "status", "REFINED",
            "blocked", true,
            "blockSource", "EXPLICIT"),
        MAPPER.readValue(request.body(), Map.class));
    assertEquals(
        Map.of("workId", "e-1", "title", "Onboarding", "status", "REPORTED", "blocked", false),
        MAPPER.readValue(received.get(1).body(), Map.class));
  }

  /**
   * A qits-workspaces older than {@code /entity} answers it 404 and still serves {@code /blocked}:
   * the same body goes there (qits-1152: both doors take one shape).
   */
  @Test
  void aFarSideWithoutTheEntityDoorIsToldOnTheBlockedDoor() throws Exception {
    String base = startServer();
    unserved.add("/workspaces/api/agent-dispatches/entity");

    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", true, null));

    assertEquals(2, received.size(), "the entity door, then the blocked door: " + received);
    assertEquals("/workspaces/api/agent-dispatches/entity", received.get(0).path());
    Received fallback = received.get(1);
    assertEquals("/workspaces/api/agent-dispatches/blocked", fallback.path());
    assertEquals("Bearer machine-token", fallback.auth());
    assertEquals(
        Map.of("workId", "w-1", "title", "X", "status", "REFINED", "blocked", true),
        MAPPER.readValue(fallback.body(), Map.class));
  }

  /**
   * The 405 sibling: a far side whose {@code /agents/*} router rejects the method for
   * {@code /entity} answers 405 rather than 404, and still gets the body on {@code /blocked}
   * (qits-617; measured live against a workspace-daemon on 2026.1001.72420).
   */
  @Test
  void aFarSideThatAnswers405OnEntityIsToldOnTheBlockedDoor() throws Exception {
    String base = startServer();
    methodNotAllowed.add("/workspaces/api/agent-dispatches/entity");

    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", true, null));

    assertEquals(2, received.size(), "the entity door, then the blocked door: " + received);
    assertEquals("/workspaces/api/agent-dispatches/entity", received.get(0).path());
    Received fallback = received.get(1);
    assertEquals("/workspaces/api/agent-dispatches/blocked", fallback.path());
    assertEquals(
        Map.of("workId", "w-1", "title", "X", "status", "REFINED", "blocked", true),
        MAPPER.readValue(fallback.body(), Map.class));
  }

  /** Only a 404 is "an older far side"; any other refusal is not retried on the old door. */
  @Test
  void aRefusalOtherThan404IsNotRetriedOnTheBlockedDoor() throws Exception {
    String base = startServer();
    status.set(500);

    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", true, null));

    assertEquals(1, received.size(), "no fallback: " + received);
  }

  /** The ordinary answer for an entity nobody dispatched an agent onto. */
  @Test
  void noAgentIsAnOrdinaryAnswer() throws Exception {
    String base = startServer();
    responseBody.set("{\"agentId\":null,\"applied\":false}");

    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", true, null));
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

    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", true, null));
    assertEquals(2, received.size(), "the entity door, then the blocked door, and no third ask");
  }

  /** The 405 sibling of the above: both doors 405, same no-throw, no-third-ask shape. */
  @Test
  void aWorkspacesThatAnswers405OnBothDoorsIsNeverAThrow() throws Exception {
    String base = startServer();
    status.set(405);
    responseBody.set("{\"message\":\"method not allowed\"}");

    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", true, null));
    assertEquals(2, received.size(), "the entity door, then the blocked door, and no third ask");
  }

  @Test
  void aRefusingOrUnreadableFarSideIsNeverAThrow() throws Exception {
    String base = startServer();
    status.set(500);
    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", true, null));
    status.set(200);
    responseBody.set("not json at all");
    assertDoesNotThrow(() -> against(base).changed("w-1", "X", "REFINED", false, null));
    assertEquals(2, received.size());
  }

  @Test
  void anUnreachableWorkspacesIsNeverAThrow() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    assertDoesNotThrow(
        () ->
            against("http://127.0.0.1:1", null, Optional.of("Bearer t"))
                .changed("w-1", "X", "REFINED", true, null));
  }

  /** An unconfigured hop, or one with no machine credential, makes no request at all. */
  @Test
  void noAddressOrNoBearerSendsNothing() throws Exception {
    String base = startServer();

    against(null, null, Optional.of("Bearer t")).changed("w-1", "X", "REFINED", true, null);
    against("  ", "", Optional.of("Bearer t")).changed("w-1", "X", "REFINED", true, null);
    against(base, null, Optional.empty()).changed("w-1", "X", "REFINED", true, null);

    assertTrue(received.isEmpty(), "a switched-off or uncredentialed hop dials nothing: " + received);
  }

  /** The dispatch door's two keys, in its order — no third address and no rename. */
  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    against(null, base, Optional.of("Bearer machine-token")).changed("w-1", "X", "REFINED", true, null);

    assertEquals(1, received.size(), "an unset own key falls back to the release path's address");
  }

  /** A null block source — not blocked, or a caller that does not know it — is OMITTED. */
  @Test
  void aNullBlockSourceIsOmittedFromTheBody() throws Exception {
    String base = startServer();

    against(base).changed("w-1", "Puce button", "REFINED", false, null);

    assertTrue(
        !MAPPER.readValue(received.get(0).body(), Map.class).containsKey("blockSource"),
        "no blockSource key on " + received.get(0).body());
  }

  /**
   * An explicit block names who set it (qits-1511) — under EXPLICIT and under BOTH — so qits-workspaces
   * can tell a block the agent set on its own work from one somebody else set.
   */
  @Test
  void anExplicitBlockRelaysWhoSetIt() throws Exception {
    String base = startServer();

    against(base).changed("t-1", "Puce button", "REFINED", true, "EXPLICIT", "tok-agent-7");
    against(base).changed("t-2", "Puce button", "REFINED", true, "BOTH", "dana");

    assertEquals(
        Map.of(
            "workId", "t-1",
            "title", "Puce button",
            "status", "REFINED",
            "blocked", true,
            "blockSource", "EXPLICIT",
            "blockedBy", "tok-agent-7"),
        MAPPER.readValue(received.get(0).body(), Map.class));
    assertEquals("dana", MAPPER.readValue(received.get(1).body(), Map.class).get("blockedBy"));
  }

  /**
   * A derived block has no author: nothing is sent beside AGENT_WAITING even when a caller hands one
   * over, nor while unblocked, nor when the author is unknown.
   */
  @Test
  void anAgentWaitingBlockRelaysNoAuthor() throws Exception {
    String base = startServer();

    against(base).changed("w-1", "X", "REFINED", true, "AGENT_WAITING", "dana");
    against(base).changed("w-2", "X", "REFINED", false, null, "dana");
    against(base).changed("w-3", "X", "REFINED", true, "EXPLICIT", null);

    assertEquals(3, received.size());
    for (Received request : received) {
      assertTrue(
          !MAPPER.readValue(request.body(), Map.class).containsKey("blockedBy"),
          "no blockedBy key on " + request.body());
    }
  }

  /** The {@code /blocked} fallback gets the same body, the author included. */
  @Test
  void theBlockedFallbackCarriesTheAuthorToo() throws Exception {
    String base = startServer();
    unserved.add("/workspaces/api/agent-dispatches/entity");

    against(base).changed("w-1", "X", "REFINED", true, "EXPLICIT", "dana");

    assertEquals(2, received.size(), "the entity door, then the blocked door: " + received);
    assertEquals("/workspaces/api/agent-dispatches/blocked", received.get(1).path());
    assertEquals(received.get(0).body(), received.get(1).body());
    assertEquals("dana", MAPPER.readValue(received.get(1).body(), Map.class).get("blockedBy"));
  }
}
