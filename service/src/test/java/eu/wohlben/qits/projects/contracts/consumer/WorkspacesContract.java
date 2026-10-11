package eu.wohlben.qits.projects.contracts.consumer;

import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
 * <b>What qits-projects asks qits-workspaces</b> (ticket qits-1149, reshaped by qits-1152):
 * dispatching an agent for a work item, the agents that work on work items, the entity signal, a
 * delivered turn, and marking the agents of a released branch obsolete. Every call but the release
 * names the work item; none names a branch to find an agent by.
 *
 * <p>{@code POST /agent-dispatches/blocked} is the fallback {@link HttpWorkspaceAgentEntities} uses
 * when the entity door answers 404 or 405 (a qits-workspaces older than it). It takes the same body
 * and has no row: a pact against a current provider cannot reach it.
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
                    workspaces(new HttpWorkspaceAgentDispatch(), base)
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
                      workspaces(new HttpWorkspaceAgentDispatch(), base)
                          .agentsFor(List.of(p.get("bugTicketId")))
                          .isEmpty()),
              "param bugTicketId; GET .../agent-dispatches/references?workId answering 200 with"
                  + " entries[].agent rows (ACTIVE, OBSOLETE and REMOVED, at least one with a"
                  + " pushed branch)"),
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
                  workspaces(new HttpWorkspaceAgentEntities(), base)
                      .changed(p.get("ticketId"), "Fix it", "IMPLEMENTING", false, null),
              "param ticketId; POST .../agent-dispatches/entity answering 200 with agentId and"
                  + " applied"),
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
                    workspaces(new HttpWorkspaceAgentTurns(), base).deliver(p.get("ticketId"), "go on");
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
                          new HttpReleasedBranchWorkspaces(),
                          "workspacesUrl", Optional.of(base),
                          "bearer", BEARER)
                      .branchReleased(
                          p.get("repositoryId"), p.get("branch"), p.get("version"), p.get("sha")),
              "params repositoryId, branch, version, sha; POST"
                  + " /workspaces/api/branches/resolution?repositoryId answering 200 with resolved"
                  + " and agentIds"));
}
