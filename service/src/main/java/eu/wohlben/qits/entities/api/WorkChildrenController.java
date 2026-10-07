package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A work entity's children: {@code /projects/api/work/{qualifiedId}/children}</b> (qits-970,
 * epic qits-965) — the {@code /work} home of the deleted {@code /epics/{epicId}/features} and {@code
 * /features/{featureId}/tasks}, one route for both edges: an epic's children are its features, a
 * feature's its tasks, and any other kind has none (an empty list to read, a 409 to add to).
 *
 * <p>{@code {qualifiedId}} is the parent's qualified id or UUID ({@link EntityIdResolver#resolve}).
 * The answers are the merged shape every {@code /work} read answers, qualified. The add is {@code
 * POST /work}'s create of the child kind with the parent the path names ({@link
 * WorkEntityDoors#createChild}), so a dependency takes a qualified id and every refusal is the
 * create's. The roles are the two edges': both admit {@code qits:agent}, the add bound to the
 * parent's project — {@code add_feature} and {@code add_task} perform it over MCP.
 */
@Path("/work/{qualifiedId}/children")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class WorkChildrenController {

  @Inject EntityIdResolver ids;

  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

  @Schema(
      name = "WorkChildren",
      description = "A work entity's direct children, in membership order.")
  public record WorkChildren(List<TransitionedEntity> children) {}

  /** The documentation of the add's body, and only that: it is read as a {@link JsonNode}. */
  @Schema(
      name = "WorkChildCreate",
      description =
          "A new child: the child archetype's create schema (FEATURE under an epic, TASK under a"
              + " feature) without archetype, parent and project, which the path decides.")
  public record WorkChildCreate(
      @Schema(required = true, description = "The label.") String title,
      @Schema(description = "The long-form Markdown body.") String description,
      @Schema(description = "A task's repository, in the parent's project. Required for a task.")
          String repositoryId,
      @Schema(description = "A sibling dependency, by qualified id or UUID.") String dependsOn) {}

  @GET
  @Operation(
      operationId = "listWorkChildren",
      summary = "A work entity's children",
      description =
          "An epic's features or a feature's tasks, in membership order, in the merged shape with"
              + " their qualified ids; empty for a kind nothing hangs under. The path names the"
              + " parent by qualified id (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The children",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkChildren.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkChildren list(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkChildren(doors.children(ids.resolve(qualifiedId)));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "createWorkChild",
      summary = "Add a child to a work entity",
      description =
          "A feature under an epic, a task under a feature: the child kind's create, placed under"
              + " the entity the path names. Its status starts REPORTED; the parent's plan must"
              + " still be REPORTED. Answers the child in the merged shape, with its qualified id.")
  @APIResponse(
      responseCode = "201",
      description = "The child as created",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "A body the child's create schema refuses, or one naming archetype, parent or project")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No entity, repository or dependency")
  @APIResponse(
      responseCode = "409",
      description = "A kind nothing hangs under, or a parent whose plan is frozen")
  public Response create(
      @PathParam("qualifiedId") String qualifiedId,
      @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON,
                      schema = @Schema(implementation = WorkChildCreate.class)))
          JsonNode body) {
    return Response.status(Response.Status.CREATED)
        .entity(doors.createChild(identity, ids.resolve(qualifiedId), body))
        .build();
  }
}
