package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
          "{\"agent\":{\"agentId\":\"agent-1\",\"workspaceRowId\":41,"
              + "\"repositoryId\":\"repo-1\"},\"fresh\":true,\"agentLaunch\":\"SCHEDULED\"}");

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
    HttpWorkspaceAgentDispatch adapter = new HttpWorkspaceAgentDispatch(QitsHttpClients.of(OpenTelemetry.noop(), null));
    adapter.workspacesUrl = Optional.ofNullable(base);
    adapter.releaseWorkspacesUrl = Optional.ofNullable(fallback);
    adapter.bearer = () -> authorization;
    return adapter;
  }

  private HttpWorkspaceAgentDispatch against(String base) {
    return against(base, null, Optional.of("Bearer machine-token"));
  }

  private static final WorkspaceAgentDispatch.Subject TICKET =
      WorkspaceAgentDispatch.Subject.ticket("t");

  @Test
  void aDispatchIsPostedWithTheWholeAskOnTheBody() throws Exception {
    String base = startServer();

    WorkspaceAgentDispatch.Dispatch made =
        against(base)
            .dispatchAgent(
                "repo-1",
                "ticket/qits-7",
                WorkspaceAgentDispatch.Subject.ticket("t-7"),
                "Work on it.");

    assertEquals("agent-1", made.agentId());
    assertEquals(41L, made.workspaceRowId());
    assertEquals("repo-1", made.repositoryId());
    assertTrue(made.fresh());
    assertEquals("SCHEDULED", made.agentLaunch());

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("POST", request.method());
    assertEquals("/workspaces/api/agent-dispatches", request.path());
    assertEquals("Bearer machine-token", request.auth());
    Map<?, ?> body = MAPPER.readValue(request.body(), Map.class);
    assertEquals("repo-1", body.get("repositoryId"));
    assertEquals("ticket/qits-7", body.get("wrapperBranch"));
    assertEquals("t-7", body.get("workId"));
    assertEquals("t-7", body.get("ticketId"));
    assertEquals("Work on it.", body.get("instruction"));
    // qits-1152: the far side derives the agent's refs and cuts no branch tree.
    assertFalse(body.containsKey("branch"), "the old member is gone");
    assertFalse(body.containsKey("branchTree"), "the old member is gone");
    assertFalse(body.containsKey("gitRefs"), "the far side derives the refs");
    assertFalse(body.containsKey("preamble"), "a dispatch states a goal nobody authored");
    assertFalse(body.containsKey("epicId"), "the member that is unset is left off the body");
    assertFalse(
        body.containsKey("entityId"),
        "a subject with no qualified id sends none, rather than an explicit null");
    assertFalse(body.containsKey("entityTitle"), "an unknown title is left off, never null");
    assertFalse(body.containsKey("entityStatus"), "an unknown status is left off, never null");
    assertEquals(Boolean.FALSE, body.get("entityBlocked"), "the flag always travels");
  }

  /**
   * The agent identity (qits-887) is read off the answer when the far side names one, and an answer
   * that names none is a dispatch with no identity, never a failed one. The assignee then falls back
   * to the agent. A QUEUED agent has no workspace yet.
   */
  @Test
  void theAgentIdentityIsReadAndItsAbsenceTolerated() throws Exception {
    String base = startServer();
    HttpWorkspaceAgentDispatch adapter = against(base);

    responseBody.set(
        "{\"agent\":{\"agentId\":\"a-41\",\"workspaceRowId\":41},\"fresh\":true,"
            + "\"agentLaunch\":\"SCHEDULED\",\"agentIdentity\":\"ws-client-41\"}");
    WorkspaceAgentDispatch.Dispatch named = adapter.dispatchAgent("repo-1", "ticket/x", TICKET, "i");
    assertEquals("ws-client-41", named.agentIdentity());
    assertEquals("ws-client-41", named.assignee());

    responseBody.set(
        "{\"agent\":{\"agentId\":\"a-42\",\"workspaceRowId\":null},\"fresh\":true,"
            + "\"agentLaunch\":\"QUEUED\",\"agentIdentity\":null}");
    WorkspaceAgentDispatch.Dispatch queued = adapter.dispatchAgent("repo-1", "ticket/x", TICKET, "i");
    assertNull(queued.agentIdentity());
    assertNull(queued.workspaceRowId(), "a queued agent stands in no workspace yet");
    assertEquals("QUEUED", queued.agentLaunch());
    assertEquals("agent a-42", queued.assignee());
  }

  /**
   * The rest of the session name travels beside the qualified id (qits-617): {@code entityTitle},
   * {@code entityStatus} as the status enum's name, and {@code entityBlocked}.
   */
  @Test
  void theTitleStatusAndFlagTravelAsEntityFields() throws Exception {
    String base = startServer();

    against(base)
        .dispatchAgent(
            "repo-1",
            "ticket/qits-617",
            WorkspaceAgentDispatch.Subject.ticket("t-7")
                .withQualifiedId("qits-617")
                .withEntityState("Session names carry the status", "REFINED", true),
            "i");

    Map<?, ?> body = MAPPER.readValue(received.get(0).body(), Map.class);
    assertEquals("qits-617", body.get("entityId"));
    assertEquals("Session names carry the status", body.get("entityTitle"));
    assertEquals("REFINED", body.get("entityStatus"));
    assertEquals(Boolean.TRUE, body.get("entityBlocked"));
  }

  /** One work id whatever the archetype: the ticket's or the epic's own row id. */
  @Test
  void theWorkIdIsTheRowIdOfEitherArchetype() throws Exception {
    String base = startServer();

    against(base)
        .dispatchAgent(
            "repo-1",
            "ticket/qits-614",
            WorkspaceAgentDispatch.Subject.ticket("t-7").withQualifiedId("qits-614"),
            "i");
    against(base)
        .dispatchAgent(
            "repo-1",
            "epic/qits-615",
            WorkspaceAgentDispatch.Subject.epic("e-9").withQualifiedId("qits-615"),
            "i");

    Map<?, ?> ticket = MAPPER.readValue(received.get(0).body(), Map.class);
    assertEquals("t-7", ticket.get("workId"));
    assertEquals("qits-614", ticket.get("entityId"));
    Map<?, ?> epic = MAPPER.readValue(received.get(1).body(), Map.class);
    assertEquals("e-9", epic.get("workId"));
    assertEquals("e-9", epic.get("epicId"));
    assertEquals("qits-615", epic.get("entityId"));
    assertFalse(epic.containsKey("ticketId"));
  }

  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    against(null, base, Optional.of("Bearer machine-token"))
        .dispatchAgent("repo-1", "ticket/x", TICKET, "i");

    assertEquals(1, received.size(), "an unset own key falls back to the release path's address");
  }

  @Test
  void noAddressAnywhereIs503() {
    DomainException failure =
        assertThrows(
            DomainException.class,
            () ->
                against(null, "", Optional.of("Bearer machine-token"))
                    .dispatchAgent("repo-1", "ticket/x", TICKET, "i"));
    assertEquals(503, failure.statusCode());
  }

  @Test
  void noBearerIs503AndNothingIsSent() throws Exception {
    String base = startServer();

    DomainException failure =
        assertThrows(
            DomainException.class,
            () -> against(base, null, Optional.empty()).dispatchAgent("r", "b", TICKET, "i"));

    assertEquals(503, failure.statusCode());
    assertTrue(received.isEmpty(), "a call this service cannot authenticate is one it does not make");
  }

  @Test
  void aRefusalIs502() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set("{\"message\":\"refused\"}");

    DomainException failure =
        assertThrows(
            DomainException.class,
            () -> against(base).dispatchAgent("r", "ticket/x", TICKET, "i"));

    assertEquals(502, failure.statusCode());
    assertTrue(failure.getMessage().contains("409"), failure.getMessage());
  }

  @Test
  void anAnswerWithNoAgentIdIs502() throws Exception {
    String base = startServer();
    responseBody.set("{\"agent\":{\"workspaceRowId\":41},\"fresh\":true}");

    DomainException failure =
        assertThrows(
            DomainException.class,
            () -> against(base).dispatchAgent("r", "ticket/x", TICKET, "i"));

    assertEquals(502, failure.statusCode());
    assertTrue(failure.getMessage().contains("no agent id"), failure.getMessage());
  }

  @Test
  void nothingListeningIs502() {
    DomainException failure =
        assertThrows(
            DomainException.class,
            () ->
                against("http://127.0.0.1:1", null, Optional.of("Bearer t"))
                    .dispatchAgent("r", "ticket/x", TICKET, "i"));

    assertEquals(502, failure.statusCode());
  }

  // --- the read back ---------------------------------------------------------------------------

  @Test
  void aLookupAsksOnceWithEveryWorkIdAndReadsTheAgentsBack() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"entries\":[{\"agent\":{\"agentId\":\"a-1\",\"workspaceRowId\":41,"
            + "\"repositoryId\":\"repo-1\",\"workId\":\"t-7\",\"entityId\":\"qits-7\","
            + "\"wrapperBranch\":\"ticket/qits-7\",\"branches\":["
            + "{\"repositoryId\":\"repo-1\",\"branch\":\"ticket/qits-7\"},"
            + "{\"repositoryId\":\"repo-2\",\"branch\":\"ticket/qits-7-totals\"}],"
            + "\"state\":\"ACTIVE\",\"runState\":\"RUNNING\","
            + "\"createdAt\":\"2026-10-10T09:00:00Z\",\"removedAt\":null}}]}");

    List<WorkspaceAgentDispatch.Agent> found =
        against(base).agentsFor(List.of("t-7", "t-8", "t-7", "e-1"));

    assertEquals(1, found.size());
    WorkspaceAgentDispatch.Agent agent = found.get(0);
    assertEquals("a-1", agent.agentId());
    assertEquals(41L, agent.workspaceRowId());
    assertEquals("repo-1", agent.repositoryId());
    assertEquals("t-7", agent.workId());
    assertEquals("qits-7", agent.entityId());
    assertEquals("ticket/qits-7", agent.wrapperBranch());
    assertEquals(
        List.of(
            new WorkspaceAgentDispatch.Branch("repo-1", "ticket/qits-7"),
            new WorkspaceAgentDispatch.Branch("repo-2", "ticket/qits-7-totals")),
        agent.branches());
    assertTrue(agent.active());
    assertEquals("RUNNING", agent.runState());
    assertEquals(Instant.parse("2026-10-10T09:00:00Z"), agent.createdAt());
    assertNull(agent.removedAt());

    assertEquals(1, received.size(), "a page of rows is one call, never one call per row");
    Received request = received.get(0);
    assertEquals("GET", request.method());
    // Under the dispatch door, whose class states qits:system — the person's door this used to
    // sit on answered 403 to every call, which this class's own contract reports as "none".
    assertEquals("/workspaces/api/agent-dispatches/references", request.path());
    assertEquals("Bearer machine-token", request.auth());
    // Every id asked about, each once — the repeat is dropped rather than asked twice.
    assertEquals("workId=t-7&workId=t-8&workId=e-1", request.query());
  }

  @Test
  void askingAboutNoRowsMakesNoCallAtAll() throws Exception {
    String base = startServer();

    assertTrue(against(base).agentsFor(List.of()).isEmpty());
    assertTrue(against(base).agentsFor(List.of("  ")).isEmpty());

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
    assertTrue(against(base).agentsFor(List.of("t-7")).isEmpty());

    status.set(200);
    responseBody.set("not json at all");
    assertTrue(against(base).agentsFor(List.of("t-7")).isEmpty());

    // Nothing listening, no address, and no credential — the three the dispatch answers 502/503 to.
    assertTrue(
        against("http://127.0.0.1:1", null, Optional.of("Bearer t")).agentsFor(List.of("t-7")).isEmpty());
    assertTrue(against(null, "", Optional.of("Bearer t")).agentsFor(List.of("t-7")).isEmpty());
    assertTrue(against(base, null, Optional.empty()).agentsFor(List.of("t-7")).isEmpty());
  }

  /**
   * <b>An obsolete agent is read back whole</b>, state and removal time included — the readers that
   * must not act on one have nothing else to go on. An unreadable time costs the time, never the
   * agent; an absent or blank state reads as ACTIVE.
   */
  @Test
  void theStateAndTimesSurviveTheWireAndABlankStateIsActive() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"entries\":[{\"agent\":{\"agentId\":\"a-1\",\"repositoryId\":\"repo-1\","
            + "\"workId\":\"t-7\",\"state\":\"REMOVED\",\"removedAt\":\"2026-09-20T09:30:00Z\"}},"
            + "{\"agent\":{\"agentId\":\"a-2\",\"repositoryId\":\"repo-1\",\"workId\":\"t-7\","
            + "\"state\":\"OBSOLETE\",\"removedAt\":\"not a time at all\"}},"
            + "{\"agent\":{\"agentId\":\"a-3\",\"repositoryId\":\"repo-1\",\"workId\":\"t-7\","
            + "\"state\":\"  \"}}]}");

    List<WorkspaceAgentDispatch.Agent> found = against(base).agentsFor(List.of("t-7"));

    assertEquals(3, found.size(), "nothing is filtered here — the port puts that on each reader");
    assertEquals("REMOVED", found.get(0).state());
    assertEquals(Instant.parse("2026-09-20T09:30:00Z"), found.get(0).removedAt());
    assertEquals("OBSOLETE", found.get(1).state());
    assertNull(found.get(1).removedAt());
    assertFalse(found.get(1).active());
    assertTrue(found.get(2).active(), "blank is ACTIVE");
    assertEquals(List.of(), found.get(2).branches(), "no branches member is no branches");
    assertNull(found.get(2).workspaceRowId(), "a queued agent has no workspace");
  }

  /** An agent nobody can link to or act on is skipped, rather than carried with a hole in it. */
  @Test
  void anEntryMissingTheAgentOrRepositoryIdIsSkipped() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"entries\":[{\"agent\":{\"repositoryId\":\"repo-1\",\"workId\":\"t-7\"}},"
            + "{\"agent\":{\"agentId\":\"a-9\",\"workId\":\"t-7\"}},"
            + "{\"agent\":{\"agentId\":\"a-12\",\"repositoryId\":\"repo-1\",\"workId\":\"t-7\"}}]}");

    List<WorkspaceAgentDispatch.Agent> found = against(base).agentsFor(List.of("t-7"));

    assertEquals(1, found.size());
    assertEquals("a-12", found.get(0).agentId());
  }
}
