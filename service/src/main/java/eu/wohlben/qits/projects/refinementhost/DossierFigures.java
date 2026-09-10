package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.epics.control.DossierAssetService;
import eu.wohlben.qits.epics.entity.DossierAsset;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.entity.RefinementPromptAttachment;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;

/**
 * Inlining a figure into a dossier: the one place a refinement-owned figure becomes an epic-owned
 * copy.
 *
 * <p><b>This class is the module seam.</b> The sources live in {@code domain}'s database and the
 * copy lives in {@code epics}', and the epics module cannot see {@code domain} and must not learn
 * to — so the read happens here, in {@code service}, which already sees both, and
 * {@link DossierAssetService#copyFrom} is handed bytes, mime type and label. One seam rather than a
 * port interface implemented across the boundary.
 *
 * <p><b>The source is validated against the refinement of THIS epic.</b> An attachment or a design
 * from somebody else's refinement is a 404, not a copy — that boundary is what keeps
 * copy-on-reference from quietly becoming a cross-epic reference.
 *
 * <p>Both doors — REST for the SPA and {@code inline_figure} for an agent — return the asset
 * <em>plus the markdown line to paste</em>. That is the point of having a door at all: a caller that
 * hand-writes an asset URL will eventually write one that does not exist, or one belonging to
 * another epic, and a call that answers with the exact line makes both impossible.
 */
@ApplicationScoped
public class DossierFigures {

  /** What was inlined, and the markdown line that renders it. */
  public record InlinedFigure(
      String id, String kind, String mimeType, String label, String url, String markdown) {}

  @Inject RefinementRepository refinements;

  @Inject RefinementPromptAttachments attachments;

  @Inject RefinementDesigns designs;

  @Inject DossierAssetService assets;

  /**
   * Copy the epic's sketch or design into the epic, keeping its id, and answer the line to paste.
   * Re-inlining the same figure answers the same line and rewrites nothing.
   */
  public InlinedFigure inline(String epicId, String sourceId, String kind) {
    DossierAsset.Kind wanted = kindOf(kind);
    Refinement refinement =
        QuarkusTransaction.requiringNew()
            .call(() -> refinements.findByEpic(epicId))
            .orElseThrow(() -> new NotFoundException("No refinement is open for epic " + epicId));

    DossierAsset asset =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    wanted == DossierAsset.Kind.IMAGE
                        ? copyImage(epicId, refinement.id, sourceId)
                        : copyDesign(epicId, refinement.id, sourceId));

    return new InlinedFigure(
        asset.id,
        asset.kind.name(),
        asset.mimeType,
        asset.label,
        DossierAssetService.contentUrl(epicId, asset.id),
        DossierAssetService.markdownFor(epicId, asset));
  }

  private DossierAsset copyImage(String epicId, long refinementId, String sourceId) {
    RefinementPromptAttachment source = attachments.get(refinementId, sourceId);
    return assets.copyFrom(
        epicId,
        source.id,
        DossierAsset.Kind.IMAGE,
        source.bytes,
        source.mimeType,
        source.label);
  }

  private DossierAsset copyDesign(String epicId, long refinementId, String sourceId) {
    RefinementDesign source = designs.get(refinementId, sourceId);
    return assets.copyFrom(
        epicId,
        source.id,
        DossierAsset.Kind.DESIGN,
        source.html.getBytes(StandardCharsets.UTF_8),
        "text/html",
        source.title);
  }

  private static DossierAsset.Kind kindOf(String kind) {
    try {
      return DossierAsset.Kind.valueOf(kind == null ? "" : kind.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new eu.wohlben.qits.projects.error.DomainException(
          400, "A figure is either an IMAGE (from the Sketch tab) or a DESIGN.");
    }
  }
}
