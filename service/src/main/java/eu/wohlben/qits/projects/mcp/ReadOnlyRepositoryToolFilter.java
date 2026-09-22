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
   * <p>{@code put_design} joins them, and the case got stronger rather than weaker when designs
   * stopped being proposals: a write is live in the Design tab the moment it lands, so an unattended
   * run holding it could overwrite a document a person is working from — not merely fill the tab
   * with work nobody asked for.
   *
   * <p>The dossier's four write tools ({@code put_dossier_page}, {@code move_dossier_page},
   * {@code remove_dossier_page} and {@code inline_figure}) are here on the epic tools' reading: a
   * dossier is the epic's long form, so rewriting one is rewriting the project's plan. Nothing here
   * is accepted by anybody either, so an unattended run holding {@code put_dossier_page} could
   * overwrite a page somebody is writing.
   *
   * <p>The five ticket write tools are here on the same reading, and {@code transition_ticket} is
   * the one worth naming: an unattended run steered by an untrusted commit message must not be able
   * to declare somebody else's bug resolved, which is a statement people act on. Filing tickets
   * nobody asked for is the {@code put_design} objection again. {@code update_ticket_comment}
   * is the sharpest of the five and the least obviously so: it rewrites a remark that is already on
   * the thread, so an unattended run holding it could edit what a person wrote and leave a record
   * saying something nobody said.
   *
   * <p>{@code block_ticket} and {@code unblock_ticket} join the ticket writes on the reading {@code
   * transition_ticket} already carries, and the second of the pair is the one worth naming. A block
   * is a statement people act on — a listing surface draws it and the next agent asked to take on
   * the outstanding work skips the ticket — so an unattended run steered by an untrusted commit
   * message must not be able to make it. An <em>unblock</em> is the sharper half rather than the
   * harmless one: it takes away somebody else's stated blocker and puts the ticket back in front of
   * whoever picks up the work next, with nothing on the thread that a person said had cleared.
   *
   * <p>{@code transition_entities} is the sharpest of all of them and belongs here on the strongest
   * reading in this list: it restates part of the plan <em>in full</em> in one transaction, so an
   * unattended run steered by an untrusted commit message could re-archetype, re-parent and clear
   * properties across a whole tree in a single call — and every property it did not restate would be
   * cleared, which no other tool on this server can do. The agents that own it (the refining one and
   * the implementing one) connect without the marker, exactly as they do for the epic tools.
   * {@code list_entities} beside it is a read and is deliberately not here: an unattended run may
   * always see the plan it must not rewrite.
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
          "put_design",
          "put_dossier_page",
          "move_dossier_page",
          "remove_dossier_page",
          "inline_figure",
          "create_ticket",
          "update_ticket",
          "transition_ticket",
          "block_ticket",
          "unblock_ticket",
          "add_ticket_comment",
          "update_ticket_comment",
          "transition_entities");

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
