package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.entity.EntityComment;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>One comment, addressed on its own id, on the thread of an entity of any archetype</b>
 * (qits-551): {@code PATCH} and {@code DELETE /projects/api/comments/{commentId}}. A root path
 * rather than a tail of {@code /entities/{id}/comments/…} for the reason {@code /features} and
 * {@code /tasks} are: a comment id is unique on its own, so a client holding one need not remember
 * which entity it hangs under. {@code PUT /ticket-comments/{id}} is the ticket-only predecessor,
 * kept as a delegate.
 *
 * <h2>The edit is a JSON merge patch, and the text is all it edits</h2>
 *
 * <p>Read as a {@link JsonNode} for the reason {@link EntityPatchController} gives — a bound record
 * cannot tell an absent property from a null one — and refused in the same shape: every complaint in
 * one 400. {@code body} is the <b>only</b> property: {@code author} is stamped at create and never
 * moves (who wrote a remark and who last changed it are different facts, and the second one is the
 * audit log's — {@code TicketMcpToolsTest.editsARemarkAndLeavesItsAuthorAlone} pins the same rule
 * on the tool), and the rest are the server's. So {@code {}}, an unknown or server-owned property,
 * {@code "body": null} (a comment cannot be emptied — delete it) and a blank body are each a 400.
 * There is deliberately <b>no author check</b>: anyone who may write the project may correct a
 * remark on it, which is the rule the thread has always had.
 *
 * <h2>Roles</h2>
 *
 * <p>The edit takes {@code qits:agent}, <b>bound to the agent's own project</b> — resolved from the
 * comment's entity before anything is written ({@link EntitiesAgentAccess}), so the order of refusals
 * is 404, then 403, then 400. The delete is {@code qits:admin} alone: deleting is on neither
 * surface, since an agent that could delete what it disagrees with could erase the record of its
 * own mistake. Both fire {@link ProjectChangeHint.Topic#of} the entity's archetype after the write.
 */
@Path("/comments")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class CommentController {

  /** RFC 7396's media type. Plain {@code application/json} is accepted beside it. */
  public static final String MERGE_PATCH_JSON = WorkEntityDoors.MERGE_PATCH_JSON;

  /** The edit and the delete themselves, shared with {@code /work} (qits-969). */
  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

  /**
   * The documentation of the body, and only that: the body itself is read as a {@link JsonNode}.
   */
  @Schema(
      name = "CommentPatch",
      description =
          "A JSON merge patch (RFC 7396) of one comment. body is the only property and is required:"
              + " it replaces the text, and may not be null or blank. id, entityId, author, createdAt"
              + " and updatedAt are server-owned; any other property is refused as unknown.")
  public record CommentPatch(
      @Schema(required = true, description = "The remark as it should now read, Markdown.")
          String body) {}

  @PATCH
  @Path("/{commentId}")
  @Consumes({MERGE_PATCH_JSON, MediaType.APPLICATION_JSON})
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Operation(
      summary = "Edit a comment's text (JSON merge patch)",
      description =
          "Replaces the body of a comment on an entity of any archetype. Only body is written; the"
              + " author stays who wrote it, and updatedAt moves.")
  @APIResponse(
      responseCode = "200",
      description = "The comment as it stands after the edit",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = EntityCommentController.CommentAnswer.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Every complaint about the body in one message: not an object, empty, body null or blank"
              + " or not a string, a server-owned or unknown property")
  @APIResponse(
      responseCode = "403",
      description = "An agent editing a comment outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No comment with this id")
  public EntityCommentController.CommentAnswer patch(
      @PathParam("commentId") String commentId,
      @RequestBody(
              required = true,
              content = {
                @Content(
                    mediaType = MERGE_PATCH_JSON,
                    schema = @Schema(implementation = CommentPatch.class)),
                @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = CommentPatch.class))
              })
          JsonNode patch) {
    EntityComment comment = doors.comment(commentId);
    return new EntityCommentController.CommentAnswer(
        doors.editComment(identity, doors.entityOf(comment), comment, patch));
  }

  public record DeleteCommentRequest() {
    @Schema(name = "CommentDeleted")
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{commentId}")
  @Operation(summary = "Delete a comment", description = "Removes a comment; audited. Admin only.")
  @APIResponse(
      responseCode = "200",
      description = "The comment is gone",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = DeleteCommentRequest.Response.class)))
  @APIResponse(responseCode = "404", description = "No comment with this id")
  public DeleteCommentRequest.Response delete(@PathParam("commentId") String commentId) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    EntityComment comment = doors.comment(commentId);
    doors.deleteComment(identity, doors.entityOf(comment), comment);
    return new DeleteCommentRequest.Response(true);
  }
}
