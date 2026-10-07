package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
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
 * <b>A work entity's refinement room: {@code /projects/api/work/{qualifiedId}/refinement}</b>
 * (qits-970, epic qits-965) — the {@code /work} home of the deleted {@code /entities/{id}/refinement}, addressed
 * by qualified id or UUID ({@link EntityIdResolver#resolve}).
 *
 * <pre>
 *   POST /work/{qualifiedId}/refinement   → {"refinement": RefinementDto}          find-or-create
 *   GET  /work/{qualifiedId}/refinement   → {"refinement": RefinementDto | null}   find only
 * </pre>
 *
 * <p>Every rule is {@link RefinementService}'s — the same {@code findOrCreate}, {@code findByEntity}
 * and {@code view} the deleted {@code /entities} door called — and so are the roles: the open is {@code
 * qits:admin} alone, the read admits {@code qits:agent}. The path's 404 is the resolver's, for an id
 * that names no entity, so the read's {@code null} still means "no room" and nothing else.
 */
@Path("/work/{qualifiedId}/refinement")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent"})
public class WorkRefinementController {

  @Inject EntityIdResolver ids;

  @Inject RefinementService refinements;

  @Schema(
      name = "WorkRefinementAnswer",
      description = "A work entity's refinement room; null on a read that found none.")
  public record WorkRefinementAnswer(RefinementDto refinement) {}

  @POST
  @Operation(
      operationId = "startWorkRefinement",
      summary = "Open (or find) a work entity's refinement room",
      description =
          "Find-or-create: an entity has at most one room, and a second open answers the first."
              + " Refused for a feature or a task, an entity not at REPORTED, one an ACTIVE workspace"
              + " names, and a project with no wrapper. The path names the entity by qualified id"
              + " (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The room",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkRefinementAnswer.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(responseCode = "409", description = "The entity cannot be refined now")
  @APIResponse(responseCode = "502", description = "The git host would not cut the branch")
  public WorkRefinementAnswer open(@PathParam("qualifiedId") String qualifiedId) {
    String id = ids.resolve(qualifiedId).id;
    return new WorkRefinementAnswer(RefinementDto.of(refinements.view(refinements.findOrCreate(id))));
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "getWorkRefinement",
      summary = "Find a work entity's refinement room",
      description =
          "The entity's room, or null when it has none; never creates one. The path names the"
              + " entity by qualified id or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The room, or null",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkRefinementAnswer.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkRefinementAnswer find(@PathParam("qualifiedId") String qualifiedId) {
    String id = ids.resolve(qualifiedId).id;
    return new WorkRefinementAnswer(
        refinements.findByEntity(id).map(row -> RefinementDto.of(refinements.view(row))).orElse(null));
  }
}
