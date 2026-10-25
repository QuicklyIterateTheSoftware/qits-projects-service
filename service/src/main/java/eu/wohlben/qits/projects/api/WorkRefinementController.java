package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesAgentAccess;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
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
 * qits:admin}'s (and {@code qits:admin-agent}'s) and, since qits-1596, the project's delivery desk's
 * ({@code qits:desk}, bound to its own project's entities); the read admits {@code qits:agent}. The path's 404 is the resolver's, for an id
 * that names no entity, so the read's {@code null} still means "no room" and nothing else.
 *
 * <p>Both verbs are bound to the caller's {@code entity} claim when its bearer carries one
 * (qits-1482, {@link EntitiesAgentAccess#requireEntity}): a refinement room's own token reaches its
 * own entity's room and answers 403 on any other, after the path resolved (so 404 still comes
 * first) and before a room is created.
 */
@Path("/work/{qualifiedId}/refinement")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk"})
public class WorkRefinementController {

  @Inject EntityIdResolver ids;

  @Inject RefinementService refinements;

  @Inject SecurityIdentity identity;

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
  @APIResponse(
      responseCode = "403",
      description =
          "Not Allowed, the credential is bound to another entity, or the delivery desk reaches"
              + " another project's entity")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(responseCode = "409", description = "The entity cannot be refined now")
  @APIResponse(responseCode = "502", description = "The git host would not cut the branch")
  public WorkRefinementAnswer open(@PathParam("qualifiedId") String qualifiedId) {
    WorkEntity entity = ids.resolve(qualifiedId);
    String id = entity.id;
    EntitiesAgentAccess.requireEntity(identity, id);
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
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
  @APIResponse(
      responseCode = "403",
      description = "Not Allowed, or the credential is bound to another entity")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkRefinementAnswer find(@PathParam("qualifiedId") String qualifiedId) {
    WorkEntity entity = ids.resolve(qualifiedId);
    String id = entity.id;
    EntitiesAgentAccess.requireEntity(identity, id);
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
    return new WorkRefinementAnswer(
        refinements.findByEntity(id).map(row -> RefinementDto.of(refinements.view(row))).orElse(null));
  }
}
