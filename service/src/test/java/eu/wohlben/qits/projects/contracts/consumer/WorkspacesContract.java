package eu.wohlben.qits.projects.contracts.consumer;

import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.workspacehost.HttpReleasedBranchWorkspaces;
import eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentDispatch;
import eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentEntities;
import eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentTurns;
import eu.wohlben.qits.projects.workspacehost.WorkspacesBearer;
import io.opentelemetry.api.OpenTelemetry;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What qits-projects asks qits-workspaces</b> (ticket qits-1149, reshaped by qits-1152):
 * dispatching an agent for a work item, the agents that work on work items, the entity signal, a
 * delivered turn, and marking the agents of a released branch obsolete. Every call but the release
 * names the work item; none names a branch to find an agent by.
 *
 * <p>{@code POST /agent-dispatches/blocked} is the fallback {@link HttpWorkspaceAgentEntities} uses
 * when the entity door answers 404 or 405 (a qits-workspaces older than it), with the same body. A
 * current provider still serves it, so it has a row: the client runs against {@link
 * #olderThanTheEntityDoor}, which answers the entity door 404 itself and passes everything else to
 * the mock — the 404 is the scenario, not part of the pact.
 */
final class WorkspacesContract {

  static final String PROVIDER = "qits-workspaces-service";
  static final String APP = "qits-workspaces";

  /** A wrapper repository and a ticket with no agent yet. */
  static final String A_TICKET_TO_DISPATCH = "a wrapper repository and a ticket to dispatch";

  /** Agents in every state, each bound to a work item of one project. */
  static final String AGENTS_BOUND_TO_WORK_ITEMS = "a project with agents bound to work items";

  /** One ACTIVE agent working on a ticket. */
  static final String AN_AGENT_ON_A_TICKET = "an agent working on a ticket";

  /** An ACTIVE agent that pushed the branch a release just deleted. */
  static final String AN_AGENT_ON_A_RELEASED_BRANCH = "an agent owning a released branch";

  private static final WorkspacesBearer BEARER = () -> Optional.of("Bearer machine-token");

  private WorkspacesContract() {}

  private static <T> T workspaces(T client, String base) {
    return Fields.with(
        client, "workspacesUrl", Optional.of(base), "releaseWorkspacesUrl", Optional.empty(),
        "bearer", BEARER);
  }

  /** Headers the JDK client sets itself and refuses to be handed. */
  private static final java.util.Set<String> HOP_BY_HOP =
      java.util.Set.of(
          "host", "content-length", "connection", "upgrade", "http2-settings", "expect", "te");

  /** A door this consumer knows to ask about, as a far side older than it answers: 404. */
  @FunctionalInterface
  interface Against {
    void run(String base) throws Exception;
  }

  /**
   * Runs {@code call} against a far side that answers {@code POST .../agent-dispatches/entity} 404
   * — a qits-workspaces older than that door — and passes every other request to {@code mock}.
   */
  static void olderThanTheEntityDoor(String mock, Against call) throws Exception {
    HttpClient forward = HttpClient.newHttpClient();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getRawPath();
          if (path.endsWith("/agent-dispatches/entity")) {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          byte[] body;
          try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
          }
          try {
            HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(mock + exchange.getRequestURI()))
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.getRequestHeaders().forEach(
                (name, values) -> {
                  if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                    values.forEach(value -> request.header(name, value));
                  }
                });
            HttpResponse<byte[]> answer =
                forward.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            answer.headers().firstValue("Content-Type")
                .ifPresent(type -> exchange.getResponseHeaders().add("Content-Type", type));
            byte[] out = answer.body();
            exchange.sendResponseHeaders(answer.statusCode(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
              exchange.getResponseBody().write(out);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(502, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      call.run("http://127.0.0.1:" + server.getAddress().getPort());
    } finally {
      server.stop(0);
    }
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "dispatchAgent",
              A_TICKET_TO_DISPATCH,
              "POST",
              "/workspaces/api/agent-dispatches",
              Map.of(),
              json(
                  "{\"repositoryId\":\"{repositoryId}\",\"wrapperBranch\":\"{wrapperBranch}\","
                      + "\"workId\":\"{ticketId}\",\"entityId\":\"{ticketQualifiedId}\","
                      + "\"entityTitle\":\"Fix it\",\"entityStatus\":\"REFINED\","
                      + "\"entityBlocked\":false,\"ticketId\":\"{ticketId}\","
                      + "\"instruction\":\"implement\"}"),
              List.of(
                  "$.agent.agentId",
                  "$.agent.workspaceRowId",
                  "$.agent.repositoryId",
                  "$.fresh",
                  "$.agentLaunch",
                  "$.agentIdentity"),
              200,
              Trigger.operation("dispatchWork"),
              (base, p) -> {
                WorkspaceAgentDispatch.Dispatch dispatch =
                    workspaces(new HttpWorkspaceAgentDispatch(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                        .dispatchAgent(
                            p.get("repositoryId"),
                            p.get("wrapperBranch"),
                            WorkspaceAgentDispatch.Subject.ticket(p.get("ticketId"))
                                .withQualifiedId(p.get("ticketQualifiedId"))
                                .withEntityState("Fix it", "REFINED", false),
                            "implement");
                assertFalse(dispatch.agentId().isBlank());
              },
              "params repositoryId (the wrapper), ticketId, ticketQualifiedId and wrapperBranch"
                  + " (ticket/<ticketQualifiedId>); POST /workspaces/api/agent-dispatches answering"
                  + " 200 with agent.agentId, agent.workspaceRowId, agent.repositoryId, fresh,"
                  + " agentLaunch and agentIdentity"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listAgentDispatchReferences",
              AGENTS_BOUND_TO_WORK_ITEMS,
              "GET",
              "/workspaces/api/agent-dispatches/references",
              Map.of("workId", "{bugTicketId}"),
              null,
              List.of(
                  "$.entries[*].agent.agentId",
                  "$.entries[*].agent.workspaceRowId",
                  "$.entries[*].agent.repositoryId",
                  "$.entries[*].agent.workId",
                  "$.entries[*].agent.entityId",
                  "$.entries[*].agent.wrapperBranch",
                  "$.entries[*].agent.branches[*].repositoryId",
                  "$.entries[*].agent.branches[*].branch",
                  "$.entries[*].agent.state",
                  "$.entries[*].agent.runState",
                  "$.entries[*].agent.createdAt",
                  "$.entries[*].agent.removedAt"),
              200,
              Trigger.operation("getWorkDispatch"),
              (base, p) ->
                  assertFalse(
                      workspaces(new HttpWorkspaceAgentDispatch(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                          .agentsFor(List.of(p.get("bugTicketId")))
                          .isEmpty()),
              "param bugTicketId; GET .../agent-dispatches/references?workId answering 200 with"
                  + " entries[].agent rows (ACTIVE, OBSOLETE and REMOVED, at least one with a"
                  + " pushed branch)"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listAgentDispatchReferences",
              AGENTS_BOUND_TO_WORK_ITEMS,
              "GET",
              "/workspaces/api/agent-dispatches/references",
              Map.of("workId", "{bugTicketId}"),
              null,
              List.of("$.entries[*].agent.workId", "$.entries[*].agent.state"),
              200,
              Trigger.schedule("TicketUnattendedGateTickets.ended"),
              (base, p) ->
                  assertTrue(
                      workspaces(new HttpWorkspaceAgentDispatch(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                          .hasActiveAgent(p.get("bugTicketId"))
                          .isPresent()),
              "param bugTicketId; GET .../agent-dispatches/references?workId answering 200 with"
                  + " entries[].agent's workId and state"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "markAgentDispatchEntity",
              AN_AGENT_ON_A_TICKET,
              "POST",
              "/workspaces/api/agent-dispatches/entity",
              Map.of(),
              json(
                  "{\"workId\":\"{ticketId}\",\"title\":\"Fix it\",\"status\":\"IMPLEMENTING\","
                      + "\"blocked\":false}"),
              List.of("$.agentId", "$.applied"),
              200,
              Trigger.operation("setWorkStatus"),
              (base, p) ->
                  workspaces(new HttpWorkspaceAgentEntities(QitsHttpClients.of(OpenTelemetry.noop(), null)), base)
                      .changed(p.get("ticketId"), "Fix it", "IMPLEMENTING", false, null),
              "param ticketId; POST .../agent-dispatches/entity answering 200 with agentId and"
                  + " applied"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "markAgentDispatchBlocked",
              AN_AGENT_ON_A_TICKET,
              "POST",
              "/workspaces/api/agent-dispatches/blocked",
              Map.of(),
              json(
                  "{\"workId\":\"{ticketId}\",\"title\":\"Fix it\",\"status\":\"IMPLEMENTING\","
                      + "\"blocked\":true,\"blockSource\":\"EXPLICIT\"}"),
              List.of("$.agentId", "$.applied"),
              200,
              Trigger.operation("setWorkBlocked"),
              (base, p) ->
                  olderThanTheEntityDoor(
                      base,
                      older ->
                          workspaces(
                                  new HttpWorkspaceAgentEntities(
                                      QitsHttpClients.of(OpenTelemetry.noop(), null)),
                                  older)
                              .changed(p.get("ticketId"), "Fix it", "IMPLEMENTING", true, "EXPLICIT")),
              "param ticketId; POST .../agent-dispatches/blocked answering 200 with agentId and"
                  + " applied, for a far side whose entity door answers 404"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "deliverAgentTurn",
              AN_AGENT_ON_A_TICKET,
              "POST",
              "/workspaces/api/agent-dispatches/delivery",
              Map.of(),
              json("{\"workId\":\"{ticketId}\",\"text\":\"go on\",\"compactFirst\":false}"),
              List.of("$.agentId", "$.delivered", "$.launched", "$.resumed", "$.detail"),
              200,
              Trigger.operation("transitionWork"),
              (base, p) -> {
                WorkspaceAgentTurns.Turn turn =
                    workspaces(new HttpWorkspaceAgentTurns(QitsHttpClients.of(OpenTelemetry.noop(), null)), base).deliver(p.get("ticketId"), "go on");
                assertNotEquals(WorkspaceAgentTurns.Outcome.NO_AGENT, turn.outcome(), turn.detail());
                assertFalse(turn.detail().isBlank(), "the answer carries its detail");
              },
              "param ticketId; POST .../agent-dispatches/delivery answering 200 with agentId,"
                  + " delivered, launched, resumed and detail"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "resolveReleasedBranch",
              AN_AGENT_ON_A_RELEASED_BRANCH,
              "POST",
              "/workspaces/api/branches/resolution",
              Map.of("repositoryId", "{repositoryId}"),
              json(
                  "{\"branch\":\"{branch}\",\"target\":\"{version}\",\"commit\":\"{sha}\","
                      + "\"result\":\"released as {version}\"}"),
              List.of("$.resolved", "$.agentIds"),
              200,
              Trigger.schedule("ReleaseRequests.resolveWorkspacesOnReleasedBranches"),
              (base, p) ->
                  Fields.with(
                          new HttpReleasedBranchWorkspaces(QitsHttpClients.of(OpenTelemetry.noop(), null)),
                          "workspacesUrl", Optional.of(base),
                          "bearer", BEARER)
                      .branchReleased(
                          p.get("repositoryId"), p.get("branch"), p.get("version"), p.get("sha")),
              "params repositoryId, branch, version, sha; POST"
                  + " /workspaces/api/branches/resolution?repositoryId answering 200 with resolved"
                  + " and agentIds"));
}
