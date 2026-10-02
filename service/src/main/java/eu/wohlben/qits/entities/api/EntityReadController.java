package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
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
 * <b>One entity of any archetype, read as itself: {@code GET /projects/api/entities/{id}}</b>
 * (qits-548).
 *
 * <p>The per-archetype reads ({@code GET /tickets/{id}}, {@code GET /epics/{id}}, …) answer a row of
 * another kind with a 404, so a caller holding only an id — {@code qits work --entity qits-548} —
 * had to guess the archetype before it could ask what the archetype was. This answers the row
 * whatever it is, in the merged {@link TransitionedEntity} shape the write doors answer in: {@code
 * qualifiedId}, {@code parent} and {@code position} filled, {@code blocked} for a ticket.
 *
 * <p>{@code {id}} is the UUID or the qualified id ({@code qits-548}), through {@link
 * EntityIdResolver}, the one lookup every door taking a typed id shares. A read, so {@code
 * qits:agent} unbound, like every GET, and {@code qits:system} too — a platform service reads back
 * what it filed through {@code POST /entities} (qits-667). A class of its own rather than a method on a write
 * controller, for {@code EntityArchetypesController}'s reason: a class-level role list that says
 * the honest thing.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
public class EntityReadController {

  @Inject EntityIdResolver ids;

  @Inject EntityCatalogService catalog;

  @Inject QualifiedEntityIds qualifiedIds;

  @GET
  @Path("/{id}")
  @Operation(
      operationId = "getEntity",
      summary = "Read one entity of any archetype",
      description =
          "The entity in the merged shape, with its qualified id, its parent and position, and"
              + " for a ticket whether it is blocked. The id is the UUID or the qualified id"
              + " (<projectSlug>-<n>).")
  @APIResponse(
      responseCode = "200",
      description = "The entity",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public TransitionedEntity get(@PathParam("id") String id) {
    WorkEntity row = ids.resolve(id);
    return qualifiedIds.qualify(catalog.byIds(List.of(row.id)).get(row.id));
  }
}
