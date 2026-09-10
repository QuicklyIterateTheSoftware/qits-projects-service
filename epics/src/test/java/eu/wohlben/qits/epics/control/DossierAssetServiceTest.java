package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.DossierAsset;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.persistence.DossierAssetRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The copy and the reference count — the two halves of what makes a dossier outlive the refinement
 * it was written in.
 *
 * <p>The cases here are the failure modes rather than the happy path: the same figure on two pages,
 * the last reference going away, a body edited to drop one, and a figure whose source refinement is
 * long discarded — which still renders, because the bytes are here and not there.
 */
@QuarkusTest
class DossierAssetServiceTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject DossierService dossier;
  @Inject DossierAssetService assets;
  @Inject DossierAssetRepository store;

  private Epic epic() {
    return epicService.create("proj-1", "Epic", null, "t");
  }

  private DossierAsset copy(String epicId, String sourceId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                assets.copyFrom(
                    epicId,
                    sourceId,
                    DossierAsset.Kind.IMAGE,
                    "png-bytes".getBytes(StandardCharsets.UTF_8),
                    "image/png",
                    "The claim loop"));
  }

  private static String line(String epicId, String assetId) {
    return "![The claim loop](" + DossierAssetService.contentUrl(epicId, assetId) + ")";
  }

  private DossierAsset stored(String assetId) {
    return QuarkusTransaction.requiringNew().call(() -> store.findById(assetId));
  }

  @Test
  void theCopyKeepsTheSourcesIdAndReInliningIsIdempotent() {
    Epic e = epic();
    String sourceId = UUID.randomUUID().toString();

    DossierAsset first = copy(e.id, sourceId);
    assertEquals(sourceId, first.id, "the copy is addressed by the source's own id");

    // Re-inlining does not rewrite: the bytes are a snapshot, and the markdown URL must not churn.
    assertEquals(first.id, copy(e.id, sourceId).id);
    assertEquals(1, QuarkusTransaction.requiringNew().call(() -> store.listByEpic(e.id)).size());
  }

  @Test
  void anAssetSurvivesWhileAnyPageStillInlinesItAndGoesWithTheLast() {
    Epic e = epic();
    String assetId = copy(e.id, UUID.randomUUID().toString()).id;

    DossierPage one = dossier.create(e.id, "One", "before " + line(e.id, assetId), "t");
    DossierPage two = dossier.create(e.id, "Two", "also " + line(e.id, assetId), "t");
    assertNotNull(stored(assetId));

    dossier.delete(one.id, "t");
    assertNotNull(stored(assetId), "the other page still inlines it");

    dossier.delete(two.id, "t");
    assertNull(stored(assetId), "the last reference took it with it");
  }

  @Test
  void editingABodyToDropTheOnlyReferenceCollectsTheAssetInThatSave() {
    Epic e = epic();
    String assetId = copy(e.id, UUID.randomUUID().toString()).id;
    DossierPage page = dossier.create(e.id, "One", line(e.id, assetId), "t");
    assertNotNull(stored(assetId));

    dossier.update(page.id, null, "the figure is gone from this argument", 0L, "t");
    assertNull(stored(assetId), "reference counting, not a sweeper: it goes in the same save");
  }

  @Test
  void aUrlBelongingToAnotherEpicIsNotAReference() {
    Epic mine = epic();
    Epic theirs = epic();
    String assetId = copy(mine.id, UUID.randomUUID().toString()).id;
    dossier.create(mine.id, "Mine", line(mine.id, assetId), "t");

    // The same asset id under another epic's path: not this page's reference, and not a copy.
    dossier.create(theirs.id, "Theirs", line(theirs.id, assetId), "t");
    assertNotNull(stored(assetId));
    assertEquals(
        List.of(mine.id),
        QuarkusTransaction.requiringNew().call(() -> store.listByEpic(mine.id)).stream()
            .map(row -> row.epicId)
            .toList());
  }

  @Test
  void aFigureWhoseSourceIsLongGoneStillRenders() {
    Epic e = epic();
    // Nothing here reads the source at all — the copy IS the reason a discarded refinement costs
    // the dossier nothing.
    String assetId = copy(e.id, UUID.randomUUID().toString()).id;
    dossier.create(e.id, "One", line(e.id, assetId), "t");

    DossierAsset served = QuarkusTransaction.requiringNew().call(() -> assets.get(e.id, assetId));
    assertEquals("image/png", served.mimeType);
    assertEquals("png-bytes", new String(served.bytes, StandardCharsets.UTF_8));
  }

  @Test
  void inUseAnswersAWholeListingAtOnce() {
    Epic e = epic();
    String inlined = copy(e.id, UUID.randomUUID().toString()).id;
    String dangling = UUID.randomUUID().toString();
    dossier.create(e.id, "One", line(e.id, inlined), "t");

    var flags = QuarkusTransaction.requiringNew().call(() -> assets.inUse(e.id, List.of(inlined, dangling)));
    assertTrue(flags.contains(inlined));
    assertTrue(!flags.contains(dangling));
  }
}
