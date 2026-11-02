package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.WorkEntityDoors;
import eu.wohlben.qits.entities.control.EntitySummary;
import eu.wohlben.qits.entities.dto.WorkspaceReferenceDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The workspaces naming every ticket and epic of a project: {@code GET
 * /projects/api/projects/{project}/work/workspaces}</b> (qits-1815) — {@link
 * WorkWorkspacesController}'s answer for a whole project in one read. The SPA's board used to ask
 * that door once per root work item (some 730 calls for {@code qits}), each its own call to
 * qits-workspaces; this is one read here and one lookup through {@link
 * DispatchedWorkspaces#referencingAll}, which the adapter splits into as many requests as the far
 * side's request line needs.
 *
 * <p>{@code {project}} is the project's id or its slug, resolved by the very listing {@code
 * ProjectWorkController} answers ({@link WorkEntityDoors#list}) — an unknown one is a 404. The
 * answer covers the project's tickets and epics in <b>every</b> status: one entry per row that has
 * workspaces, naming the row by its id and its qualified id, in that listing's tree order; a row
 * with none has no entry. The port's never-throw contract holds here as it does one row at a time:
 * qits-workspaces being away is an empty list, the same answer as nobody working on anything.
 *
 * <p><b>A list of entries, not a map keyed by id</b>, and that is for the contracts: the golden-
 * master recorder freezes UUIDs in values but refuses one in a key, since no JSONPath names "the
 * key this id was frozen to" — and the frontend's pacts are built from those golden masters.
 *
 * <p>A sibling class of {@code ProjectWorkController} ({@code @Path("/projects/{project}/work")})
 * rather than a sub-resource of it: that class lives in {@code entities.api}, and this answer needs
 * {@link DispatchedWorkspaces}, which is {@code domain}'s port — the crossing {@link
 * DispatchedWorkspaces} explains. JAX-RS matches the longer literal template first, so the two do
 * not compete. A read: {@code qits:agent} too, as on the listing beside it.
 */
@Path("/projects/{project}/work/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
public class ProjectWorkWorkspacesController {

  @Inject WorkEntityDoors doors;

  @Inject DispatchedWorkspaces workspaces;

  @Schema(
      name = "WorkEntityWorkspaces",
      description = "The workspaces a dispatch stood on one ticket's or epic's branch.")
  public record WorkEntityWorkspaces(
      @Schema(description = "The work entity's id (UUID).") String workId,
      @Schema(description = "The work entity's qualified id, <projectSlug>-<n>.")
          String qualifiedId,
      @Schema(description = "Its workspaces, live and resolved alike; never empty.")
          List<WorkspaceReferenceDto> workspaces) {}

  @Schema(
      name = "ProjectWorkWorkspaces",
      description =
          "The workspaces a dispatch stood on each of a project's tickets and epics: one entry per"
              + " entity that has any, in the project listing's tree order.")
  public record ProjectWorkWorkspaces(List<WorkEntityWorkspaces> workspaces) {}

  @GET
  @Operation(
      operationId = "listProjectWorkWorkspaces",
      summary = "The workspaces naming every ticket and epic of a project",
      description =
          "For each of the project's tickets and epics, in every status, every workspace a dispatch"
              + " stood on its branch, live and resolved alike, each with its status: one entry per"
              + " entity that has any, naming it by id and qualified id, in the project listing's"
              + " tree order. An entity with no workspaces has no entry; the list is empty when"
              + " qits-workspaces cannot be asked. The project is its id or its slug.")
  @APIResponse(
      responseCode = "200",
      description = "The workspaces, one entry per work entity",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ProjectWorkWorkspaces.class)))
  @APIResponse(responseCode = "404", description = "No such project")
  public ProjectWorkWorkspaces list(@PathParam("project") String project) {
    // The listing the board draws, so the project resolves (or 404s) and orders as it does there.
    List<EntitySummary> rows = doors.list(project, null, null, null);
    Map<String, List<WorkspaceReferenceDto>> byId = workspaces.referencingAll(rows);
    return new ProjectWorkWorkspaces(
        rows.stream()
            .filter(row -> byId.containsKey(row.id()))
            .map(row -> new WorkEntityWorkspaces(row.id(), row.qualifiedId(), byId.get(row.id())))
            .toList());
  }
}
