package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.TicketCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.dto.TicketCommentDto;
import eu.wohlben.qits.entities.dto.TicketDto;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.mapper.TicketCommentMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * A single ticket and its comment thread.
 *
 * <p>A thin resource over {@link EntityRoutes}, the one implementation behind every per-archetype
 * route (qits-399): this class holds what the wire names — the paths, the request records, the
 * {@code {"ticket": …}} envelopes and the role lists. The ticket's words are edited through {@code
 * POST /entities/transition} (or the {@code update_ticket} tool); the per-ticket {@code PUT} went in
 * qits-399. What is a ticket's alone — the block door and the thread — is here.
 */
@Path("/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TicketController {

  @Inject EntityRoutes routes;

  @Inject WorkEntityService entities;

  @Inject TicketCommentService comments;

  @Inject TicketCommentMapper commentMapper;

  @Inject SecurityIdentity identity;

  @Inject TicketsTopicHints hints;

  /**
   * The block door's whole rule — the refusal, the row and the remark — shared with the two MCP
   * tools over the same write. It is in {@code projects.api} because what a status means for the
   * work — whether a phase runs — is decided there.
   */
  @Inject eu.wohlben.qits.projects.api.TicketBlocks blocks;

  // --- Ticket ---

  public record GetTicketRequest() {
    public record Response(TicketDto ticket) {}
  }

  /**
   * The detail read, and the one place a single ticket carries its workspaces. The writes below
   * answer the row they changed and leave the field empty: an edit is not the question "who is
   * working on this", and the client re-reads.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}")
  public GetTicketRequest.Response get(@PathParam("id") String id) {
    return new GetTicketRequest.Response(routes.get(routes.tickets(), id));
  }

  /**
   * A lifecycle move. {@code target} is the status name; along REPORTED → REFINED → IMPLEMENTED →
   * VERIFIED → DONE the move must be to a NEIGHBOUR of the ticket's current status — one step,
   * forward or back — and DROPPED sits off that line, reachable from any status that is not already
   * closed and reopening only to REPORTED. The rule is argued once, in {@code
   * EntityLifecycle.LEGAL_TARGETS}. A move the lifecycle does not allow (including a move to the
   * status the ticket already has), and a target naming no status, both answer 409 with a message;
   * an absent target is a 400.
   */
  public record TransitionTicketRequest(String target) {
    public record Response(TicketDto ticket) {}
  }

  /**
   * Moving a ticket takes {@code qits:agent}, bound to the agent's own project: the {@code
   * transition_ticket} MCP tool already performs this write for an agent, lifecycle rule and all.
   * It starts a phase the agent is itself the subject of, and — like an epic's — a resolving move
   * discards the ticket's refinement room first (qits-395). See {@link EntitiesAgentAccess}.
   */
  @POST
  @Path("/{id}/transition")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public TransitionTicketRequest.Response transition(
      @PathParam("id") String id, @Valid TransitionTicketRequest request) {
    return new TransitionTicketRequest.Response(
        routes.transition(routes.tickets(), id, request.target(), true, identity).entity());
  }

  /**
   * <b>Blocking is not part of the edit, and that separation is the same one the status has.</b>
   * An edit deliberately cannot move the status, because a statement about where
   * the work stands is a different act from editing the text that describes it; "the phase running
   * now cannot finish" is exactly such a statement, so it gets a door of its own rather than a
   * field on the edit form — otherwise a retitle could assert that somebody is stuck.
   *
   * <p>{@code reason} is <b>required when blocking</b> (400 if blank) and optional when
   * unblocking: a block with no stated blocker is one nobody can clear. It is recorded on the
   * ticket's thread as a comment rather than stored on the row — see {@link
   * eu.wohlben.qits.projects.api.TicketBlocks}.
   *
   * <p>Blocking a ticket whose status starts no phase — VERIFIED, DONE or DROPPED — is a <b>409</b>
   * saying there is no phase to block.
   */
  public record SetTicketBlockedRequest(boolean blocked, String reason) {
    public record Response(TicketDto ticket) {}
  }

  /**
   * Blocking a ticket takes {@code qits:agent}, bound to the agent's own project, for the reason
   * every other granted write here does: the {@code block_ticket} and {@code unblock_ticket} MCP
   * tools already perform this write for an agent, and the agent working the phase is the one that
   * knows it is stuck. See {@link EntitiesAgentAccess}.
   */
  @POST
  @Path("/{id}/blocked")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public SetTicketBlockedRequest.Response setBlocked(
      @PathParam("id") String id, @Valid SetTicketBlockedRequest request) {
    EntitiesAgentAccess.requireProject(identity, hints.projectOfTicket(id));
    var ticket =
        blocks.apply(
            entities.get(Archetype.TICKET, id),
            request.blocked(),
            request.reason(),
            EntitiesPrincipal.changedBy(identity));
    hints.fire(ticket.projectId);
    return new SetTicketBlockedRequest.Response(
        routes.tickets().qualify().apply(routes.tickets().render(ticket, null)));
  }

  public record DeleteTicketRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteTicketRequest.Response delete(@PathParam("id") String id) {
    routes.delete(routes.tickets(), id, false, identity);
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
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{ticketId}/comments")
  public ListTicketCommentsRequest.Response listComments(@PathParam("ticketId") String ticketId) {
    entities.get(Archetype.TICKET, ticketId); // 404 if the ticket does not exist
    var entries =
        comments.listComments(ticketId).stream()
            .map(c -> new ListTicketCommentsRequest.Response.Entry(commentMapper.toDto(c)))
            .toList();
    return new ListTicketCommentsRequest.Response(entries);
  }

  /** No {@code author} on the request: it is stamped from the caller's identity. */
  public record CreateTicketCommentRequest(@NotBlank String body) {
    public record Response(TicketCommentDto comment) {}
  }

  /**
   * Commenting takes {@code qits:agent}, bound to the agent's own project: the {@code
   * add_ticket_comment} MCP tool already performs this write for an agent. The ticket's project is
   * resolved before the write and reused for the hint — see {@link EntitiesAgentAccess}.
   */
  @POST
  @Path("/{ticketId}/comments")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CreateTicketCommentRequest.Response createComment(
      @PathParam("ticketId") String ticketId, @Valid CreateTicketCommentRequest request) {
    String projectId = hints.projectOfTicket(ticketId); // 404 if the ticket does not exist
    EntitiesAgentAccess.requireProject(identity, projectId);
    var comment =
        comments.addComment(ticketId, request.body(), EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new CreateTicketCommentRequest.Response(commentMapper.toDto(comment));
  }
}
