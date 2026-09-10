package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.DossierAsset;
import eu.wohlben.qits.epics.entity.DossierPageAsset;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.persistence.DossierAssetRepository;
import eu.wohlben.qits.epics.persistence.DossierPageAssetRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The copy, and the reference count — what makes a dossier survive the refinement it was written
 * in.
 *
 * <p><b>The copy.</b> {@link #copyFrom} writes an epic-owned {@code dossier_asset} <b>under the
 * source figure's own id</b>. Already present is answered with the existing row and nothing is
 * rewritten: re-inlining the same figure has to be idempotent, and a rewrite would break the
 * snapshot rule that lets a page keep arguing from the document it argued from.
 *
 * <p><b>The module boundary is why the bytes are handed in.</b> The sources are a
 * {@code refinement_prompt_attachment} and a {@code refinement_design}, both in {@code domain}'s
 * database, which this module cannot see and must not learn to. The caller in {@code service} — the
 * one module that sees both — reads the source and hands over bytes, mime type and label. That is
 * one seam rather than a port interface implemented across the boundary, and the {@code service}
 * module already crosses it everywhere else.
 *
 * <p><b>The reference count.</b> {@link #syncReferences} runs inside the same transaction as every
 * page save: it scans the body for this epic's asset URLs, rewrites the page's rows in
 * {@code dossier_page_asset}, and deletes any asset of the epic no page names any more. Reference
 * counting, not a sweeper — so a dossier is self-contained at every instant, with no
 * seal-at-a-milestone step that can fail to run. Deleting a page runs the same path with an empty
 * body.
 */
@ApplicationScoped
public class DossierAssetService {

  /**
   * The URL shape a page's markdown carries, as the inline door writes it:
   * {@code /epics/{epicId}/dossier-assets/{assetId}/content}. Matched with the epic id in it, so a
   * URL belonging to another epic is not counted as a reference here — the copy is per epic and a
   * cross-epic reference is exactly what this feature exists to make impossible.
   */
  private static final Pattern REFERENCE =
      Pattern.compile("/epics/([A-Za-z0-9._~-]+)/dossier-assets/([A-Za-z0-9._~-]+)/content");

  @Inject DossierAssetRepository assets;

  @Inject DossierPageAssetRepository references;

  /** The URL an inlined figure is addressed by, which the inline door hands back in a markdown line. */
  public static String contentUrl(String epicId, String assetId) {
    return "/epics/" + epicId + "/dossier-assets/" + assetId + "/content";
  }

  /** The markdown line to paste — image syntax, whatever the kind; the renderer decides the tag. */
  public static String markdownFor(String epicId, DossierAsset asset) {
    return "![" + asset.label.replace("]", ")") + "](" + contentUrl(epicId, asset.id) + ")";
  }

  /**
   * Copy a figure into the epic, keeping its id. Answers the existing row when the epic already
   * holds that figure, untouched.
   *
   * <p>The caller in {@code service} has already read the source and validated that it belongs to
   * <em>this</em> epic's refinement; a figure from somebody else's refinement is a 404 there, before
   * anything reaches this method.
   */
  public DossierAsset copyFrom(
      String epicId,
      String sourceId,
      DossierAsset.Kind kind,
      byte[] bytes,
      String mimeType,
      String label) {
    DossierAsset existing = assets.findById(sourceId);
    if (existing != null) {
      // Idempotent by construction, and a snapshot: an existing copy is never rewritten.
      if (!existing.epicId.equals(epicId)) {
        throw new NotFoundException("No such figure: " + sourceId);
      }
      return existing;
    }
    DossierAsset asset = new DossierAsset();
    asset.id = sourceId;
    asset.epicId = epicId;
    asset.kind = kind;
    asset.mimeType = mimeType;
    asset.label = label == null || label.isBlank() ? "Figure" : label;
    asset.bytes = bytes;
    asset.createdAt = Instant.now();
    assets.persist(asset);
    return asset;
  }

  /** One copied figure of this epic, by id. */
  public DossierAsset get(String epicId, String assetId) {
    DossierAsset asset = assets.findById(assetId);
    if (asset == null || !asset.epicId.equals(epicId)) {
      // The epic id in the path is an authorisation boundary, not decoration: another epic's asset
      // is absent here, not forbidden with its existence confirmed.
      throw new NotFoundException("No such figure: " + assetId);
    }
    return asset;
  }

  /**
   * Rewrite one page's references from its body, then delete any asset of this epic nothing names
   * any more. Runs inside the caller's transaction — the save's — so the reference count is exact at
   * every instant rather than eventually.
   */
  public void syncReferences(String pageId, String epicId, String body) {
    Set<String> named = referencedIds(epicId, body);
    Set<String> before = new HashSet<>();
    for (DossierPageAsset row : references.listByPage(pageId)) {
      before.add(row.assetId);
    }
    for (String assetId : named) {
      if (!before.contains(assetId) && assets.findById(assetId) != null) {
        DossierPageAsset row = new DossierPageAsset();
        row.pageId = pageId;
        row.assetId = assetId;
        references.persist(row);
      }
    }
    for (String assetId : before) {
      if (!named.contains(assetId)) {
        references.delete("pageId = ?1 and assetId = ?2", pageId, assetId);
      }
    }
    references.flush();
    collect(epicId, before);
  }

  /** Which of this epic's assets a body names. A URL under another epic is not one of them. */
  private static Set<String> referencedIds(String epicId, String body) {
    Set<String> ids = new HashSet<>();
    if (body == null) {
      return ids;
    }
    Matcher matcher = REFERENCE.matcher(body);
    while (matcher.find()) {
      if (matcher.group(1).equals(epicId)) {
        ids.add(matcher.group(2));
      }
    }
    return ids;
  }

  /** Delete the assets among {@code candidates} that no page references any more. */
  private void collect(String epicId, Collection<String> candidates) {
    List<String> held = assets.idsHeldByEpic(epicId, candidates);
    for (String assetId : held) {
      if (!references.anyReferences(assetId)) {
        assets.deleteById(assetId);
      }
    }
  }

  /**
   * Which of {@code sourceIds} this epic has a copy of — the "in use" flag for a whole listing, one
   * query rather than one per row. The ids match because a copy keeps the source's id.
   */
  public Set<String> inUse(String epicId, Collection<String> sourceIds) {
    return new HashSet<>(assets.idsHeldByEpic(epicId, sourceIds));
  }
}
