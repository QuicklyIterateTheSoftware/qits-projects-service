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
   * Ensures {@code repoId} names a repository inside the project this session is scoped to. When
   * the session is narrowed to a single repository, also rejects any other repository in the
   * project. Throws {@link NotFoundException} otherwise (also covering a non-existent repository).
   */
  public Repository requireRepoInProject(String repoId) {
    var scopedRepo = scope.repositoryId();
    if (scopedRepo.isPresent() && !scopedRepo.get().equals(repoId)) {
      throw new NotFoundException("Repository not in this session's scope: " + repoId);
    }
    return projectService.getRepositories(scope.requireProjectId()).stream()
        .filter(r -> r.id.equals(repoId))
        .findFirst()
        .orElseThrow(
            () -> new NotFoundException("Repository not found in this project: " + repoId));
  }
}
