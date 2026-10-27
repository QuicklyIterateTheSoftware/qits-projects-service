package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A project's release requests, across every repository it owns — the one read that shows what is
 * waiting to be released without opening each repository in turn.
 *
 * <p><b>Why a route of its own rather than a query on the repository collection.</b> The
 * repository-scoped controller answers about one repository and is the machine peers' surface (the
 * door, the train). This is a person's overview: the scope is the project, the default is the
 * pending work — plus the handful that has just landed — rather than the whole history, and the rows
 * carry the repository's name because nothing else on the page says which repository a branch
 * belongs to.
 *
 * <p>The roles match {@code ReleaseRequestController}'s deliberately: reading a request tells you
 * what is being released and by whom, so the same two callers see it and no wider audience does.
 *
 * <p>An unknown project is a 404 rather than an empty list, the same reasoning the unknown-state
 * 400 in {@code ReleaseRequests} follows: on a page whose whole message is "nothing is pending
 * here", a mistyped scope must not be able to say it.
 */
@Path("/projects/{projectId}/release-requests")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
public class ProjectReleaseRequestsController {

  @Inject ReleaseRequests releaseRequests;

  @Inject ProjectService projects;

  public record ListProjectReleaseRequests() {
    public record Response(List<ReleaseRequestDto> requests) {}
  }

  /**
   * @param state which requests to answer: omitted means the open ones (PENDING, READY, FAILED,
   *     REJECTED, CONFLICTED — everything that can still move) plus the last 10 released, {@code
   *     all} means every state, and a state's own name narrows to it. A word naming no state is a
   *     400.
   */
  @GET
  @Operation(
      operationId = "listProjectReleaseRequests",
      summary = "The project's release requests, across all of its repositories",
      description =
          "Most recently moved first. With no state the answer is the open requests — the work"
              + " still waiting on somebody — plus the last 10 released, so that a release does not"
              + " leave the page the moment it lands. Pass state=all for the whole history, or a"
              + " state name (PENDING, READY, RELEASED, REJECTED, FAILED, CONFLICTED, WITHDRAWN) to"
              + " narrow to one.")
  public ListProjectReleaseRequests.Response list(
      @PathParam("projectId") String projectId, @QueryParam("state") String state) {
    projects.get(projectId); // 404 if the project does not exist
    return new ListProjectReleaseRequests.Response(
        releaseRequests.listByProject(projectId, state));
  }

  /**
   * The envelope of {@link #get}: {@code getReleaseRequest}'s shape. Its schema is <b>named</b>,
   * because one more record called {@code Response} is numbered among the others and renumbers every
   * anonymous response schema after it in {@code docs/openapi.yml} — and answering {@code
   * ReleaseRequestController}'s own record instead moves the numbering just the same.
   */
  @Schema(
      name = "ProjectReleaseRequest",
      description = "One release request, as getReleaseRequest answers it.")
  public record GetProjectReleaseRequest(ReleaseRequestDto request) {}

  /**
   * One request of this project, for a caller that knows the project and the request but not which
   * repository it was made in (qits-1203) — a ticket thread, a CLI line naming {@code
   * qits-qits-rr-131}. The answer is exactly {@code GET /repositories/{repoId}/release-requests/
   * {requestId}}'s, in the same {@code {"request": …}} envelope.
   *
   * @param ref the request's uuid, or its qualified id ({@code <repo>-rr-<n>}, any case). A request
   *     of another project is a 404 like one that does not exist: this route reads through the
   *     project, and a project does not own another's requests.
   */
  @GET
  @Path("/{ref}")
  @Operation(
      operationId = "getProjectReleaseRequest",
      summary = "One of the project's release requests, by its id or qualified id",
      description =
          "The same answer as the repository's own single read, addressed through the project:"
              + " {ref} is the request's uuid or its qualified id (e.g. qits-qits-rr-131). A"
              + " request that does not exist, or belongs to another project, is a 404.")
  public GetProjectReleaseRequest get(
      @PathParam("projectId") String projectId, @PathParam("ref") String ref) {
    return new GetProjectReleaseRequest(releaseRequests.getInProject(projectId, ref));
  }
}
