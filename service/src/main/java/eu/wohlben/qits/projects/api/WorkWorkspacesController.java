package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.dto.WorkspaceReferenceDto;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The workspaces naming a work entity: {@code GET /projects/api/work/{qualifiedId}/workspaces}</b>
 * (qits-970, epic qits-965) — the {@code /work} home of the {@code workspaces} field {@code GET
 * /epics/{id}} and {@code GET /tickets/{id}} decorate their answers with, which the merged shape
 * {@code GET /work/{qualifiedId}} does not carry. Every workspace a dispatch stood on the entity's
 * branch, live and resolved alike, each with its status, through {@link DispatchedWorkspaces} — the
 * one place that asks qits-workspaces, under that port's never-throw contract: unreachable is an
 * empty list, the same answer as none. Only a ticket and an epic are dispatched, so any other
 * archetype answers empty. A read: {@code qits:agent} too. In {@code projects.api} because the port
 * is {@code domain}'s.
 */
@Path("/work/{qualifiedId}/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class WorkWorkspacesController {

  @Inject EntityIdResolver ids;

  @Inject DispatchedWorkspaces workspaces;

  @Schema(
      name = "WorkWorkspaces",
      description = "The workspaces a dispatch stood on a work entity's branch.")
  public record WorkWorkspaces(List<WorkspaceReferenceDto> workspaces) {}

  @GET
  @Operation(
      operationId = "listWorkWorkspaces",
      summary = "The workspaces naming a work entity",
      description =
          "Every workspace a dispatch stood on the entity's branch, live and resolved alike, each"
              + " with its status; empty when qits-workspaces cannot be asked. The path names the"
              + " entity by qualified id (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The workspaces",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkWorkspaces.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkWorkspaces list(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkWorkspaces(workspaces.referencing(ids.resolve(qualifiedId)));
  }
}
