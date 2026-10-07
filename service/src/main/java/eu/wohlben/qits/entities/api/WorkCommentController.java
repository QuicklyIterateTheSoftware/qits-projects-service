package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.dto.CommentDto;
import eu.wohlben.qits.entities.entity.EntityComment;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A work entity's thread: {@code /projects/api/work/{qualifiedId}/comments}</b> (qits-969) —
 * read and comment on it, and edit or delete one comment <b>under its entity's address</b>, {@code
 * …/comments/{commentId}}. The comment's own address on {@code /comments/{commentId}} is the
 * {@code /entities} family's; here the path names the pair, so a comment on another entity's thread
 * is a 404, the same answer as one that does not exist.
 *
 * <p>{@code {qualifiedId}} is the qualified id or the UUID ({@link EntityIdResolver#resolve}). The
 * rules are {@link WorkEntityDoors}', shared with the {@code /entities} doors, and so are the roles:
 * reading and commenting admit {@code qits:agent} and {@code qits:system} (an agent's comment bound
 * to its own project), the edit admits the agent bound the same way, and the delete is {@code
 * qits:admin} alone — an agent that could delete what it disagrees with could erase the record of
 * its own mistake.
 */
@Path("/work/{qualifiedId}/comments")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent"})
public class WorkCommentController {

  @Inject EntityIdResolver ids;

  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

  // The answers carry explicit schema names: the document numbers every nested `Response` it
  // meets, and a new one would renumber the ones after it.

  @Schema(name = "WorkCommentList", description = "A work entity's thread, oldest first.")
  public record WorkCommentList(List<WorkCommentListEntry> entries) {}

  @Schema(name = "WorkCommentListEntry")
  public record WorkCommentListEntry(CommentDto comment) {}

  /** One comment, as every write on the thread answers it. */
  @Schema(name = "WorkCommentAnswer", description = "One comment on a work entity's thread.")
  public record WorkCommentAnswer(CommentDto comment) {}

  /** No {@code author} on the request: it is stamped from the caller's identity. */
  @Schema(name = "WorkCommentCreate", description = "A remark for a work entity's thread.")
  public record WorkCommentCreate(
      @NotBlank @Schema(required = true, description = "The remark, Markdown.") String body) {}

  /** The documentation of the edit's body, and only that: it is read as a {@link JsonNode}. */
  @Schema(
      name = "WorkCommentPatch",
      description =
          "A JSON merge patch (RFC 7396) of one comment. body is the only property and is required:"
              + " it replaces the text, and may not be null or blank. id, entityId, author, createdAt"
              + " and updatedAt are server-owned; any other property is refused as unknown.")
  public record WorkCommentPatch(
      @Schema(required = true, description = "The remark as it should now read, Markdown.")
          String body) {}

  @Schema(name = "WorkCommentDeleted", description = "The comment is gone.")
  public record WorkCommentDeleted(boolean success) {}

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent", "qits:system"})
  @Operation(
      operationId = "listWorkComments",
      summary = "Read a work entity's comment thread",
      description =
          "The thread of an entity of any archetype, oldest first. The path names the entity by"
              + " qualified id (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The thread, oldest first",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkCommentList.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkCommentList list(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkCommentList(
        doors.comments(ids.resolve(qualifiedId)).stream().map(WorkCommentListEntry::new).toList());
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent", "qits:system"})
  @Operation(
      operationId = "addWorkComment",
      summary = "Comment on a work entity",
      description =
          "Adds a remark to the thread of an entity of any archetype, named by qualified id or"
              + " UUID. The author is stamped from the caller.")
  @APIResponse(
      responseCode = "200",
      description = "The comment as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkCommentAnswer.class)))
  @APIResponse(responseCode = "400", description = "A blank body")
  @APIResponse(
      responseCode = "403",
      description = "An agent commenting outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkCommentAnswer add(
      @PathParam("qualifiedId") String qualifiedId, @Valid WorkCommentCreate request) {
    return new WorkCommentAnswer(
        doors.addComment(identity, ids.resolve(qualifiedId), request == null ? null : request.body()));
  }

  @PATCH
  @Path("/{commentId}")
  @Consumes({WorkEntityDoors.MERGE_PATCH_JSON, MediaType.APPLICATION_JSON})
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "editWorkComment",
      summary = "Edit a comment's text (JSON merge patch)",
      description =
          "Replaces the body of a comment on a work entity's thread. Only body is written; the"
              + " author stays who wrote it, and updatedAt moves.")
  @APIResponse(
      responseCode = "200",
      description = "The comment as it stands after the edit",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkCommentAnswer.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Every complaint about the body in one message: not an object, empty, body null or blank"
              + " or not a string, a server-owned or unknown property")
  @APIResponse(
      responseCode = "403",
      description = "An agent editing a comment outside its own project; nothing is written")
  @APIResponse(
      responseCode = "404",
      description = "No entity with this id, or no comment with this id on its thread")
  public WorkCommentAnswer edit(
      @PathParam("qualifiedId") String qualifiedId,
      @PathParam("commentId") String commentId,
      @RequestBody(
              required = true,
              content = {
                @Content(
                    mediaType = WorkEntityDoors.MERGE_PATCH_JSON,
                    schema = @Schema(implementation = WorkCommentPatch.class)),
                @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = WorkCommentPatch.class))
              })
          JsonNode patch) {
    WorkEntity entity = ids.resolve(qualifiedId);
    EntityComment comment = doors.comment(entity, commentId);
    return new WorkCommentAnswer(doors.editComment(identity, entity, comment, patch));
  }

  @DELETE
  @Path("/{commentId}")
  @Operation(
      operationId = "deleteWorkComment",
      summary = "Delete a comment",
      description = "Removes a comment from a work entity's thread; audited. Admin only.")
  @APIResponse(
      responseCode = "200",
      description = "The comment is gone",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkCommentDeleted.class)))
  @APIResponse(
      responseCode = "404",
      description = "No entity with this id, or no comment with this id on its thread")
  public WorkCommentDeleted delete(
      @PathParam("qualifiedId") String qualifiedId, @PathParam("commentId") String commentId) {
    WorkEntity entity = ids.resolve(qualifiedId);
    doors.deleteComment(identity, entity, doors.comment(entity, commentId));
    return new WorkCommentDeleted(true);
  }
}
