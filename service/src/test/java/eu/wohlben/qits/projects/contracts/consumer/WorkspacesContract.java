package eu.wohlben.qits.projects.contracts.consumer;

import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.workspacehost.HttpReleasedBranchWorkspaces;
import eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentDispatch;
import eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentEntities;
import eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentTurns;
import eu.wohlben.qits.projects.workspacehost.WorkspacesBearer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-projects asks qits-workspaces</b> (ticket qits-1149): dispatching an agent onto a
 * branch, the workspaces that reference work items, the entity and blocked signals, a delivered
 * turn, and resolving the workspace on a released branch.
 *
 * <p>{@code POST /agent-dispatches/blocked} is the fallback {@link HttpWorkspaceAgentEntities} uses
 * when the entity door answers 404 or 405 (a qits-workspaces older than it). It is listed in the
 * inventory and has no row: a pact against a current provider cannot reach it.
 */
final class WorkspacesContract {

  static final String PROVIDER = "qits-workspaces-service";
  static final String APP = "qits-workspaces";

  private static final WorkspacesBearer BEARER = () -> Optional.of("Bearer machine-token");

  private WorkspacesContract() {}

  private static <T> T workspaces(T client, String base) {
    return Fields.with(
        client, "workspacesUrl", Optional.of(base), "releaseWorkspacesUrl", Optional.empty(),
        "bearer", BEARER);
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "dispatchAgent",
              "a repository with a branch for a ticket",
              "POST",
              "/workspaces/api/agent-dispatches",
              Map.of(),
              json(
                  "{\"repositoryId\":\"{repositoryId}\",\"branch\":\"{branch}\",\"branchTree\":false,"
                      + "\"ticketId\":\"{ticketId}\",\"workId\":\"{ticketId}\","
                      + "\"entityBlocked\":false,\"instruction\":\"implement\"}"),
              List.of("$.workspace.id", "$.fresh", "$.agentLaunch", "$.agentIdentity"),
              200,
              Trigger.operation("dispatchWork"),
              (base, p) -> {
                WorkspaceAgentDispatch.Dispatch dispatch =
                    workspaces(new HttpWorkspaceAgentDispatch(), base)
                        .dispatchAgent(
                            p.get("repositoryId"),
                            p.get("branch"),
                            null,
                            false,
                            WorkspaceAgentDispatch.Subject.ticket(p.get("ticketId")),
                            "implement");
                assertTrue(dispatch.workspaceRowId() > 0);
              },
              "POST /workspaces/api/agent-dispatches answering 200 with workspace.id, fresh,"
                  + " agentLaunch and agentIdentity"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listAgentDispatchReferences",
              "a project with workspaces bound to work items",
              "GET",
              "/workspaces/api/agent-dispatches/references",
              Map.of("ticketId", "{bugTicketId}"),
              null,
              List.of(
                  "$.entries[*].workspace.workspaceRowId",
                  "$.entries[*].workspace.repositoryId",
                  "$.entries[*].workspace.workspaceId",
                  "$.entries[*].workspace.branch",
                  "$.entries[*].workspace.ticketId",
                  "$.entries[*].workspace.epicId",
                  "$.entries[*].workspace.status",
                  "$.entries[*].workspace.resolvedAt"),
              200,
              Trigger.operation("getWorkDispatch"),
              (base, p) ->
                  assertFalse(
                      workspaces(new HttpWorkspaceAgentDispatch(), base)
                          .workspacesReferencing(List.of(p.get("bugTicketId")), List.of())
                          .isEmpty()),
              "GET .../agent-dispatches/references?ticketId answering 200 with entries[].workspace"
                  + " rows"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "markAgentDispatchEntity",
              "a workspace standing on a ticket's branch",
              "POST",
              "/workspaces/api/agent-dispatches/entity",
              Map.of(),
              json(
                  "{\"repositoryId\":\"{repositoryId}\",\"branch\":\"{branch}\",\"title\":\"Fix it\","
                      + "\"status\":\"IMPLEMENTING\",\"blocked\":false,\"workId\":\"{ticketId}\"}"),
              List.of("$.workspaceId", "$.applied"),
              200,
              Trigger.operation("setWorkStatus"),
              (base, p) ->
                  workspaces(new HttpWorkspaceAgentEntities(), base)
                      .changed(
                          p.get("ticketId"),
                          p.get("repositoryId"),
                          p.get("branch"),
                          "Fix it",
                          "IMPLEMENTING",
                          false),
              "POST .../agent-dispatches/entity answering 200 with workspaceId and applied"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "deliverAgentTurn",
              "a workspace standing on a ticket's branch",
              "POST",
              "/workspaces/api/agent-dispatches/delivery",
              Map.of(),
              json(
                  "{\"repositoryId\":\"{repositoryId}\",\"branch\":\"{branch}\",\"text\":\"go on\","
                      + "\"workId\":\"{ticketId}\",\"compactFirst\":false}"),
              List.of("$.workspaceId", "$.launched", "$.delivered", "$.detail"),
              200,
              Trigger.operation("transitionWork"),
              (base, p) -> {
                WorkspaceAgentTurns.Turn turn =
                    workspaces(new HttpWorkspaceAgentTurns(), base)
                        .deliver(p.get("ticketId"), p.get("repositoryId"), p.get("branch"), "go on");
                // The recorded workspace may have no runner yet, so the answer can be "queued"
                // rather than delivered. The row checks that the answer is read: it names a
                // workspace and says why.
                assertNotEquals(WorkspaceAgentTurns.Outcome.NO_WORKSPACE, turn.outcome());
                assertFalse(turn.detail().isBlank(), "the answer carries its detail");
              },
              "POST .../agent-dispatches/delivery answering 200 with workspaceId, launched,"
                  + " delivered and detail"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "resolveReleasedBranch",
              "a workspace standing on a released branch",
              "POST",
              "/workspaces/api/branches/resolution",
              Map.of("repositoryId", "{repositoryId}"),
              json(
                  "{\"branch\":\"{branch}\",\"target\":\"{version}\",\"commit\":\"{sha}\","
                      + "\"result\":\"released as {version}\"}"),
              List.of("$.resolved", "$.workspaceId"),
              200,
              Trigger.schedule("ReleaseRequests.resolveWorkspacesOnReleasedBranches"),
              (base, p) ->
                  Fields.with(
                          new HttpReleasedBranchWorkspaces(),
                          "workspacesUrl", Optional.of(base),
                          "bearer", BEARER)
                      .branchReleased(
                          p.get("repositoryId"), p.get("branch"), p.get("version"), p.get("sha")),
              "POST /workspaces/api/branches/resolution?repositoryId answering 200 with resolved and"
                  + " workspaceId"));
}
