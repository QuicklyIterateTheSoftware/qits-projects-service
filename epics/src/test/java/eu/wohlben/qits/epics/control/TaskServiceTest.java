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
 * <p>{@link FeatureServiceTest}'s note one level down: a task is a merged row plus the feature its
 * membership edge names, and every assertion is the one it always made.
 */
@QuarkusTest
class TaskServiceTest extends EpicsTestSupport {

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;

  private Nested feature() {
    WorkEntity e = epicService.create("proj-1", "Epic", null, "t");
    return featureService.create(e.id, "Feature", null, null, "t");
  }

  @Test
  void createReadUpdateDelete() {
    Nested f = feature();
    Nested task = taskService.create(f.entity().id, "repo-1", "Wire it up", "body", null, "alice");
    assertNotNull(task.entity().id);
    assertEquals("repo-1", task.entity().repositoryId);
    assertEquals(f.entity().id, task.parentId());

    Nested fetched = taskService.get(task.entity().id);
    assertEquals("Wire it up", fetched.entity().title);

    Nested updated =
        taskService.update(task.entity().id, "Wire it up v2", "body2", null, false, null, false, "bob");
    assertEquals("Wire it up v2", updated.entity().title);
    assertEquals(task.entity().createdAt, updated.entity().createdAt);

    taskService.delete(task.entity().id, "bob");
    inFreshTx(() -> assertThrows(NotFoundException.class, () -> taskService.get(task.entity().id)));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheFeature() {
    Nested f = feature();
    Nested first = taskService.create(f.entity().id, "repo-1", "Planning domain", null, null, "t");
    assertEquals("planning-domain", first.entity().slug);

    Nested second = taskService.create(f.entity().id, "repo-1", "Planning   DOMAIN!", null, null, "t");
    assertEquals("planning-domain-2", second.entity().slug);

    // Another feature is another scope, so the clean slug is free again.
    Nested other = feature();
    assertEquals(
        "planning-domain",
        taskService.create(other.entity().id, "repo-1", "Planning domain", null, null, "t")
            .entity()
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Nested task = taskService.create(feature().entity().id, "repo-1", "Planning domain", null, null, "t");
    Nested renamed = taskService.update(task.entity().id, "Renamed", null, null, false, null, false, "t");
    assertEquals("planning-domain", renamed.entity().slug);
  }

  @Test
  void createUnderUnknownFeatureThrowsNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> taskService.create("no-feature", "repo-1", "T", null, null, "t"));
  }

  @Test
  void blankRepositoryIdIsRejected() {
    Nested f = feature();
    assertThrows(
        BadRequestException.class, () -> taskService.create(f.entity().id, " ", "T", null, null, "t"));
  }

  @Test
  void dependencySetClearAndSelfCycleGuard() {
    Nested f = feature();
    Nested a = taskService.create(f.entity().id, "repo-1", "A", null, null, "t");
    Nested b = taskService.create(f.entity().id, "repo-1", "B", null, a.entity().id, "t");
    assertEquals(a.entity().id, b.entity().dependsOnEntityId);

    Nested cleared = taskService.update(b.entity().id, null, null, null, true, null, false, "t");
    assertNull(cleared.entity().dependsOnEntityId);

    // Self-dependency, unknown dependency, and multi-hop cycles are all rejected.
    assertThrows(
        BadRequestException.class,
        () -> taskService.update(a.entity().id, null, null, a.entity().id, false, null, false, "t"));
    assertThrows(
        BadRequestException.class,
        () -> taskService.update(a.entity().id, null, null, "ghost", false, null, false, "t"));
    taskService.update(b.entity().id, null, null, a.entity().id, false, null, false, "t"); // B -> A
    assertThrows(
        BadRequestException.class,
        () ->
            taskService.update(
                a.entity().id, null, null, b.entity().id, false, null, false, "t")); // A -> B closes cycle
  }

  @Test
  void crossFeatureDependencyIsRejected() {
    Nested f1 = feature();
    Nested f2 = feature();
    Nested inOther = taskService.create(f2.entity().id, "repo-1", "Other", null, null, "t");
    assertThrows(
        BadRequestException.class,
        () -> taskService.create(f1.entity().id, "repo-1", "A", null, inOther.entity().id, "t"));
  }

  @Test
  void implementedAtTransitions() {
    Nested f = feature();
    Nested t = taskService.create(f.entity().id, "repo-1", "A", null, null, "t");
    assertNull(t.entity().implementedAt);
    // The marker only moves once the epic's scope is frozen.
    epicService.transition(f.parentId(), "IMPLEMENTATION", "t");

    Instant when = Instant.parse("2026-07-25T10:15:30.00Z");
    Nested done = taskService.update(t.entity().id, null, null, null, false, when, false, "t");
    assertEquals(when, done.entity().implementedAt);

    Nested reopened = taskService.update(t.entity().id, null, null, null, false, null, true, "t");
    assertNull(reopened.entity().implementedAt);
  }

  @Test
  void mutationsAreAudited() {
    Nested f = feature();
    Nested t = taskService.create(f.entity().id, "repo-1", "A", null, null, "alice");
    var entries = auditService.listForEntity(AuditEntityType.TASK, t.entity().id);
    assertEquals(1, entries.size());
    assertEquals(AuditOperation.CREATE, entries.get(0).operation);
    assertEquals("alice", entries.get(0).changedBy);
    assertNotNull(entries.get(0).changedAt);
  }
}
