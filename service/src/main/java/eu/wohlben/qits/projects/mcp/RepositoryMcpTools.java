package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.CommitService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.dto.BranchDto;
import eu.wohlben.qits.projects.dto.CommitChangesDto;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.CommitLogDto;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * The "repository" MCP server — the working surface for a project's git repositories, exposed to an
 * LLM. Every tool is bound to the named {@code repository} MCP server (mounted at {@code
 * /projects/mcp}); nothing from other domain areas (projects, feature flows) is exposed here, so a
 * client connected to this endpoint only ever sees repository tools and stays on task. The server
 * <em>name</em> stays {@code repository} even though the path carries this service's segment — the
 * tools really are repository-scoped, and qits-workspace-daemon addresses the server by name.
 *
 * <p><strong>Use case: working inside a repository.</strong> Inspect its branches, commits and
 * diffs; manipulate workspaces (branch off, integrate, clean up, merge a parent in). Defining and
 * editing actions is the job of the separate "actions" server (see {@link
 * eu.wohlben.qits.domain.featureflow.mcp.ActionConfigurationMcpTools}); running a config-declared
 * workspace action moved to the workspace-daemon's own surface (Part 5 removed the repo-anchored
 * {@code listActions}/{@code runAction} pair with the DB config store). The split is intentional: a
 * session here is for getting work done in a checkout, not for changing what actions exist.
 *
 * <p>Each session is scoped to a single project via {@link ProjectScope} (the {@code
 * X-QITS-Project} header), and may be further narrowed to one repository within it (the optional
 * {@code X-QITS-Repository} header). Tools take a {@code repoId} but never a project id, and {@link
 * #requireRepoInProject} rejects any repository that does not belong to the scoped project — or,
 * when the session is narrowed, any repository other than the scoped one — so the model cannot
 * reach across project boundaries or out of its checkout.
 *
 * <p><b>The narrowing fences the git tools, not the whole surface.</b> It says which repository
 * this session has a checkout of, so {@link #listBranches}, {@link #listCommits}, {@link
 * #listCommitChanges} and {@link #getCommitFileDiff} answer for that one alone. {@link
 * #listRepositories} lists the whole project regardless, marking the session's own row, because
 * the ids it hands out are also what a plan is written against — a task's {@code repositoryId}
 * ({@code add_task}) is a reference to where work belongs and takes project membership alone. See
 * {@link ProjectScopeGuard} for the two rules.
 *
 * <p>{@link WrapBusinessError} turns any exception a tool throws — the scoping guards here and the
 * domain {@code NotFoundException}/{@code BadRequestException}s from the services — into a tool
 * result with {@code isError=true} carrying the message, so the model sees a readable failure
 * instead of a hard JSON-RPC protocol error.
 */
@ApplicationScoped
@WrapBusinessError
public class RepositoryMcpTools {

  @Inject ProjectScope scope;

  @Inject ProjectScopeGuard scopeGuard;

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject CommitService commitService;

  // --- Context (read) -------------------------------------------------------

  /**
   * A repository visible to this session, trimmed to what the model needs to pick one.
   *
   * <p>{@code thisSession} marks the one repository this session is standing on — the narrowing
   * from {@code X-QITS-Repository} — and is false on every row when the session is not narrowed.
   * It is the row whose checkout the agent has and the only one the git tools answer for; the rest
   * are listed because a plan is written for the whole project.
   */
  public record RepositorySummary(
      String id, String url, String archetype, String mainBranch, boolean thisSession) {}

  @McpServer("repository")
  @Tool(
      description =
          "List the git repositories belonging to the project this session is scoped to. Start here"
              + " to obtain a repoId for the other tools. Every repository of the project is"
              + " listed, including when the session is narrowed to one of them: that is the set a"
              + " task may be filed against (add_task), because a plan spans the project. The row"
              + " with thisSession=true is the repository this session is standing on, and it is"
              + " the ONLY one listBranches, listCommits, listCommitChanges and getCommitFileDiff"
              + " answer for when the session is narrowed — they refuse any other repoId.")
  @Transactional
  public List<RepositorySummary> listRepositories() {
    var scopedRepo = scope.repositoryId();
    return projectService.getRepositories(scope.requireProjectId()).stream()
        .map(
            r ->
                new RepositorySummary(
                    r.id,
                    r.url,
                    r.archetype == null ? null : r.archetype.name(),
                    r.mainBranch,
                    scopedRepo.isPresent() && scopedRepo.get().equals(r.id)))
        .toList();
  }

  @McpServer("repository")
  @Tool(
      description =
          "List the branches of a repository. Each branch reports its parent, how far it is"
              + " ahead/behind that parent, and whether it can be safely cleaned up (fully merged,"
              + " no dependents).")
  @Transactional
  public List<BranchDto> listBranches(
      @ToolArg(description = "id of a repository in this project") String repoId) {
    requireRepoInProject(repoId);
    return repositoryService.listBranchesWithCleanup(repoId);
  }

  // SEAM (migration-plan.md §6): the listWorkspaces tool is cut — WorkspaceService/WorkspaceDto
  // are qits-workspaces'. See the note above the scoping helpers.

  @McpServer("repository")
  @Tool(
      description =
          "List the commits unique to a branch (the commits on it that are not on its parent),"
              + " newest first, each with the files it changed.")
  @Transactional
  public CommitLogDto listCommits(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "branch name to read the log of") String branch) {
    requireRepoInProject(repoId);
    return commitService.listCommits(repoId, branch);
  }

  @McpServer("repository")
  @Tool(
      description =
          "List the files a commit changed relative to its diff base (the explicit parent, or the"
              + " commit's own first parent when omitted). This is the commit-detail view; use"
              + " getCommitFileDiff for the contents of one file.")
  @Transactional
  public CommitChangesDto listCommitChanges(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "full or short commit hash") String commitHash,
      @ToolArg(required = false, description = "diff base; omit to use the commit's first parent")
          String parent) {
    requireRepoInProject(repoId);
    return commitService.listChanges(repoId, commitHash, parent);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Get the unified diff of a single file within a commit, relative to the same base as"
              + " listCommitChanges. The diff is empty for a binary file or a pure rename.")
  @Transactional
  public CommitFileDiffDto getCommitFileDiff(
      @ToolArg(description = "id of a repository in this project") String repoId,
      @ToolArg(description = "full or short commit hash") String commitHash,
      @ToolArg(description = "path of the file within the repository") String path,
      @ToolArg(required = false, description = "diff base; omit to use the commit's first parent")
          String parent) {
    requireRepoInProject(repoId);
    return commitService.getFileDiff(repoId, commitHash, parent, path);
  }

  // --- Actions (write) ------------------------------------------------------

  /** Result of creating a workspace. */

  // SEAM (migration-plan.md §6, repository <-> workspace). Five workspace-shaped MCP tools stood
  // here — listWorkspaces, createWorkspace, cleanupBranch, integrateBranch and
  // mergeParentIntoWorkspace — every one of them a thin wrapper over WorkspaceService, which is
  // WS_REPO (qits-workspaces'). They are cut rather than ported: an MCP tool that only forwards to
  // another bounded context belongs in that context's own tool surface, not behind a port here.
  //
  // NOTE FOR THE ORCHESTRATOR: qits-workspaces ships no MCP surface today, so these five tools are
  // currently unowned. The repository-scoped tools that remain (listRepositories, listBranches,
  // listCommits, submodules, pull/push/sync) are unchanged.

  // --- Scoping --------------------------------------------------------------

  /** See {@link ProjectScopeGuard#requireRepoInProject}. */
  private Repository requireRepoInProject(String repoId) {
    return scopeGuard.requireRepoInProject(repoId);
  }
}
