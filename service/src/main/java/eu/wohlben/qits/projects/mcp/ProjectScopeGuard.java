package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The scoping guard shared by every tool class mounted on the "repository" MCP server ({@link
 * RepositoryMcpTools}): resolves the session's {@link ProjectScope} and rejects any repository
 * outside it, so no tool can operate across project boundaries.
 *
 * <p><b>Two rules, not one, and picking the wrong one is a bug either way.</b> A repoId naming a
 * git target the call is about to read is guarded by {@link #requireRepoInProject}, which honours
 * the session's optional narrowing to a single repository. A repoId that is a stored reference —
 * which repository some planned work belongs to — is guarded by {@link
 * #requireRepoInProjectUnnarrowed}, which asks for project membership alone.
 */
@ApplicationScoped
public class ProjectScopeGuard {

  @Inject ProjectScope scope;

  @Inject ProjectService projectService;

  /**
   * <b>The slug of the project this session is scoped to</b> — the qualifier in {@code
   * <project-slug>-<number>}, which is the form every entity-shaped MCP return carries.
   *
   * <p>It lives here for the reason the whole class does: {@code entities} depends on {@code domain}
   * nowhere and the slug is {@code domain}'s, in a different physical database, so the tool classes
   * cross through this guard rather than each reaching for {@code ProjectService} themselves.
   *
   * <p><b>Resolve it ONCE per tool call</b> and hand it to the summarizers. A listing of forty
   * entities is forty rows of one project, and asking per row would be forty identical queries.
   */
  public String scopedProjectSlug() {
    return projectService.get(scope.requireProjectId()).slug;
  }

  /**
   * <b>The guard for a repoId that names a GIT TARGET</b> — a repository this call is about to read
   * branches, commits or diffs from. It is the strict one: the repository must be in the scoped
   * project, and when the session is narrowed to a single repository ({@code X-QITS-Repository})
   * every other repository is refused as well. Throws {@link NotFoundException} otherwise (which
   * also covers a repository that does not exist).
   *
   * <p><b>A stored reference to a repository wants {@link #requireRepoInProjectUnnarrowed}
   * instead.</b> A task's {@code repositoryId} says which repository the planned work belongs to,
   * not which one this call touches, and a refinement session stands on the project's wrapper
   * repository — so narrowing that id would leave an estate-spanning epic unable to file its tasks
   * anywhere but the wrapper. Project membership is the rule for a reference; the narrowing is the
   * rule for a target.
   */
  public Repository requireRepoInProject(String repoId) {
    var scopedRepo = scope.repositoryId();
    if (scopedRepo.isPresent() && !scopedRepo.get().equals(repoId)) {
      throw new NotFoundException("Repository not in this session's scope: " + repoId);
    }
    return repoInProject(repoId);
  }

  /**
   * <b>The guard for a repoId that is a stored REFERENCE</b> — a task's repository, and anything
   * else that records which repository work belongs to rather than naming one to read from. It
   * checks project membership and nothing else: a session narrowed to one repository may still
   * write a reference to any sibling of the scoped project, because a session narrowed to a wrapper
   * is still planning work for the whole estate.
   *
   * <p>That is the same rule the REST door performing the identical write applies ({@code
   * FeatureController.createTask}), and the same set of ids {@code
   * EntityMcpTools.transition_entities} already accepts for a TASK. Reading a git target is the
   * other case and takes {@link #requireRepoInProject}.
   */
  public Repository requireRepoInProjectUnnarrowed(String repoId) {
    return repoInProject(repoId);
  }

  /** The membership lookup both guards share, so the two cannot drift apart. */
  private Repository repoInProject(String repoId) {
    return projectService.getRepositories(scope.requireProjectId()).stream()
        .filter(r -> r.id.equals(repoId))
        .findFirst()
        .orElseThrow(
            () -> new NotFoundException("Repository not found in this project: " + repoId));
  }
}
