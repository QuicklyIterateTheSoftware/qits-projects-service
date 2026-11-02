package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.dto.WorkspaceReferenceDto;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
 * <p>{@code {project}} is the project's id or its slug, resolved as {@code ProjectWorkController}
 * resolves it — an unknown one is a 404. The answer is keyed by the work entity's row id (UUID) and
 * covers the project's tickets and epics in <b>every</b> status; a row with no workspaces has no
 * key. The port's never-throw contract holds here as it does one row at a time: qits-workspaces
 * being away is an empty map, the same answer as nobody working on anything.
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

  @Inject EntityIdResolver ids;

  @Inject WorkEntityRepository entities;

  @Inject DispatchedWorkspaces workspaces;

  @Schema(
      name = "ProjectWorkWorkspaces",
      description =
          "The workspaces a dispatch stood on each of a project's tickets and epics, keyed by the"
              + " work entity's id; an entity with none has no key.")
  public record ProjectWorkWorkspaces(Map<String, List<WorkspaceReferenceDto>> workspaces) {}

  @GET
  @Operation(
      operationId = "listProjectWorkWorkspaces",
      summary = "The workspaces naming every ticket and epic of a project",
      description =
          "For each of the project's tickets and epics, in every status, every workspace a dispatch"
              + " stood on its branch, live and resolved alike, each with its status — keyed by the"
              + " work entity's id (UUID). An entity with no workspaces has no key; the map is empty"
              + " when qits-workspaces cannot be asked. The project is its id or its slug.")
  @APIResponse(
      responseCode = "200",
      description = "The workspaces, by work entity id",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ProjectWorkWorkspaces.class)))
  @APIResponse(responseCode = "404", description = "No such project")
  public ProjectWorkWorkspaces list(@PathParam("project") String project) {
    String projectId = ids.resolveProject(project).id;
    // The rows without their description: only the archetype and the id are read here.
    List<WorkEntity> rows =
        QuarkusTransaction.requiringNew()
            .call(() -> entities.listByProjectWithoutDescription(projectId));
    return new ProjectWorkWorkspaces(workspaces.referencingAll(rows));
  }
}
