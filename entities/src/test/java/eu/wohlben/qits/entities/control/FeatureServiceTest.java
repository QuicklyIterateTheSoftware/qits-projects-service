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
 * implementedAt} is what was {@code implementedOn}, one column for what were two.
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
                Archetype.FEATURE, "no-epic", EntityWrite.feature("Feature", null), "t"));
  }

  @Test
  void blankTitleIsRejected() {
    WorkEntity e = epic();
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE, e.id, EntityWrite.feature(" ", null), "t"));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheEpic() {
    WorkEntity e = epic();
    Nested first =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("Planning domain", null), "t");
    assertEquals("planning-domain", first.entity().slug);

    Nested second =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("Planning   DOMAIN!", null), "t");
    assertEquals("planning-domain-2", second.entity().slug);

    // Another epic is another scope, so the clean slug is free again.
    WorkEntity other = epic();
    assertEquals(
        "planning-domain",
        workEntities
            .create(
                Archetype.FEATURE,
                other.id,
                EntityWrite.feature("Planning domain", null),
                "t")
            .entity()
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic().id, EntityWrite.feature("Planning domain", null), "t");
    Nested renamed =
        workEntities.update(
            Archetype.FEATURE,
            feature.entity().id,
            EntityWrite.nodeEdit("Renamed", null, null, false),
            "t");
    assertEquals("planning-domain", renamed.entity().slug);
  }

  @Test
  void partialUpdateDoesNotClearOmittedFields() {
    WorkEntity e = epic();
    Nested b =
        workEntities.create(
            Archetype.FEATURE, e.id, EntityWrite.feature("B", "the body"), "t");

    // A title-only edit must not drop the description.
    Nested renamed =
        workEntities.update(
            Archetype.FEATURE,
            b.entity().id,
            EntityWrite.nodeEdit("B renamed", null, null, false),
            "t");
    assertEquals("B renamed", renamed.entity().title);
    assertEquals("the body", renamed.entity().description);

    // The ship date needs a frozen scope, and setting it must not drop title or description.
    workEntities.transition(Archetype.EPIC, e.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, e.id, "READY_FOR_DEV", Mover.person("t"));
    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested shipped =
        workEntities.update(
            Archetype.FEATURE,
            b.entity().id,
            EntityWrite.nodeEdit(null, null, when, false),
            "t");
    assertEquals("B renamed", shipped.entity().title);
    assertEquals("the body", shipped.entity().description);
    assertEquals(when, shipped.entity().implementedAt);
  }

  @Test
  void implementedOnTransitions() {
    WorkEntity e = epic();
    Nested f =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null), "t");
    assertNull(f.entity().implementedAt);
    // The marker only moves once the epic's scope is frozen.
    workEntities.transition(Archetype.EPIC, e.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, e.id, "READY_FOR_DEV", Mover.person("t"));

    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested shipped =
        workEntities.update(
            Archetype.FEATURE,
            f.entity().id,
            EntityWrite.nodeEdit(null, null, when, false),
            "t");
    assertEquals(when, shipped.entity().implementedAt);

    Nested unshipped =
        workEntities.update(
            Archetype.FEATURE,
            f.entity().id,
            EntityWrite.nodeEdit(null, null, null, true),
            "t");
    assertNull(unshipped.entity().implementedAt);
  }

  @Test
  void deleteCascadesToTasks() {
    WorkEntity e = epic();
    Nested f =
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null), "t");
    var task =
        workEntities.create(
            Archetype.TASK, f.entity().id, EntityWrite.task("repo-1", "T", null), "t");

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
        workEntities.create(Archetype.FEATURE, e.id, EntityWrite.feature("A", null), "alice");
    var entries = auditService.listForEntity(AuditEntityType.FEATURE, f.entity().id);
    assertEquals(1, entries.size());
    assertEquals(AuditOperation.CREATE, entries.get(0).operation);
    assertEquals("alice", entries.get(0).changedBy);
    assertNotNull(entries.get(0).changedAt);
  }
}
