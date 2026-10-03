package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.dto.FeatureDto;
import eu.wohlben.qits.entities.dto.TaskDto;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.validation.NotBlankIfPresent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * A single feature and its task collection — a thin resource over {@link EntityRoutes}, the one
 * implementation behind every per-archetype route (qits-399).
 */
@Path("/features")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class FeatureController {

  @Inject EntityRoutes routes;

  @Inject SecurityIdentity identity;

  // --- Feature ---

  public record GetFeatureRequest() {
    public record Response(FeatureDto feature) {}
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}")
  public GetFeatureRequest.Response get(@PathParam("id") String id) {
    return new GetFeatureRequest.Response(routes.get(routes.features(), id));
  }

  /**
   * Partial update: a null {@code title}/{@code description} leaves it unchanged. The nullable
   * dependency and ship-date change only when their {@code clear*} flag is true (→ cleared) or a
   * non-null value is supplied (→ set) — so a title-only edit can't silently un-ship a feature or
   * drop its dependency. Setting the ship-date moves the feature to IMPLEMENTED and clearing it
   * takes an IMPLEMENTED feature back, in the same write (qits-763).
   */
  public record UpdateFeatureRequest(
      @NotBlankIfPresent String title,
      String description,
      String dependsOnFeatureId,
      boolean clearDependsOn,
      Instant implementedOn,
      boolean clearImplementedOn) {
    public record Response(FeatureDto feature) {}
  }

  /**
   * Editing a feature takes {@code qits:agent}, bound to the agent's own project: the {@code
   * update_feature} MCP tool already performs this write for an agent. The project is resolved from
   * the feature before the write, so an id naming nothing is the 404 it always was — see {@link
   * EntitiesAgentAccess}.
   */
  @PUT
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public UpdateFeatureRequest.Response update(
      @PathParam("id") String id, @Valid UpdateFeatureRequest request) {
    return new UpdateFeatureRequest.Response(
        routes.update(
            routes.features(),
            id,
            EntityWrite.nodeEdit(
                request.title(),
                request.description(),
                request.dependsOnFeatureId(),
                request.clearDependsOn(),
                request.implementedOn(),
                request.clearImplementedOn()),
            identity));
  }

  public record DeleteFeatureRequest() {
    public record Response(boolean success) {}
  }

  /**
   * Deleting a feature takes {@code qits:agent}, bound to the agent's own project: the {@code
   * remove_feature} MCP tool already performs this write for an agent. This is the one delete on
   * this surface a tool serves, which is why it is granted where the epic's, the ticket's and the
   * comment's are not — see {@link EntitiesAgentAccess}.
   */
  @DELETE
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public DeleteFeatureRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from, and the binding
    // needs the project while the row still exists.
    routes.delete(routes.features(), id, true, identity);
    return new DeleteFeatureRequest.Response(true);
  }

  // --- Tasks under a feature ---

  public record ListTasksRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(TaskDto task) {}
    }
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{featureId}/tasks")
  public ListTasksRequest.Response listTasks(@PathParam("featureId") String featureId) {
    return new ListTasksRequest.Response(
        routes.listChildren(routes.tasks(), Archetype.FEATURE, featureId).stream()
            .map(ListTasksRequest.Response.Entry::new)
            .toList());
  }

  public record CreateTaskRequest(
      @NotBlank String repositoryId,
      @NotBlank String title,
      String description,
      String dependsOnTaskId) {
    public record Response(TaskDto task) {}
  }

  /**
   * Adding a task takes {@code qits:agent}, bound to the agent's own project: the {@code add_task}
   * MCP tool already performs this write for an agent. The binding is against the feature's project,
   * and the repository has to be in that project too — a 404 for none, a 400 for one elsewhere. See
   * {@link EntitiesAgentAccess}.
   */
  @POST
  @Path("/{featureId}/tasks")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CreateTaskRequest.Response createTask(
      @PathParam("featureId") String featureId, @Valid CreateTaskRequest request) {
    return new CreateTaskRequest.Response(
        routes.createChild(
            routes.tasks(),
            Archetype.FEATURE,
            featureId,
            EntityWrite.task(
                request.repositoryId(),
                request.title(),
                request.description(),
                request.dependsOnTaskId()),
            identity));
  }
}
