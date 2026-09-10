package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.ConflictException;
import eu.wohlben.qits.epics.error.StaleWriteException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dossier's storage layer, tested here rather than through the controllers so the MCP door
 * inherits proven behaviour instead of a second implementation of it.
 *
 * <p>Two of these carry the feature's whole argument. The {@code REFINING} guard is the same one
 * features and tasks obey, so a frozen epic's dossier is readable and unwritable everywhere at once;
 * and the version check is what stands where an acceptance step would be, since nothing on this
 * route accepts a write.
 */
@QuarkusTest
class DossierServiceTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject DossierService dossier;
  @Inject AuditService auditService;

  private Epic epic() {
    return epicService.create("proj-1", "Epic", null, "t");
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheEpic() {
    Epic e = epic();
    assertEquals("the-claim-loop", dossier.create(e.id, "The claim loop", "", "t").slug);
    assertEquals("the-claim-loop-2", dossier.create(e.id, "The CLAIM  loop!", "", "t").slug);

    // Another epic is another scope, so the clean slug is free again.
    assertEquals("the-claim-loop", dossier.create(epic().id, "The claim loop", "", "t").slug);
  }

  @Test
  void aRenameLeavesTheSlugAlone() {
    DossierPage page = dossier.create(epic().id, "The claim loop", "", "t");
    DossierPage renamed = dossier.update(page.id, "The claim loop, again", null, 0L, "t");
    assertEquals("the-claim-loop", renamed.slug);
    assertEquals(1L, renamed.version);
  }

  @Test
  void createAppendsAndPositionsStayDense() {
    Epic e = epic();
    dossier.create(e.id, "One", "", "t");
    dossier.create(e.id, "Two", "", "t");
    DossierPage third = dossier.create(e.id, "Three", "", "t");
    assertEquals(2, third.position);

    dossier.move(third.id, 0, "t");
    assertEquals(List.of("Three", "One", "Two"), titles(e.id));

    dossier.delete(third.id, "t");
    // In a fresh session: the gap is closed by one statement, which the thread's first-level cache
    // from the reads above would otherwise mask (see EpicsTestSupport.inFreshTx).
    inFreshTx(
        () ->
            assertEquals(
                List.of(0, 1), dossier.listByEpic(e.id).stream().map(p -> p.position).toList()));
  }

  @Test
  void aPositionPastTheEndMeansLast() {
    Epic e = epic();
    DossierPage first = dossier.create(e.id, "One", "", "t");
    dossier.create(e.id, "Two", "", "t");
    dossier.move(first.id, 99, "t");
    assertEquals(List.of("Two", "One"), titles(e.id));
  }

  @Test
  void aWriteCarryingAStaleVersionIsRefusedWithTheCurrentPage() {
    DossierPage page = dossier.create(epic().id, "The claim loop", "first", "t");
    dossier.update(page.id, null, "second", 0L, "t");

    StaleWriteException refused =
        assertThrows(
            StaleWriteException.class, () -> dossier.update(page.id, null, "third", 0L, "t"));
    assertTrue(refused.getMessage().contains("written since you read it"));
    assertTrue(refused.current().toString().contains("second"), "the current page travels with it");

    // Nothing was merged: the first write still stands.
    assertEquals("second", dossier.get(page.id).body);
  }

  @Test
  void aWriteWithNoVersionAtAllIsRejected() {
    DossierPage page = dossier.create(epic().id, "The claim loop", "", "t");
    assertThrows(BadRequestException.class, () -> dossier.update(page.id, null, "x", null, "t"));
  }

  @Test
  void aFrozenEpicIsReadableAndUnwritable() {
    Epic e = epic();
    DossierPage page = dossier.create(e.id, "The claim loop", "the body", "t");
    epicService.transition(e.id, EpicStatus.IMPLEMENTATION.name(), "t");

    // The read is what implementation is for.
    assertEquals("the body", dossier.listByEpic(e.id).get(0).body);

    assertThrows(ConflictException.class, () -> dossier.create(e.id, "Another", "", "t"));
    assertThrows(ConflictException.class, () -> dossier.update(page.id, "New", null, 0L, "t"));
    assertThrows(ConflictException.class, () -> dossier.move(page.id, 0, "t"));
    assertThrows(ConflictException.class, () -> dossier.delete(page.id, "t"));
  }

  @Test
  void everyChangeLeavesAnAuditEntryUnderTheEpic() {
    Epic e = epic();
    DossierPage page = dossier.create(e.id, "The claim loop", "", "t");
    dossier.update(page.id, "Renamed", null, 0L, "t");
    dossier.delete(page.id, "t");

    List<AuditOperation> operations =
        auditService.listForEntity(AuditEntityType.DOSSIER_PAGE, page.id).stream()
            .map(entry -> entry.operation)
            .toList();
    assertEquals(
        List.of(AuditOperation.DELETE, AuditOperation.UPDATE, AuditOperation.CREATE), operations);

    // The subtree key is the epic's id, so an epic's history keeps including what its pages did.
    assertTrue(
        auditService.listForEpic(e.id).stream()
            .anyMatch(entry -> entry.entityType == AuditEntityType.DOSSIER_PAGE));
  }

  private List<String> titles(String epicId) {
    return dossier.listByEpic(epicId).stream().map(page -> page.title).toList();
  }
}
