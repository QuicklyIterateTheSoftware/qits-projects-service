package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.WorkProgressController.WorkProgressAnswer;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A work entity's dispatch: {@code /projects/api/work/{qualifiedId}/dispatch}</b> (qits-970,
 * epic qits-965) — the {@code /work} home of the deleted {@code /entities/{id}/dispatch}, addressed by qualified
 * id or UUID ({@link EntityIdResolver#resolve}).
 *
 * <pre>
 *   POST /work/{qualifiedId}/dispatch   {"mode":"FLOW"|"PHASE"}  → {"dispatch": EntityDispatchDto}
 *                                        on a campaign            → {"progress": CampaignProgressDto}
 *   GET  /work/{qualifiedId}/dispatch                             → {"state": EntityDispatchStateDto}
 * </pre>
 *
 * <p>Every rule is {@link DispatchDoors}' (it was shared with the deleted {@code /entities} door), and so are the
 * roles: the press is {@code qits:admin} alone (standing a workspace up is a person's press), the
 * read admits {@code qits:agent}. In {@code projects.api} for {@link EntityDispatch}'s reason: it
 * needs {@code domain}.
 */
@Path("/work/{qualifiedId}/dispatch")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent"})
public class WorkDispatchController {

  @Inject EntityIdResolver ids;

  @Inject DispatchDoors doors;

  @Inject SecurityIdentity identity;

  /** The press's body: which of the two actions. */
  @Schema(name = "WorkDispatchRequest", description = "Which of the two dispatch actions to press.")
  public record WorkDispatchRequest(
      @Schema(
              required = true,
              description =
                  "FLOW (Dispatch: run the whole flow) or PHASE (Run the next phase: one, then"
                      + " stop)")
          String mode) {}

  @Schema(name = "WorkDispatchAnswer", description = "The dispatch a press made.")
  public record WorkDispatchAnswer(EntityDispatchDto dispatch) {}

  @Schema(name = "WorkDispatchState", description = "What a press would start now.")
  public record WorkDispatchState(EntityDispatchStateDto state) {}

  @POST
  @Operation(
      operationId = "dispatchWork",
      summary = "Put an agent on an epic or a ticket, or start a campaign",
      description =
          "mode PHASE runs the one phase the entity's status starts; mode FLOW runs that phase and"
              + " the ones after it until a status starts none. On a campaign the press is its"
              + " start. The path names the entity by qualified id (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description =
          "The dispatch made, or — for a campaign — its progress as the start press left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema =
                  @Schema(oneOf = {WorkDispatchAnswer.class, WorkProgressAnswer.class})))
  @APIResponse(responseCode = "400", description = "A missing or unknown mode")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description =
          "A status that starts no phase, a feature or a task, a blocked entity, a project with no"
              + " wrapper")
  public Response dispatch(
      @PathParam("qualifiedId") String qualifiedId, WorkDispatchRequest request) {
    String mode = request == null ? null : request.mode();
    DispatchMode.parse(mode); // the 400 first, before the entity is looked up
    DispatchDoors.Pressed pressed = doors.press(identity, ids.resolve(qualifiedId).id, mode);
    if (pressed.progress() != null) {
      return Response.ok(new WorkProgressAnswer(pressed.progress())).build();
    }
    return Response.ok(new WorkDispatchAnswer(pressed.dispatch())).build();
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "getWorkDispatch",
      summary = "What a dispatch press would start now",
      description =
          "The entity's status, the phase a press would start (or null), whether it is blocked and"
              + " dispatchable, and the mode the last press recorded. The path names the entity by"
              + " qualified id or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The dispatch state",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkDispatchState.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkDispatchState state(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkDispatchState(doors.state(ids.resolve(qualifiedId).id));
  }
}
