package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.DossierService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.dto.DossierPageDto;
import eu.wohlben.qits.entities.entity.DossierOwner;
import eu.wohlben.qits.entities.entity.DossierPage;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.mapper.DossierPageMapper;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A work entity's dossier: {@code /projects/api/work/{qualifiedId}/dossier}</b> (qits-970, epic
 * qits-965) — one family for every archetype that has a dossier, replacing the two halves {@code
 * /epics/{epicId}/dossier} (pages by id) and {@code /tickets/{ticketId}/dossier} (pages by slug).
 * An EPIC and a TICKET own a dossier ({@link DossierOwner}); any other archetype answers 404 here,
 * by the same absence the per-archetype routes had.
 *
 * <p><b>{@code {page}} is the page's SLUG.</b> A slug is minted from the title at create and never
 * re-derived (a rename changes the title alone), it is unique within its owner, and it is what the
 * SPA's URLs carry ({@code ?tab=dossier&page=<slug>}) — URLs people have sent each other. So it is
 * the stable, human key in the same way the qualified id is the entity's, and a reader holding a
 * page's address never needs a second lookup. <b>The page's id is still accepted</b>, as the
 * fallback when no page of the owner has that slug — the way {@code {qualifiedId}} still takes a
 * UUID — so a caller coming from the epic half's id addressing is not broken mid-migration. A page
 * of another owner is not found rather than forbidden: the entity in the path is a boundary.
 *
 * <p>Every rule is {@link DossierService}'s, exactly as behind the two per-archetype halves: the
 * {@code REPORTED} freeze on an epic's pages (a ticket's are writable at every status), dense
 * positions, the slug, and a stale {@code version} on {@code PUT} answering <b>409 carrying the
 * current page</b>. The roles are the halves': {@code qits:agent} on every route, each write bound
 * to the entity's project ({@link EntitiesAgentAccess}). A write redraws its archetype's topic.
 */
