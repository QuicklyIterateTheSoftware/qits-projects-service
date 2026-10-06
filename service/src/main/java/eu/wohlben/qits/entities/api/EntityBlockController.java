package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.projects.api.EntityBlocks;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
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
 * <b>The block of any archetype with a lifecycle: {@code POST /projects/api/entities/{id}/blocked}</b>
 * (qits-592), body {@code {"blocked": true|false, "reason": "…"}}.
 *
 * <p>Blocking was a ticket's alone ({@code POST /tickets/{id}/blocked}), so an agent stuck on an
 * epic's implement phase, or a person holding a campaign back, had no way to say so short of moving
 * the status — which is the one thing a block must not do, because the status is the phase to
 * resume. The flag, its write and its clearing on every transition were already archetype-blind;
 * this door is the missing address. It is {@link EntityBlocks} and nothing else — the reason rule,
 * the phase refusal and the remark on the entity's own thread — so it and the ticket door cannot
 * drift, and the ticket door stays as a delegate with its {@code {"ticket": …}} answer.
 *
 * <p>{@code {id}} is the UUID <b>or</b> the qualified id ({@code qits-592}), resolved by {@link
 * EntityIdResolver} as the thread's door resolves it. The refusal order is the other entity doors':
 * the id (404), the caller (403), the reason (400), the phase (409 — a feature or a task, which has
 * none of its own, and VERIFIED, DONE or DROPPED, where none runs).
 *
 * <p>The roles are the ticket block door's: {@code qits:agent} <b>bound to its own project</b>
 * ({@link EntitiesAgentAccess}), resolved before the write, because {@code block_entity} and {@code
 * unblock_entity} already perform this write for an agent over MCP and the agent working the phase
 * is the one that knows it is stuck. The hint is the archetype's ({@link
 * ProjectChangeHint.Topic#of}), fired after the write returns.
 *
 * <p><b>The answer is {@link EntityBlocks.Blocked}</b> in a {@code {"block": …}} envelope — the
 * flag as the write left it, with the archetype and the status beside it — rather than an
 * archetype's DTO, for the reason {@code EntityPatchController} gives: a door that takes any id
 * answers one shape.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityBlockController {

  @Inject EntityIdResolver ids;

  /** The block itself, shared with {@code POST /work/{qualifiedId}/blocked} (qits-969). */
  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

  /** {@code reason} is required when blocking and optional when unblocking. */
  @Schema(name = "EntityBlockRequest", description = "What the block flag should become, and why.")
  public record EntityBlockRequest(
      @Schema(required = true, description = "true to block, false to unblock") boolean blocked,
      @Schema(
              description =
                  "what is in the way — required when blocking; lands on the entity's thread")
          String reason) {}

  /** The answer, named explicitly so no other schema is renumbered. */
  @Schema(name = "EntityBlockAnswer", description = "An entity's block flag as the write left it.")
  public record EntityBlockAnswer(EntityBlocks.Blocked block) {}

  @POST
  @Path("/{id}/blocked")
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Operation(
      summary = "Block or unblock an entity",
      description =
          "Says the phase an epic's, ticket's or campaign's status starts cannot finish right now"
              + " (or can again). The status does not move — it is the phase to resume — and the next"
              + " transition clears the block. The reason is required to block and lands on the"
              + " entity's thread. The id is the UUID or the qualified id.")
  @APIResponse(
      responseCode = "200",
      description = "The flag as the write left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = EntityBlockAnswer.class)))
  @APIResponse(responseCode = "400", description = "Blocking with no reason")
  @APIResponse(
      responseCode = "403",
      description = "An agent blocking outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description =
          "A feature or a task (which runs no phase of its own), or a status that starts no phase"
              + " (VERIFIED, DONE, DROPPED)")
  public EntityBlockAnswer setBlocked(@PathParam("id") String id, EntityBlockRequest request) {
    return new EntityBlockAnswer(
        doors.block(
            identity,
            ids.resolve(id),
            request != null && request.blocked(),
            request == null ? null : request.reason()));
  }
}
