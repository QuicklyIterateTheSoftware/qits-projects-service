package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The lifecycle move of any archetype: {@code POST /projects/api/entities/{id}/status}</b>
 * (qits-548), body {@code {"target": "<STATUS>"}}.
 *
 * <p>Three archetypes have a lifecycle and each has its own door — {@code /tickets/{id}/transition},
 * {@code /epics/{id}/transition}, {@code /campaigns/{id}/transition} — so a caller holding only an id
 * had to learn the archetype and pick the path. This door reads the archetype off the row and makes
 * <b>the same move that archetype's door makes</b>: {@link EntityRoutes#move}, which is {@code
 * EntityResolutions} (a resolving move tears the refinement room down first), the archetype's hint and
 * {@code PhaseAdvance}, with {@code EntityStateMachine}'s adjacency behind it. An illegal move is its
 * 409, an absent target its 400. It adds nothing to the move and has no rule of its own but these:
 *
 * <ul>
 *   <li><b>A kind with no lifecycle is a 400 saying so</b> — a feature's and a task's phase is their
 *       epic's, and there is no status on the row to move.
 *   <li><b>The roles follow the archetype's own door.</b> A ticket's and a campaign's admit {@code
 *       qits:agent} bound to its own project, so this door does. An epic's is {@code qits:admin} alone
 *       — freezing or resolving a plan is a person's press; an agent's claim goes through {@code
 *       transition_epic} on the MCP server — so an agent moving an epic here is a 403 and nothing is
 *       written, exactly as {@code POST /epics/{id}/transition} answers it. Widening that is a
 *       person's decision and is not taken here.
 * </ul>
 *
 * <p>The refusal order is the other entity doors': the id (404), then the caller (403 — the epic's
 * role, then the project binding), then the body (400), then the move (409). {@code {id}} is the
 * UUID or the qualified id.
 *
 * <p><b>The answer is the {@link TransitionedEntity}</b> as the move left it, with {@code
 * statusBefore} and {@code changedBy} filled: built from the row the move handed back, never
 * re-read, for {@link TransitionedEntity#edited}'s reason. A supersede's successor is not in it; the
 * row's {@code supersededBy} names it.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityStatusController {

  @Inject EntityIdResolver ids;

  /** The row's edge, read before the move: a move changes none. */
  @Inject EntityCatalogService catalog;

  @Inject EntityRoutes routes;

  @Inject SecurityIdentity identity;

  @Inject QualifiedEntityIds qualifiedIds;

  @Schema(name = "EntityStatusMove", description = "A lifecycle move: the status to move to.")
  public record EntityStatusMove(
      @Schema(
              required = true,
              description =
                  "REPORTED, REFINED, IMPLEMENTED, VERIFIED, DONE or DROPPED — one the entity's"
                      + " current status may move to")
          String target) {}

  @POST
  @Path("/{id}/status")
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      summary = "Move an entity through its lifecycle",
      description =
          "Moves an epic, ticket or campaign to the target status, by the same path as the"
              + " archetype's own /{id}/transition door and under its roles: an agent may move a"
              + " ticket or a campaign of its own project, a platform service (qits:system) one of any"
              + " project, and an epic is qits:admin alone. A feature"
              + " or a task has no status. The id is the UUID or the qualified id. Answers the entity"
              + " in the merged shape, with statusBefore.")
  @APIResponse(
      responseCode = "200",
      description = "The entity as the move left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description = "No target, or an archetype with no lifecycle (a feature or a task)")
  @APIResponse(
      responseCode = "403",
      description =
          "An agent moving an epic, or an entity outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description = "A move the lifecycle does not allow, or a target naming no status")
  public TransitionedEntity move(@PathParam("id") String id, EntityStatusMove request) {
    WorkEntity row = ids.resolve(id);
    Archetype archetype = row.archetype;
    if (archetype == Archetype.EPIC && !identity.hasRole(AgentAccess.ADMIN_ROLE)) {
      throw new ForbiddenException(
          "Moving an epic's status is qits:admin alone, as POST /projects/api/epics/{id}/transition"
              + " is; an agent's claim goes through the transition_epic MCP tool.");
    }
    EntitiesAgentAccess.requireProject(identity, row.projectId);
    if (Archetypes.legalStatuses(archetype).isEmpty()) {
      throw new BadRequestException(
          "a "
              + archetype
              + " has no status: its phase is its epic's, so there is nothing to move");
    }

    TransitionedEntity before = catalog.byIds(List.of(row.id)).get(row.id);
    String changedBy = EntitiesPrincipal.changedBy(identity);
    // Bound above, in this door's refusal order, so the move is asked not to bind again.
    WorkEntityService.Transition moved =
        routes.move(archetype, row.id, request == null ? null : request.target(), false, identity);
    return qualifiedIds.qualify(
        TransitionedEntity.moved(moved.entity(), before, moved.statusBefore(), changedBy));
  }
}
