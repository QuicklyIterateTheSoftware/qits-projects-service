package eu.wohlben.qits.projects.workspacehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
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
 * {@link HttpWorkspaceAgentTurns} against a local server standing in for qits-workspaces — plain
 * JUnit over a directly-constructed bean, {@link HttpReleasedBranchWorkspacesTest}'s and {@link
 * HttpWorkspaceAgentDispatchTest}'s shape one class over.
 *
 * <p>Two things are under test and they are the two the flow cannot see: the exact wire shape (the
 * path under {@code agent-dispatches}, the method, the bearer and the four body members the delivery
 * door declares), and that <b>every</b> way this can go wrong returns an outcome rather than
 * throwing. The second is the port's whole contract: the transition it follows has already been
 * recorded by the time this runs.
 */
class HttpWorkspaceAgentTurnsTest {

  private record Received(String method, String path, String body, String auth) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>(
          "{\"workspaceId\":41,\"delivered\":true,\"launched\":false,\"detail\":\"told it\"}");

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

  private HttpWorkspaceAgentTurns against(
      String base, String fallback, Optional<String> authorization) {
    HttpWorkspaceAgentTurns turns = new HttpWorkspaceAgentTurns();
    turns.workspacesUrl = Optional.ofNullable(base);
    turns.releaseWorkspacesUrl = Optional.ofNullable(fallback);
    turns.bearer = () -> authorization;
    return turns;
  }

  private HttpWorkspaceAgentTurns against(String base) {
    return against(base, null, Optional.of("Bearer machine-token"));
  }

  @Test
  void aTurnIsPostedToTheDeliveryDoorWithTheTextOnTheBody() throws Exception {
    String base = startServer();

    WorkspaceAgentTurns.Turn turn =
        against(base).deliver("repo-1", "ticket/puce-button", "The ticket is REFINED now.");

    assertEquals(WorkspaceAgentTurns.Outcome.DELIVERED, turn.outcome());
    assertEquals("told it", turn.detail());
    assertTrue(turn.spoken());

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("POST", request.method());
    // Under agent-dispatches, where the roles are: the sibling path under /workspaces/… is a
    // person's door and answered 403 to this service's machine bearer.
    assertEquals("/workspaces/api/agent-dispatches/delivery", request.path());
    assertEquals("Bearer machine-token", request.auth());
    assertEquals(
        Map.of(
            "repositoryId", "repo-1",
            "branch", "ticket/puce-button",
            "text", "The ticket is REFINED now.",
            "compactFirst", false),
        MAPPER.readValue(request.body(), Map.class));
  }

  /** An agent that was not running is launched to take the turn, and that is its own outcome. */
  @Test
  void aLaunchIsReportedAsALaunchAndNotAsADelivery() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"workspaceId\":41,\"delivered\":true,\"launched\":true,\"detail\":\"started one\"}");

    WorkspaceAgentTurns.Turn turn = against(base).deliver("repo-1", "ticket/x", "go");

    assertEquals(WorkspaceAgentTurns.Outcome.LAUNCHED, turn.outcome());
    assertTrue(turn.spoken());
  }

  /**
   * The ordinary answer for a ticket nobody has dispatched an agent onto: a 200, a null workspace
   * id, and a sentence. The door never creates a workspace, so this is not a failure.
   */
  @Test
  void noWorkspaceOnTheBranchIsAnOrdinaryAnswerAndNotAFailure() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"workspaceId\":null,\"delivered\":false,\"launched\":false,"
            + "\"detail\":\"no workspace stands on ticket/x\"}");

    WorkspaceAgentTurns.Turn turn =
        assertDoesNotThrow(() -> against(base).deliver("repo-1", "ticket/x", "go"));

    assertEquals(WorkspaceAgentTurns.Outcome.NO_WORKSPACE, turn.outcome());
    assertEquals("no workspace stands on ticket/x", turn.detail());
    assertEquals(1, received.size(), "and it was asked exactly once");
  }

  @Test
  void aRefusingFarSideIsAnOutcomeAndNeverAThrow() throws Exception {
    String base = startServer();
    status.set(500);
    responseBody.set("{\"message\":\"the harness is gone\"}");

    WorkspaceAgentTurns.Turn turn =
        assertDoesNotThrow(() -> against(base).deliver("repo-1", "ticket/x", "go"));

    assertEquals(WorkspaceAgentTurns.Outcome.COULD_NOT, turn.outcome());
    assertTrue(turn.detail().contains("500"), turn.detail());
    assertTrue(!turn.spoken(), "nothing was said, so nothing may claim it was");
  }

  @Test
  void anAnswerThatWillNotParseIsAnOutcomeAndNeverAThrow() throws Exception {
    String base = startServer();
    responseBody.set("not json at all");

    assertEquals(
        WorkspaceAgentTurns.Outcome.COULD_NOT,
        assertDoesNotThrow(() -> against(base).deliver("repo-1", "ticket/x", "go")).outcome());
  }

  /** A workspace that neither took the text nor launched: this side cannot claim it was told. */
  @Test
  void anAnswerClaimingNeitherDeliveryNorLaunchIsNotADelivery() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"workspaceId\":41,\"delivered\":false,\"launched\":false,\"detail\":\"busy\"}");

    assertEquals(
        WorkspaceAgentTurns.Outcome.COULD_NOT,
        against(base).deliver("repo-1", "ticket/x", "go").outcome());
  }

  @Test
  void anUnreachableWorkspacesIsAnOutcomeAndNeverAThrow() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    WorkspaceAgentTurns.Turn turn =
        assertDoesNotThrow(
            () ->
                against("http://127.0.0.1:1", null, Optional.of("Bearer t"))
                    .deliver("repo-1", "ticket/x", "go"));

    assertEquals(WorkspaceAgentTurns.Outcome.COULD_NOT, turn.outcome());
  }

  /** One WARN and nothing else: an unconfigured hop makes no request at all. */
  @Test
  void anUnsetOrBlankAddressSendsNothing() throws Exception {
    String base = startServer();

    assertEquals(
        WorkspaceAgentTurns.Outcome.COULD_NOT,
        against(null, null, Optional.of("Bearer t")).deliver("repo-1", "ticket/x", "go").outcome());
    assertEquals(
        WorkspaceAgentTurns.Outcome.COULD_NOT,
        against("  ", "", Optional.of("Bearer t")).deliver("repo-1", "ticket/x", "go").outcome());

    assertTrue(received.isEmpty(), "a switched-off hop dials nothing: " + received);
    assertTrue(base.startsWith("http://"), "the server was up, so an attempt would have landed");
  }

  /** The dispatch door's two keys, in its order — no third address and no rename. */
  @Test
  void theReleaseKeyIsTheFallbackAddress() throws Exception {
    String base = startServer();

    assertEquals(
        WorkspaceAgentTurns.Outcome.DELIVERED,
        against(null, base, Optional.of("Bearer machine-token"))
            .deliver("repo-1", "ticket/x", "go")
            .outcome());
    assertEquals(1, received.size(), "an unset own key falls back to the release path's address");
  }

  /** No credential is not a reason to ask anonymously: the far side drives an agent. */
  @Test
  void noBearerSendsNothing() throws Exception {
    String base = startServer();

    assertEquals(
        WorkspaceAgentTurns.Outcome.COULD_NOT,
        against(base, null, Optional.empty()).deliver("repo-1", "ticket/x", "go").outcome());

    assertTrue(received.isEmpty(), "an uncredentialed hop dials nothing: " + received);
  }
}
