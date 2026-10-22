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
 * <p>The ensure and recreate verbs answer a technical-process id to watch; the work itself runs
 * off the request thread, behind an image pull if it must.
 */
@Path("/refinements")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent"})
public class RefinementController {

  @Inject RefinementService refinements;

  @Inject SecurityIdentity identity;

  public record RefinementResponse(RefinementDto refinement) {}

  @Schema(
      name = "ProcessResponse",
      description = "A refinement room and the technical process narrating its container's bring-up.")
  public record ProcessResponse(RefinementDto refinement, String technicalProcessId) {}

  public record ActiveProcessResponse(String technicalProcessId) {}

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
  @Path("/{id}/ensure-container")
  @Operation(
      operationId = "ensureRefinementContainer",
      summary = "Bring a refinement room's container up",
      description =
          "Provisions a fresh container, wakes a stopped one or touches a running one, off the"
              + " request thread, and answers the room together with the technical process that"
              + " narrates the bring-up. A second call while one is under way answers the live"
              + " process. The path names the room by its row id.")
  @APIResponse(
      responseCode = "200",
      description = "The room and the bring-up's technical process id",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ProcessResponse.class)))
  @APIResponse(responseCode = "404", description = "No refinement with this id")
  @APIResponse(
      responseCode = "409",
      description = "The container is already being worked on and no process is live")
  public ProcessResponse ensure(@PathParam("id") long id) {
    RefinementAccess.reach(refinements, identity, id);
    String processId = refinements.ensureContainer(id);
    return new ProcessResponse(
        RefinementDto.of(refinements.view(refinements.get(id))), processId);
  }

  @POST
  @Path("/{id}/recreate-container")
  public ProcessResponse recreate(@PathParam("id") long id) {
    RefinementAccess.reach(refinements, identity, id);
    String processId = refinements.recreateContainer(id);
    return new ProcessResponse(
        RefinementDto.of(refinements.view(refinements.get(id))), processId);
  }

  @POST
  @Path("/{id}/stop-container")
  public RefinementResponse stop(@PathParam("id") long id) {
    RefinementAccess.reach(refinements, identity, id);
    return new RefinementResponse(RefinementDto.of(refinements.view(refinements.stopContainer(id))));
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Path("/{id}/active-process")
  public ActiveProcessResponse activeProcess(@PathParam("id") long id) {
    // 404 an unknown row rather than answering a hopeful null; 403 another entity's room.
    RefinementAccess.reach(refinements, identity, id);
    return new ActiveProcessResponse(refinements.activeProcessId(id));
  }

  /**
   * Tear the refinement down: container, volume, credential, branch, row — and nothing else. It
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
          "Tears the room down: container, volume, credential, refining branch and row. The work"
              + " entity stays where it is, and a later open starts a fresh room. The path names"
              + " the room by its row id.")
  @APIResponse(
      responseCode = "200",
      description = "The room is gone",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = DiscardResponse.class)))
  @APIResponse(responseCode = "404", description = "No refinement with this id")
  public DiscardResponse discard(@PathParam("id") long id) {
    RefinementAccess.reach(refinements, identity, id);
    refinements.discard(id);
    return new DiscardResponse(true);
  }
}
