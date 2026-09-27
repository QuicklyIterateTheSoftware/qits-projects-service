package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.TicketComment;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.TicketCommentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * <b>A ticket's thread</b>: its comments, oldest first, each write audited under the ticket's id.
 *
 * <p>This was the second half of {@code TicketService} until qits-399 folded the four archetype
 * services into {@link WorkEntityService}. A comment is not an archetype of the merged model — it
 * has no slug, no number and no membership, and lives in its own table — so it did not fold with
 * them; it moved here instead, with nothing about it changed but the class it is in. The in-service
 * cascade that removes a ticket's comments with it is {@link WorkEntityService#delete}'s, so every
 * removed remark still gets its own DELETE audit row.
 *
 * <p><b>Audit rows carry the TICKET's id as their subtree key</b> — the {@code epicId} argument of
 * {@code AuditService.record}, the root a change hangs under rather than a foreign key to an epic —
 * so a ticket's own rows and its comments' rows are one indexed query that still answers after the
 * ticket is deleted.
 *
 * <p>{@code changedBy} is stored as the {@code author} and is never client-supplied; an edit does
 * not re-stamp it, because who wrote a remark and who last changed it are different facts and the
 * second one is the log's.
 */
@ApplicationScoped
public class TicketCommentService {

  @Inject WorkEntityService entities;

  @Inject TicketCommentRepository commentRepository;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /**
   * A ticket's comments, oldest first, held through a postgres cutover ({@link ReadPatience}): a
   * severed connection would draw a discussion that never happened, which is an answer rather than
   * an outage.
   */
  public List<TicketComment> listComments(String ticketId) {
    return patience.hold("ticket comments", () -> commentRepository.listByTicket(ticketId));
  }

  public TicketComment getComment(String id) {
    return commentRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Ticket comment not found: " + id));
  }

  /** A new comment on an existing ticket (404 if there is none). */
  public TicketComment addComment(String ticketId, String body, String changedBy) {
    Validations.requireText(body, "body");
    return writes.hold(
        "ticket comment create",
        () -> {
          WorkEntity ticket = entities.get(Archetype.TICKET, ticketId);
          TicketComment comment = new TicketComment();
          comment.id = UUID.randomUUID().toString();
          comment.ticketId = ticket.id;
          comment.author = changedBy;
          comment.body = body;
          commentRepository.persist(comment);
          auditService.record(
              AuditEntityType.TICKET_COMMENT,
              comment.id,
              ticket.id,
              AuditOperation.CREATE,
              changedBy,
              comment);
          return comment;
        });
  }

  /** Edits a comment's body; the author is not re-stamped. */
  public TicketComment updateComment(String id, String body, String changedBy) {
    Validations.requireText(body, "body");
    return writes.hold(
        "ticket comment update",
        () -> {
          TicketComment comment = getComment(id);
          comment.body = body;
          auditService.record(
              AuditEntityType.TICKET_COMMENT,
              comment.id,
              comment.ticketId,
              AuditOperation.UPDATE,
              changedBy,
              comment);
          return comment;
        });
  }

  public void deleteComment(String id, String changedBy) {
    writes.run(
        "ticket comment delete",
        () -> {
          TicketComment comment = getComment(id);
          commentRepository.delete(comment);
          auditService.record(
              AuditEntityType.TICKET_COMMENT,
              id,
              comment.ticketId,
              AuditOperation.DELETE,
              changedBy,
              comment);
        });
  }
}
