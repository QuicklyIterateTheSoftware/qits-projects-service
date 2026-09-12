package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.dto.TicketDto;
import eu.wohlben.qits.epics.mapper.TicketMapper;
import eu.wohlben.qits.projects.api.DispatchedWorkspaces;
import eu.wohlben.qits.projects.control.ProjectService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Tickets collection under a project — the twin of {@link ProjectEpicsController}, and a separate
 * collection rather than a filter on that one because a ticket is a sibling root and not a kind of
 * epic. {@code projectId} is validated against {@code domain} here (the epics module has no
 * dependency on {@code domain}) so a bad project yields a clean 404.
 */
@Path("/projects/{projectId}/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class ProjectTicketsController {

  @Inject TicketService ticketService;

  @Inject TicketMapper ticketMapper;

  @Inject ProjectService projectService;

  @Inject SecurityIdentity identity;

  @Inject TicketChangeHints hints;

  /** One lookup for the whole listing — see {@link DispatchedWorkspaces}. */
  @Inject DispatchedWorkspaces dispatchedWorkspaces;

  public record ListTicketsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(TicketDto ticket) {}
    }
  }

  /**
   * The project's tickets, oldest first, optionally narrowed to one status. {@code status} is the
   * status name; a value naming none is a 400, so a typo in the filter does not read as "no
   * tickets".
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public ListTicketsRequest.Response list(
      @PathParam("projectId") String projectId, @QueryParam("status") String status) {
    projectService.get(projectId); // 404 if the project does not exist
    // Mapped first, then decorated in one call: the workspaces lookup is asked once about the whole
    // page, never once per row.
    var entries =
        dispatchedWorkspaces
            .decorateTickets(
                ticketService.listByProject(projectId, status).stream()
                    .map(ticketMapper::toDto)
                    .toList())
            .stream()
            .map(ListTicketsRequest.Response.Entry::new)
            .toList();
    return new ListTicketsRequest.Response(entries);
  }

  /**
   * {@code type} is the {@code TicketType} name and is required — a ticket that says neither bug
   * nor improvement is a report nobody can triage. There is deliberately no {@code createdBy} on
   * this request: it is stamped from the caller's identity, so nobody can file a ticket as somebody
   * else.
   */
  public record CreateTicketRequest(
      @NotBlank String title, String description, @NotBlank String type, String assignee) {
    public record Response(TicketDto ticket) {}
  }

  @POST
  public CreateTicketRequest.Response create(
      @PathParam("projectId") String projectId, @Valid CreateTicketRequest request) {
    projectService.get(projectId); // 404 if the project does not exist
    var ticket =
        ticketService.create(
            projectId,
            request.title(),
            request.description(),
            request.type(),
            request.assignee(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new CreateTicketRequest.Response(ticketMapper.toDto(ticket));
  }
}
