package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.TaskService;
import eu.wohlben.qits.entities.dto.TaskDto;
import eu.wohlben.qits.entities.mapper.WorkEntityMapper;
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

/** A single task. */
@Path("/tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TaskController {

  @Inject TaskService taskService;

  /** One mapper where there were four; a task's parent travels beside the row as a {@code Nested}. */
  @Inject WorkEntityMapper workEntityMapper;

  @Inject SecurityIdentity identity;

  @Inject EpicsTopicHints hints;

  /**
   * The qualified id {@code <project-slug>-<number>} every answer here carries. One batched slug
   * lookup per listing; see {@link eu.wohlben.qits.projects.api.QualifiedEntityIds}, and
   * {@code DispatchedWorkspaces} for why the crossing into {@code domain} lives in that package.
   */
  @Inject eu.wohlben.qits.projects.api.QualifiedEntityIds qualifiedIds;

  public record GetTaskRequest() {
    public record Response(TaskDto task) {}
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}")
  public GetTaskRequest.Response get(@PathParam("id") String id) {
    var task = taskService.get(id);
    return new GetTaskRequest.Response(
        qualifiedIds.qualify(workEntityMapper.toTaskDto(task.entity(), task.parentId())));
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

  @PUT
  @Path("/{id}")
  public UpdateTaskRequest.Response update(
      @PathParam("id") String id, @Valid UpdateTaskRequest request) {
    var task =
        taskService.update(
            id,
            request.title(),
            request.description(),
            request.dependsOnTaskId(),
            request.clearDependsOn(),
            request.implementedAt(),
            request.clearImplementedAt(),
            EntitiesPrincipal.changedBy(identity));
    hints.fire(hints.projectOfFeature(task.parentId()));
    return new UpdateTaskRequest.Response(
        qualifiedIds.qualify(workEntityMapper.toTaskDto(task.entity(), task.parentId())));
  }

  public record DeleteTaskRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteTaskRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfTask(id);
    taskService.delete(id, EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeleteTaskRequest.Response(true);
  }
}
