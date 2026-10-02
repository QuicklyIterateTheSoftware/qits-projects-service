package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.dto.CommentDto;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.mapper.EntityCommentMapper;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>An entity's thread, for every archetype</b> (qits-551): {@code GET} and {@code POST
 * /projects/api/entities/{id}/comments}. A ticket had the only thread ({@code /tickets/{id}/comments},
 * kept as a delegate for the released clients); an epic, a feature, a task and a campaign had
 * nowhere to write, and an agent implementing a REFINED epic — scope and dossier frozen — had only a
 * chat reply nobody reads again. The freeze stays; the thread is the writable log beside it.
 *
 * <p><b>{@code {id}} is the entity's UUID or its qualified id</b> ({@code qits-551}), resolved by
 * {@link EntityIdResolver} — the one lookup the MCP tools and the commit-subject reader share. An id
 * naming nothing is a 404.
 *
 * <p>The comment itself — edited and deleted on its own id — is {@link CommentController}'s, for
 * the reason {@code /features} and {@code /tasks} are root paths: a comment id is unique on its own.
 *
 * <p>The roles are the ticket thread's. Reading takes {@code qits:agent} unbound, like every read.
 * Commenting takes it <b>bound to the agent's own project</b> ({@link EntitiesAgentAccess}), resolved
 * before the write: the {@code add_comment} tool already performs this write for an agent. Every
 * write fires {@link ProjectChangeHint.Topic#of} the entity's archetype — {@code TICKETS} for a
 * ticket, {@code EPICS} otherwise — after the service returns, never inside its retried body.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityCommentController {

  @Inject EntityIdResolver ids;

  @Inject EntityCommentService comments;

  @Inject EntityCommentMapper mapper;

  @Inject SecurityIdentity identity;

  @Inject ProjectChangePublisher publisher;

  // The answers carry explicit schema names: the document numbers every nested `Response` it
  // meets (Response22, …), and a new one would renumber the ones after it.

  public record ListEntityCommentsRequest() {
    @Schema(name = "CommentList", description = "An entity's thread, oldest first.")
    public record Response(List<Entry> entries) {
      @Schema(name = "CommentListEntry")
      public record Entry(CommentDto comment) {}
    }
  }

  /** One comment, as every write on the thread answers it — the create here, the PATCH there. */
  @Schema(name = "CommentAnswer", description = "One comment on an entity's thread.")
  public record CommentAnswer(CommentDto comment) {}

  @GET
  @Path("/{id}/comments")
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "listEntityComments",
      summary = "Read an entity's comment thread",
      description =
          "The thread of an entity of any archetype, oldest first. The id is the entity's UUID or"
              + " its qualified id (<projectSlug>-<n>).")
  @APIResponse(
      responseCode = "200",
      description = "The thread, oldest first",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = ListEntityCommentsRequest.Response.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public ListEntityCommentsRequest.Response list(@PathParam("id") String id) {
    WorkEntity entity = ids.resolve(id);
    return new ListEntityCommentsRequest.Response(
        comments.listComments(entity.id).stream()
            .map(c -> new ListEntityCommentsRequest.Response.Entry(mapper.toDto(c)))
            .toList());
  }

  /** No {@code author} on the request: it is stamped from the caller's identity. */
  public record CreateEntityCommentRequest(@NotBlank String body) {}

  @POST
  @Path("/{id}/comments")
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      summary = "Comment on an entity",
      description =
          "Adds a remark to the thread of an entity of any archetype. The id is the entity's UUID or"
              + " its qualified id (<projectSlug>-<n>). The author is stamped from the caller.")
  @APIResponse(
      responseCode = "200",
      description = "The comment as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = CommentAnswer.class)))
  @APIResponse(responseCode = "400", description = "A blank body")
  @APIResponse(
      responseCode = "403",
      description = "An agent commenting outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public CommentAnswer create(
      @PathParam("id") String id, @Valid CreateEntityCommentRequest request) {
    WorkEntity entity = ids.resolve(id);
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
    var comment =
        comments.addComment(
            entity.id, request == null ? null : request.body(), EntitiesPrincipal.changedBy(identity));
    publisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
    return new CommentAnswer(mapper.toDto(comment));
  }
}
