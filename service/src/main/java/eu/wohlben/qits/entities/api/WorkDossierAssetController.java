package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.DossierAssetService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.refinementhost.DossierFigures;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The figures a work entity's dossier inlines: {@code
 * /projects/api/work/{qualifiedId}/dossier-assets}</b> (qits-970, epic qits-965) — the {@code /work}
 * home of {@code /epics/{epicId}/dossier-assets}: the door that copies a figure of the entity's
 * refinement in, the listing, and the one hardened route that serves the copied bytes ({@link
 * DossierAssetContent}, shared with the epic route byte for byte).
 *
 * <p><b>An EPIC's dossier inlines figures and nothing else's does</b> (epics V8: {@code
 * dossier_asset} is epic-only, a copy of the refining route's sketches and designs). Any other
 * archetype answers 404 here, the same absence the ticket half always had.
 *
 * <p><b>The {@code url} and {@code markdown} an answer carries keep the stored shape</b>, {@code
 * /epics/{epicId}/dossier-assets/{assetId}/content}: that URL is what page bodies hold and what
 * {@link DossierAssetService}'s reference count parses, so it is data, not an address this family
 * may restate. The bytes are served under both addresses.
 *
 * <p>The roles are the epic route's: {@code qits:agent} on all three, the inline bound to the
 * entity's project ({@link EntitiesAgentAccess}) — {@code inline_figure} performs it over MCP
 * already.
 */
@Path("/work/{qualifiedId}/dossier-assets")
@RolesAllowed({"qits:admin", "qits:agent"})
public class WorkDossierAssetController {

  @Inject EntityIdResolver ids;

  @Inject DossierAssetService assets;

  @Inject DossierFigures figures;

  @Inject SecurityIdentity identity;

  @Schema(
      name = "WorkDossierAssetInline",
      description = "A figure of the entity's refinement to copy into its dossier.")
  public record WorkDossierAssetInline(
      @NotBlank @Schema(required = true, description = "The sketch's or the design's id.")
          String sourceId,
      @NotBlank @Schema(required = true, description = "IMAGE (a sketch) or DESIGN") String kind) {}

  @Schema(
      name = "WorkDossierAssetInlined",
      description = "What was inlined, and the markdown line that renders it.")
  public record WorkDossierAssetInlined(
      String id, String kind, String mimeType, String label, String url, String markdown) {}

  @Schema(
      name = "WorkDossierAssetEntry",
      description =
          "One inlined figure: what it is, the URL and markdown line that render it, and the pages"
              + " that name it.")
  public record WorkDossierAssetEntry(
      String id,
      String kind,
      String mimeType,
      String label,
      String url,
      String markdown,
      List<String> pageIds,
      Instant createdAt) {}

  @Schema(name = "WorkDossierAssetList", description = "The figures a dossier inlines, by label.")
  public record WorkDossierAssetList(List<WorkDossierAssetEntry> assets) {}

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "inlineWorkDossierAsset",
      summary = "Copy a figure of the refinement into an epic's dossier",
      description =
          "Copies a sketch or a design of the epic's own refinement in, keeping its id, and answers"
              + " the markdown line to paste. Inlining the same figure again answers the same line."
              + " The path names the epic by qualified id (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The inlined figure",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkDossierAssetInlined.class)))
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(
      responseCode = "404",
      description =
          "No entity with this id, one whose dossier inlines nothing, or no such figure in its"
              + " refinement")
  public WorkDossierAssetInlined inline(
      @PathParam("qualifiedId") String qualifiedId, @Valid WorkDossierAssetInline request) {
    WorkEntity epic = epic(qualifiedId);
    EntitiesAgentAccess.requireProject(identity, epic.projectId);
    DossierFigures.InlinedFigure inlined =
        figures.inline(epic.id, request.sourceId(), request.kind());
    return new WorkDossierAssetInlined(
        inlined.id(),
        inlined.kind(),
        inlined.mimeType(),
        inlined.label(),
        inlined.url(),
        inlined.markdown());
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "listWorkDossierAssets",
      summary = "List the figures a work entity's dossier inlines",
      description =
          "Each asset's kind, mime type, label, content URL, markdown line and the pages that name"
              + " it, by label. No bytes: getWorkDossierAssetContent serves those.")
  @APIResponse(
      responseCode = "200",
      description = "The figures",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkDossierAssetList.class)))
  @APIResponse(
      responseCode = "404",
      description = "No entity with this id, or one whose dossier inlines nothing")
  public WorkDossierAssetList list(@PathParam("qualifiedId") String qualifiedId) {
    String epicId = epic(qualifiedId).id;
    return new WorkDossierAssetList(
        assets.list(epicId).stream()
            .map(
                listed ->
                    new WorkDossierAssetEntry(
                        listed.asset().id,
                        listed.asset().kind.name(),
                        listed.asset().mimeType,
                        listed.asset().label,
                        DossierAssetService.contentUrl(epicId, listed.asset().id),
                        DossierAssetService.markdownFor(epicId, listed.asset()),
                        listed.pageIds(),
                        listed.asset().createdAt))
            .toList());
  }

  @GET
  @Path("/{assetId}/content")
  @Operation(
      operationId = "getWorkDossierAssetContent",
      summary = "The bytes of one figure a work entity's dossier inlines, sandboxed",
      description =
          "Served under the row's own mime type with Content-Security-Policy: sandbox,"
              + " X-Content-Type-Options: nosniff and private caching, on every response.")
  @APIResponse(responseCode = "200", description = "The figure's bytes")
  @APIResponse(
      responseCode = "404",
      description = "No such entity, or no such figure in its dossier")
  public Response content(
      @PathParam("qualifiedId") String qualifiedId, @PathParam("assetId") String assetId) {
    return DossierAssetContent.serve(assets.get(epic(qualifiedId).id, assetId));
  }

  /** The epic behind the path, or a 404 — for an id naming nothing, or another archetype. */
  private WorkEntity epic(String qualifiedId) {
    WorkEntity entity = ids.resolve(qualifiedId);
    if (entity.archetype != Archetype.EPIC) {
      throw new NotFoundException(
          "A "
              + WorkEntityService.nounOf(entity.archetype).toLowerCase(Locale.ROOT)
              + "'s dossier inlines no figures: "
              + qualifiedId);
    }
    return entity;
  }
}