@Path("/work/{qualifiedId}/dossier")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class WorkDossierController {

  @Inject EntityIdResolver ids;

  @Inject DossierService dossier;

  @Inject DossierPageMapper mapper;

  @Inject ProjectChangePublisher publisher;

  @Inject SecurityIdentity identity;

  @Schema(name = "WorkDossier", description = "A work entity's dossier pages, in position order.")
  public record WorkDossier(List<DossierPageDto> pages) {}

  @Schema(name = "WorkDossierPageCreate", description = "A new page, appended to the dossier.")
  public record WorkDossierPageCreate(
      @NotBlank @Schema(required = true, description = "The title; the slug is minted from it.")
          String title,
      @Schema(description = "The Markdown body.") String body) {}

  /** A write onto an existing page: the title, the body, or both — and the version read before it. */
  @Schema(
      name = "WorkDossierPageWrite",
      description = "A retitle, a rewrite, or both — with the version the writer read.")
  public record WorkDossierPageWrite(
      @Schema(description = "The new title; omitted keeps it. The slug never moves.") String title,
      @Schema(description = "The new Markdown body; omitted keeps it.") String body,
      @Schema(
              required = true,
              description = "The version read before the write; a stale one is a 409.")
          Long version) {}

  @Schema(name = "WorkDossierPageMove", description = "Where a page should now sit.")
  public record WorkDossierPageMove(
      @Schema(required = true, description = "The new position, 0-based.") int position) {}

  @Schema(name = "WorkDossierPageDeleted", description = "The page is gone.")
  public record WorkDossierPageDeleted(boolean success) {}

  /** The entity behind the path and the dossier it owns. */
  private record Owned(WorkEntity entity, DossierOwner owner) {}

  @GET
  @Operation(
      operationId = "listWorkDossier",
      summary = "Read a work entity's dossier",
      description =
          "The dossier pages of an epic or a ticket in position order, bodies included; empty,"
              + " never 404, for an entity that has a dossier. The path names the entity by"
              + " qualified id (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The pages",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkDossier.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id, or one with no dossier")
  public WorkDossier list(@PathParam("qualifiedId") String qualifiedId) {
    Owned owned = owned(qualifiedId);
    return new WorkDossier(
        dossier.listByOwner(owned.owner()).stream().map(mapper::toDto).toList());
  }

  @POST
  @Operation(
      operationId = "createWorkDossierPage",
      summary = "Add a page to a work entity's dossier",
      description =
          "Appends a page; its slug is minted from the title and never changes. An epic's dossier"
              + " is writable while the epic is REPORTED; a ticket's at every status.")
  @APIResponse(
      responseCode = "201",
      description = "The page as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = DossierPageDto.class)))
  @APIResponse(responseCode = "400", description = "A blank title")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No entity with this id, or one with no dossier")
  @APIResponse(responseCode = "409", description = "An epic past REPORTED: its plan is frozen")
  public Response create(
      @PathParam("qualifiedId") String qualifiedId, @Valid WorkDossierPageCreate request) {
    Owned owned = bound(qualifiedId);
    DossierPage page =
        dossier.create(
            owned.owner(),
            request.title(),
            request.body(),
            EntitiesPrincipal.changedBy(identity));
    fire(owned);
    return Response.status(Response.Status.CREATED).entity(mapper.toDto(page)).build();
  }

  @GET
  @Path("/{page}")
  @Operation(
      operationId = "getWorkDossierPage",
      summary = "Read one page of a work entity's dossier",
      description = "The page named by its slug (or, as a fallback, its id).")
  @APIResponse(
      responseCode = "200",
      description = "The page",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = DossierPageDto.class)))
  @APIResponse(responseCode = "404", description = "No such entity, or no such page in its dossier")
  public DossierPageDto get(
      @PathParam("qualifiedId") String qualifiedId, @PathParam("page") String page) {
    return mapper.toDto(page(owned(qualifiedId), page));
  }

  @PUT
  @Path("/{page}")
  @Operation(
      operationId = "putWorkDossierPage",
      summary = "Retitle or rewrite a page",
      description =
          "Writes the title, the body, or both. version is required: a stale one is a 409 carrying"
              + " the page as it stands (under current), never a merge. The slug never moves.")
  @APIResponse(
      responseCode = "200",
      description = "The page as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = DossierPageDto.class)))
  @APIResponse(responseCode = "400", description = "No version")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No such entity, or no such page in its dossier")
  @APIResponse(
      responseCode = "409",
      description = "A stale version (the current page is in the body), or a frozen epic")
  public DossierPageDto put(
      @PathParam("qualifiedId") String qualifiedId,
      @PathParam("page") String page,
      WorkDossierPageWrite request) {
    Owned owned = bound(qualifiedId);
    DossierPage found = page(owned, page);
    DossierPage written =
        dossier.update(
            found.id,
            request == null ? null : request.title(),
            request == null ? null : request.body(),
            request == null ? null : request.version(),
            EntitiesPrincipal.changedBy(identity));
    fire(owned);
    return mapper.toDto(written);
  }

  @POST
  @Path("/{page}/move")
  @Operation(
      operationId = "moveWorkDossierPage",
      summary = "Move a page within its dossier",
      description = "Positions stay dense and 0-based.")
  @APIResponse(
      responseCode = "200",
      description = "The page in its new position",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = DossierPageDto.class)))
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No such entity, or no such page in its dossier")
  @APIResponse(responseCode = "409", description = "A frozen epic")
  public DossierPageDto move(
      @PathParam("qualifiedId") String qualifiedId,
      @PathParam("page") String page,
      WorkDossierPageMove request) {
    Owned owned = bound(qualifiedId);
    DossierPage found = page(owned, page);
    DossierPage moved =
        dossier.move(
            found.id, request == null ? 0 : request.position(), EntitiesPrincipal.changedBy(identity));
    fire(owned);
    return mapper.toDto(moved);
  }

  @DELETE
  @Path("/{page}")
  @Operation(
      operationId = "deleteWorkDossierPage",
      summary = "Remove a page from its dossier",
      description = "The pages after it close the gap.")
  @APIResponse(
      responseCode = "200",
      description = "The page is gone",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkDossierPageDeleted.class)))
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No such entity, or no such page in its dossier")
  @APIResponse(responseCode = "409", description = "A frozen epic")
  public WorkDossierPageDeleted delete(
      @PathParam("qualifiedId") String qualifiedId, @PathParam("page") String page) {
    Owned owned = bound(qualifiedId);
    DossierPage found = page(owned, page);
    dossier.delete(found.id, EntitiesPrincipal.changedBy(identity));
    fire(owned);
    return new WorkDossierPageDeleted(true);
  }

  // --- the owner and the page ------------------------------------------------------------------

  /** The entity and its dossier, or a 404 for an id naming nothing or an archetype with none. */
  private Owned owned(String qualifiedId) {
    WorkEntity entity = ids.resolve(qualifiedId);
    DossierOwner owner =
        switch (entity.archetype) {
          case EPIC -> DossierOwner.epic(entity.id);
          case TICKET -> DossierOwner.ticket(entity.id);
          default ->
              throw new NotFoundException(
                  "A "
                      + WorkEntityService.nounOf(entity.archetype).toLowerCase(java.util.Locale.ROOT)
                      + " has no dossier: "
                      + qualifiedId);
        };
    return new Owned(entity, owner);
  }

  /** {@link #owned}, with a bound agent held to the entity's project — before any write. */
  private Owned bound(String qualifiedId) {
    Owned owned = owned(qualifiedId);
    EntitiesAgentAccess.requireProject(identity, owned.entity().projectId);
    return owned;
  }

  /**
   * The page by its slug, else by its id — and only if this entity owns it. A page of another owner
   * is not found rather than forbidden: a 403 would confirm it exists somewhere else.
   */
  private DossierPage page(Owned owned, String key) {
    var bySlug = dossier.findBySlug(owned.owner(), key);
    if (bySlug.isPresent()) {
      return bySlug.get();
    }
    DossierPage page;
    try {
      page = dossier.get(key);
    } catch (NotFoundException e) {
      throw new NotFoundException("Dossier page not found: " + key);
    }
    if (!Objects.equals(DossierOwner.of(page), owned.owner())) {
      throw new NotFoundException("Dossier page not found: " + key);
    }
    return page;
  }

  private void fire(Owned owned) {
    publisher.fire(
        owned.entity().projectId, ProjectChangeHint.Topic.of(owned.entity().archetype));
  }
}
