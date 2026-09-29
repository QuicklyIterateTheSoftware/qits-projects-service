package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.EntityComment;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
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
 * <b>The thread of any entity</b> on the "repository" MCP server (qits-551): {@code add_comment},
 * {@code update_comment} and {@code list_comments}, for an epic, a feature, a task, a campaign or a
 * ticket alike. Mounted on the same declared server as its neighbours for the reason {@link
 * EpicMcpTools} gives.
 *
 * <p><strong>Use case: the finding that has nowhere else to go.</strong> An agent implementing a
 * REFINED epic may not touch the scope or the dossier — the freeze protects the plan — and until
 * this class existed it had nowhere to write what it found except a chat reply nobody reads again.
 * The thread is the writable log beside the frozen plan; the epic prompts ({@code PhasePrompts})
 * send the agent here, to the epic's thread or to a task's own for a task-level finding.
 *
 * <p><strong>A class of its own</strong>, for {@link EntityMcpTools}' reason: a comment is not an
 * archetype and belongs to no archetype's surface, and an agent looking for "how do I note
 * something on this task" should not have to already know it lives under the ticket tools. {@code
 * add_ticket_comment} and {@code update_ticket_comment} stay on {@link TicketMcpTools} with their
 * names and shapes, because both names are pre-approved in other repositories.
 *
 * <p><strong>An entity id is its UUID or its qualified id</strong> ({@code qits-551}), resolved by
 * {@link EntityIdResolver}, the lookup the REST doors share. Scope comes from {@link ProjectScope}
 * (the {@code X-QITS-Project} header), never from an argument, and an entity or comment in another
 * project reads as not found — the model is told nothing about what other projects hold.
 *
 * <p>{@code add_comment} and {@code update_comment} are in {@link ReadOnlyRepositoryToolFilter}'s
 * mutating set, which <em>fails open</em> for a name it does not list. There is no delete tool,
 * deliberately: an agent that could delete what it disagrees with could erase the record of its own
 * mistake. Every write fires the hint of the entity's archetype ({@link
 * ProjectChangeHint.Topic#of}) after the service returns.
 *
 * <p><strong>No {@code @Transactional} here</strong>, for the reason {@link EpicMcpTools} states.
 */
@ApplicationScoped
@WrapBusinessError
public class CommentMcpTools {

  /** What the audit log records for a write with no forwarded identity — see {@link TicketMcpTools}. */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject EntityIdResolver ids;

  @Inject WorkEntityService entities;

  @Inject EntityCommentService thread;

  @Inject ProjectChangePublisher changePublisher;

  @Inject SecurityIdentity identity;

  /**
   * One remark on an entity's thread. Also what {@code get_epic} and {@code get_campaign} embed for
   * their own thread.
   */
  public record CommentDetail(
      String id,
      String entityId,
      String author,
      String body,
      Instant createdAt,
      Instant updatedAt) {

    static CommentDetail of(EntityComment comment) {
      return new CommentDetail(
          comment.id,
          comment.entityId,
          comment.author,
          comment.body,
          comment.createdAt,
          comment.updatedAt);
    }
  }

  /** An entity's whole thread, oldest first — the embedding the detail tools share. */
  static List<CommentDetail> threadOf(EntityCommentService thread, String entityId) {
    return thread.listComments(entityId).stream().map(CommentDetail::of).toList();
  }

  @McpServer("repository")
  @Tool(
      name = "list_comments",
      description =
          "Read the comment thread of any entity of this project — an epic, a feature, a task, a"
              + " campaign or a ticket — oldest first. get_ticket, get_epic and get_campaign already"
              + " carry their own entity's thread; this is how a feature's or a task's is read.")
  public List<CommentDetail> listComments(
      @ToolArg(
              description =
                  "id of an entity in this project: its UUID or its qualified id (<project>-<n>)")
          String entityId) {
    return threadOf(thread, requireEntityInProject(entityId).id);
  }

  @McpServer("repository")
  @Tool(
      name = "add_comment",
      description =
          "Add a remark to the thread of any entity of this project — an epic, a feature, a task, a"
              + " campaign or a ticket. Record what you decided, what surprised you, what landed and"
              + " what is still missing, as the work goes. The thread stays writable when an epic's"
              + " scope and dossier are frozen, so it is where a finding or a correction to the plan"
              + " goes. Put a finding about one task on that task's own thread.")
  public CommentDetail addComment(
      @ToolArg(
              description =
                  "id of an entity in this project: its UUID or its qualified id (<project>-<n>)")
          String entityId,
      @ToolArg(description = "the remark, Markdown") String body) {
    WorkEntity entity = requireEntityInProject(entityId);
    EntityComment comment = thread.addComment(entity.id, body, changedBy());
    announce(entity);
    return CommentDetail.of(comment);
  }

  @McpServer("repository")
  @Tool(
      name = "update_comment",
      description =
          "Rewrite a remark already on an entity's thread. Use it to correct or extend a note you"
              + " left earlier once you know more — an edit is honest where a second comment"
              + " contradicting the first leaves the next reader to work out which one still"
              + " holds. The body is the whole of what an edit changes: it replaces the remark"
              + " outright, it moves the comment's updatedAt, and it does NOT move the author.")
  public CommentDetail updateComment(
      @ToolArg(description = "id of a comment on an entity in this project") String id,
      @ToolArg(description = "the remark as it should now read, Markdown; it replaces the old body")
          String body) {
    WorkEntity entity = requireCommentInProject(id);
    EntityComment comment = thread.updateComment(id, body, changedBy());
    announce(entity);
    return CommentDetail.of(comment);
  }

  // --- Scoping --------------------------------------------------------------

  private WorkEntity requireEntityInProject(String entityId) {
    WorkEntity entity;
    try {
      entity = ids.resolve(entityId);
    } catch (NotFoundException e) {
      throw new NotFoundException("Entity not found in this project: " + entityId);
    }
    if (!scope.requireProjectId().equals(entity.projectId)) {
      throw new NotFoundException("Entity not found in this project: " + entityId);
    }
    return entity;
  }

  /** The entity a comment of the scoped project hangs on; the refusal names the comment. */
  private WorkEntity requireCommentInProject(String commentId) {
    WorkEntity entity = entities.find(thread.getComment(commentId).entityId);
    if (!scope.requireProjectId().equals(entity.projectId)) {
      throw new NotFoundException("Comment not found in this project: " + commentId);
    }
    return entity;
  }

  // --- Plumbing -------------------------------------------------------------

  private void announce(WorkEntity entity) {
    changePublisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
  }

  /** The audit's {@code changed_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }
}
