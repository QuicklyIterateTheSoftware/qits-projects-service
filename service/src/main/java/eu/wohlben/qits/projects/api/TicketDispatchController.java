package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesPrincipal;
import eu.wohlben.qits.entities.control.TicketService;
import eu.wohlben.qits.entities.entity.WorkEntity;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * "Assign agent" on a ticket — <b>a thin delegate onto the one dispatch path</b> ({@link
 * EntityDispatch}, qits-394) in {@link DispatchMode#FLOW}, which is what this door always did: start
 * the phase the ticket's status implies and let each transition carry it on.
 *
 * <p><b>Retiring.</b> The deployed SPA still calls {@code POST /tickets/{id}/dispatch-agent}, so the
 * route keeps answering in this release, with its old response shape ({@link
 * TicketAgentDispatchDto}). It is removed — with its DTO — in a later release, once the SPA calls
 * {@code POST /entities/{id}/dispatch} instead. Nothing may be added here in the meantime: every
 * rule (the refusals, the comment, the address, the prompt) lives on the one path.
 *
 * <p>The ticket is resolved through {@link TicketService} first, so an id naming an epic answers
 * this door's own 404 rather than dispatching the epic.
 */
@Path("/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class TicketDispatchController {

  @Inject TicketService tickets;

  @Inject EntityDispatch dispatch;

  @Inject SecurityIdentity identity;

  /** No body: everything the dispatch needs is derived from the ticket it is about. */
  public record DispatchAgentRequest() {
    public record Response(TicketAgentDispatchDto dispatch) {}
  }

  @POST
  @Path("/{id}/dispatch-agent")
  public DispatchAgentRequest.Response dispatchAgent(@PathParam("id") String id) {
    WorkEntity ticket = tickets.get(id); // 404 if no TICKET has this id
    EntityDispatch.Outcome outcome =
        dispatch.dispatch(ticket, DispatchMode.FLOW, EntitiesPrincipal.changedBy(identity));
    return new DispatchAgentRequest.Response(
        TicketAgentDispatchDto.of(outcome.made(), outcome.repositoryId(), outcome.branch()));
  }
}
