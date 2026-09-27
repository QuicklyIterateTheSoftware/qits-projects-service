package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.dto.TaskDto;
import eu.wohlben.qits.projects.validation.NotBlankIfPresent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;

/** A single task — a thin resource over {@link EntityRoutes} (qits-399). */
@Path("/tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TaskController {

  @Inject EntityRoutes routes;

  @Inject SecurityIdentity identity;

  public record GetTaskRequest() {
    public record Response(TaskDto task) {}
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}")
  public GetTaskRequest.Response get(@PathParam("id") String id) {
    return new GetTaskRequest.Response(routes.get(routes.tasks(), id));
  }

  /**
   * Partial update: a null {@code title}/{@code description} leaves it unchanged. The nullable
   * dependency and completion marker change only when their {@code clear*} flag is true (→ cleared)
   * or a non-null value is supplied (→ set) — so a title-only edit can't silently un-complete a
   * task or drop its dependency.
   */
  public record UpdateTaskRequest(
      @NotBlankIfPresent String title,
      String description,
      String dependsOnTaskId,
      boolean clearDependsOn,
      Instant implementedAt,
      boolean clearImplementedAt) {
    public record Response(TaskDto task) {}
  }

  /**
   * Editing a task takes {@code qits:agent}, bound to the agent's own project: {@code update_task}
   * and {@code mark_task_implemented} both perform this write for an agent over the MCP surface. The
   * project is resolved from the task before the write, so an id naming nothing is the 404 it always
   * was — see {@link EntitiesAgentAccess}.
   */
  @PUT
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public UpdateTaskRequest.Response update(
      @PathParam("id") String id, @Valid UpdateTaskRequest request) {
    return new UpdateTaskRequest.Response(
        routes.update(
            routes.tasks(),
            id,
            EntityWrite.nodeEdit(
                request.title(),
                request.description(),
                request.dependsOnTaskId(),
                request.clearDependsOn(),
                request.implementedAt(),
                request.clearImplementedAt()),
            identity));
  }

  public record DeleteTaskRequest() {
    public record Response(boolean success) {}
  }

  /**
   * Deleting a task takes {@code qits:agent}, bound to the agent's own project: the {@code
   * remove_task} MCP tool already performs this write for an agent. See {@link EntitiesAgentAccess}.
   */
  @DELETE
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public DeleteTaskRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from, and the binding
    // needs the project while the row still exists.
    routes.delete(routes.tasks(), id, true, identity);
    return new DeleteTaskRequest.Response(true);
  }
}
