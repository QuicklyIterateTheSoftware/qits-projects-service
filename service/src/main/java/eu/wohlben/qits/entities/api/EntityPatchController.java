package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.AcceptanceCriteria;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.WorkEntityService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The field edit of the merged model: {@code PATCH /projects/api/entities/{id}}.</b>
 *
 * <p>{@code PUT /epics/{id}} and {@code PUT /tickets/{id}} were retired with qits-399, which left
 * {@code POST /entities/transition} as the only HTTP door that edits a row — and that one is a PUT
 * in all but verb: an absent property is cleared ({@code EntityTransition}). A caller that wanted to
 * retitle a ticket had to restate everything else about it, and one that forgot the assignee
 * unassigned it. This is the partial edit the MCP tools {@code update_ticket}, {@code update_epic},
 * {@code update_feature} and {@code update_task} already perform, exposed over REST for any
 * archetype: it is {@link WorkEntityService#update} and nothing else, so the freeze, the dependency
 * rules and the registry's slots are the ones every other edit obeys. There is no second update
 * path here, only a translation from the wire.
 *
 * <h2>A JSON merge patch (RFC 7396)</h2>
 *
 * <p>An absent property is left alone and an explicit {@code null} clears it. That distinction is
 * the whole reason the body is read as a {@link JsonNode} rather than bound to a record: a record
 * cannot tell a property that was not sent from one sent as null. It maps onto {@link EntityWrite}
 * directly — a value is a value, a null is that property's {@code clear*} flag — and a property
 * with no clear flag ({@code title}, {@code ticketType}, {@code repositoryId}) is required wherever
 * it is permitted, so clearing it is a 400 rather than a flag that does not exist.
 *
 * <p><b>What this door does not write, and says so.</b> The status, the archetype, the membership
 * and a supersede are moves, not edits, and each has its door: the archetype's {@code
 * …/{id}/transition} for a lifecycle move, {@code POST /entities/transition} for a reshape, a
 * reparent or a supersede, and {@code POST /entities/{id}/blocked} for the block. {@code slug} and
 * {@code createdBy} are the server's. Naming any of those, or a property nobody has heard of, is a
 * 400 rather than a silent drop — a caller that thinks it moved a status and did not is worse off
 * than one that was told. Every such complaint comes back in <b>one</b> 400, joined with {@code "; "}
 * like the transition's.
 *
 * <p><b>The answer is the {@link TransitionedEntity}</b> — the flat, merged shape {@code
 * /entities/transition} and {@code list_entities} answer — built from the row the write handed
 * back ({@link TransitionedEntity#edited}). Not the per-archetype DTOs: those come wrapped in an
 * envelope named for the kind ({@code {"epic": …}}), so a generic door would answer a different
 * shape per row and a caller would have to know the archetype before it could read the result.
 * {@code statusBefore} and {@code changedBy} are null, as on every read.
 *
 * <p><b>The id is the entity's UUID.</b> A qualified id ({@code qits-547}) is not resolved here:
 * {@code /entities/transition} resolves none either, and the one reader of that form, {@code
 * CommitSubjectEntities}, reads it off a commit subject rather than a path.
 *
 * <p>The binding and the hints are {@code EntityTransitionController}'s: {@code qits:agent} is
 * granted because the four MCP update tools already serve this write, bound to the agent's own
 * project before anything is written; and both SSE topics are fired after the write returns, never
 * inside its retried body. No bus event, the same as every other field edit.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityPatchController {

  /** RFC 7396's media type. Plain {@code application/json} is accepted beside it. */
  public static final String MERGE_PATCH_JSON = WorkEntityDoors.MERGE_PATCH_JSON;

  /** The patch itself, shared with {@code PATCH /work/{qualifiedId}} (qits-969). */
  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

  /**
   * The documentation of the body, and only that: the body itself is read as a {@link JsonNode},
   * because this record could not tell an absent property from a null one.
   */
  @Schema(
      name = "EntityPatch",
      description =
          "A JSON merge patch (RFC 7396) of one entity. An absent property is left as it is; an"
              + " explicit null clears it. At least one property must be named. status, archetype,"
              + " membership, supersededBy and blocked are refused with the door that moves them;"
              + " slug and createdBy are server-owned; any other property is refused as unknown. A"
              + " property the entity's archetype has no slot for is refused (impetus on an epic).")
  public record EntityPatch(
      @Schema(description = "The label. Cannot be cleared.") String title,
      @Schema(nullable = true, description = "The long-form Markdown body; null clears it.")
          String description,
      @Schema(nullable = true, description = "A ticket's impetus; null clears it.") String impetus,
      @Schema(
              description =
                  "A ticket's type, BUG or IMPROVEMENT; MAINTENANCE marks a ticket the platform"
                      + " filed and will close itself, and retyping one away from it takes it over."
                      + " Cannot be cleared.")
          String ticketType,
      @Schema(nullable = true, description = "An epic's or a ticket's assignee; null clears it.")
          String assignee,
      @Schema(description = "A task's repository, in the task's project. Cannot be cleared.")
          String repositoryId,
      @Schema(
              nullable = true,
              description = "A feature's or task's sibling dependency; null clears it.")
          String dependsOn,
      @Schema(
              nullable = true,
              description =
                  "A feature's or task's implemented marker (ISO-8601 instant); null clears it."
                      + " Moves only while the owning epic is READY_FOR_DEV or IMPLEMENTING. Setting"
                      + " it moves the item's status to IMPLEMENTED; clearing it takes an"
                      + " IMPLEMENTED item back to IMPLEMENTING (or READY_FOR_DEV when it was never"
                      + " marked implementing).")
          Instant implementedAt,
      @Schema(
              description =
                  "A feature's or task's implementing marker (ISO-8601 instant): when its"
                      + " implementation was started. Cannot be cleared. Moves only while the"
                      + " owning epic is READY_FOR_DEV or IMPLEMENTING, and moves the item's status to"
                      + " IMPLEMENTING when it is not already there or further.")
          Instant implementingAt,
      @Schema(
              nullable = true,
              description =
                  "An epic's or ticket's acceptance criteria, the whole list in order; null or an"
                      + " empty list clears them. "
                      + AcceptanceCriteria.RULES
                      + " Editable at REFINED (outside an epic's scope freeze); from READY_FOR_DEV"
                      + " on a changed list is a 409, and restating the same list passes.")
          List<String> acceptanceCriteria) {}

  /**
   * Applies the patch and answers the entity as it now stands.
   *
   * <p>The order is the refusal order: the id first (404), then the binding (403), then the body
   * (400), then the write — whose own refusals, the scope freeze's 409 above all, are {@link
   * WorkEntityService#update}'s. So an agent reaching into another project is told 403 whatever it
   * sent, and nothing is written for it.
   */
  @PATCH
  @Path("/{id}")
  @Consumes({MERGE_PATCH_JSON, MediaType.APPLICATION_JSON})
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      summary = "Edit an entity's fields (JSON merge patch)",
      description =
          "Partial update of one entity of any archetype. An absent property is left unchanged and"
              + " an explicit null clears it. The status is never written here — a lifecycle move"
              + " goes through POST /projects/api/entities/{id}/status (or the archetype's own"
              + " /{id}/transition), and a reshape, reparent or"
              + " supersede through POST /projects/api/entities/transition. An epic's, feature's or"
              + " task's scope follows the epic's freeze: scope edits need the epic REPORTED, the"
              + " markers need it READY_FOR_DEV or IMPLEMENTING, and a marker moves the item's status with"
              + " it. An epic's or a ticket's acceptance criteria sit outside the scope freeze and"
              + " are frozen from READY_FOR_DEV on instead, where a changed list is refused and the"
              + " same list restated passes. Answers the entity in the merged shape.")
  @APIResponse(
      responseCode = "200",
      description = "The entity as it stands after the edit",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Every complaint about the body in one message: not an object, empty, a property that is"
              + " a move or server-owned or unknown, one the archetype has no slot for, a null"
              + " where the property cannot be cleared, or a value of the wrong shape")
  @APIResponse(
      responseCode = "403",
      description = "An agent writing an entity outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description =
          "The owning epic's status freezes what the patch touches, or the acceptance criteria are"
              + " changed from READY_FOR_DEV on")
  public TransitionedEntity patch(
      @PathParam("id") String id,
      @RequestBody(
              required = true,
              content = {
                @Content(
                    mediaType = MERGE_PATCH_JSON,
                    schema = @Schema(implementation = EntityPatch.class)),
                @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = EntityPatch.class))
              })
          JsonNode body) {
    return doors.patch(identity, id, body, WorkEntityDoors.Surface.ENTITIES);
  }
}
