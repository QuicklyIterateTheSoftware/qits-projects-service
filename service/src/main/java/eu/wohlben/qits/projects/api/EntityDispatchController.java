package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesPrincipal;
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
import java.util.Locale;

/**
 * <b>The one dispatch door</b> (qits-394): put an agent on any entity with a lifecycle — an epic or
 * a ticket — at the phase its status implies, as the whole flow or as one phase.
 *
 * <pre>
 *   POST /projects/api/entities/{id}/dispatch   {"mode":"FLOW"|"PHASE"}  → {"dispatch": EntityDispatchDto}
 *   GET  /projects/api/entities/{id}/dispatch                            → {"state": EntityDispatchStateDto}
 * </pre>
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

  @POST
  @Path("/{id}/dispatch")
  public DispatchRequest.Response dispatch(
      @PathParam("id") String id, DispatchRequest request) {
    DispatchMode mode = modeOf(request);
    EntityDispatch.Outcome outcome =
        dispatch.dispatch(id, mode, EntitiesPrincipal.changedBy(identity));
    return new DispatchRequest.Response(outcome.toDto());
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
