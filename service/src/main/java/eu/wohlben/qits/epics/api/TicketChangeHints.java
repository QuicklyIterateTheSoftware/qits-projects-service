package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a ticket or comment id into the project whose live channel has to hear about it, and fires
 * the hint. The twin of {@link EpicChangeHints} and deliberately a second bean rather than four
 * more methods on that one: the two announce different topics ({@code TICKETS} against {@code
 * EPICS}) and reach different services, so folding them together would mean one bean injecting
 * every planning service in the module to serve two unrelated channels.
 *
 * <p>Resolve <em>before</em> a delete: once the row is gone there is no way back to its project.
 */
@ApplicationScoped
class TicketChangeHints {

  @Inject ProjectChangePublisher publisher;

  @Inject TicketService ticketService;

  /** Announce that the project's tickets changed. */
  void fire(String projectId) {
    publisher.fire(projectId, ProjectChangeHint.Topic.TICKETS);
  }

  String projectOfTicket(String ticketId) {
    return ticketService.get(ticketId).projectId;
  }

  String projectOfComment(String commentId) {
    return projectOfTicket(ticketService.getComment(commentId).ticketId);
  }
}
