package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntitySummary;
import eu.wohlben.qits.entities.entity.WorkOutcome;
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
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A project's work of every archetype: {@code GET /projects/api/projects/{project}/work}</b>
 * (qits-969) — the work family's listing, the archetype a filter rather than a path segment.
 *
 * <p>{@code {project}} is the project's id or its slug ({@code qits}). The whole planning tree,
 * flat — each root oldest first, its descendants depth-first in position order — narrowed by the
 * optional {@code archetype}, {@code status} and {@code parent} (qualified id or UUID; the direct
 * children of one entity), and since qits-248 by {@code outcome} (why a DROPPED item was closed)
 * and {@code supersededBy} (qualified id or UUID; the items naming that entity as what answers for
 * them — the reverse link). The answer is {@code {"entities": [...]}}, each a qualified {@link
 * EntitySummary} without its description. {@link WorkEntityDoors#list} is the implementation,
 * shared with {@code GET /projects/{projectId}/entities}. A read: {@code qits:agent}, unbound.
 */
@Path("/projects/{project}/work")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
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
              + " the direct children of one parent (qualified id or UUID), one drop outcome, and"
              + " the items naming one entity (qualified id or UUID) as their supersededBy — what"
              + " that entity closed as a duplicate, resolved elsewhere or superseded. The project is"
              + " its id or its slug.")
  @APIResponse(
      responseCode = "200",
      description = "The work entities",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkList.class)))
  @APIResponse(
      responseCode = "400",
      description = "An archetype, a status or an outcome naming none")
  @APIResponse(
      responseCode = "404",
      description = "No such project, no such parent, or no such supersededBy entity")
  public WorkList list(
      @PathParam("project") String project,
      @QueryParam("archetype") String archetype,
      @QueryParam("status") String status,
      @QueryParam("parent") String parent,
      @Parameter(
              description =
                  "Only the DROPPED items closed with this outcome: DUPLICATE, RESOLVED_ELSEWHERE,"
                      + " SUPERSEDED, OBSOLETE, INVALID or WONT_DO.",
              schema = @Schema(implementation = WorkOutcome.class))
          @QueryParam("outcome")
          String outcome,
      @Parameter(
              description =
                  "Only the items naming this entity (qualified id or UUID) as their supersededBy.")
          @QueryParam("supersededBy")
          String supersededBy) {
    return new WorkList(doors.list(project, archetype, status, parent, outcome, supersededBy));
  }
}
