package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.refinementhost.RefinementDesigns;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;

/**
 * The refinement's HTML designs — the Design tab's surface. A design is one self-contained document
 * with inline styles, written and rewritten in place: there is no proposal, no ACTIVE row and
 * nobody who accepts a write. The gate on the draft is the epic's own {@code REFINING →
 * IMPLEMENTATION} transition.
 *
 * <p>{@code POST} creates and {@code PUT} updates, and the update carries the {@code version} the
 * caller last read. A stale one is a <b>409 carrying the current design</b>, document included, so
 * the SPA can show what the write would have overwritten instead of dropping what somebody typed.
 *
 * <p>The document travels as a JSON field, and the list leaves it out: a design is bytes measured
 * in megabytes and a listing is drawn from titles and sizes.
 *
 * <p>There is deliberately NO {@code /content} route serving {@code text/html}: agent-authored HTML
 * served same-origin would be an XSS door, so the SPA renders each design in a sandboxed iframe
 * with scripts off. A <em>copy</em> of a design inlined into a dossier is served from one hardened
 * route in the epics half of this service; these bytes are not.
 */
@Path("/refinements/{id}/designs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class RefinementDesignController {

  /** What a write with no forwarded identity records as its author. */
  private static final String UNKNOWN = "unknown";

  @Inject RefinementService refinements;

  @Inject RefinementDesigns designs;

  @Inject SecurityIdentity identity;

  public record NewDesign(
      @NotBlank String title, @NotBlank String html, String sourceRoute, boolean truncated) {}

  /** A write onto an existing design: the title always, the document when it changed. */
  public record UpdateDesign(@NotBlank String title, String html, Long version, Boolean truncated) {}

  public record DesignDto(
      String id,
      String title,
      String sourceRoute,
      int htmlBytes,
      boolean truncated,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      String html) {}

  public record ListResponse(List<DesignDto> designs) {}

  /** Oldest first, without the documents. Empty list, never a 404. */
  @GET
  public ListResponse list(@PathParam("id") long id) {
    refinements.get(id);
    return new ListResponse(designs.list(id).stream().map(row -> dto(row, false)).toList());
  }

  /** 201 with the row sans html — the caller just sent it. */
  @POST
  public Response add(@PathParam("id") long id, NewDesign request) {
    refinements.get(id);
    RefinementDesign row =
        designs.put(
            id,
            null,
            request.title(),
            request.html(),
            null,
            request.sourceRoute(),
            request.truncated(),
            createdBy());
    return Response.status(Response.Status.CREATED).entity(dto(row, false)).build();
  }

  /** One design with its whole document — what the sandboxed iframe is fed. */
  @GET
  @Path("/{designId}")
  public DesignDto get(@PathParam("id") long id, @PathParam("designId") String designId) {
    refinements.get(id);
    return dto(designs.get(id, designId), true);
  }

  /**
   * Rewrite a design in place. {@code html} may be omitted, which leaves the document alone and
   * makes this a rename; {@code version} is what the caller last read and a stale one is a 409
   * carrying the current design.
   */
  @PUT
  @Path("/{designId}")
  public DesignDto update(
      @PathParam("id") long id, @PathParam("designId") String designId, UpdateDesign request) {
    refinements.get(id);
    RefinementDesign row =
        designs.put(
            id,
            designId,
            request.title(),
            request.html(),
            request.version(),
            null,
            request.truncated() != null && request.truncated(),
            createdBy());
    return dto(row, false);
  }

  @DELETE
  @Path("/{designId}")
  public void delete(@PathParam("id") long id, @PathParam("designId") String designId) {
    refinements.get(id);
    designs.delete(id, designId);
  }

  /** The forwarded user, else the marker — a design row always names an author. */
  private String createdBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return UNKNOWN;
    }
    return identity.getPrincipal().getName();
  }

  private static DesignDto dto(RefinementDesign row, boolean withHtml) {
    return new DesignDto(
        row.id,
        row.title,
        row.sourceRoute,
        row.htmlBytes,
        row.truncated,
        row.version,
        row.createdBy,
        row.createdAt,
        row.updatedAt,
        withHtml ? row.html : null);
  }
}
