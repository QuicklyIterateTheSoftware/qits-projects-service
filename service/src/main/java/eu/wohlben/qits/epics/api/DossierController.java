package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.DossierService;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.dto.DossierPageDto;
import eu.wohlben.qits.epics.mapper.DossierPageMapper;
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
 * {@code EpicsExceptionMapper}'s, which puts the row under {@code current}.
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

  @Inject EpicChangeHints hints;

  public record ListPagesResponse(List<DossierPageDto> pages) {}

  public record NewPage(@NotBlank String title, String body) {}

  /** A write onto an existing page: the title, the body, or both — and the version read before it. */
  public record WritePage(String title, String body, Long version) {}

  public record MovePage(int position) {}

  public record DeletePageResponse(boolean success) {}

  /** The epic's pages in position order, bodies included. Empty list, never a 404. */
  @GET
  public ListPagesResponse list(@PathParam("epicId") String epicId) {
    // The epic is resolved here rather than in the epics module, which cannot see `domain` and has
    // no way to check a project scope — the same split EpicController makes.
    epicService.get(epicId);
    return new ListPagesResponse(dossier.listByEpic(epicId).stream().map(mapper::toDto).toList());
  }

  @POST
  public DossierPageDto create(@PathParam("epicId") String epicId, @Valid NewPage request) {
    var page =
        dossier.create(
            epicId, request.title(), request.body(), EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfEpic(epicId));
    return mapper.toDto(page);
  }

  @GET
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
  public DossierPageDto write(
      @PathParam("epicId") String epicId,
      @PathParam("pageId") String pageId,
      WritePage request) {
    requireOfEpic(epicId, pageId);
    var page =
        dossier.update(
            pageId,
            request.title(),
            request.body(),
            request.version(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfEpic(epicId));
    return mapper.toDto(page);
  }

  @POST
  @Path("/{pageId}/move")
  public DossierPageDto move(
      @PathParam("epicId") String epicId,
      @PathParam("pageId") String pageId,
      MovePage request) {
    requireOfEpic(epicId, pageId);
    var page = dossier.move(pageId, request.position(), EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfEpic(epicId));
    return mapper.toDto(page);
  }

  @DELETE
  @Path("/{pageId}")
  public DeletePageResponse delete(
      @PathParam("epicId") String epicId, @PathParam("pageId") String pageId) {
    requireOfEpic(epicId, pageId);
    String projectId = hints.projectOfEpic(epicId);
    dossier.delete(pageId, EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeletePageResponse(true);
  }

  /**
   * The page, if it is this epic's. A page of another epic is <b>not found</b> rather than
   * forbidden: the epic id in the path is a boundary, not decoration, and a 403 would confirm the
   * row exists somewhere else.
   */
  private eu.wohlben.qits.epics.entity.DossierPage requireOfEpic(String epicId, String pageId) {
    epicService.get(epicId);
    var page = dossier.get(pageId);
    if (!page.epicId.equals(epicId)) {
      throw new eu.wohlben.qits.epics.error.NotFoundException("Dossier page not found: " + pageId);
    }
    return page;
  }
}
