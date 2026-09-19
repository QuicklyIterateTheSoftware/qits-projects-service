package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketComment;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.TicketType;
import eu.wohlben.qits.epics.entity.WorkEntity;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.ConflictException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.TicketCommentRepository;
import eu.wohlben.qits.epics.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD, lifecycle and comments for the {@link Archetype#TICKET} rows of the merged {@link
 * WorkEntity} table. {@code projectId} is stored verbatim — cross-boundary existence against {@code
 * domain}'s {@code Project} is validated in the {@code service} controller (this module has no
 * dependency on {@code domain}), exactly as {@link EpicService} has it.
 *
 * <p>A new ticket starts {@link TicketStatus#REPORTED}; {@link #transition} is the only thing that
 * moves the status, one step at a time, and {@link TicketLifecycle} is where the adjacency rule
 * lives. Nothing here freezes: see that class for why a ticket has no equivalent of the epic scope
 * freeze.
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
 *
 * <h2>The merged table is the source of truth, and there is no mirror left</h2>
 *
 * <p>{@code entity} answers every read and every rule here, and a caller gets back a {@link
 * WorkEntityProjections detached projection} of the row shaped as a {@link Ticket}, so the mapper,
 * the DTO and the controllers above are untouched.
 *
 * <p><b>The legacy {@code ticket} row is not written at all any more.</b> The write-behind mirror
 * was held by two live foreign keys and one reader, and epics V12 and {@code DossierService} have
 * answered all three: {@code fk_ticket_comment_ticket}, {@code dossier_page.ticket_id} and {@code
 * dossier_asset.epic_id} name {@code entity(id)} now, and the dossier resolves a page's owner from
 * the merged row. Keeping the mirror would have left a table written by nobody's intent purely to
 * satisfy a constraint — the half-live state {@code docs/unified-entity-model.md} already refused
 * for {@code feature}/{@code task}. So {@code ticket} has <b>no writer and no referent</b>: a frozen
 * snapshot of what V10 found, which is what the verification door compares against.
 *
 * <p><b>Comments stay on {@code TicketComment} and nothing about them moved but the key under
 * them.</b> This class still writes those rows, they still carry {@code ticket_id}, and the column
 * still holds the ticket's id — which is the {@code entity} row's id, because the backfill copied
 * every row in under the id it already had. Only the foreign key's referent changed. A comment is
 * not an archetype of the merged model at all, and the in-service cascade delete stays exactly where
 * it was so every removed remark keeps getting its own DELETE audit row.
 */
@ApplicationScoped
public class TicketService {

  @Inject WorkEntityRepository entities;

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
   *
   * <p>One query either way, with the rows projected in memory — nothing is resolved per row.
   */
  public List<Ticket> listByProject(String projectId, String status) {
    if (status == null || status.isBlank()) {
      return patience.hold(
          "ticket list",
          () -> project(entities.listByProjectAndArchetype(projectId, Archetype.TICKET)));
    }
    TicketStatus filter =
        TicketLifecycle.parse(status)
            .orElseThrow(() -> new BadRequestException("Unknown ticket status: " + status));
    return patience.hold(
        "ticket list by status",
        () ->
            project(
                entities.listByProjectArchetypeAndStatus(
                    projectId, Archetype.TICKET, filter.name())));
  }

  public Ticket get(String id) {
    return WorkEntityProjections.ticket(entity(id));
  }

  /**
   * A new {@link TicketStatus#REPORTED} ticket. {@code type} is the enum name and is required — a
   * ticket that says neither bug nor improvement is a report nobody can triage — and so is {@code
   * impetus}, which is what a REPORTED ticket consists of: see {@link Ticket#impetus} for the
   * length rule the intake surfaces quote. {@code description} is the refinement's output and is
   * ordinarily absent here.
   *
   * <p>{@code changedBy} is both the audit principal and the stored {@code createdBy}: they are the
   * same fact asked at the same moment, and the column exists so a list has a reporter without a
   * join against the log. It is never a client-supplied value — the surfaces above read it from the
   * request identity.
   */
  public Ticket create(
      String projectId,
      String title,
      String impetus,
      String description,
      String type,
      String assignee,
      String changedBy) {
    Validations.requireText(projectId, "projectId");
    Validations.requireText(title, "title");
    Validations.requireText(impetus, "impetus");
    Validations.requireText(type, "type");
    TicketType kind =
        TicketLifecycle.parseType(type)
            .orElseThrow(() -> new BadRequestException("Unknown ticket type: " + type));
    return writes.hold(
        "ticket create",
        () -> {
          WorkEntity row = new WorkEntity();
          // Minted INSIDE the wrap, which is what makes a second attempt a fresh row rather than a
          // duplicate of a lost one.
          row.id = UUID.randomUUID().toString();
          row.archetype = Archetype.TICKET;
          row.projectId = projectId;
          row.title = title;
          // Minted once, at create, and never re-derived on update: the slug is a stable address
          // for the row, so retitling must not move it. Its scope is the project id — which is the
          // epics' scope too now, the narrowing docs/unified-entity-model.md states.
          row.slugScope = projectId;
          row.slug =
              Slugs.unique(
                  Slugs.slugify(title, row.id, "ticket-"), entities.slugsInScope(projectId));
          row.ticketType = kind;
          row.status = TicketStatus.REPORTED.name();
          row.assignee = blankToNull(assignee);
          row.createdBy = changedBy;
          row.impetus = impetus;
          row.description = description;
          requireArchetypeValid(row);
          entities.persist(row);
          Ticket ticket = settled(row);
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
   * Partial update. A null {@code title}/{@code impetus}/{@code description}/{@code type} leaves
   * that field unchanged; the three nullable fields change only when their {@code clear*} flag is
   * true (→ cleared) or a non-null value is supplied (→ set), so a retitle cannot silently unassign
   * a ticket or drop its body. The same value/clear pairing {@code FeatureService.update} uses.
   *
   * <p><b>The impetus takes that pairing too, and is editable by triage</b> — a report filed in
   * haste is often the wrong words for the right problem. It is nullable in the column (rows that
   * predate V7 have none, and triage may write one onto them), so it is a nullable field on this
   * surface like the other two: an omitted impetus never overwrites one, and emptying it is a
   * deliberate act rather than the side effect of a blank form field. What this method must never
   * be used for is restating the refinement here — see {@link Ticket#impetus}.
   *
   * <p>The status is deliberately not settable here: {@link #transition} is the only way it moves.
   */
  public Ticket update(
      String id,
      String title,
      String impetus,
      boolean clearImpetus,
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
          WorkEntity row = entity(id);
          if (title != null) {
            row.title = title;
          }
          if (clearImpetus) {
            row.impetus = null;
          } else if (impetus != null) {
            row.impetus = impetus;
          }
          if (clearDescription) {
            row.description = null;
          } else if (description != null) {
            row.description = description;
          }
          if (kind != null) {
            row.ticketType = kind;
          }
          if (clearAssignee) {
            row.assignee = null;
          } else if (assignee != null) {
            row.assignee = blankToNull(assignee);
          }
          requireArchetypeValid(row, ImpetusConcession::theImpetusTheColumnStillAllowsToBeAbsent);
          Ticket ticket = settled(row);
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
   * with a 409 — that is anything other than one step forward or one step back along the five
   * statuses, including a move to the status it already has. A target naming no status is a 409
   * too, for the reason {@link EpicService#transition} gives; an absent one is a 400, because that
   * is a malformed request rather than a refused move.
   */
  public Ticket transition(String id, String target, String changedBy) {
    Validations.requireText(target, "target");
    return writes.hold(
        "ticket transition",
        () -> {
          WorkEntity row = entity(id);
          TicketStatus to =
              TicketLifecycle.parse(target)
                  .orElseThrow(() -> new ConflictException("Unknown ticket status: " + target));
          TicketLifecycle.requireTransition(TicketStatus.valueOf(row.status), to);
          row.status = to.name();
          requireArchetypeValid(row, ImpetusConcession::theImpetusTheColumnStillAllowsToBeAbsent);
          Ticket ticket = settled(row);
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
          WorkEntity row = entity(id);
          Ticket ticket = WorkEntityProjections.ticket(row);
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
          entities.delete(row);
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
          WorkEntity ticket = entity(ticketId); // 404 if the ticket does not exist
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

  /**
   * The row as a caller sees it, taken after an explicit flush so the Hibernate-managed timestamps
   * are populated — a create is promised a {@code createdAt} and an update an {@code updatedAt} that
   * is not before it.
   */
  private Ticket settled(WorkEntity row) {
    entities.getEntityManager().flush();
    return WorkEntityProjections.ticket(row);
  }

  private List<Ticket> project(List<WorkEntity> rows) {
    return rows.stream().map(WorkEntityProjections::ticket).toList();
  }

  /**
   * The row this id names, or a 404 — and a row of another archetype is a 404 too. The four kinds
   * share one table and one id space now, so "no ticket with this id" has to mean "no TICKET row
   * with this id" rather than "no row at all".
   */
  private WorkEntity entity(String id) {
    WorkEntity row = id == null ? null : entities.findById(id);
    if (row == null || row.archetype != Archetype.TICKET) {
      throw new NotFoundException("Ticket not found: " + id);
    }
    return row;
  }

  /** Every violation refused — the create's gate, and the shape the update narrows. */
  private static void requireArchetypeValid(WorkEntity candidate) {
    requireArchetypeValid(candidate, violation -> false);
  }

  /**
   * <b>The archetype registry on the ordinary write.</b> A row the registry refuses is a 400 naming
   * every violation at once, which is what {@code Archetypes.validate} answers for and why it
   * returns all of them rather than the first. {@code tolerated} is the one documented narrowing —
   * see {@link ImpetusConcession}, which is where that narrowing lives now: it is a property of
   * every UPDATE path rather than of this one, and the multi-entity transition gets it too.
   */
  private static void requireArchetypeValid(
      WorkEntity candidate, java.util.function.Predicate<ArchetypeViolation> tolerated) {
    List<String> refused =
        Archetypes.validate(candidate).stream()
            .filter(violation -> !tolerated.test(violation))
            .map(ArchetypeViolation::message)
            .toList();
    if (!refused.isEmpty()) {
      throw new BadRequestException(refused.stream().collect(Collectors.joining("; ")));
    }
  }

  /** A supplied-but-empty assignee means nobody, not the empty string. */
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}
