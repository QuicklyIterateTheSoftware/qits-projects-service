package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.EntitySummary;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A project's entities of every archetype: {@code GET /projects/api/projects/{projectId}/entities}</b>
 * (qits-548).
 *
 * <p>The merged list existed only as the MCP tool {@code list_entities}; over REST there were five
 * per-archetype listings, each of roots or of one parent's children. This is that tool's read —
 * {@link EntityCatalogService#listByProject}, the whole tree flat, each root oldest first and its
 * descendants depth-first in position order — narrowed by three optional filters, all applied to
 * that one read so the order is the tool's whatever is asked:
 *
 * <ul>
 *   <li>{@code archetype} — one kind, case-insensitive; a word naming none is a 400.
 *   <li>{@code status} — one status word; a word naming none is a 400, like the per-archetype
 *       listings' filter. It matches every archetype by its own status — a feature and a task too,
 *       since qits-763 gave them one — so {@code ?status=VERIFIED} lists the verified tasks beside
 *       the verified epics; combine it with {@code archetype} for one kind.
 *   <li>{@code parent} — the direct children of one entity, named by UUID or qualified id; one naming
 *       nothing is a 404.
 * </ul>
 *
 * <p>{@code {projectId}} is the project's id or its slug ({@code qits}). The answer is {@code
 * {"entities": [...]}}, each a qualified {@link EntitySummary} — one slug lookup for the page — with
 * {@code blocked} on the tickets. A read: {@code qits:agent}, unbound.
 *
 * <p><b>A list row carries no description.</b> The body is long-form Markdown and only a detail view
 * shows it, so it is read one entity at a time through {@code GET /projects/api/entities/{id}}. The
 * list does not even read the column ({@link EntityCatalogService#listByProjectWithoutDescription}).
 */
@Path("/projects/{projectId}/entities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:agent"})
public class ProjectEntitiesController {

  @Inject EntityIdResolver ids;

  @Inject EntityCatalogService catalog;

  @Inject QualifiedEntityIds qualifiedIds;

  @Schema(name = "EntityList", description = "A project's entities, in tree order.")
  public record EntityList(List<EntitySummary> entities) {}

  @GET
  @Operation(
      operationId = "listProjectEntities",
      summary = "List a project's entities of every archetype",
      description =
          "The project's whole planning tree, flat: each root oldest first, its descendants"
              + " depth-first in position order — the order of the MCP list_entities. Optionally"
              + " narrowed to one archetype, one status, and the direct children of one parent (UUID"
              + " or qualified id). The project is its id or its slug.")
  @APIResponse(
      responseCode = "200",
      description = "The entities",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = EntityList.class)))
  @APIResponse(responseCode = "400", description = "An archetype or a status naming none")
  @APIResponse(responseCode = "404", description = "No such project, or no such parent")
  public EntityList list(
      @PathParam("projectId") String projectId,
      @QueryParam("archetype") String archetype,
      @QueryParam("status") String status,
      @QueryParam("parent") String parent) {
    Archetype kind = blank(archetype) ? null : archetype(archetype);
    String word = blank(status) ? null : status(status);
    String project = ids.resolveProject(projectId).id;
    String parentId = blank(parent) ? null : ids.resolve(parent).id;
    List<TransitionedEntity> entities =
        catalog.listByProjectWithoutDescription(project).stream()
            .filter(entity -> kind == null || entity.archetype() == kind)
            .filter(entity -> word == null || word.equals(entity.status()))
            .filter(entity -> parentId == null || parentId.equals(entity.parent()))
            .toList();
    return new EntityList(
        qualifiedIds.qualifyEntities(entities).stream().map(EntitySummary::of).toList());
  }

  private static Archetype archetype(String word) {
    try {
      return Archetype.valueOf(word.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown archetype: " + word);
    }
  }

  private static String status(String word) {
    try {
      return EntityStatus.valueOf(word.trim().toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown status: " + word);
    }
  }

  private static boolean blank(String value) {
    return Objects.requireNonNullElse(value, "").isBlank();
  }
}
