package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.CampaignController.CampaignProgressResponse;
import eu.wohlben.qits.entities.api.CampaignViews;
import eu.wohlben.qits.entities.api.EntitiesPrincipal;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.campaignhost.CampaignStarter;
import eu.wohlben.qits.projects.error.DomainException;
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
import java.util.Locale;
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
 * <p><b>On a campaign the press is its start</b> (qits-417): the branch is here, not in {@link
 * EntityDispatch}, because neither {@link DispatchRequest.Response} nor {@link
 * EntityDispatch.Outcome} can carry a campaign's answer — see {@link CampaignStarter}. The read
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

  @Inject EntityDispatch dispatch;

  @Inject SecurityIdentity identity;

  /** A campaign's press is its start. */
  @Inject CampaignStarter starter;

  @Inject CampaignViews views;

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
   * The press. On a campaign it is the campaign's start ({@link CampaignStarter}), answering {@link
   * CampaignProgressResponse} — {@code {"progress": CampaignProgressDto}}, the same wrapper {@code
   * GET /campaigns/{id}/progress} answers (qits-418); on anything else the one dispatch path, answering {@link
   * DispatchRequest.Response}. Hence a {@link Response} rather than either record.
   */
  @POST
  @Path("/{id}/dispatch")
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
    DispatchMode mode = modeOf(request);
    String changedBy = EntitiesPrincipal.changedBy(identity);
    WorkEntity entity = dispatch.get(id); // 404
    if (entity.archetype == Archetype.CAMPAIGN) {
      CampaignService.ProgressRead started = starter.start(entity, mode, changedBy);
      return Response.ok(new CampaignProgressResponse(views.progress(started))).build();
    }
    EntityDispatch.Outcome outcome = dispatch.dispatch(id, mode, changedBy);
    return Response.ok(new DispatchRequest.Response(outcome.toDto())).build();
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}/dispatch")
  public DispatchStateRequest.Response state(@PathParam("id") String id) {
    return new DispatchStateRequest.Response(dispatch.state(id));
  }

  /** A missing or unknown mode is a 400 naming both words, never a guessed default. */
  private static DispatchMode modeOf(DispatchRequest request) {
    String raw = request == null ? null : request.mode();
    if (raw == null || raw.isBlank()) {
      throw new DomainException(
          400, "mode is required: FLOW (run the whole flow) or PHASE (run the next phase).");
    }
    try {
      return DispatchMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainException(
          400, "Unknown mode " + raw + ": FLOW (run the whole flow) or PHASE (run the next phase).");
    }
  }
}
