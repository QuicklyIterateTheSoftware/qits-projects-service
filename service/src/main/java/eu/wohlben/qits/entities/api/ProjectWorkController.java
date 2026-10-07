package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntitySummary;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A project's work of every archetype: {@code GET /projects/api/projects/{project}/work}</b>
 * (qits-969) — the work family's listing, the archetype a filter rather than a path segment.
 *
 * <p>{@code {project}} is the project's id or its slug ({@code qits}). The whole planning tree,
 * flat — each root oldest first, its descendants depth-first in position order — narrowed by the
 * optional {@code archetype}, {@code status} and {@code parent} (qualified id or UUID; the direct
 * children of one entity). The answer is {@code {"entities": [...]}}, each a qualified {@link
 * EntitySummary} without its description. {@link WorkEntityDoors#list} is the implementation,
 * shared with {@code GET /projects/{projectId}/entities}. A read: {@code qits:agent}, unbound.
 */
@Path("/projects/{project}/work")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class ProjectWorkController {

  @Inject WorkEntityDoors doors;

  @Schema(name = "WorkList", description = "A project's work entities, in tree order.")
  public record WorkList(List<EntitySummary> entities) {}

  @GET
  @Operation(
      operationId = "listProjectWork",
      summary = "List a project's work of every archetype",
      description =
          "The project's whole planning tree, flat: each root oldest first, its descendants"
              + " depth-first in position order. Optionally narrowed to one archetype, one status,"
              + " and the direct children of one parent (qualified id or UUID). The project is its id"
              + " or its slug.")
  @APIResponse(
      responseCode = "200",
      description = "The work entities",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkList.class)))
  @APIResponse(responseCode = "400", description = "An archetype or a status naming none")
  @APIResponse(responseCode = "404", description = "No such project, or no such parent")
  public WorkList list(
      @PathParam("project") String project,
      @QueryParam("archetype") String archetype,
      @QueryParam("status") String status,
      @QueryParam("parent") String parent) {
    return new WorkList(doors.list(project, archetype, status, parent));
  }
}
