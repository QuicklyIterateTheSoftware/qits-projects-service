package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <p>The fixtures are merged {@link WorkEntity} rows and {@link Nested} descendants now; every claim
 * below is the one it always made. {@code status} is a {@code String} holding the enum's {@code
 * name()} on the merged row, so each status assertion states the same word through the same enum
 * constant.
 */
@QuarkusTest
class EpicServiceTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;

  @Test
  void createReadUpdateDelete() {
    WorkEntity epic =
        workEntities
            .create(
                Archetype.EPIC, "proj-1", EntityWrite.epic("Planning domain", "The spine"), "alice")
            .entity();
    assertNotNull(epic.id);
    assertEquals("proj-1", epic.projectId);
    assertEquals(EntityStatus.REPORTED.name(), epic.status);
    assertNull(epic.supersededByEntityId);
    assertNotNull(epic.createdAt);
    assertNotNull(epic.updatedAt);

    WorkEntity fetched = workEntities.get(Archetype.EPIC, epic.id);
    assertEquals("Planning domain", fetched.title);

    WorkEntity updated =
        workEntities
            .update(
                Archetype.EPIC,
                epic.id,
                EntityWrite.epic("Planning domain v2", "Longer spine"),
                "bob")
            .entity();
    assertEquals("Planning domain v2", updated.title);
    // created_at is immutable; update bumps updated_at only.
    assertEquals(epic.createdAt, updated.createdAt);
    assertFalse(updated.updatedAt.isBefore(updated.createdAt));

    workEntities.delete(Archetype.EPIC, epic.id, "bob");
    inFreshTx(
        () ->
            assertThrows(NotFoundException.class, () -> workEntities.get(Archetype.EPIC, epic.id)));
  }

  @Test
  void listByProjectScopesToTheProject() {
    workEntities.create(Archetype.EPIC, "proj-a", EntityWrite.epic("A1", null), "t").entity();
    workEntities.create(Archetype.EPIC, "proj-a", EntityWrite.epic("A2", null), "t").entity();
    workEntities.create(Archetype.EPIC, "proj-b", EntityWrite.epic("B1", null), "t").entity();

    assertEquals(2, workEntities.listByProject(Archetype.EPIC, "proj-a").size());
    assertEquals(1, workEntities.listByProject(Archetype.EPIC, "proj-b").size());
    assertTrue(workEntities.listByProject(Archetype.EPIC, "proj-none").isEmpty());
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheProject() {
    WorkEntity first =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Planning domain", null), "t")
            .entity();
    assertEquals("planning-domain", first.slug);

    // Same slug in the same project → the next free suffix, oldest keeps the clean one.
    WorkEntity second =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Planning   DOMAIN!", null), "t")
            .entity();
    assertEquals("planning-domain-2", second.slug);

    // Another project is another scope, so the clean slug is free again.
    assertEquals(
        "planning-domain",
        workEntities
            .create(Archetype.EPIC, "proj-2", EntityWrite.epic("Planning domain", null), "t")
            .entity()
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    WorkEntity epic =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Planning domain", null), "t")
            .entity();
    WorkEntity renamed =
        workEntities
            .update(Archetype.EPIC, epic.id, EntityWrite.epic("Something else entirely", null), "t")
            .entity();
    // The slug names a branch; a rename must not orphan the branches already cut from it.
    assertEquals("planning-domain", renamed.slug);
  }

  @Test
  void blankTitleIsRejected() {
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .create(Archetype.EPIC, "proj-1", EntityWrite.epic("  ", null), "t")
                .entity());
  }

  @Test
  void getUnknownEpicThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> workEntities.get(Archetype.EPIC, "nope"));
  }

  @Test
  void deleteCascadesToFeaturesAndTasks() {
    WorkEntity epic =
        workEntities.create(Archetype.EPIC, "proj-1", EntityWrite.epic("Epic", null), "t").entity();
    var feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
    var task =
        workEntities.create(
            Archetype.TASK,
            feature.entity().id,
            EntityWrite.task("repo-1", "Task", null, null),
            "t");

    workEntities.delete(Archetype.EPIC, epic.id, "t");

    // The in-service cascade removed the whole subtree.
    inFreshTx(
        () -> {
          assertThrows(
              NotFoundException.class,
              () -> workEntities.nested(Archetype.FEATURE, feature.entity().id));
          assertThrows(
              NotFoundException.class, () -> workEntities.nested(Archetype.TASK, task.entity().id));
        });
  }

  @Test
  void deleteRecordsAuditForWholeSubtreeAndSurvivesDeletion() {
    WorkEntity epic =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Epic", null), "carol")
            .entity();
    var feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "carol");
    var task =
        workEntities.create(
            Archetype.TASK,
            feature.entity().id,
            EntityWrite.task("repo-1", "Task", null, null),
            "carol");

    workEntities.delete(Archetype.EPIC, epic.id, "carol");

    // The audit log is queryable by epicId even though the live rows are gone (git replacement).
    var history = auditService.listForEpic(epic.id);
    // A DELETE row exists for the epic AND each cascaded child.
    assertTrue(
        history.stream()
            .anyMatch(
                a -> a.entityType == AuditEntityType.EPIC && a.operation == AuditOperation.DELETE));
    assertTrue(
        history.stream()
            .anyMatch(
                a ->
                    a.entityType == AuditEntityType.FEATURE
                        && a.entityId.equals(feature.entity().id)
                        && a.operation == AuditOperation.DELETE));
    assertTrue(
        history.stream()
            .anyMatch(
                a ->
                    a.entityType == AuditEntityType.TASK
                        && a.entityId.equals(task.entity().id)
                        && a.operation == AuditOperation.DELETE));
    history.forEach(a -> assertEquals("carol", a.changedBy));
  }

  @Test
  void mutationsAreAudited() {
    WorkEntity epic =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Epic", null), "alice")
            .entity();
    workEntities.update(Archetype.EPIC, epic.id, EntityWrite.epic("Epic v2", null), "bob").entity();
    workEntities.delete(Archetype.EPIC, epic.id, "carol");

    List<AuditOperation> ops =
        auditService.listForEntity(AuditEntityType.EPIC, epic.id).stream()
            .map(a -> a.operation)
            .toList();
    // Newest first: DELETE, UPDATE, CREATE.
    assertEquals(List.of(AuditOperation.DELETE, AuditOperation.UPDATE, AuditOperation.CREATE), ops);

    var entries = auditService.listForEntity(AuditEntityType.EPIC, epic.id);
    assertEquals("carol", entries.get(0).changedBy);
    assertEquals("bob", entries.get(1).changedBy);
    assertEquals("alice", entries.get(2).changedBy);
    entries.forEach(e -> assertNotNull(e.changedAt));
    // The CREATE snapshot carries the entity's fields as JSON.
    assertTrue(entries.get(2).snapshot.contains("\"projectId\":\"proj-1\""));
  }
}
