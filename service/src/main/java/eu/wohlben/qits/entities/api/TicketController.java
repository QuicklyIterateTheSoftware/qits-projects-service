package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.TicketService;
import eu.wohlben.qits.entities.dto.TicketCommentDto;
import eu.wohlben.qits.entities.dto.TicketDto;
import eu.wohlben.qits.entities.mapper.TicketCommentMapper;
import eu.wohlben.qits.entities.mapper.WorkEntityMapper;
import eu.wohlben.qits.projects.api.DispatchedWorkspaces;
import eu.wohlben.qits.projects.api.TicketPhaseAdvance;
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
import org.jboss.logging.Logger;

/**
 * A single ticket and its comment thread.
 *
 * <h2>One injection points the other way, and it is the service layer's crossing</h2>
 *
 * <p>{@link #transition} calls {@link TicketPhaseAdvance}, which lives in {@code
 * eu.wohlben.qits.projects.api} because it needs the project, the wrapper repository and a workspace
 * port. That is not the epics <b>jar</b> learning about {@code domain}: this class is under {@code
 * service/}, the module that assembles both, and it is only the package name that reads like the
 * entities module. The {@code entities} jar itself still depends on {@code domain} nowhere, which is what
 * keeps it liftable — and the same crossing {@code DispatchedWorkspaces} already makes below, one
 * field up.
 */
