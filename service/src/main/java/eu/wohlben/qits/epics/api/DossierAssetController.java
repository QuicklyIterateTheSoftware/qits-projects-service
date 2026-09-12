package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.DossierAssetService;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.entity.DossierAsset;
import eu.wohlben.qits.projects.refinementhost.DossierFigures;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The figures a dossier page inlines: the door that copies one in, and the one hardened route that
 * serves the copied bytes.
 *
 * <h2>Why this route may exist when {@code RefinementDesign}'s javadoc forbids one</h2>
 *
 * That prohibition is real and is not lifted here: agent-authored HTML served same-origin would be
 * an XSS door into the platform's own session, and the Design tab still renders from a JSON field
 * for exactly that reason. What makes this route safe is the <b>CSP {@code sandbox} directive</b>,
 * which is a different mechanism from the iframe attribute: the browser applies it to <em>the
 * response itself</em>, so the document lands in a unique opaque origin whether the SPA frames it or
 * somebody opens the URL directly — and the direct open is precisely the case the attribute cannot
 * cover. The attribute is still set by the renderer, never with {@code allow-same-origin}.
 *
 * <p><b>The headers go on every response, whatever the kind.</b> An asset is addressed by id and its
 * kind is a column; a route that decided its own hardening from a database value would be one bad
 * row away from serving a document unsandboxed.
 *
 * <p><b>The epic id in the path is an authorisation boundary, not decoration.</b> An asset of
 * another epic requested under this one is a 404 rather than a body.
 */
@Path("/epics/{epicId}/dossier-assets")
@RolesAllowed("qits:admin")
public class DossierAssetController {

  /**
   * The whole hardening, as one literal, because it is asserted byte for byte in the suite and read
   * by a person at the edge. {@code sandbox} with no allow-list: no scripts, no forms, no same
   * origin, no top-level navigation.
   */
  static final String SANDBOX_CSP =
      "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'";

  @Inject DossierAssetService assets;

  @Inject DossierFigures figures;

  @Inject EpicService epicService;

  /** What a caller asks to inline: a figure of this epic's refinement, and which kind it is. */
  public record InlineFigureRequest(@NotBlank String sourceId, @NotBlank String kind) {}

  /**
   * Copy a sketch or a design into this epic and answer <b>the markdown line to paste</b>.
   *
   * <p>The line is the point of the door: a caller that writes an asset URL by hand will eventually
   * write one that does not exist, or one belonging to another epic.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public DossierFigures.InlinedFigure inline(
      @PathParam("epicId") String epicId, InlineFigureRequest request) {
    epicService.get(epicId);
    return figures.inline(epicId, request.sourceId(), request.kind());
  }

  /**
   * The copied bytes, with the sandbox headers on every response.
   *
   * <p>The mime type comes from the row and is never sniffed from the request: the stored value is
   * what the copy recorded, and letting a caller influence it would be the one way to make a
   * document render as something else.
   */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{assetId}/content")
  public Response content(
      @PathParam("epicId") String epicId, @PathParam("assetId") String assetId) {
    DossierAsset asset = assets.get(epicId, assetId);
    return Response.ok(asset.bytes, asset.mimeType)
        .header("Content-Security-Policy", SANDBOX_CSP)
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, max-age=3600")
        .build();
  }
}
