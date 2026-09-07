package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.dto.TicketCommentDto;
import eu.wohlben.qits.epics.mapper.TicketCommentMapper;
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
 * A single ticket comment, addressed on its own. Its own root path rather than a tail of {@code
 * /tickets/{id}/comments/{commentId}} for the reason {@code /features} and {@code /tasks} carry: a
 * comment id is unique on its own, so a client holding one should not have to also remember which
 * ticket it hung under. Creating and listing stay under the ticket, where the parent is the point.
 */
@Path("/ticket-comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TicketCommentController {

  @Inject TicketService ticketService;

  @Inject TicketCommentMapper commentMapper;

  @Inject SecurityIdentity identity;

  @Inject TicketChangeHints hints;

  /**
   * The body is the only editable field. The author is not re-stamped on an edit: it records who
   * wrote the remark, and who changed it afterwards is the audit log's answer.
   */
  public record UpdateTicketCommentRequest(@NotBlank String body) {
    public record Response(TicketCommentDto comment) {}
  }

  @PUT
  @Path("/{id}")
  public UpdateTicketCommentRequest.Response update(
      @PathParam("id") String id, @Valid UpdateTicketCommentRequest request) {
    var comment =
        ticketService.updateComment(id, request.body(), EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfTicket(comment.ticketId));
    return new UpdateTicketCommentRequest.Response(commentMapper.toDto(comment));
  }

  public record DeleteTicketCommentRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteTicketCommentRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfComment(id);
    ticketService.deleteComment(id, EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeleteTicketCommentRequest.Response(true);
  }
}
