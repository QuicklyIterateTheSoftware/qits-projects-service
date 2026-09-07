package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketComment;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.TicketType;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.ConflictException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.TicketCommentRepository;
import eu.wohlben.qits.epics.persistence.TicketRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * CRUD, lifecycle and comments for {@link Ticket}. {@code projectId} is stored verbatim —
 * cross-boundary existence against {@code domain}'s {@code Project} is validated in the {@code
 * service} controller (this module has no dependency on {@code domain}), exactly as {@link
 * EpicService} has it.
 *
 * <p>A new ticket starts {@link TicketStatus#OPEN}; {@link #transition} is the only thing that
 * moves the status, and {@link TicketLifecycle} is where the legal moves live. Nothing here
 * freezes: see that class for why a ticket has no equivalent of the epic scope freeze.
 *
 * <p><b>Audit rows carry the TICKET's id as their subtree key</b> — the {@code epicId} argument of
 * {@code AuditService.record}, which is the root a change hangs under rather than a foreign key to
 * an epic (see {@code AuditEntry.epicId}). So a ticket's own rows and its comments' rows are one
 * indexed query, and that query still answers after the ticket is deleted.
 *
 * <p><b>Every write is wrapped in {@link WritePatience} and none is {@code @Transactional}</b>, for
 * the reason that bean's javadoc gives: it owns the transaction boundary per attempt, and an
 * annotation beside it would join a transaction the retry cannot replace. Validations run
 * <em>before</em> the wrap where they need no row, so a missing title is a 400 on the first attempt
 * rather than a question retried for fifteen seconds. Reads go through {@link ReadPatience} for the
 * mirror-image reason: a severed connection would draw a project with no tickets, or a ticket whose
 * discussion never happened, and both read as an answer.
 */
@ApplicationScoped
public class TicketService {

  @Inject TicketRepository ticketRepository;

  @Inject TicketCommentRepository commentRepository;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  // --- Tickets --------------------------------------------------------------

  public List<Ticket> listByProject(String projectId) {
    return listByProject(projectId, null);
  }

  /**
   * The project's tickets, oldest first, optionally narrowed to one status. {@code status} is the
   * enum name; a value naming none is a 400 rather than an empty list, so a typo in the filter is
   * visible instead of reading as "no tickets". Parsed before the wrap, so that 400 lands on the
   * first attempt.
   */
  public List<Ticket> listByProject(String projectId, String status) {
    if (status == null || status.isBlank()) {
      return patience.hold("ticket list", () -> ticketRepository.listByProject(projectId));
    }
    TicketStatus filter =
        TicketLifecycle.parse(status)
            .orElseThrow(() -> new BadRequestException("Unknown ticket status: " + status));
    return patience.hold(
        "ticket list by status", () -> ticketRepository.listByProjectAndStatus(projectId, filter));
  }

  public Ticket get(String id) {
    return ticketRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Ticket not found: " + id));
  }

  /**
   * A new {@link TicketStatus#OPEN} ticket. {@code type} is the enum name and is required — a
   * ticket that says neither bug nor improvement is a report nobody can triage.
   *
   * <p>{@code changedBy} is both the audit principal and the stored {@code createdBy}: they are the
   * same fact asked at the same moment, and the column exists so a list has a reporter without a
   * join against the log. It is never a client-supplied value — the surfaces above read it from the
   * request identity.
   */
  public Ticket create(
      String projectId,
      String title,
      String description,
      String type,
      String assignee,
      String changedBy) {
    Validations.requireText(projectId, "projectId");
    Validations.requireText(title, "title");
    Validations.requireText(type, "type");
    TicketType kind =
        TicketLifecycle.parseType(type)
            .orElseThrow(() -> new BadRequestException("Unknown ticket type: " + type));
    return writes.hold(
        "ticket create",
        () -> {
          Ticket ticket = new Ticket();
          // Minted INSIDE the wrap, which is what makes a second attempt a fresh row rather than a
          // duplicate of a lost one.
          ticket.id = UUID.randomUUID().toString();
          ticket.projectId = projectId;
          ticket.title = title;
          // Minted once, at create, and never re-derived on update: the slug is a stable address
          // for the row, so retitling must not move it. Unique within the project.
          ticket.slug =
              Slugs.unique(
                  Slugs.slugify(title, ticket.id, "ticket-"),
                  ticketRepository.listByProject(projectId).stream().map(t -> t.slug).toList());
          ticket.type = kind;
          ticket.status = TicketStatus.OPEN;
          ticket.assignee = blankToNull(assignee);
          ticket.createdBy = changedBy;
          ticket.description = description;
          ticketRepository.persist(ticket);
          auditService.record(
              AuditEntityType.TICKET,
              ticket.id,
              ticket.id,
              AuditOperation.CREATE,
              changedBy,
              ticket);
          return ticket;
        });
  }

  /**
   * Partial update. A null {@code title}/{@code description}/{@code type} leaves that field
   * unchanged; the two nullable fields change only when their {@code clear*} flag is true (→
   * cleared) or a non-null value is supplied (→ set), so a retitle cannot silently unassign a
   * ticket or drop its body. The same value/clear pairing {@code FeatureService.update} uses.
   *
   * <p>The status is deliberately not settable here: {@link #transition} is the only way it moves.
   */
  public Ticket update(
      String id,
      String title,
      String description,
      boolean clearDescription,
      String type,
      String assignee,
      boolean clearAssignee,
      String changedBy) {
    // Before the wrap, both of them: neither needs a row, and a supplied blank title or an
    // unreadable type is a 400 on the first attempt rather than a question retried for fifteen
    // seconds.
    if (title != null) {
      Validations.requireText(title, "title");
    }
    TicketType kind =
        type == null
            ? null
            : TicketLifecycle.parseType(type)
                .orElseThrow(() -> new BadRequestException("Unknown ticket type: " + type));
    return writes.hold(
        "ticket update",
        () -> {
          Ticket ticket = get(id);
          if (title != null) {
            ticket.title = title;
          }
          if (clearDescription) {
            ticket.description = null;
          } else if (description != null) {
            ticket.description = description;
          }
          if (kind != null) {
            ticket.type = kind;
          }
          if (clearAssignee) {
            ticket.assignee = null;
          } else if (assignee != null) {
            ticket.assignee = blankToNull(assignee);
          }
          auditService.record(
              AuditEntityType.TICKET,
              ticket.id,
              ticket.id,
              AuditOperation.UPDATE,
              changedBy,
              ticket);
          return ticket;
        });
  }

  /**
   * Moves a ticket to {@code target} (the enum name), rejecting a move the lifecycle does not allow
   * with a 409 — today that is only a move to the status it is already in. A target naming no
   * status is a 409 too, for the reason {@link EpicService#transition} gives; an absent one is a
   * 400, because that is a malformed request rather than a refused move.
   */
  public Ticket transition(String id, String target, String changedBy) {
    Validations.requireText(target, "target");
    return writes.hold(
        "ticket transition",
        () -> {
          Ticket ticket = get(id);
          TicketStatus to =
              TicketLifecycle.parse(target)
                  .orElseThrow(() -> new ConflictException("Unknown ticket status: " + target));
          TicketLifecycle.requireTransition(ticket.status, to);
          ticket.status = to;
          auditService.record(
              AuditEntityType.TICKET,
              ticket.id,
              ticket.id,
              AuditOperation.UPDATE,
              changedBy,
              ticket);
          return ticket;
        });
  }

  /**
   * Removes a ticket and its comments. The comments go in-service rather than through the DB
   * cascade so every removed remark gets its own DELETE audit row — {@code EpicService.delete}'s
   * decision about features and tasks, made again here for the same reason: the audit log is the
   * git replacement and a row that vanished silently never happened.
   */
  public void delete(String id, String changedBy) {
    writes.run(
        "ticket delete",
        () -> {
          Ticket ticket = get(id);
          for (TicketComment comment : commentRepository.listByTicket(id)) {
            commentRepository.delete(comment);
            auditService.record(
                AuditEntityType.TICKET_COMMENT,
                comment.id,
                id,
                AuditOperation.DELETE,
                changedBy,
                comment);
          }
          ticketRepository.delete(ticket);
          auditService.record(
              AuditEntityType.TICKET, id, id, AuditOperation.DELETE, changedBy, ticket);
        });
  }

  // --- Comments -------------------------------------------------------------

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

  /**
   * A new comment on an existing ticket. {@code changedBy} is stored as the {@code author} for the
   * reason {@code createdBy} is stored on the ticket, and is never client-supplied.
   */
  public TicketComment addComment(String ticketId, String body, String changedBy) {
    Validations.requireText(body, "body");
    return writes.hold(
        "ticket comment create",
        () -> {
          Ticket ticket = get(ticketId); // 404 if the ticket does not exist
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

  /**
   * Edits a comment's body. The author is not re-stamped: it records who wrote the remark, and an
   * edit by somebody else is recorded in the audit log where it belongs rather than by rewriting
   * the attribution.
   */
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

  /** A supplied-but-empty assignee means nobody, not the empty string. */
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}
