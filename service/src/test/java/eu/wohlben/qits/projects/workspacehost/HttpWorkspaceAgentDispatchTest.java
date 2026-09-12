package eu.wohlben.qits.projects.workspacehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  private record Received(
      String method, String path, String query, String body, String auth) {}

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
                  exchange.getRequestURI().getRawQuery(),
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
            .dispatchAgent(
                "repo-1",
                "ticket/puce-button",
                List.of("refs/heads/ticket/puce-button"),
                true,
                WorkspaceAgentDispatch.Subject.ticket("t-7"),
                "Work on it.");

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
    assertEquals(
        List.of("refs/heads/ticket/puce-button"),
        body.get("gitRefs"),
        "the refs the agent may push travel as a JSON array (plan contract C4)");
    assertEquals(Boolean.TRUE, body.get("branchTree"));
    assertEquals("t-7", body.get("ticketId"));
    assertEquals("Work on it.", body.get("instruction"));
    // Only what the dispatch is about travels. No goal, because this door has no prose of its own —
    // and no `epicId: null`, because an explicit null is this hop stating a subject it has not got.
    assertFalse(body.containsKey("preamble"), "a dispatch states a goal nobody authored");
    assertFalse(body.containsKey("epicId"), "the member that is unset is left off the body");
  }

  /** No refs given: the member is left off, and qits-workspaces allows the workspace's own branch. */
  @Test
  void noRefsLeaveTheMemberOffTheBody() throws Exception {
    String base = startServer();

    against(base)
        .dispatchAgent("repo-1", "ticket/x", null, true, WorkspaceAgentDispatch.Subject.ticket("t"), "i");

    Map<?, ?> body = MAPPER.readValue(received.get(0).body(), Map.class);
    assertFalse(body.containsKey("gitRefs"), "an explicit null would state a scope nobody computed");
  }

  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    against(null, base, Optional.of("Bearer machine-token"))
        .dispatchAgent("repo-1", "ticket/x", null, true, WorkspaceAgentDispatch.Subject.ticket("t"), "i");

    assertEquals(1, received.size(), "an unset own key falls back to the release path's address");
  }

  @Test
  void noAddressAnywhereIs503() {
    DomainException failure =
        assertThrows(
            DomainException.class,
            () ->
                against(null, "", Optional.of("Bearer machine-token"))
                    .dispatchAgent("repo-1", "ticket/x", null, true, WorkspaceAgentDispatch.Subject.ticket("t"), "i"));
    assertEquals(503, failure.statusCode());
  }

  @Test
  void noBearerIs503AndNothingIsSent() throws Exception {
    String base = startServer();

    DomainException failure =
        assertThrows(
            DomainException.class,
            () -> against(base, null, Optional.empty()).dispatchAgent("r", "b", null, true, WorkspaceAgentDispatch.Subject.ticket("t"), "i"));

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
            () -> against(base).dispatchAgent("r", "ticket/x", null, true, WorkspaceAgentDispatch.Subject.ticket("t"), "i"));

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
            () -> against(base).dispatchAgent("r", "ticket/x", null, true, WorkspaceAgentDispatch.Subject.ticket("t"), "i"));

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
                    .dispatchAgent("r", "ticket/x", null, true, WorkspaceAgentDispatch.Subject.ticket("t"), "i"));

    assertEquals(502, failure.statusCode());
  }

  // --- the read back ---------------------------------------------------------------------------

  @Test
  void aLookupAsksOnceWithEveryIdAndReadsTheEntriesBack() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"entries\":[{\"workspace\":{\"workspaceRowId\":41,\"repositoryId\":\"repo-1\","
            + "\"workspaceId\":\"ticket-puce\",\"branch\":\"ticket/puce\",\"ticketId\":\"t-7\","
            + "\"epicId\":null}}]}");

    List<WorkspaceAgentDispatch.Reference> found =
        against(base).workspacesReferencing(List.of("t-7", "t-8", "t-7"), List.of("e-1"));

    assertEquals(1, found.size());
    WorkspaceAgentDispatch.Reference reference = found.get(0);
    assertEquals(41L, reference.workspaceRowId());
    assertEquals("repo-1", reference.repositoryId());
    assertEquals("ticket-puce", reference.workspaceId());
    assertEquals("ticket/puce", reference.branch());
    assertEquals("t-7", reference.ticketId());

    assertEquals(1, received.size(), "a page of rows is one call, never one call per row");
    Received request = received.get(0);
    assertEquals("GET", request.method());
    // Under the dispatch door, whose class states qits:system — the person's door this used to
    // sit on answered 403 to every call, which this class's own contract reports as "none".
    assertEquals("/workspaces/api/agent-dispatches/references", request.path());
    assertEquals("Bearer machine-token", request.auth());
    // Every id asked about, each once — the repeat is dropped rather than asked twice.
    assertEquals("ticketId=t-7&ticketId=t-8&epicId=e-1", request.query());
  }

  @Test
  void askingAboutNoRowsMakesNoCallAtAll() throws Exception {
    String base = startServer();

    assertTrue(against(base).workspacesReferencing(List.of(), List.of()).isEmpty());
    assertTrue(against(base).workspacesReferencing(List.of("  "), List.of()).isEmpty());

    assertTrue(received.isEmpty(), "a question about no rows was still asked over the network");
  }

  /**
   * The port's contract, and the whole reason it differs from the dispatch above: this read
   * decorates a listing, so every way it can fail has to end in an empty answer rather than in an
   * exception that takes the page down with it.
   */
  @Test
  void everyFailureIsAnEmptyAnswerAndNeverAThrow() throws Exception {
    String base = startServer();

    status.set(503);
    assertTrue(against(base).workspacesReferencing(List.of("t-7"), List.of()).isEmpty());

    status.set(200);
    responseBody.set("not json at all");
    assertTrue(against(base).workspacesReferencing(List.of("t-7"), List.of()).isEmpty());

    // Nothing listening, no address, and no credential — the three the dispatch answers 502/503 to.
    assertTrue(
        against("http://127.0.0.1:1", null, Optional.of("Bearer t"))
            .workspacesReferencing(List.of("t-7"), List.of())
            .isEmpty());
    assertTrue(
        against(null, "", Optional.of("Bearer t"))
            .workspacesReferencing(List.of("t-7"), List.of())
            .isEmpty());
    assertTrue(
        against(base, null, Optional.empty())
            .workspacesReferencing(List.of("t-7"), List.of())
            .isEmpty());
  }

  /** A row a link cannot be composed from is skipped, rather than carried with a hole in it. */
  @Test
  void anEntryMissingThePairALinkNeedsIsSkipped() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"entries\":[{\"workspace\":{\"repositoryId\":\"repo-1\",\"ticketId\":\"t-7\"}},"
            + "{\"workspace\":{\"workspaceRowId\":9,\"ticketId\":\"t-7\"}},"
            + "{\"workspace\":{\"workspaceRowId\":12,\"repositoryId\":\"repo-1\","
            + "\"ticketId\":\"t-7\"}}]}");

    List<WorkspaceAgentDispatch.Reference> found =
        against(base).workspacesReferencing(List.of("t-7"), List.of());

    assertEquals(1, found.size());
    assertEquals(12L, found.get(0).workspaceRowId());
  }
}
