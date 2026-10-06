package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.NotFoundException;
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
class FeatureServiceTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;

  private WorkEntity epic() {
    return workEntities
        .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Epic", null).withAcceptanceCriteria(EntitiesTestSupport.CRITERIA), "t")
        .entity();
  }

  @Test
  void createUnderUnknownEpicThrowsNotFound() {
    assertThrows(
        NotFoundException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE, "no-epic", EntityWrite.feature("Feature", null, null), "t"));
  }

  @Test
  void blankTitleIsRejected() {
    WorkEntity e = epic();
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE, e.id, EntityWrite.feature(" ", null, null), "t"));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheEpic() {
    WorkEntity e = epic();
    Nested first =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("Planning domain", null, null), "t");
    assertEquals("planning-domain", first.entity().slug);

    Nested second =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("Planning   DOMAIN!", null, null), "t");
    assertEquals("planning-domain-2", second.entity().slug);

    // Another epic is another scope, so the clean slug is free again.
    WorkEntity other = epic();
    assertEquals(
        "planning-domain",
        workEntities
            .create(
                Archetype.FEATURE,
                other.id,
                EntityWrite.feature("Planning domain", null, null),
                "t")
            .entity()
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic().id, EntityWrite.feature("Planning domain", null, null), "t");
    Nested renamed =
        workEntities.update(
            Archetype.FEATURE,
            feature.entity().id,
            EntityWrite.nodeEdit("Renamed", null, null, false, null, false),
            "t");
    assertEquals("planning-domain", renamed.entity().slug);
  }

  @Test
  void dependencyCanBeSetThenCleared() {
    WorkEntity e = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "t");
    Nested b =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("B", null, a.entity().id), "t");
    assertEquals(a.entity().id, b.entity().dependsOnEntityId);

    // Clear the dependency via the explicit clear flag.
    Nested cleared =
        workEntities.update(
            Archetype.FEATURE,
            b.entity().id,
            EntityWrite.nodeEdit(null, null, null, true, null, false),
            "t");
    assertNull(cleared.entity().dependsOnEntityId);

    // Set it again by supplying a value.
    Nested reset =
        workEntities.update(
            Archetype.FEATURE,
            b.entity().id,
            EntityWrite.nodeEdit(null, null, a.entity().id, false, null, false),
            "t");
    assertEquals(a.entity().id, reset.entity().dependsOnEntityId);
  }

  @Test
  void partialUpdateDoesNotClearOmittedFields() {
    WorkEntity e = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "t");
    Nested b =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("B", null, a.entity().id), "t");

    // A title-only edit must not drop the dependency.
    Nested renamed =
        workEntities.update(
            Archetype.FEATURE,
            b.entity().id,
            EntityWrite.nodeEdit("B renamed", null, null, false, null, false),
            "t");
    assertEquals("B renamed", renamed.entity().title);
    assertEquals(a.entity().id, renamed.entity().dependsOnEntityId);

    // The ship date needs a frozen scope, and setting it must not drop title or dependency.
    workEntities.transition(Archetype.EPIC, e.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, e.id, "READY_FOR_DEV", Mover.person("t"));
    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested shipped =
        workEntities.update(
            Archetype.FEATURE,
            b.entity().id,
            EntityWrite.nodeEdit(null, null, null, false, when, false),
            "t");
    assertEquals("B renamed", shipped.entity().title);
    assertEquals(a.entity().id, shipped.entity().dependsOnEntityId);
    assertEquals(when, shipped.entity().implementedAt);
  }

  @Test
  void selfDependencyIsRejected() {
    WorkEntity e = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "t");
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                a.entity().id,
                EntityWrite.nodeEdit(null, null, a.entity().id, false, null, false),
                "t"));
  }

  @Test
  void unknownDependencyIsRejected() {
    WorkEntity e = epic();
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE, e.id, EntityWrite.feature("A", null, "ghost"), "t"));
  }

  @Test
  void crossEpicDependencyIsRejected() {
    WorkEntity e1 = epic();
    WorkEntity e2 =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Epic2", null).withAcceptanceCriteria(EntitiesTestSupport.CRITERIA), "t")
            .entity();
    Nested inOther =
        workEntities.create(
            Archetype.FEATURE, e2.id, EntityWrite.feature("Other", null, null), "t");
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE,
                e1.id,
                EntityWrite.feature("A", null, inOther.entity().id),
                "t"));
  }

  @Test
  void multiHopCycleIsRejected() {
    WorkEntity e = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "t");
    Nested b =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("B", null, a.entity().id), "t"); // B -> A
    // A -> B would close the cycle A -> B -> A.
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                a.entity().id,
                EntityWrite.nodeEdit(null, null, b.entity().id, false, null, false),
                "t"));
  }

  @Test
  void implementedOnTransitions() {
    WorkEntity e = epic();
    Nested f =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "t");
    assertNull(f.entity().implementedAt);
    // The marker only moves once the epic's scope is frozen.
    workEntities.transition(Archetype.EPIC, e.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, e.id, "READY_FOR_DEV", Mover.person("t"));

    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested shipped =
        workEntities.update(
            Archetype.FEATURE,
            f.entity().id,
            EntityWrite.nodeEdit(null, null, null, false, when, false),
            "t");
    assertEquals(when, shipped.entity().implementedAt);

    Nested unshipped =
        workEntities.update(
            Archetype.FEATURE,
            f.entity().id,
            EntityWrite.nodeEdit(null, null, null, false, null, true),
            "t");
    assertNull(unshipped.entity().implementedAt);
  }

  @Test
  void deletingADependedOnFeatureClearsAndAuditsDependents() {
    WorkEntity e = epic();
    Nested a =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "t");
    Nested b =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("B", null, a.entity().id), "alice");

    workEntities.delete(Archetype.FEATURE, a.entity().id, "carol");

    Nested reloaded = workEntities.nested(Archetype.FEATURE, b.entity().id);
    assertNull(reloaded.entity().dependsOnEntityId);
    // The clear is recorded as an UPDATE on the dependent, by the actor that deleted A.
    var bHistory = auditService.listForEntity(AuditEntityType.FEATURE, b.entity().id);
    assertEquals(AuditOperation.UPDATE, bHistory.get(0).operation);
    assertEquals("carol", bHistory.get(0).changedBy);
  }

  @Test
  void deleteCascadesToTasks() {
    WorkEntity e = epic();
    Nested f =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "t");
    var task =
        workEntities.create(
            Archetype.TASK, f.entity().id, EntityWrite.task("repo-1", "T", null, null), "t");

    workEntities.delete(Archetype.FEATURE, f.entity().id, "t");

    inFreshTx(
        () ->
            assertThrows(
                NotFoundException.class,
                () -> workEntities.nested(Archetype.TASK, task.entity().id)));
  }

  @Test
  void mutationsAreAudited() {
    WorkEntity e = epic();
    Nested f =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null, null), "alice");
    var entries = auditService.listForEntity(AuditEntityType.FEATURE, f.entity().id);
    assertEquals(1, entries.size());
    assertEquals(AuditOperation.CREATE, entries.get(0).operation);
    assertEquals("alice", entries.get(0).changedBy);
    assertNotNull(entries.get(0).changedAt);
  }
}
