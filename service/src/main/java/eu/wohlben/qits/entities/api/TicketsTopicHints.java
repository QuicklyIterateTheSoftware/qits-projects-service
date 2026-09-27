package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.TicketCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a ticket or comment id into the project whose live channel has to hear about it, and fires
 * the hint. The twin of {@link EpicsTopicHints} and deliberately a second bean rather than more
 * methods on that one: the two announce different topics ({@code TICKETS} against {@code EPICS}),
 * and the topic a write redraws is the whole of the difference between them.
 *
 * <p><b>Named for the SSE topic it fires</b>, the same rule its twin carries and for the same
 * reason: {@code ProjectChangeHint.Topic.TICKETS} reaches the wire as {@code tickets} and the SPA
 * subscribes to it, so the topic is what survived the module rename and the bean is named after it.
 * The pair used to be {@code EpicChangeHints}/{@code TicketChangeHints}, which read as one bean per
 * entity kind; there is one entity kind now, and the difference between these two beans is only
 * which channel they redraw.
 *
 * <p>Resolve <em>before</em> a delete: once the row is gone there is no way back to its project.
 */
@ApplicationScoped
class TicketsTopicHints {

  @Inject ProjectChangePublisher publisher;

  @Inject WorkEntityService entities;

  @Inject TicketCommentService comments;

  /** Announce that the project's tickets changed. */
  void fire(String projectId) {
    publisher.fire(projectId, ProjectChangeHint.Topic.TICKETS);
  }

  String projectOfTicket(String ticketId) {
    return entities.get(Archetype.TICKET, ticketId).projectId;
  }

  String projectOfComment(String commentId) {
    return projectOfTicket(comments.getComment(commentId).ticketId);
  }
}