@Path("/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TicketController {

  private static final Logger LOG = Logger.getLogger(TicketController.class);

  @Inject TicketService ticketService;

  /** One mapper where there were four; this route answers the ticket shape. */
  @Inject WorkEntityMapper workEntityMapper;

  @Inject TicketCommentMapper commentMapper;

  @Inject SecurityIdentity identity;

  @Inject TicketsTopicHints hints;

  /** Which live workspaces are on this ticket — derived per read; see {@link DispatchedWorkspaces}. */
  @Inject DispatchedWorkspaces dispatchedWorkspaces;

  /**
   * The qualified id {@code <project-slug>-<number>} every answer here carries. One batched slug
   * lookup per listing; see {@link eu.wohlben.qits.projects.api.QualifiedEntityIds}, and
   * {@code DispatchedWorkspaces} for why the crossing into {@code domain} lives in that package.
   */
  @Inject eu.wohlben.qits.projects.api.QualifiedEntityIds qualifiedIds;

  /** The phase a transition starts, delivered into the workspace on the ticket's branch. */
  @Inject TicketPhaseAdvance phaseAdvance;

  /**
   * The block door's whole rule — the refusal, the row and the remark — shared with the two MCP
   * tools over the same write. It is in {@code projects.api} for {@link TicketPhaseAdvance}'s
   * reason: what a status means for the work is decided there.
   */
  @Inject eu.wohlben.qits.projects.api.TicketBlocks blocks;

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
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}")
  public GetTicketRequest.Response get(@PathParam("id") String id) {
    return new GetTicketRequest.Response(
        qualifiedIds.qualify(
            dispatchedWorkspaces.decorate(workEntityMapper.toTicketDto(ticketService.get(id)))));
  }

  /**
   * Partial update: a null {@code title}/{@code type} leaves it unchanged. The three nullable
   * fields change only when their {@code clear*} flag is true (→ cleared) or a non-null value is
   * supplied (→ set), so a retitle can't silently unassign a ticket or drop its body — the pairing
   * {@code FeatureController.UpdateFeatureRequest} carries.
   *
   * <p>{@code impetus} is editable here because triage corrects reports; what it must not become is
   * a second place to write the refinement — see {@code Ticket.impetus} for the length rule.
   *
   * <p>The status is deliberately absent: {@link #transition} is the only thing that moves it.
   */
  public record UpdateTicketRequest(
      @NotBlankIfPresent String title,
      @NotBlankIfPresent String impetus,
      boolean clearImpetus,
      String description,
      boolean clearDescription,
      @NotBlankIfPresent String type,
      String assignee,
      boolean clearAssignee) {
    public record Response(TicketDto ticket) {}
  }

  /**
   * Editing a ticket takes {@code qits:agent}, bound to the agent's own project: the {@code
   * update_ticket} MCP tool already performs this write for an agent. The project is resolved from
   * the ticket before the write, so an id naming nothing is the 404 it always was — see {@link
   * EntitiesAgentAccess}.
   */
  @PUT
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public UpdateTicketRequest.Response update(
      @PathParam("id") String id, @Valid UpdateTicketRequest request) {
    EntitiesAgentAccess.requireProject(identity, hints.projectOfTicket(id));
    var ticket =
        ticketService.update(
            id,
            request.title(),
            request.impetus(),
            request.clearImpetus(),
            request.description(),
            request.clearDescription(),
            request.type(),
            request.assignee(),
            request.clearAssignee(),
            EntitiesPrincipal.changedBy(identity));
    hints.fire(ticket.projectId);
    return new UpdateTicketRequest.Response(
        qualifiedIds.qualify(workEntityMapper.toTicketDto(ticket)));
  }

  /**
   * A lifecycle move. {@code target} is the status name; along REPORTED → REFINED → IMPLEMENTED →
   * VERIFIED → DONE the move must be to a NEIGHBOUR of the ticket's current status — one step,
   * forward or back — and DROPPED sits off that line, reachable from any status that is not already
   * closed and reopening only to REPORTED. The rule is argued once, in {@code
   * TicketLifecycle.LEGAL_TARGETS}. A move the lifecycle does not allow (including a move to the
   * status the ticket already has), and a target naming no status, both answer 409 with a message;
   * an absent target is a 400.
   */
  public record TransitionTicketRequest(String target) {
    public record Response(TicketDto ticket) {}
  }

  /**
   * Moving a ticket takes {@code qits:agent}, bound to the agent's own project: the {@code
   * transition_ticket} MCP tool already performs this write for an agent, lifecycle rule and all.
   * Unlike an epic's transition, this one resolves nothing and starts a phase the agent is itself
   * the subject of. See {@link EntitiesAgentAccess}.
   */
  @POST
  @Path("/{id}/transition")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public TransitionTicketRequest.Response transition(
      @PathParam("id") String id, @Valid TransitionTicketRequest request) {
    EntitiesAgentAccess.requireProject(identity, hints.projectOfTicket(id));
    String changedBy = EntitiesPrincipal.changedBy(identity);
    var ticket = ticketService.transition(id, request.target(), changedBy);
    hints.fire(ticket.projectId);
    // AFTER the move is recorded and outside its transaction, like the hint above: the next phase
    // is started from the status the ticket now holds, and a transition that rolled back speaks to
    // nobody. See TicketPhaseAdvance for why this is not a step inside TicketService.transition.
    try {
      phaseAdvance.afterTransition(ticket, changedBy);
    } catch (RuntimeException e) {
      // It says it must not throw; a throw is a bug in it and must not touch a transition that has
      // already been recorded and already been answered for.
      LOG.warnf(e, "Could not start the phase ticket %s just moved into", ticket.id);
    }
    return new TransitionTicketRequest.Response(
        qualifiedIds.qualify(workEntityMapper.toTicketDto(ticket)));
  }

  /**
   * <b>Blocking is not part of {@link #update}, and that separation is the same one the status
   * has.</b> {@code update} deliberately cannot move the status, because a statement about where
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
            ticketService.get(id),
            request.blocked(),
            request.reason(),
            EntitiesPrincipal.changedBy(identity));
    hints.fire(ticket.projectId);
    return new SetTicketBlockedRequest.Response(
        qualifiedIds.qualify(workEntityMapper.toTicketDto(ticket)));
  }

  public record DeleteTicketRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteTicketRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfTicket(id);
    ticketService.delete(id, EntitiesPrincipal.changedBy(identity));
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
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
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
        ticketService.addComment(ticketId, request.body(), EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new CreateTicketCommentRequest.Response(commentMapper.toDto(comment));
  }
}
