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
 * <p>{@link FeatureServiceTest}'s note one level down: a task is a merged row plus the feature its
 * membership edge names, and every assertion is the one it always made.
 */
@QuarkusTest
class TaskServiceTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;

  private Nested feature() {
    WorkEntity e =
        workEntities.create(Archetype.EPIC, "proj-1", EntityWrite.epic("Epic", null), "t").entity();
    return workEntities.create(
        Archetype.FEATURE, e.id, EntityWrite.feature("Feature", null, null), "t");
  }

  @Test
  void createReadUpdateDelete() {
    Nested f = feature();
    Nested task =
        workEntities.create(
            Archetype.TASK,
            f.entity().id,
            EntityWrite.task("repo-1", "Wire it up", "body", null),
            "alice");
    assertNotNull(task.entity().id);
    assertEquals("repo-1", task.entity().repositoryId);
    assertEquals(f.entity().id, task.parentId());

    Nested fetched = workEntities.nested(Archetype.TASK, task.entity().id);
    assertEquals("Wire it up", fetched.entity().title);

    Nested updated =
        workEntities.update(
            Archetype.TASK,
            task.entity().id,
            EntityWrite.nodeEdit("Wire it up v2", "body2", null, false, null, false),
            "bob");
    assertEquals("Wire it up v2", updated.entity().title);
    assertEquals(task.entity().createdAt, updated.entity().createdAt);

    workEntities.delete(Archetype.TASK, task.entity().id, "bob");
    inFreshTx(
        () ->
            assertThrows(
                NotFoundException.class,
                () -> workEntities.nested(Archetype.TASK, task.entity().id)));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheFeature() {
    Nested f = feature();
    Nested first =
        workEntities.create(
            Archetype.TASK,
            f.entity().id,
            EntityWrite.task("repo-1", "Planning domain", null, null),
            "t");
    assertEquals("planning-domain", first.entity().slug);

    Nested second =
        workEntities.create(
            Archetype.TASK,
            f.entity().id,
            EntityWrite.task("repo-1", "Planning   DOMAIN!", null, null),
            "t");
    assertEquals("planning-domain-2", second.entity().slug);

    // Another feature is another scope, so the clean slug is free again.
    Nested other = feature();
    assertEquals(
        "planning-domain",
        workEntities
            .create(
                Archetype.TASK,
                other.entity().id,
                EntityWrite.task("repo-1", "Planning domain", null, null),
                "t")
            .entity()
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Nested task =
        workEntities.create(
            Archetype.TASK,
            feature().entity().id,
            EntityWrite.task("repo-1", "Planning domain", null, null),
            "t");
    Nested renamed =
        workEntities.update(
            Archetype.TASK,
            task.entity().id,
            EntityWrite.nodeEdit("Renamed", null, null, false, null, false),
            "t");
    assertEquals("planning-domain", renamed.entity().slug);
  }

  @Test
  void createUnderUnknownFeatureThrowsNotFound() {
    assertThrows(
        NotFoundException.class,
        () ->
            workEntities.create(
                Archetype.TASK, "no-feature", EntityWrite.task("repo-1", "T", null, null), "t"));
  }

  @Test
  void blankRepositoryIdIsRejected() {
    Nested f = feature();
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.create(
                Archetype.TASK, f.entity().id, EntityWrite.task(" ", "T", null, null), "t"));
  }

  @Test
  void dependencySetClearAndSelfCycleGuard() {
    Nested f = feature();
    Nested a =
        workEntities.create(
            Archetype.TASK, f.entity().id, EntityWrite.task("repo-1", "A", null, null), "t");
    Nested b =
        workEntities.create(
            Archetype.TASK,
            f.entity().id,
            EntityWrite.task("repo-1", "B", null, a.entity().id),
            "t");
    assertEquals(a.entity().id, b.entity().dependsOnEntityId);

    Nested cleared =
        workEntities.update(
            Archetype.TASK,
            b.entity().id,
            EntityWrite.nodeEdit(null, null, null, true, null, false),
            "t");
    assertNull(cleared.entity().dependsOnEntityId);

    // Self-dependency, unknown dependency, and multi-hop cycles are all rejected.
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.update(
                Archetype.TASK,
                a.entity().id,
                EntityWrite.nodeEdit(null, null, a.entity().id, false, null, false),
                "t"));
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.update(
                Archetype.TASK,
                a.entity().id,
                EntityWrite.nodeEdit(null, null, "ghost", false, null, false),
                "t"));
    workEntities.update(
        Archetype.TASK,
        b.entity().id,
        EntityWrite.nodeEdit(null, null, a.entity().id, false, null, false),
        "t"); // B -> A
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.update(
                Archetype.TASK,
                a.entity().id,
                EntityWrite.nodeEdit(null, null, b.entity().id, false, null, false),
                "t")); // A -> B closes cycle
  }

  @Test
  void crossFeatureDependencyIsRejected() {
    Nested f1 = feature();
    Nested f2 = feature();
    Nested inOther =
        workEntities.create(
            Archetype.TASK, f2.entity().id, EntityWrite.task("repo-1", "Other", null, null), "t");
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities.create(
                Archetype.TASK,
                f1.entity().id,
                EntityWrite.task("repo-1", "A", null, inOther.entity().id),
                "t"));
  }

  @Test
  void implementedAtTransitions() {
    Nested f = feature();
    Nested t =
        workEntities.create(
            Archetype.TASK, f.entity().id, EntityWrite.task("repo-1", "A", null, null), "t");
    assertNull(t.entity().implementedAt);
    // The marker only moves once the epic's scope is frozen.
    workEntities.transition(Archetype.EPIC, f.parentId(), "REFINED", "t");

    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested done =
        workEntities.update(
            Archetype.TASK,
            t.entity().id,
            EntityWrite.nodeEdit(null, null, null, false, when, false),
            "t");
    assertEquals(when, done.entity().implementedAt);

    Nested reopened =
        workEntities.update(
            Archetype.TASK,
            t.entity().id,
            EntityWrite.nodeEdit(null, null, null, false, null, true),
            "t");
    assertNull(reopened.entity().implementedAt);
  }

  @Test
  void mutationsAreAudited() {
    Nested f = feature();
    Nested t =
        workEntities.create(
            Archetype.TASK, f.entity().id, EntityWrite.task("repo-1", "A", null, null), "alice");
    var entries = auditService.listForEntity(AuditEntityType.TASK, t.entity().id);
    assertEquals(1, entries.size());
    assertEquals(AuditOperation.CREATE, entries.get(0).operation);
    assertEquals("alice", entries.get(0).changedBy);
    assertNotNull(entries.get(0).changedAt);
  }
}
