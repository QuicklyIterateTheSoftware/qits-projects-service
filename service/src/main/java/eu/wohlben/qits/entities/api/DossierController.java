package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.DossierService;
import eu.wohlben.qits.entities.control.EpicService;
import eu.wohlben.qits.entities.dto.DossierPageDto;
import eu.wohlben.qits.entities.entity.DossierOwner;
import eu.wohlben.qits.entities.mapper.DossierPageMapper;
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
import java.util.List;

/**
 * The epic's dossier — its long form. The epic's description is the value pitch; the dossier is the
 * breakdown, with examples, and this is the surface the SPA's Dossier tab reads and writes.
 *
 * <p><b>The list carries bodies.</b> A dossier is a handful of pages and the tab renders one
 * immediately; a second round trip per page would buy nothing.
 *
 * <p><b>{@code version} is required on {@code PUT}, and a mismatch is a 409 carrying the current
 * page.</b> Nothing on this route accepts a write, so a person editing here while an agent writes
 * from a prompt is the ordinary case rather than an edge one — and the tab needs the current body to
 * tell the person what their write would have overwritten. The mapping is
 * {@code EntitiesExceptionMapper}'s, which puts the row under {@code current}.
 *
 * <p><b>This is the epic half of the dossier; the ticket half is {@link TicketDossierController}.</b>
 * Two root resources rather than one class, because these are two paths — {@code /epics/{epicId}}
 * and {@code /tickets/{ticketId}} — and JAX-RS gives a class exactly one. Everything either of them
 * decides is {@code DossierService}'s, so the pair cannot drift on a rule; what differs is only what
 * a path parameter names and which SSE topic a write announces on.
 *
 * <p><b>All four writes here take {@code qits:agent}, bound to the agent's own project.</b> {@code
 * put_dossier_page}, {@code move_dossier_page} and {@code remove_dossier_page} already perform every
 * one of them for an agent over the MCP surface — a dossier is largely what a refining agent writes
 * — so the REST door admits what the tool door hands over anyway. The epic's project is resolved
 * before each write, which is also the {@code hints.fire} argument, so the binding costs no extra
 * lookup; see {@link EntitiesAgentAccess} for the rule and for why a forwarded header is refused.
 *
 * <p><b>A frozen epic is readable and unwritable.</b> Reads succeed in every status — implementation
 * reads this months later — and every mutation is refused by the service's own {@code REFINING}
 * guard, the same one features and tasks obey. The tab renders read-only off that.
 */
@Path("/epics/{epicId}/dossier")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class DossierController {

  @Inject DossierService dossier;

  @Inject EpicService epicService;

  @Inject DossierPageMapper mapper;

  @Inject SecurityIdentity identity;

  @Inject EpicsTopicHints hints;

  public record ListPagesResponse(List<DossierPageDto> pages) {}

  public record NewPage(@NotBlank String title, String body) {}

  /** A write onto an existing page: the title, the body, or both — and the version read before it. */
  public record WritePage(String title, String body, Long version) {}

  public record MovePage(int position) {}

  public record DeletePageResponse(boolean success) {}

  /** The epic's pages in position order, bodies included. Empty list, never a 404. */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  public ListPagesResponse list(@PathParam("epicId") String epicId) {
    // The epic is resolved here rather than in the entities module, which cannot see `domain` and has
    // no way to check a project scope — the same split EpicController makes.
    epicService.get(epicId);
    return new ListPagesResponse(
        dossier.listByOwner(DossierOwner.epic(epicId)).stream().map(mapper::toDto).toList());
  }

  @POST
  @RolesAllowed({"qits:admin", "qits:agent"})
  public DossierPageDto create(@PathParam("epicId") String epicId, @Valid NewPage request) {
    String projectId = hints.projectOfEpic(epicId); // 404 if the epic does not exist
    EntitiesAgentAccess.requireProject(identity, projectId);
    var page =
        dossier.create(
            DossierOwner.epic(epicId),
            request.title(),
            request.body(),
            EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return mapper.toDto(page);
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{pageId}")
  public DossierPageDto get(@PathParam("epicId") String epicId, @PathParam("pageId") String pageId) {
    return mapper.toDto(requireOfEpic(epicId, pageId));
  }

  /**
   * Retitle a page, rewrite its body, or both. A stale {@code version} is a 409 carrying the page as
   * it stands, so nothing a person typed is dropped on the floor.
   */
  @PUT
  @Path("/{pageId}")
  @RolesAllowed({"qits:admin", "qits:agent"})
  public DossierPageDto write(
      @PathParam("epicId") String epicId,
      @PathParam("pageId") String pageId,
      WritePage request) {
    String projectId = hints.projectOfEpic(epicId); // 404 if the epic does not exist
    EntitiesAgentAccess.requireProject(identity, projectId);
    requireOfEpic(epicId, pageId);
    var page =
        dossier.update(
            pageId,
            request.title(),
            request.body(),
            request.version(),
            EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return mapper.toDto(page);
  }

  @POST
  @Path("/{pageId}/move")
  @RolesAllowed({"qits:admin", "qits:agent"})
  public DossierPageDto move(
      @PathParam("epicId") String epicId,
      @PathParam("pageId") String pageId,
      MovePage request) {
    String projectId = hints.projectOfEpic(epicId); // 404 if the epic does not exist
    EntitiesAgentAccess.requireProject(identity, projectId);
    requireOfEpic(epicId, pageId);
    var page = dossier.move(pageId, request.position(), EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return mapper.toDto(page);
  }

  @DELETE
  @Path("/{pageId}")
  @RolesAllowed({"qits:admin", "qits:agent"})
  public DeletePageResponse delete(
      @PathParam("epicId") String epicId, @PathParam("pageId") String pageId) {
    String projectId = hints.projectOfEpic(epicId);
    EntitiesAgentAccess.requireProject(identity, projectId);
    requireOfEpic(epicId, pageId);
    dossier.delete(pageId, EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeletePageResponse(true);
  }

  /**
   * The page, if it is this epic's. A page of another epic — or a ticket-owned page, whose {@code
   * epicId} is null — is <b>not found</b> rather than forbidden: the epic id in the path is a
   * boundary, not decoration, and a 403 would confirm the row exists somewhere else.
   */
  private eu.wohlben.qits.entities.entity.DossierPage requireOfEpic(String epicId, String pageId) {
    epicService.get(epicId);
    var page = dossier.get(pageId);
    if (!epicId.equals(page.epicId)) {
      throw new eu.wohlben.qits.entities.error.NotFoundException("Dossier page not found: " + pageId);
    }
    return page;
  }
}
