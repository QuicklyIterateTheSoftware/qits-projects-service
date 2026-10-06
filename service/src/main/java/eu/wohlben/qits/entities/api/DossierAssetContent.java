package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.entity.DossierAsset;
import jakarta.ws.rs.core.Response;

/**
 * <b>The one hardened content answer</b>, shared by both routes that serve a copied figure's bytes —
 * {@code GET /epics/{epicId}/dossier-assets/{assetId}/content} and {@code GET
 * /work/{qualifiedId}/dossier-assets/{assetId}/content} (qits-970) — so the hardening is one literal
 * and neither route can serve a document with less of it.
 *
 * <p>The headers go on every response, whatever the kind: an asset is addressed by id and its kind
 * is a column, and a route that decided its own hardening from a database value would be one bad row
 * away from serving a document unsandboxed. The CSP {@code sandbox} <em>directive</em> is what
 * carries the safety — the browser applies it to the response itself, so the document lands in an
 * opaque origin even when the URL is opened directly. The mime type comes from the row and is never
 * sniffed from the request.
 */
final class DossierAssetContent {

  /**
   * The whole hardening, as one literal, because it is asserted byte for byte in the suite and read
   * by a person at the edge. {@code sandbox} with no allow-list: no scripts, no forms, no same
   * origin, no top-level navigation.
   */
  static final String SANDBOX_CSP =
      "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'";

  private DossierAssetContent() {}

  /** The copied bytes, under the row's own mime type, with the sandbox headers. */
  static Response serve(DossierAsset asset) {
    return Response.ok(asset.bytes, asset.mimeType)
        .header("Content-Security-Policy", SANDBOX_CSP)
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, max-age=3600")
        .build();
  }
}
