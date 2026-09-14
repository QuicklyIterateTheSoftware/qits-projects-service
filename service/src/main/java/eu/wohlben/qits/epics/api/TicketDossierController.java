package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.api.DossierController.DeletePageResponse;
import eu.wohlben.qits.epics.api.DossierController.ListPagesResponse;
import eu.wohlben.qits.epics.api.DossierController.MovePage;
import eu.wohlben.qits.epics.api.DossierController.NewPage;
import eu.wohlben.qits.epics.api.DossierController.WritePage;
import eu.wohlben.qits.epics.control.DossierService;
import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.dto.DossierPageDto;
import eu.wohlben.qits.epics.entity.DossierOwner;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.mapper.DossierPageMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The ticket's dossier — the long form of a ticket, and the mirror of {@link DossierController}.
 *
 * <p><b>What a ticket's dossier is for.</b> The refine phase writes its result into the ticket's
 * {@code description}; a page is what it writes when the body cannot hold it — an error scenario
 * crossing four services, a sequence worth a figure. The implement phase reads it, and the verify
 * phase reads it again, both from containers the refine phase's workspace long outlived.
 *
 * <p><b>Every rule here is {@link DossierService}'s and none is restated.</b> The slug is minted at
 * create and never re-derived, positions are dense per owner, and a write carrying a stale {@code
 * version} is a <b>409 carrying the current page</b>, body included, exactly as on the epic routes —
 * nobody accepts a write on either, so a person editing in the SPA while an agent writes from a
 * prompt is the ordinary case.
 *
 * <p><b>What is deliberately different is the freeze, and it is the absence of one.</b> A ticket
 * commits to no scope, so these pages are writable while the ticket is {@code REPORTED}, {@code
 * IMPLEMENTED} and {@code DONE} alike. {@code EpicLifecycle.requireRefining} is not reached from
 * here at all.
 *
 * <p><b>There is no {@code /tickets/{id}/dossier-assets} route, by simple absence.</b> {@code
 * dossier_asset} is epic-only (V8): a figure is a copy of the refining route's sketches and designs,
 * and a ticket has no refining route. An asset upload against a ticket-owned page is therefore a 404
 * because nothing serves that path, rather than a refusal somebody has to maintain.
 *
 * <p>A page is addressed by <b>its slug or its id</b>, either one. The SPA sends the slug — it is
 * what the URL it links carries — and {@code DossierController}'s epic routes are addressed by id;
 * accepting both here means the two halves answer the same request rather than the client having to
 * know which half it is on.
 */
@Path("/tickets/{ticketId}/dossier")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class TicketDossierController {

  @Inject DossierService dossier;

  @Inject TicketService ticketService;

  @Inject DossierPageMapper mapper;

  @Inject SecurityIdentity identity;

  @Inject TicketChangeHints hints;

  /** The ticket's pages in position order, bodies included. Empty list, never a 404. */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  public ListPagesResponse list(@PathParam("ticketId") String ticketId) {
    // The ticket is resolved here rather than in the epics module, which cannot see `domain` and
    // has no way to check a project scope — the same split every other ticket route makes.
    ticketService.get(ticketId);
    return new ListPagesResponse(
        dossier.listByOwner(owner(ticketId)).stream().map(mapper::toDto).toList());
  }

  @POST
  public DossierPageDto create(@PathParam("ticketId") String ticketId, @Valid NewPage request) {
    var ticket = ticketService.get(ticketId);
    var page =
        dossier.create(
            owner(ticketId),
            request.title(),
            request.body(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(ticket.projectId);
    return mapper.toDto(page);
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{slug}")
  public DossierPageDto get(
      @PathParam("ticketId") String ticketId, @PathParam("slug") String slug) {
    return mapper.toDto(requireOfTicket(ticketId, slug));
  }

  /**
   * Retitle a page, rewrite its body, or both. A stale {@code version} is a 409 carrying the page as
   * it stands, so nothing a person typed is dropped on the floor.
   */
  @PUT
  @Path("/{slug}")
  public DossierPageDto write(
      @PathParam("ticketId") String ticketId,
      @PathParam("slug") String slug,
      WritePage request) {
    DossierPage page = requireOfTicket(ticketId, slug);
    var written =
        dossier.update(
            page.id,
            request.title(),
            request.body(),
            request.version(),
            EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfTicket(ticketId));
    return mapper.toDto(written);
  }

  @POST
  @Path("/{slug}/move")
  public DossierPageDto move(
      @PathParam("ticketId") String ticketId,
      @PathParam("slug") String slug,
      MovePage request) {
    DossierPage page = requireOfTicket(ticketId, slug);
    var moved = dossier.move(page.id, request.position(), EpicsPrincipal.changedBy(identity));
    hints.fire(hints.projectOfTicket(ticketId));
    return mapper.toDto(moved);
  }

  @DELETE
  @Path("/{slug}")
  public DeletePageResponse delete(
      @PathParam("ticketId") String ticketId, @PathParam("slug") String slug) {
    DossierPage page = requireOfTicket(ticketId, slug);
    String projectId = hints.projectOfTicket(ticketId);
    dossier.delete(page.id, EpicsPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new DeletePageResponse(true);
  }

  private static DossierOwner owner(String ticketId) {
    return DossierOwner.ticket(ticketId);
  }

  /**
   * The page, if it is this ticket's — by slug, or by id for a caller holding one. A page of another
   * owner is <b>not found</b> rather than forbidden: the ticket id in the path is a boundary, not
   * decoration, and a 403 would confirm the row exists somewhere else.
   */
  private DossierPage requireOfTicket(String ticketId, String slug) {
    ticketService.get(ticketId);
    DossierOwner owner = owner(ticketId);
    var bySlug = dossier.findBySlug(owner, slug);
    if (bySlug.isPresent()) {
      return bySlug.get();
    }
    DossierPage page = dossier.get(slug);
    if (!ticketId.equals(page.ticketId)) {
      throw new NotFoundException("Dossier page not found: " + slug);
    }
    return page;
  }
}
