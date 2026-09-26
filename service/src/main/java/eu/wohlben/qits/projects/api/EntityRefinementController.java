package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.refinementhost.RefinementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * <b>The refine action, for any archetype with a lifecycle</b> (qits-395): open — or find — the
 * refinement room of an epic or a ticket.
 *
 * <pre>
 *   POST /projects/api/entities/{id}/refinement   → {"refinement": RefinementDto}          find-or-create
 *   GET  /projects/api/entities/{id}/refinement   → {"refinement": RefinementDto | null}   find only
 * </pre>
 *
 * <p><b>Beside the dispatch door and deliberately not part of it.</b> Addressed the same way — by
 * entity id, under {@code /entities} like {@code EntityDispatchController} — because the SPA's one
 * entity route presses both. But a refinement is a different runtime: a row in {@code domain} and a
 * container under {@code refinementhost/} with its own registry, commissions, proxy and control
 * socket, on {@code refining/<slug>} — not a qits-workspaces workspace on {@code ticket/*} or {@code
 * epic/*}. The two actions share an address and nothing else, and the open refuses while a dispatch
 * is running on the entity ({@link RefinementService#findOrCreate} says why and how).
 *
 * <p><b>The POST is idempotent</b>: an entity has at most one room, and a second open answers the
 * first. Its refusals: 404 for an unknown id; 409 for a feature or a task (no lifecycle), for an
 * entity not at REPORTED, for one an ACTIVE workspace names, and for a project with no wrapper; 502
 * when the git host would not cut {@code refining/<slug>}. <b>The GET never creates</b> and answers
 * {@code {"refinement": null}} when the entity has no room — 404 is kept for an id that names no
 * entity, so the two are never confused.
 *
 * <p>Everything after the open is addressed by the room's row id on {@code /refinements/{id}/…}
 * ({@link RefinementController}), unchanged. The press is {@code qits:admin} alone, as the epic-only
 * open it replaces was; the read admits {@code qits:agent} too, by the standing rule that an agent
 * reads everywhere.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityRefinementController {

  @Inject RefinementService refinements;

  /** The one response shape of both verbs; {@code refinement} is null on a GET that found none. */
  public record EntityRefinementResponse(RefinementDto refinement) {}

  @POST
  @Path("/{id}/refinement")
  public EntityRefinementResponse open(@PathParam("id") String id) {
    return new EntityRefinementResponse(
        RefinementDto.of(refinements.view(refinements.findOrCreate(id))));
  }

  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}/refinement")
  public EntityRefinementResponse find(@PathParam("id") String id) {
    refinements.requireEntity(id); // 404 an id that names nothing, rather than a hopeful null
    return new EntityRefinementResponse(
        refinements.findByEntity(id).map(row -> RefinementDto.of(refinements.view(row))).orElse(null));
  }
}
