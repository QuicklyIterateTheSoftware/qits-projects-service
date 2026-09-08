package eu.wohlben.qits.projects.mcp;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Hides the <em>mutating</em> repository tools from a session that connected read-only — the {@code
 * agentReadOnly=true} marker {@code AgentLaunchService.renderAutonomousChat} stamps into the MCP
 * URL of an <strong>autonomous</strong> run. An unattended run under {@code
 * --dangerously-skip-permissions} (today only conflict resolution, riding the chat pipeline)
 * attaches the repository server solely for {@code taskPrompt}; it must not be able to drive
 * host-side, cross-repository mutations (create workspaces, integrate or clean up branches) with no
 * human in the loop — its own git work happens inside its container. Interactive/chat sessions do
 * not set the marker, so their tool set is unchanged.
 *
 * <p>Mirrors {@link TaskPromptToolFilter}: fails <em>closed</em> (hide the mutating tool) if the
 * request scope can't be read, so a listing error never widens access.
 */
@ApplicationScoped
public class ReadOnlyRepositoryToolFilter implements ToolFilter {

  /**
   * Query-parameter marker set by an autonomous (unattended) launch to request a read-only view.
   */
  public static final String READ_ONLY_PARAM = "agentReadOnly";

  /**
   * The mutating tools of the "repository" MCP server (see {@code RepositoryMcpTools} and {@code
   * EpicMcpTools}); everything else it exposes is read-only. Kept explicit so a newly added
   * mutating tool is a conscious choice to add here. {@code runAction} executes a configured action
   * script in a workspace container — a host-side side effect — so it must be hidden from an
   * unattended read-only run just like the branch/workspace mutators, else a conflict-resolution
   * agent steered by an untrusted commit message could run arbitrary actions with no human in the
   * loop.
   *
   * <p>The epic write tools are here for the same reason: an unattended run must not rewrite the
   * project's plan. The refinement agent that owns them connects without the marker, so its own
   * surface is unchanged. {@code mark_task_implemented} is the newest of them and belongs here on a
   * slightly different reading than its neighbours: it does not edit the plan at all, it declares
   * one of its tasks shipped. That is a statement people act on — a board reads it as progress and
   * an epic's "done" is derived from it — so an unattended run steered by an untrusted commit
   * message must not be able to make it. The implementing agent that owns it is dispatched through
   * {@code EpicDispatchController} and connects without the marker, exactly as the refinement agent
   * does.
   *
   * <p>{@code propose_design} joins them: a proposal is a row a person then has to read and rule
   * on, and an unattended run must not fill the Design tab with work nobody asked for.
   *
   * <p>The five ticket write tools are here on the same reading, and {@code transition_ticket} is
   * the one worth naming: an unattended run steered by an untrusted commit message must not be able
   * to declare somebody else's bug resolved, which is a statement people act on. Filing tickets
   * nobody asked for is the {@code propose_design} objection again. {@code update_ticket_comment}
   * is the sharpest of the five and the least obviously so: it rewrites a remark that is already on
   * the thread, so an unattended run holding it could edit what a person wrote and leave a record
   * saying something nobody said.
   */
  private static final Set<String> MUTATING_TOOLS =
      Set.of(
          "createWorkspace",
          "cleanupBranch",
          "integrateBranch",
          "mergeParentIntoWorkspace",
          "runAction",
          "propose_epic",
          "update_epic",
          "add_feature",
          "update_feature",
          "remove_feature",
          "add_task",
          "update_task",
          "remove_task",
          "mark_task_implemented",
          "propose_design",
          "create_ticket",
          "update_ticket",
          "transition_ticket",
          "add_ticket_comment",
          "update_ticket_comment");

  @Inject HttpServerRequest request;

  @Override
  public boolean test(ToolInfo tool, McpConnection connection) {
    if (!MUTATING_TOOLS.contains(tool.name())) {
      return true;
    }
    try {
      return !"true".equalsIgnoreCase(request.getParam(READ_ONLY_PARAM));
    } catch (RuntimeException e) {
      return false;
    }
  }
}
