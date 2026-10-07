package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.AcceptanceCriteria;
import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.EntityBlocks;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The work-entity family, addressed by qualified id: {@code /projects/api/work}</b> (qits-969,
 * epic qits-965).
 *
 * <p>One family for every archetype, with no archetype in any path: {@code GET}, {@code PUT} and
 * {@code PATCH /work/{qualifiedId}}, the create {@code POST /work}, the bulk {@code POST
 * /work/transition}, and the lifecycle and block moves {@code POST /work/{qualifiedId}/status} and
 * {@code …/blocked}, and the delete {@code DELETE /work/{qualifiedId}} (qits-970). The thread is
 * {@link WorkCommentController}'s, the registry {@link WorkArchetypesController}'s and a project's
 * listing {@link ProjectWorkController}'s; the sub-resources (qits-970) each have a class of their
 * own — {@link WorkDossierController}, {@link WorkDossierAssetController}, {@link
 * WorkChildrenController}, {@link WorkAuditController}, {@link WorkProgressController}, {@link
 * WorkMembersController}, and in {@code projects.api} the dispatch, the refinement room and the
 * workspaces.
 *
 * <p><b>It is served beside {@code /entities} and the per-archetype routes, and shares their
 * implementation</b>: every route body here is one call into {@link WorkEntityDoors}, which is also
 * what the {@code /entities} controllers delegate to — so the rules (refusal order, agent binding,
 * hints after the write) are one copy, and deleting the old routes later deletes their resources and
 * nothing here. This class names no old controller and no schema of theirs; its records carry
 * {@code Work*} schema names of their own.
 *
 * <p><b>{@code {qualifiedId}} is the qualified id ({@code qits-111}) or the UUID</b>, through {@link
 * EntityIdResolver#resolve}; naming nothing is a 404. <b>Every entity id in a body takes either
 * form too</b>: the create's {@code parent} and {@code dependsOn}, the patch's {@code dependsOn}, and
 * on the PUT and the bulk transition the map's keys and each entry's {@code membership.parent},
 * {@code supersededBy} and {@code dependsOn} ({@link WorkEntityDoors}). Every answer
 * carries {@code qualifiedId}.
 *
 * <p><b>The roles are the routes' they mirror, method by method</b>: a read and the create, the
 * patch and the status move admit {@code qits:system} beside {@code qits:agent} (qits-667); the PUT,
 * the bulk transition and the block admit the agent, bound to its own project; the class list is
 * {@code qits:admin}, and each method states its own because a method list replaces the class's.
 */
@Path("/work")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class WorkController {

  @Inject EntityIdResolver ids;

  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

  // --- the bodies, documented ------------------------------------------------------------------

  /**
   * The documentation of the create's body, and only that: it is read as a {@link JsonNode} so that
   * it can be judged against the archetype's schema, property by property.
   */
  @Schema(
      name = "WorkCreate",
      description =
          "A new work entity of any archetype: the archetype, plus exactly the body GET"
              + " /projects/api/work/archetypes/{archetype}/schemas/create describes for it. A root"
              + " (EPIC, TICKET, CAMPAIGN) names its project, a node (FEATURE, TASK) its parent.")
  public record WorkCreate(
      @Schema(required = true, description = "EPIC, TICKET, FEATURE, TASK or CAMPAIGN")
          Archetype archetype,
      @Schema(description = "A root's project: its id or its slug.") String project,
      @Schema(
              description =
                  "A node's parent — an EPIC for a FEATURE, a FEATURE for a TASK — by qualified id"
                      + " or UUID.")
          String parent,
      @Schema(description = "The label.") String title,
      @Schema(description = "The long-form Markdown body.") String description,
      @Schema(
              description =
                  "A ticket's kind, BUG or IMPROVEMENT. MAINTENANCE is reserved for the tickets the"
                      + " platform files about its own stuck release requests, and it closes those"
                      + " itself.")
          String ticketType,
      @Schema(description = "Why a ticket came about. Required for a ticket.") String impetus,
      @Schema(description = "An epic's or a ticket's assignee.") String assignee,
      @Schema(description = "A task's repository, in the task's project.") String repositoryId,
      @Schema(description = "A feature's or task's sibling dependency, by qualified id or UUID.")
          String dependsOn,
      @Schema(description = "An epic's or a ticket's acceptance criteria, in order.")
          List<String> acceptanceCriteria) {}

  /**
   * The documentation of the patch's body, and only that: the body itself is read as a {@link
   * JsonNode}, because a record could not tell an absent property from a null one.
   */
  @Schema(
      name = "WorkPatch",
      description =
          "A JSON merge patch (RFC 7396) of one work entity. An absent property is left as it is;"
              + " an explicit null clears it. At least one property must be named. status,"
              + " archetype, membership, supersededBy and blocked are refused with the door that"
              + " moves them; slug and createdBy are server-owned; any other property is refused as"
              + " unknown. A property the entity's archetype has no slot for is refused (impetus on"
              + " an epic).")
  public record WorkPatch(
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
              description =
                  "A feature's or task's sibling dependency, by qualified id or UUID; null clears"
                      + " it.")
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

  @Schema(name = "WorkStatusMove", description = "A lifecycle move: the status to move to.")
  public record WorkStatusMove(
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

  /** {@code reason} is required when blocking and optional when unblocking. */
  @Schema(name = "WorkBlockRequest", description = "What the block flag should become, and why.")
  public record WorkBlockRequest(
      @Schema(required = true, description = "true to block, false to unblock") boolean blocked,
      @Schema(
              description =
                  "what is in the way — required when blocking; lands on the entity's thread")
          String reason) {}

  @Schema(name = "WorkBlockAnswer", description = "A work entity's block flag as the write left it.")
  public record WorkBlockAnswer(EntityBlocks.Blocked block) {}

  // --- the routes ------------------------------------------------------------------------------

  @GET
  @Path("/{qualifiedId}")
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "getWork",
      summary = "Read one work entity of any archetype",
      description =
          "The entity in the merged shape, with its qualified id, its parent and position, and"
              + " whether it is blocked. The path names it by qualified id (<projectSlug>-<n>) or"
              + " UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The entity",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public TransitionedEntity get(@PathParam("qualifiedId") String qualifiedId) {
    return doors.read(ids.resolve(qualifiedId));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "createWork",
      summary = "Create a work entity of any archetype",
      description =
          "Files a new epic, ticket, campaign, feature or task from one body shape: the archetype"
              + " plus the archetype's create schema (GET"
              + " /projects/api/work/archetypes/{archetype}/schemas/create). A root names its"
              + " project (id or slug), a node its parent (qualified id or UUID): a feature's parent"
              + " is an EPIC, a task's a FEATURE. A dependency is a qualified id or a UUID. The status"
              + " starts REPORTED. Answers the entity in the merged shape, with its qualified id.")
  @APIResponse(
      responseCode = "201",
      description = "The entity as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Every complaint about the body in one message: not an object, no or an unknown"
              + " archetype, a property the create schema does not list, a missing required one, a"
              + " value of the wrong shape, a parent of the wrong kind, or a repository outside the"
              + " project")
  @APIResponse(
      responseCode = "403",
      description = "An agent creating in a project other than its own; nothing is written")
  @APIResponse(
      responseCode = "404",
      description = "The project, the parent or the repository names nothing")
  @APIResponse(
      responseCode = "409",
      description = "The owning epic's status freezes its scope: a node needs the epic REPORTED")
  public Response create(
      @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON,
                      schema = @Schema(implementation = WorkCreate.class)))
          JsonNode body) {
    return Response.status(Response.Status.CREATED).entity(doors.create(identity, body)).build();
  }

  @PUT
  @Path("/{qualifiedId}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Operation(
      operationId = "putWork",
      summary = "State a work entity in full",
      description =
          "The full edit of one entity: the body is the entity's whole state afterwards — what it"
              + " is (archetype), where it hangs (membership), every property — and an absent"
              + " property is cleared, not left alone. It is the one-entry form of POST"
              + " /projects/api/work/transition and obeys every rule of it, so it is also how a"
              + " single entity is reshaped, reparented or superseded. Every entity id in the body"
              + " (membership.parent, supersededBy, dependsOn) is a qualified id or a UUID. Answers"
              + " the entity as written, in the merged shape.")
  @APIResponse(
      responseCode = "200",
      description = "The entity as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description = "Every violation of the stated state, in one message")
  @APIResponse(
      responseCode = "403",
      description = "An agent writing outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description =
          "A status this door may not state (a change to or from READY_FOR_DEV, a jump into started"
              + " work, a gated move — those go through POST /work/{qualifiedId}/status), or a"
              + " changed acceptance-criteria list from READY_FOR_DEV on")
  public TransitionedEntity put(
      @PathParam("qualifiedId") String qualifiedId, EntityTransition state) {
    return doors.put(identity, ids.resolve(qualifiedId), state);
  }

  @PATCH
  @Path("/{qualifiedId}")
  @Consumes({WorkEntityDoors.MERGE_PATCH_JSON, MediaType.APPLICATION_JSON})
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "patchWork",
      summary = "Edit a work entity's fields (JSON merge patch)",
      description =
          "Partial update of one entity of any archetype. An absent property is left unchanged and"
              + " an explicit null clears it. The status is never written here — a lifecycle move"
              + " goes through POST /projects/api/work/{qualifiedId}/status, and a reshape, reparent"
              + " or supersede through PUT /projects/api/work/{qualifiedId} or POST"
              + " /projects/api/work/transition. dependsOn is a qualified id or a UUID. An epic's,"
              + " feature's or task's scope follows the epic's freeze: scope edits need the epic"
              + " REPORTED, the markers need it READY_FOR_DEV or IMPLEMENTING, and a marker moves"
              + " the item's status with it. An epic's or a ticket's acceptance criteria are frozen"
              + " from READY_FOR_DEV on, where a changed list is refused and the same list restated"
              + " passes. Answers the entity in the merged shape.")
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
      @PathParam("qualifiedId") String qualifiedId,
      @RequestBody(
              required = true,
              content = {
                @Content(
                    mediaType = WorkEntityDoors.MERGE_PATCH_JSON,
                    schema = @Schema(implementation = WorkPatch.class)),
                @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WorkPatch.class))
              })
          JsonNode body) {
    return doors.patch(identity, ids.resolve(qualifiedId).id, body);
  }

  @POST
  @Path("/transition")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Operation(
      operationId = "transitionWork",
      summary = "State several work entities in full, as one post-state",
      description =
          "A map of entity id to the whole state that entity is to have afterwards, validated as"
              + " one post-state, applied in one transaction and announced once. The keys and every"
              + " id in an entry (membership.parent, supersededBy, dependsOn) are qualified ids or"
              + " UUIDs; an id naming nothing is one of the violations in the 400. Answers each"
              + " entity in the merged shape, keyed as the request was.")
  @APIResponse(
      responseCode = "200",
      description = "What was written, keyed as the request was",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema =
                  @Schema(
                      type = SchemaType.OBJECT,
                      additionalProperties = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description = "Every violation of the stated post-state, in one message")
  @APIResponse(
      responseCode = "403",
      description =
          "An agent whose batch reaches outside its own project; nothing of it is written")
  @APIResponse(
      responseCode = "409",
      description =
          "A status this door may not state, or a changed acceptance-criteria list from"
              + " READY_FOR_DEV on")
  public Map<String, TransitionedEntity> transition(
      @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON,
                      schema =
                          @Schema(
                              type = SchemaType.OBJECT,
                              additionalProperties = EntityTransition.class)))
          Map<String, EntityTransition> request) {
    return doors.transition(identity, request);
  }

  @POST
  @Path("/{qualifiedId}/status")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "setWorkStatus",
      summary = "Move a work entity through its lifecycle",
      description =
          "Moves an epic, ticket, campaign, feature or task to the target status, by the same path"
              + " as the archetype's own transition and under its roles: an agent may move a ticket,"
              + " a campaign, a feature or a task of its own project, a platform service"
              + " (qits:system) one of any project, and an epic is qits:admin alone. A feature or a"
              + " task moves only once its epic is past REPORTED, and its scheduling (to"
              + " READY_FOR_DEV, or back) is the epic's. Answers the entity in the merged shape, with"
              + " statusBefore.")
  @APIResponse(
      responseCode = "200",
      description = "The entity as the move left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(responseCode = "400", description = "No target")
  @APIResponse(
      responseCode = "403",
      description =
          "An agent moving an epic, or an entity outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description =
          "A move the lifecycle does not allow, a target naming no status, a feature or a task"
              + " whose epic is still REPORTED, or a quality gate refusing a forward move —"
              + " ACCEPTANCE_CRITERIA, or PERSON_APPROVAL for REFINED to READY_FOR_DEV by a caller"
              + " this service did not verify as a person")
  public TransitionedEntity setStatus(
      @PathParam("qualifiedId") String qualifiedId, WorkStatusMove request) {
    return doors.move(identity, ids.resolve(qualifiedId), request == null ? null : request.target());
  }

  @POST
  @Path("/{qualifiedId}/blocked")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Operation(
      operationId = "setWorkBlocked",
      summary = "Block or unblock a work entity",
      description =
          "Says the phase an epic's, ticket's or campaign's status starts cannot finish right now"
              + " (or can again). The status does not move — it is the phase to resume — and the next"
              + " transition clears the block. The reason is required to block and lands on the"
              + " entity's thread.")
  @APIResponse(
      responseCode = "200",
      description = "The flag as the write left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkBlockAnswer.class)))
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
  public WorkBlockAnswer setBlocked(
      @PathParam("qualifiedId") String qualifiedId, WorkBlockRequest request) {
    return new WorkBlockAnswer(
        doors.block(
            identity,
            ids.resolve(qualifiedId),
            request != null && request.blocked(),
            request == null ? null : request.reason()));
  }

  @Schema(name = "WorkDeleted", description = "The entity and its subtree are gone.")
  public record WorkDeleted(boolean success) {}

  @DELETE
  @Path("/{qualifiedId}")
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Operation(
      operationId = "deleteWork",
      summary = "Delete a work entity and its subtree",
      description =
          "Removes the entity, its descendants and their threads, each audited; the audit log"
              + " outlives them. An epic's or a ticket's delete is qits:admin alone; a feature's or a"
              + " task's admits an agent bound to its project and obeys its epic's freeze. A campaign"
              + " is not deleted but dropped. The path names the entity by qualified id"
              + " (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The entity is gone",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkDeleted.class)))
  @APIResponse(
      responseCode = "403",
      description = "An agent deleting an epic or a ticket, or outside its own project")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description = "A campaign, or a feature or a task whose epic is no longer REPORTED")
  public WorkDeleted delete(@PathParam("qualifiedId") String qualifiedId) {
    doors.delete(identity, ids.resolve(qualifiedId));
    return new WorkDeleted(true);
  }
}
