package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.dto.EpicDto;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Epics collection under a project — a thin resource over {@link EntityRoutes} (qits-399). {@code
 * projectId} is validated against {@code domain} there (the entities module has no dependency on
 * {@code domain}) so a bad project yields a clean 404.
 */
@Path("/projects/{projectId}/epics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class ProjectEpicsController {

  @Inject EntityRoutes routes;

  @Inject SecurityIdentity identity;

  public record ListEpicsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(EpicDto epic) {}
    }
  }

  /**
   * The project's epics, oldest first, optionally narrowed to one phase. {@code status} is the
   * status name; a value naming none is a 400, so a typo in the filter does not read as "no epics".
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public ListEpicsRequest.Response list(
      @PathParam("projectId") String projectId, @QueryParam("status") String status) {
    return new ListEpicsRequest.Response(
        routes.listRoots(routes.epics(), projectId, status).stream()
            .map(ListEpicsRequest.Response.Entry::new)
            .toList());
  }

  public record CreateEpicRequest(@NotBlank String title, String description) {
    public record Response(EpicDto epic) {}
  }

  /**
   * Filing an epic takes {@code qits:agent}, bound to the agent's own project: the {@code
   * propose_epic} MCP tool already performs this write for an agent, and the path names the project
   * the binding is against. See {@link EntitiesAgentAccess} for the rule.
   */
  @POST
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CreateEpicRequest.Response create(
      @PathParam("projectId") String projectId, @Valid CreateEpicRequest request) {
    return new CreateEpicRequest.Response(
        routes.createRoot(
            routes.epics(),
            projectId,
            EntityWrite.epic(request.title(), request.description()),
            identity));
  }
}
