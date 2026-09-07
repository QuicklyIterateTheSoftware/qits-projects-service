package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketComment;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * The ticket half of the "repository" MCP server — the surface an agent files and works bugs and
 * improvements through, mounted on the same declared server as {@link RepositoryMcpTools} and
 * {@link EpicMcpTools} ({@code /projects/mcp}) for the reason that class's javadoc gives.
 *
 * <p><strong>Use case: the small thing found on the way.</strong> An agent working in a repository
 * notices something wrong that is not what it was asked to do. Filing it as an epic would put a
 * plan on the board where a note belongs; {@code create_ticket} is the note. The same surface reads
 * the open tickets, so an agent asked to "fix the outstanding bugs" has somewhere to look.
 *
 * <p><strong>Unlike epics, the transition IS here.</strong> {@code EpicMcpTools} deliberately
 * exposes no lifecycle move, because freezing a plan is a human decision about committing to scope.
 * Resolving a ticket is a statement about work that is done, which is exactly the thing the agent
 * that did it knows and nobody else does yet — and it is reversible, so a wrong answer costs a
 * click rather than a superseded epic.
 *
 * <p>Scope comes from {@link ProjectScope} (the {@code X-QITS-Project} header), never from a tool
 * argument, and every id a tool is handed is checked back to that project — a ticket or comment in
 * another project reads as not found, so the model cannot work across project boundaries.
 *
 * <p>{@link WrapBusinessError} turns anything a tool throws into a tool result with {@code
 * isError=true} carrying the message, so a refusal is something the model can read and act on
 * rather than a JSON-RPC protocol error that kills the turn.
 *
 * <p>Every mutating tool fires a {@link ProjectChangeHint} on {@code TICKETS}, so a browser
 * watching the ticket list redraws as the agent works — and every one of them is registered in
 * {@link ReadOnlyRepositoryToolFilter}, which fails closed.
 *
 * <p><strong>No {@code @Transactional} here</strong>, for the reason {@link EpicMcpTools} states:
 * these tools straddle two persistence units and Narayana enlists only one local resource per
 * transaction. The ticket service opens its own, exactly as it does for the REST controllers.
 */
@ApplicationScoped
@WrapBusinessError
public class TicketMcpTools {

  /**
   * What the audit log records for a write with no forwarded identity. An MCP session is a machine
   * caller; naming it beats a null {@code changed_by} that reads as "unknown human".
   */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject TicketService ticketService;

  @Inject ProjectChangePublisher changePublisher;

  @Inject SecurityIdentity identity;

  // --- Result shapes --------------------------------------------------------

  /** A ticket as it appears in a list: no thread, just enough to choose one. */
  public record TicketSummary(
      String id,
      String slug,
      String title,
      String type,
      String status,
      String assignee,
      String createdBy,
      String description) {}

  /** One remark inside {@link TicketDetail}. */
  public record CommentDetail(String id, String author, String body, Instant createdAt) {}

  /** One ticket with its whole thread, oldest first. */
  public record TicketDetail(
      String id,
      String slug,
      String title,
      String type,
      String status,
      String assignee,
      String createdBy,
      String description,
      List<CommentDetail> comments) {}

