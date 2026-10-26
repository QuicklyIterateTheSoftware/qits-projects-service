package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The refinement lifecycle surface the refining route drives — the projects-side replacement for
 * the workspace verbs it used to call on qits-workspaces, keyed by refinement row id.
 *
 * <p><b>The way in is not here</b> (qits-395): {@code POST /work/{qualifiedId}/refinement} ({@link
 * WorkRefinementController}) opens a room on an epic or a ticket — find-or-create in one
 * idempotent POST, the adopt-existing dance an older create needed being the server's ordinary path
 * now. {@code POST /refinements} with
 * an {@code epicId} body was the older SPA's door onto the same {@link RefinementService#findOrCreate}
 * and was removed in qits-399, once the SPA called the entity door; the per-row verbs below are not
 * duplicates and stay.
 *
 * <p><b>A room is a session of its project's front desk (epic qits-770)</b>, driven by {@code ensure}
 * and {@code stop}: they stamp or clear the session's demand and push the project's desk daemon its
 * sessions list, answering the room. {@code ensure-container} is {@code ensure} under its old name,
 * answering an already-settled technical process beside the room, and stays only because
 * qits-platform-access-cli's pact still calls it ({@code qits work refinement open}); {@code
 * stop-container}, {@code recreate-container} and {@code active-process} went with the containers
 * (qits-1316) and answer 404 ({@code RefinementContainersRetiredTest}).
 *
 * <p>The lifecycle verbs admit the project's delivery desk ({@code qits:desk}, qits-1596) beside the
 * person and the coordinator, bound to the room's project ({@link RefinementAccess#reachWrite}).
 */
@Path("/refinements")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk"})
public class RefinementController {

  @Inject RefinementService refinements;

  @Inject SecurityIdentity identity;

  public record RefinementResponse(RefinementDto refinement) {}

  @Schema(
      name = "ProcessResponse",
      description =
          "A refinement room and a technical process id, already settled: ensure-container's"
              + " answer, kept for the consumers that still call that door.")
  public record ProcessResponse(RefinementDto refinement, String technicalProcessId) {}

  @Schema(name = "DiscardResponse", description = "A refinement room's teardown, done.")
  public record DiscardResponse(boolean success) {}

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Path("/{id}")
  public RefinementResponse get(@PathParam("id") long id) {
    return new RefinementResponse(
        RefinementDto.of(refinements.view(RefinementAccess.reach(refinements, identity, id))));
  }

  @POST
  @Path("/{id}/ensure")
  @Operation(
      operationId = "ensureRefinementSession",
      summary = "Ask for a refinement room's session",
      description =
          "Stamps the room's demand, so its session is wanted for the idle window, tells the"
              + " project's front desk (an ON_DEMAND desk is started for it) and sends the desk's"
              + " daemon its sessions list; answers the room. The path names the room by its row"
              + " id.")
  @APIResponse(
      responseCode = "200",
      description = "The room",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = RefinementResponse.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "The credential is bound to another entity, or the delivery desk reaches another"
              + " project's room")
  @APIResponse(responseCode = "404", description = "No refinement with this id")
  @APIResponse(
      responseCode = "409",
      description =
          "REFINEMENT_SESSIONS_FULL: the project already runs as many refinement sessions as"
              + " qits.projects.refinement-sessions-max allows; the message names them")
  public RefinementResponse ensureSession(@PathParam("id") long id) {
    RefinementAccess.reachWrite(refinements, identity, id);
    return new RefinementResponse(RefinementDto.of(refinements.view(refinements.ensure(id))));
  }

  @POST
  @Path("/{id}/stop")
  @Operation(
      operationId = "stopRefinementSession",
      summary = "Stop a refinement room's session",
      description =
          "Clears the room's demand, so its session is no longer wanted, and sends the project's"
              + " desk daemon its sessions list, which stops it; idempotent. The room, its draft and"
              + " its designs stay. The path names the room by its row id.")
  @APIResponse(
      responseCode = "200",
      description = "The room",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = RefinementResponse.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "The credential is bound to another entity, or the delivery desk reaches another"
              + " project's room")
  @APIResponse(responseCode = "404", description = "No refinement with this id")
  public RefinementResponse stopSession(@PathParam("id") long id) {
    RefinementAccess.reachWrite(refinements, identity, id);
    return new RefinementResponse(RefinementDto.of(refinements.view(refinements.stop(id))));
  }

  @POST
  @Path("/{id}/ensure-container")
  @Operation(
      operationId = "ensureRefinementContainer",
      summary = "Ask for a refinement room's session, under the door's old name",
      description =
          "The alias of ensure, kept while a consumer still calls it: the session is asked for and"
              + " the technical process answered is already settled. The path names the room by its"
              + " row id.")
  @APIResponse(
      responseCode = "200",
      description = "The room and a settled technical process id",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ProcessResponse.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "The credential is bound to another entity, or the delivery desk reaches another"
              + " project's room")
  @APIResponse(responseCode = "404", description = "No refinement with this id")
  @APIResponse(
      responseCode = "409",
      description = "REFINEMENT_SESSIONS_FULL, as on ensure")
  public ProcessResponse ensure(@PathParam("id") long id) {
    RefinementAccess.reachWrite(refinements, identity, id);
    String processId = refinements.ensureContainer(id);
    return new ProcessResponse(
        RefinementDto.of(refinements.view(refinements.get(id))), processId);
  }

  /**
   * Tear the refinement down: its session, its row, its token — and nothing else. It
   * says "this refinement is over", never "this epic (or ticket) is over": the entity stays where it
   * is and a later Refine starts a fresh one.
   *
   * <p>Resolving the entity is the other direction and no longer needs this door at all —
   * {@code EntityResolutions} discards on the way to a terminal status, so a client that means "done
   * with this entity" makes the transition call alone.
   */
  @POST
  @Path("/{id}/discard")
  @Operation(
      operationId = "discardRefinement",
      summary = "Discard a refinement room",
      description =
          "Tears the room down: its session is stopped, its row (draft, sketches and designs with"
              + " it) deleted and its token revoked. The work entity stays where it is, and a later"
              + " open starts a fresh room. The path names the room by its row id.")
  @APIResponse(
      responseCode = "200",
      description = "The room is gone",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = DiscardResponse.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "The credential is bound to another entity, or the delivery desk reaches another"
              + " project's room")
  @APIResponse(responseCode = "404", description = "No refinement with this id")
  public DiscardResponse discard(@PathParam("id") long id) {
    RefinementAccess.reachWrite(refinements, identity, id);
    refinements.discard(id);
    return new DiscardResponse(true);
  }
}
