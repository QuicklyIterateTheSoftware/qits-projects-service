package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.CampaignController.CampaignProgressResponse;
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
 * <b>The one dispatch door</b> (qits-394): put an agent on any entity with a lifecycle — an epic or
 * a ticket — at the phase its status implies, as the whole flow or as one phase.
 *
 * <pre>
 *   POST /projects/api/entities/{id}/dispatch   {"mode":"FLOW"|"PHASE"}  → {"dispatch": EntityDispatchDto}
 *                                                on a campaign: {"mode":"FLOW"} → {"progress": CampaignProgressDto}
 *   GET  /projects/api/entities/{id}/dispatch                            → {"state": EntityDispatchStateDto}
 * </pre>
 *
 * <p><b>On a campaign the press is its start</b> (qits-417): the branch is in {@link
 * DispatchDoors} (shared with {@code /work/{qualifiedId}/dispatch} since qits-970), not in {@link
 * EntityDispatch}, because neither {@link DispatchRequest.Response} nor {@link
 * EntityDispatch.Outcome} can carry a campaign's answer — see {@code CampaignStarter}. The read
 * answers a campaign too, with {@code dispatchable} meaning REFINED and {@code nextPhase} {@code
 * start} or, while a start is active, {@code recheck}.
 *
 * <p><b>Addressed by entity id</b>, like {@code POST /entities/transition} and every per-archetype
 * route: the id is the one key every archetype shares, and the SPA's number-addressed detail route
 * resolves a number to a row before it presses anything. Under {@code /entities} beside {@code
 * EntityTransitionController} and {@code EntityArchetypesController} — several resources sharing a
 * path is fine while no method path collides — but in {@code projects.api} for {@link
 * EntityDispatch}'s reason: it needs {@code domain}.
 *
 * <p><b>The press is {@code qits:admin} alone</b>, exactly as the two doors it replaces: standing a
 * workspace up is a person's press. <b>The read admits {@code qits:agent} too</b>, by the standing
 * rule that an agent reads everywhere ({@code AgentReadAccessTest}); it starts nothing and says only
 * what the status already implies.
 *
 * <p><b>The GET is how the SPA learns the next phase</b> and is the least invasive place for it: no
 * existing DTO, no registry document and no MCP result shape moves, and the server keeps sole
 * ownership of the status→phase rule ({@code PhasePrompts.phaseOf}). A page that draws the two
 * actions asks once per entity it draws them for.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityDispatchController {

  /** The press and the read, shared with {@code /work/{qualifiedId}/dispatch} (qits-970). */
  @Inject DispatchDoors doors;

  @Inject SecurityIdentity identity;

  /**
   * The press. {@code mode} is required — the two actions are both reasonable defaults, so a caller
   * that named neither has not said what it wants.
   *
   * @param mode {@code FLOW} (Dispatch: run the whole flow) or {@code PHASE} (Run the next phase)
   */
  public record DispatchRequest(String mode) {
    public record Response(EntityDispatchDto dispatch) {}
  }

  /** The read: what a press would start now. */
  public record DispatchStateRequest() {
    public record Response(EntityDispatchStateDto state) {}
  }

  /**
   * The press. On a campaign it is the campaign's start ({@code CampaignStarter}), answering {@link
   * CampaignProgressResponse} — {@code {"progress": CampaignProgressDto}}, the same wrapper {@code
   * GET /campaigns/{id}/progress} answers (qits-418); on anything else the one dispatch path, answering {@link
   * DispatchRequest.Response}. Hence a {@link Response} rather than either record.
   */
  @POST
  @Path("/{id}/dispatch")
  @Operation(
      operationId = "dispatchEntity",
      summary = "Put an agent on an epic or a ticket, or start a campaign",
      description =
          "mode PHASE runs the one phase the entity's status starts; mode FLOW runs that phase and"
              + " the ones after it until a status starts none. The archetype registry's phases"
              + " say which phases those are, per status. On a campaign the press is its start.")
  @APIResponse(
      responseCode = "200",
      description =
          "The dispatch made, or — for a campaign — its progress as the start press left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema =
                  @Schema(oneOf = {DispatchRequest.Response.class, CampaignProgressResponse.class})))
  public Response dispatch(@PathParam("id") String id, DispatchRequest request) {
    DispatchDoors.Pressed pressed =
        doors.press(identity, id, request == null ? null : request.mode());
    if (pressed.progress() != null) {
      return Response.ok(new CampaignProgressResponse(pressed.progress())).build();
    }
    return Response.ok(new DispatchRequest.Response(pressed.dispatch())).build();
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}/dispatch")
  public DispatchStateRequest.Response state(@PathParam("id") String id) {
    return new DispatchStateRequest.Response(doors.state(id));
  }
}