  // --- Tickets --------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_tickets",
      description =
          "List the tickets of the project this session is scoped to, oldest first, without their"
              + " comments. A ticket is a small-scoped piece of work — a BUG or an IMPROVEMENT —"
              + " that is not big enough to be an epic. Call it with status=\"OPEN\" to find what"
              + " is still outstanding.")
  public List<TicketSummary> listTickets(
      @ToolArg(
              required = false,
              description = "exact status to filter by: OPEN or RESOLVED. Omit for every ticket.")
          String status) {
    return ticketService.listByProject(scope.requireProjectId(), status).stream()
        .map(TicketMcpTools::summarize)
        .toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_ticket",
      description =
          "Read one ticket of this project in full: its description plus every comment on it,"
              + " oldest first. Read it before working on a ticket — the thread is usually where"
              + " the reproduction and the decisions are.")
  public TicketDetail getTicket(@ToolArg(description = "id of a ticket in this project") String id) {
    Ticket ticket = requireTicketInProject(id);
    List<CommentDetail> comments =
        ticketService.listComments(ticket.id).stream()
            .map(c -> new CommentDetail(c.id, c.author, c.body, c.createdAt))
            .toList();
    return new TicketDetail(
        ticket.id,
        ticket.slug,
        ticket.title,
        ticket.type.name(),
        ticket.status.name(),
        ticket.assignee,
        ticket.createdBy,
        ticket.description,
        comments);
  }

  @McpServer("repository")
  @Tool(
      name = "create_ticket",
      description =
          "File a new ticket on this project. It is created OPEN. Use this for the small thing you"
              + " found on the way — a defect, or something that works and could work better —"
              + " rather than proposing an epic for it: an epic is a plan, a ticket is a note. Say"
              + " in the description how to see the problem, not just that it exists.")
  public TicketSummary createTicket(
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(description = "BUG for something behaving wrongly, IMPROVEMENT for something that"
              + " works and could be better")
          String type,
      @ToolArg(required = false, description = "the long-form Markdown body") String description,
      @ToolArg(required = false, description = "who is looking at it; omit for nobody")
          String assignee) {
    Ticket ticket =
        ticketService.create(
            scope.requireProjectId(), title, description, type, assignee, changedBy());
    announce();
    return summarize(ticket);
  }

  @McpServer("repository")
  @Tool(
      name = "update_ticket",
      description =
          "Change a ticket's title, description, type or assignee. Omitted fields keep their"
              + " current value. The status is not editable here — use transition_ticket, which is"
              + " the only thing that moves it.")
  public TicketSummary updateTicket(
      @ToolArg(description = "id of a ticket in this project") String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description,
      @ToolArg(required = false, description = "BUG or IMPROVEMENT; omit to keep it") String type,
      @ToolArg(required = false, description = "new assignee; omit to keep the current one")
          String assignee) {
    requireTicketInProject(id);
    // Omitted means unchanged on this surface: the clear flags the REST route carries are a
    // deliberate act in a form, and a model that meant "no value" would reach for a null it cannot
    // express here anyway.
    Ticket ticket =
        ticketService.update(id, title, description, false, type, assignee, false, changedBy());
    announce();
    return summarize(ticket);
  }

  @McpServer("repository")
  @Tool(
      name = "transition_ticket",
      description =
          "Move a ticket to RESOLVED when the work is done, or back to OPEN when it turns out not"
              + " to be. Both directions are legal and nothing is frozen by either — unlike an"
              + " epic, a ticket you resolve by mistake simply reopens. Asking for the status it"
              + " already has is refused.")
  public TicketSummary transitionTicket(
      @ToolArg(description = "id of a ticket in this project") String id,
      @ToolArg(description = "the status to move to: RESOLVED or OPEN") String target) {
    requireTicketInProject(id);
    Ticket ticket = ticketService.transition(id, target, changedBy());
    announce();
    return summarize(ticket);
  }

  @McpServer("repository")
  @Tool(
      name = "add_ticket_comment",
      description =
          "Add a remark to a ticket's thread — what you found, what you tried, why you resolved it"
              + " the way you did. The thread is what the next reader has to go on, so a resolution"
              + " with no comment is a resolution nobody can check.")
  public CommentDetail addTicketComment(
      @ToolArg(description = "id of a ticket in this project") String ticketId,
      @ToolArg(description = "the remark, Markdown") String body) {
    requireTicketInProject(ticketId);
    TicketComment comment = ticketService.addComment(ticketId, body, changedBy());
    announce();
    return new CommentDetail(comment.id, comment.author, comment.body, comment.createdAt);
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * Ensures {@code ticketId} names a ticket of the scoped project. A ticket elsewhere reads as not
   * found rather than as forbidden — the model is told nothing about what other projects hold.
   */
  private Ticket requireTicketInProject(String ticketId) {
    Ticket ticket = ticketService.get(ticketId);
    if (!scope.requireProjectId().equals(ticket.projectId)) {
      throw new NotFoundException("Ticket not found in this project: " + ticketId);
    }
    return ticket;
  }

  // --- Plumbing -------------------------------------------------------------

  /** Tell the project's browsers to re-read the tickets. */
  private void announce() {
    changePublisher.fire(scope.requireProjectId(), ProjectChangeHint.Topic.TICKETS);
  }

  /** The audit's {@code changed_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }

  private static TicketSummary summarize(Ticket ticket) {
    return new TicketSummary(
        ticket.id,
        ticket.slug,
        ticket.title,
        ticket.type.name(),
        ticket.status.name(),
        ticket.assignee,
        ticket.createdBy,
        ticket.description);
  }
}
