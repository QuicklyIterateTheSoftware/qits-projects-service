package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.WorkEntity;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * <p>A feature is a merged {@code entity} row plus the epic its {@code entity_membership} edge names
 * — {@link Nested} — so the fixtures read {@code .entity()} and {@code .parentId()} where they used
 * to read a shape's own fields. Every assertion states exactly the fact it stated before: {@code
 * dependsOnEntityId} is what was {@code dependsOnFeatureId}, and {@code implementedAt} is what was
 * {@code implementedOn}, one column for what were two.
 */
@QuarkusTest
class FeatureServiceTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;

  private WorkEntity epic() {
    return epicService.create("proj-1", "Epic", null, "t");
  }

  @Test
  void createUnderUnknownEpicThrowsNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> featureService.create("no-epic", "Feature", null, null, "t"));
  }

  @Test
  void blankTitleIsRejected() {
    WorkEntity e = epic();
    assertThrows(
        BadRequestException.class, () -> featureService.create(e.id, " ", null, null, "t"));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheEpic() {
    WorkEntity e = epic();
    Nested first = featureService.create(e.id, "Planning domain", null, null, "t");
    assertEquals("planning-domain", first.entity().slug);

    Nested second = featureService.create(e.id, "Planning   DOMAIN!", null, null, "t");
    assertEquals("planning-domain-2", second.entity().slug);

    // Another epic is another scope, so the clean slug is free again.
    WorkEntity other = epic();
    assertEquals(
        "planning-domain",
        featureService.create(other.id, "Planning domain", null, null, "t").entity().slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Nested feature = featureService.create(epic().id, "Planning domain", null, null, "t");
    Nested renamed =
        featureService.update(feature.entity().id, "Renamed", null, null, false, null, false, "t");
    assertEquals("planning-domain", renamed.entity().slug);
  }

  @Test
  void dependencyCanBeSetThenCleared() {
    WorkEntity e = epic();
    Nested a = featureService.create(e.id, "A", null, null, "t");
    Nested b = featureService.create(e.id, "B", null, a.entity().id, "t");
    assertEquals(a.entity().id, b.entity().dependsOnEntityId);

    // Clear the dependency via the explicit clear flag.
    Nested cleared = featureService.update(b.entity().id, null, null, null, true, null, false, "t");
    assertNull(cleared.entity().dependsOnEntityId);

    // Set it again by supplying a value.
    Nested reset = featureService.update(b.entity().id, null, null, a.entity().id, false, null, false, "t");
    assertEquals(a.entity().id, reset.entity().dependsOnEntityId);
  }

  @Test
  void partialUpdateDoesNotClearOmittedFields() {
    WorkEntity e = epic();
    Nested a = featureService.create(e.id, "A", null, null, "t");
    Nested b = featureService.create(e.id, "B", null, a.entity().id, "t");

    // A title-only edit must not drop the dependency.
    Nested renamed = featureService.update(b.entity().id, "B renamed", null, null, false, null, false, "t");
    assertEquals("B renamed", renamed.entity().title);
    assertEquals(a.entity().id, renamed.entity().dependsOnEntityId);

    // The ship date needs a frozen scope, and setting it must not drop title or dependency.
    epicService.transition(e.id, "IMPLEMENTATION", "t");
    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested shipped = featureService.update(b.entity().id, null, null, null, false, when, false, "t");
    assertEquals("B renamed", shipped.entity().title);
    assertEquals(a.entity().id, shipped.entity().dependsOnEntityId);
    assertEquals(when, shipped.entity().implementedAt);
  }

  @Test
  void selfDependencyIsRejected() {
    WorkEntity e = epic();
    Nested a = featureService.create(e.id, "A", null, null, "t");
    assertThrows(
        BadRequestException.class,
        () -> featureService.update(a.entity().id, null, null, a.entity().id, false, null, false, "t"));
  }

  @Test
  void unknownDependencyIsRejected() {
    WorkEntity e = epic();
    assertThrows(
        BadRequestException.class, () -> featureService.create(e.id, "A", null, "ghost", "t"));
  }

  @Test
  void crossEpicDependencyIsRejected() {
    WorkEntity e1 = epic();
    WorkEntity e2 = epicService.create("proj-1", "Epic2", null, "t");
    Nested inOther = featureService.create(e2.id, "Other", null, null, "t");
    assertThrows(
        BadRequestException.class, () -> featureService.create(e1.id, "A", null, inOther.entity().id, "t"));
  }

  @Test
  void multiHopCycleIsRejected() {
    WorkEntity e = epic();
    Nested a = featureService.create(e.id, "A", null, null, "t");
    Nested b = featureService.create(e.id, "B", null, a.entity().id, "t"); // B -> A
    // A -> B would close the cycle A -> B -> A.
    assertThrows(
        BadRequestException.class,
        () -> featureService.update(a.entity().id, null, null, b.entity().id, false, null, false, "t"));
  }

  @Test
  void implementedOnTransitions() {
    WorkEntity e = epic();
    Nested f = featureService.create(e.id, "A", null, null, "t");
    assertNull(f.entity().implementedAt);
    // The marker only moves once the epic's scope is frozen.
    epicService.transition(e.id, "IMPLEMENTATION", "t");

    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested shipped = featureService.update(f.entity().id, null, null, null, false, when, false, "t");
    assertEquals(when, shipped.entity().implementedAt);

    Nested unshipped = featureService.update(f.entity().id, null, null, null, false, null, true, "t");
    assertNull(unshipped.entity().implementedAt);
  }

  @Test
  void deletingADependedOnFeatureClearsAndAuditsDependents() {
    WorkEntity e = epic();
    Nested a = featureService.create(e.id, "A", null, null, "t");
    Nested b = featureService.create(e.id, "B", null, a.entity().id, "alice");

    featureService.delete(a.entity().id, "carol");

    Nested reloaded = featureService.get(b.entity().id);
    assertNull(reloaded.entity().dependsOnEntityId);
    // The clear is recorded as an UPDATE on the dependent, by the actor that deleted A.
    var bHistory = auditService.listForEntity(AuditEntityType.FEATURE, b.entity().id);
    assertEquals(AuditOperation.UPDATE, bHistory.get(0).operation);
    assertEquals("carol", bHistory.get(0).changedBy);
  }

  @Test
  void deleteCascadesToTasks() {
    WorkEntity e = epic();
    Nested f = featureService.create(e.id, "A", null, null, "t");
    var task = taskService.create(f.entity().id, "repo-1", "T", null, null, "t");

    featureService.delete(f.entity().id, "t");

    inFreshTx(() -> assertThrows(NotFoundException.class, () -> taskService.get(task.entity().id)));
  }

  @Test
  void mutationsAreAudited() {
    WorkEntity e = epic();
    Nested f = featureService.create(e.id, "A", null, null, "alice");
    var entries = auditService.listForEntity(AuditEntityType.FEATURE, f.entity().id);
    assertEquals(1, entries.size());
    assertEquals(AuditOperation.CREATE, entries.get(0).operation);
    assertEquals("alice", entries.get(0).changedBy);
    assertNotNull(entries.get(0).changedAt);
  }
}
