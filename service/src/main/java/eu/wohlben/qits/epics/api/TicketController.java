package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.dto.TicketCommentDto;
import eu.wohlben.qits.epics.dto.TicketDto;
import eu.wohlben.qits.epics.mapper.TicketCommentMapper;
import eu.wohlben.qits.epics.mapper.TicketMapper;
import eu.wohlben.qits.projects.api.DispatchedWorkspaces;
import eu.wohlben.qits.projects.validation.NotBlankIfPresent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** A single ticket and its comment thread. */
@Path("/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TicketController {

  @Inject TicketService ticketService;

  @Inject TicketMapper ticketMapper;

  @Inject TicketCommentMapper commentMapper;

  @Inject SecurityIdentity identity;

  @Inject TicketChangeHints hints;

  /** Which live workspaces are on this ticket — derived per read; see {@link DispatchedWorkspaces}. */
  @Inject DispatchedWorkspaces dispatchedWorkspaces;

  // --- Ticket ---

  public record GetTicketRequest() {
    public record Response(TicketDto ticket) {}
  }

  /**
   * The detail read, and the one place a single ticket carries its workspaces. The writes below
   * answer the row they changed and leave the field empty: an edit is not the question "who is
   * working on this", and asking a sibling service on every keystroke's save would be a round trip
   * bought for nothing — the client re-reads.
   */
  @GET
  @Path("/{id}")
  public GetTicketRequest.Response get(@PathParam("id") String id) {
    return new GetTicketRequest.Response(
        dispatchedWorkspaces.decorate(ticketMapper.toDto(ticketService.get(id))));
  }

  /**
   * Partial update: a null {@code title}/{@code type} leaves it unchanged. The two nullable fields
   * change only when their {@code clear*} flag is true (→ cleared) or a non-null value is supplied
   * (→ set), so a retitle can't silently unassign a ticket or drop its body — the pairing {@code
   * FeatureController.UpdateFeatureRequest} carries.
   *
   * <p>The status is deliberately absent: {@link #transition} is the only thing that moves it.
   */
  public record UpdateTicketRequest(
      @NotBlankIfPresent String title,
      String description,
      boolean clearDescription,
      @NotBlankIfPresent String type,
      String assignee,
      boolean clearAssignee) {
    public record Response(TicketDto ticket) {}
  }

  @PUT
  @Path("/{id}")
  public UpdateTicketRequest.Response update(
      @PathParam("id") String id, @Valid UpdateTicketRequest request) {
    var ticket =
        ticketService.update(
            id,
            request.title(),
            request.description(),
            request.clearDescription(),
            request.type(),
            request.assignee(),
            request.clearAssignee(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(ticket.projectId);
    return new UpdateTicketRequest.Response(ticketMapper.toDto(ticket));
  }

  /**
   * A lifecycle move. {@code target} is the status name — {@code RESOLVED} or {@code OPEN}, both
   * directions being legal. A move the lifecycle does not allow, and a target naming no status,
   * both answer 409 with a message; an absent target is a 400.
   */
  public record TransitionTicketRequest(String target) {
    public record Response(TicketDto ticket) {}
  }

  @POST
  @Path("/{id}/transition")
  public TransitionTicketRequest.Response transition(
      @PathParam("id") String id, @Valid TransitionTicketRequest request) {
    var ticket =
        ticketService.transition(id, request.target(), EpicsPrincipal.changedBy(identity));
    hints.fire(ticket.projectId);
    return new TransitionTicketRequest.Response(ticketMapper.toDto(ticket));
  }

  public record DeleteTicketRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteTicketRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfTicket(id);
    ticketService.delete(id, EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeleteTicketRequest.Response(true);
  }

  // --- Comments under a ticket ---

  public record ListTicketCommentsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(TicketCommentDto comment) {}
    }
  }

  /** The ticket's thread, OLDEST FIRST — a conversation is read from the start. */
  @GET
  @Path("/{ticketId}/comments")
  public ListTicketCommentsRequest.Response listComments(@PathParam("ticketId") String ticketId) {
    ticketService.get(ticketId); // 404 if the ticket does not exist
    var entries =
        ticketService.listComments(ticketId).stream()
            .map(c -> new ListTicketCommentsRequest.Response.Entry(commentMapper.toDto(c)))
            .toList();
    return new ListTicketCommentsRequest.Response(entries);
  }

  /** No {@code author} on the request: it is stamped from the caller's identity. */
  public record CreateTicketCommentRequest(@NotBlank String body) {
    public record Response(TicketCommentDto comment) {}
  }

  @POST
  @Path("/{ticketId}/comments")
  public CreateTicketCommentRequest.Response createComment(
      @PathParam("ticketId") String ticketId, @Valid CreateTicketCommentRequest request) {
    var comment =
        ticketService.addComment(ticketId, request.body(), EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfTicket(ticketId));
    return new CreateTicketCommentRequest.Response(commentMapper.toDto(comment));
  }
}
