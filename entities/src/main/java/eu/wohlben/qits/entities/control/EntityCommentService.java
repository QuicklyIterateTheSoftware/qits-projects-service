package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityComment;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.EntityCommentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * <b>An entity's thread</b>, of any archetype: its comments, oldest first, each write audited under
 * the entity's subtree key.
 *
 * <p>It was {@code TicketCommentService} until qits-551, and ticket-only: an epic, a feature, a
 * task and a campaign had no thread, so an agent implementing a REFINED epic — whose scope and
 * dossier are frozen — had nowhere to write what it found but a chat reply nobody reads again. The
 * dossier freeze stays; the thread is the writable log beside it. A comment is still not an
 * archetype of the merged model — no slug, no number, no membership, a table of its own — which is
 * why it did not fold into {@link WorkEntityService} with the four archetype services (qits-399).
 * The in-service cascade that removes a subtree's comments with it is {@link
 * WorkEntityService#delete}'s, so every removed remark still gets its own DELETE audit row.
 *
 * <p><b>The entity is resolved with no archetype check.</b> A thread belongs to the row, not to the
 * kind the row happened to be when somebody wrote on it, so a ticket that {@code
 * transition_entities} re-archetyped keeps a readable, writable thread.
 *
 * <p><b>Audit rows carry the entity's subtree key</b> — the {@code epicId} argument of {@code
 * AuditService.record}, the root a change hangs under rather than a foreign key to an epic: the
 * entity's own id for a root (a ticket, an epic, a campaign), the epic's for a feature or a task
 * ({@link WorkEntityService#auditRootOf}). So an epic's history includes what was said on its tasks,
 * and still answers after they are deleted.
 *
 * <p>{@code changedBy} is stored as the {@code author} and is never client-supplied; an edit does
 * not re-stamp it, because who wrote a remark and who last changed it are different facts and the
 * second one is the log's.
 */
@ApplicationScoped
public class EntityCommentService {

  @Inject WorkEntityService entities;

  @Inject EntityCommentRepository commentRepository;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /**
   * An entity's comments, oldest first, held through a postgres cutover ({@link ReadPatience}): a
   * severed connection would draw a discussion that never happened, which is an answer rather than
   * an outage. The caller resolves the entity first when an unknown one must be a 404.
   */
  public List<EntityComment> listComments(String entityId) {
    return patience.hold("entity comments", () -> commentRepository.listByEntity(entityId));
  }

  public EntityComment getComment(String id) {
    return commentRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Comment not found: " + id));
  }

  /** A new comment on an existing entity of any archetype (404 if there is none). */
  public EntityComment addComment(String entityId, String body, String changedBy) {
    Validations.requireText(body, "body");
    return writes.hold(
        "comment create",
        () -> {
          WorkEntity entity = entities.find(entityId);
          EntityComment comment = new EntityComment();
          comment.id = UUID.randomUUID().toString();
          comment.entityId = entity.id;
          comment.author = changedBy;
          comment.body = body;
          commentRepository.persist(comment);
          auditService.record(
              AuditEntityType.COMMENT,
              comment.id,
              entities.auditRootOf(entity),
              AuditOperation.CREATE,
              changedBy,
              comment);
          return comment;
        });
  }

  /** Edits a comment's body; the author is not re-stamped. */
  public EntityComment updateComment(String id, String body, String changedBy) {
    Validations.requireText(body, "body");
    return writes.hold(
        "comment update",
        () -> {
          EntityComment comment = getComment(id);
          comment.body = body;
          auditService.record(
              AuditEntityType.COMMENT,
              comment.id,
              entities.auditRootOf(entities.find(comment.entityId)),
              AuditOperation.UPDATE,
              changedBy,
              comment);
          return comment;
        });
  }

  public void deleteComment(String id, String changedBy) {
    writes.run(
        "comment delete",
        () -> {
          EntityComment comment = getComment(id);
          String root = entities.auditRootOf(entities.find(comment.entityId));
          commentRepository.delete(comment);
          auditService.record(
              AuditEntityType.COMMENT, id, root, AuditOperation.DELETE, changedBy, comment);
        });
  }
}
