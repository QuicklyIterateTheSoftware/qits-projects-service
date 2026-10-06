package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
 * <b>The lifecycle move of any archetype: {@code POST /projects/api/entities/{id}/status}</b>
 * (qits-548), body {@code {"target": "<STATUS>"}}.
 *
 * <p>Three archetypes have a door of their own — {@code /tickets/{id}/transition}, {@code
 * /epics/{id}/transition}, {@code /campaigns/{id}/transition} — so a caller holding only an id had
 * to learn the archetype and pick the path. A feature and a task, which hold the one lifecycle since
 * qits-763, have no other door: this is theirs. This door reads the archetype off the row and makes
 * <b>the same move that archetype's door makes</b>: {@link EntityRoutes#move}, which is {@code
 * EntityResolutions} (a resolving move tears the refinement room down first), the archetype's hint and
 * {@code PhaseAdvance}, with {@code EntityStateMachine}'s adjacency behind it. An illegal move is its
 * 409, an absent target its 400. It adds nothing to the move and has no rule of its own but these:
 *
 * <ul>
 *   <li><b>The roles follow the archetype's own door.</b> A ticket's and a campaign's admit {@code
 *       qits:agent} bound to its own project, so this door does — and so it does for a feature and a
 *       task (qits-763), whose move records where one piece of a plan stands and is the agent's to
 *       make as it works, like a ticket's. An epic's is {@code qits:admin} alone
 *       — freezing or resolving a plan is a person's press; an agent's claim goes through {@code
 *       transition_epic} on the MCP server — so an agent moving an epic here is a 403 and nothing is
 *       written, exactly as {@code POST /epics/{id}/transition} answers it. Widening that is a
 *       person's decision and is not taken here.
 *   <li><b>A feature's or a task's move waits for its epic</b>: while the epic is REPORTED the plan
 *       is a draft and the move is a 409 — {@code WorkEntityService}'s rule, not this door's. No
 *       phase follows such a move ({@code PhaseAdvance} returns at once for a piece).
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

  /** The move itself, shared with {@code POST /work/{qualifiedId}/status} (qits-969). */
  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

  @Schema(name = "EntityStatusMove", description = "A lifecycle move: the status to move to.")
  public record EntityStatusMove(
      @Schema(
              required = true,
              description =
                  "REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED,"
                      + " DONE or DROPPED — one the entity's current status may move to: a"
                      + " neighbour on the walk (IMPLEMENTING has no move back, and READY_FOR_DEV"
                      + " none to REPORTED), or IMPLEMENTED from READY_FOR_DEV or VERIFIED from"
                      + " IMPLEMENTED (the skips)."
                      + " A campaign never moves to IMPLEMENTING or VERIFYING.")
          String target) {}

  @POST
  @Path("/{id}/status")
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "moveEntityStatus",
      summary = "Move an entity through its lifecycle",
      description =
          "Moves an epic, ticket, campaign, feature or task to the target status, by the same path"
              + " as the archetype's own /{id}/transition door and under its roles: an agent may move a"
              + " ticket or a campaign of its own project, a platform service (qits:system) one of any"
              + " project, and an epic is qits:admin alone. A feature or a task moves under a ticket's"
              + " roles, and only once its epic is past REPORTED; the epic's moves to REFINED, back"
              + " to REPORTED, to READY_FOR_DEV, back to REFINED and to IMPLEMENTED carry it, no"
              + " other does, and its own scheduling (to READY_FOR_DEV, or back) is refused — that is"
              + " the epic's. The id is the UUID or the"
              + " qualified id. Answers the entity in the merged shape, with statusBefore.")
  @APIResponse(
      responseCode = "200",
      description = "The entity as the move left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description = "No target")
  @APIResponse(
      responseCode = "403",
      description =
          "An agent moving an epic, or an entity outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description =
          "A move the lifecycle does not allow, a target naming no status, a feature or a task"
              + " whose epic is still REPORTED, or a quality gate refusing a forward move — no"
              + " acceptance criteria into REFINED or READY_FOR_DEV (ACCEPTANCE_CRITERIA), or REFINED"
              + " to READY_FOR_DEV by a caller this service did not verify as a person"
              + " (PERSON_APPROVAL)")
  public TransitionedEntity move(@PathParam("id") String id, EntityStatusMove request) {
    return doors.move(identity, ids.resolve(id), request == null ? null : request.target());
  }
}
