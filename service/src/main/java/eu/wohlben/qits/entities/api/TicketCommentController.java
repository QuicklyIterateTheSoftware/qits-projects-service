package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.dto.TicketCommentDto;
import eu.wohlben.qits.entities.mapper.EntityCommentMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A single ticket comment, addressed on its own — <b>the ticket-only predecessor of {@link
 * CommentController}</b> ({@code PATCH}/{@code DELETE /comments/{commentId}}, qits-551), kept as a
 * thin delegate onto the one comment service because the released CLI and SPA call it. Its shape
 * does not move: a {@code PUT} of the whole body, answered with {@code ticketId}, and the refusals
 * it always gave. It retires once both clients are on the new routes.
 */
@Path("/ticket-comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TicketCommentController {

  @Inject EntityCommentService comments;

  @Inject EntityCommentMapper commentMapper;

  @Inject SecurityIdentity identity;

  @Inject TicketsTopicHints hints;

  /**
   * The body is the only editable field. The author is not re-stamped on an edit: it records who
   * wrote the remark, and who changed it afterwards is the audit log's answer.
   */
  public record UpdateTicketCommentRequest(@NotBlank String body) {
    public record Response(TicketCommentDto comment) {}
  }

  /**
   * Editing a comment takes {@code qits:agent}, bound to the agent's own project: the {@code
   * update_ticket_comment} MCP tool already performs this write for an agent. The delete below does
   * not, and stays {@code qits:admin} — see {@link EntitiesAgentAccess}.
   */
  @PUT
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public UpdateTicketCommentRequest.Response update(
      @PathParam("id") String id, @Valid UpdateTicketCommentRequest request) {
    EntitiesAgentAccess.requireProject(identity, hints.projectOfComment(id));
    var comment =
        comments.updateComment(id, request.body(), EntitiesPrincipal.changedBy(identity));
    hints.fire(hints.projectOfEntity(comment.entityId));
    return new UpdateTicketCommentRequest.Response(commentMapper.toTicketDto(comment));
  }

  public record DeleteTicketCommentRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteTicketCommentRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfComment(id);
    comments.deleteComment(id, EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeleteTicketCommentRequest.Response(true);
  }
}
