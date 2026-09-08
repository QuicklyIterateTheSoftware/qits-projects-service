package eu.wohlben.qits.projects.workspacehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
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
 * {@link HttpWorkspaceAgentDispatch} against a local server standing in for qits-workspaces — plain
 * JUnit over a directly-constructed bean, {@link HttpReleasedBranchWorkspacesTest}'s shape one class
 * over.
 *
 * <p>Three things are under test, and they are the three the flow above cannot see: the exact wire
 * shape (path, method, bearer and the five body members qits-workspaces' dispatch door declares),
 * the reading of the answer (the workspace's row id is nested under {@code workspace}), and the
 * failure contract — <b>502</b> for everything about the exchange and <b>503</b> for a hop that is
 * not configured, which is the opposite of the never-throwing port beside it and is the whole reason
 * this is a second class rather than a second method there.
 */
class HttpWorkspaceAgentDispatchTest {

  private record Received(String method, String path, String body, String auth) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>(
          "{\"workspace\":{\"id\":41,\"branch\":\"ticket/x\"},\"fresh\":true,"
              + "\"agentLaunch\":\"SCHEDULED\",\"technicalProcessId\":\"tp-1\"}");

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

  private HttpWorkspaceAgentDispatch against(
      String base, String fallback, Optional<String> authorization) {
    HttpWorkspaceAgentDispatch adapter = new HttpWorkspaceAgentDispatch();
    adapter.workspacesUrl = Optional.ofNullable(base);
    adapter.releaseWorkspacesUrl = Optional.ofNullable(fallback);
    adapter.bearer = () -> authorization;
    return adapter;
  }

  private HttpWorkspaceAgentDispatch against(String base) {
    return against(base, null, Optional.of("Bearer machine-token"));
  }

  @Test
  void aDispatchIsPostedWithTheWholeAskOnTheBody() throws Exception {
    String base = startServer();

    WorkspaceAgentDispatch.Dispatch made =
        against(base)
            .dispatchAgent("repo-1", "ticket/puce-button", true, "# Ticket: Puce", "Work on it.");

    assertEquals(41L, made.workspaceRowId());
    assertTrue(made.fresh());
    assertEquals("SCHEDULED", made.agentLaunch());

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("POST", request.method());
    assertEquals("/workspaces/api/agent-dispatches", request.path());
    assertEquals("Bearer machine-token", request.auth());
    Map<?, ?> body = MAPPER.readValue(request.body(), Map.class);
    assertEquals("repo-1", body.get("repositoryId"));
    assertEquals("ticket/puce-button", body.get("branch"));
    assertEquals(Boolean.TRUE, body.get("branchTree"));
    assertEquals("# Ticket: Puce", body.get("preamble"));
    assertEquals("Work on it.", body.get("instruction"));
  }

  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    against(null, base, Optional.of("Bearer machine-token"))
        .dispatchAgent("repo-1", "ticket/x", true, "p", "i");

    assertEquals(1, received.size(), "an unset own key falls back to the release path's address");
  }

  @Test
  void noAddressAnywhereIs503() {
    DomainException failure =
        assertThrows(
            DomainException.class,
            () ->
                against(null, "", Optional.of("Bearer machine-token"))
                    .dispatchAgent("repo-1", "ticket/x", true, "p", "i"));
    assertEquals(503, failure.statusCode());
  }

  @Test
  void noBearerIs503AndNothingIsSent() throws Exception {
    String base = startServer();

    DomainException failure =
        assertThrows(
            DomainException.class,
            () -> against(base, null, Optional.empty()).dispatchAgent("r", "b", true, "p", "i"));

    assertEquals(503, failure.statusCode());
    assertTrue(received.isEmpty(), "a call this service cannot authenticate is one it does not make");
  }

  @Test
  void aRefusalIs502() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set("{\"message\":\"branch is taken\"}");

    DomainException failure =
        assertThrows(
            DomainException.class,
            () -> against(base).dispatchAgent("r", "ticket/x", true, "p", "i"));

    assertEquals(502, failure.statusCode());
    assertTrue(failure.getMessage().contains("409"), failure.getMessage());
  }

  @Test
  void anAnswerWithNoWorkspaceIdIs502() throws Exception {
    String base = startServer();
    responseBody.set("{\"fresh\":true,\"agentLaunch\":\"SCHEDULED\"}");

    DomainException failure =
        assertThrows(
            DomainException.class,
            () -> against(base).dispatchAgent("r", "ticket/x", true, "p", "i"));

    assertEquals(502, failure.statusCode());
    assertTrue(failure.getMessage().contains("no workspace id"), failure.getMessage());
  }

  @Test
  void nothingListeningIs502() {
    DomainException failure =
        assertThrows(
            DomainException.class,
            () ->
                against("http://127.0.0.1:1", null, Optional.of("Bearer t"))
                    .dispatchAgent("r", "ticket/x", true, "p", "i"));

    assertEquals(502, failure.statusCode());
  }
}
