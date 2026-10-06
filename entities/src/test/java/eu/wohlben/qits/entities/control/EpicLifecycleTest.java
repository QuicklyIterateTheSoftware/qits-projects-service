package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The epic on the one entity lifecycle ({@link EntityStatus}, {@link EntityLifecycle}): which moves
 * are legal, what superseding copies, and what each status freezes.
 *
 * <p>The freeze is the part worth testing hardest — it is enforced in the services, not the UI, and
 * every case here is a rejection a client could otherwise talk the API into. Since qits-392 it is
 * also reversible: moving the epic back to REPORTED reopens its scope.
 */
@QuarkusTest
class EpicLifecycleTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;

  private static final Instant WHEN = Instant.parse("2026-07-25T10:15:30.00Z");

  private WorkEntity epic() {
    return workEntities
        .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Planning domain", "The spine").withAcceptanceCriteria(EntitiesTestSupport.CRITERIA), "t")
        .entity();
  }

  private WorkEntity frozen() {
    WorkEntity epic = epic();
    return workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t").entity();
  }

  /** A frozen epic a person scheduled (qits-887): READY_FOR_DEV, where implement runs. */
  private WorkEntity scheduled() {
    WorkEntity epic = frozen();
    return workEntities.transition(Archetype.EPIC, epic.id, "READY_FOR_DEV", "t").entity();
  }

  // --- legal moves ---------------------------------------------------------------------------

  @Test
  void aNewEpicIsReportedAndFreezesToRefined() {
    WorkEntity epic = epic();
    assertEquals(EntityStatus.REPORTED.name(), epic.status);
    var result = workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "alice");
    assertEquals(EntityStatus.REFINED.name(), result.entity().status);
    assertNull(result.successor());
    assertNull(result.entity().supersededByEntityId);
  }

  @Test
  void aDraftCanBeAbandoned() {
    WorkEntity epic = epic();
    assertEquals(
        EntityStatus.DROPPED.name(),
        workEntities.transition(Archetype.EPIC, epic.id, "DROPPED", "t").entity().status);
  }

  @Test
  void aFrozenEpicCanBeDroppedAndADropCarriesNoSuccessor() {
    WorkEntity epic = frozen();
    var result = workEntities.transition(Archetype.EPIC, epic.id, "DROPPED", "t");
    assertEquals(EntityStatus.DROPPED.name(), result.entity().status);
    assertNull(result.successor());
    assertNull(result.entity().supersededByEntityId, "an abandoned epic names no successor");
  }

  /** The point of qits-392: an epic can be VERIFIED and DONE, which it could not before. */
  @Test
  void anEpicWalksAllTheWayToVerifiedAndDoneAndStaysThere() {
    WorkEntity epic = epic();
    for (String target : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE")) {
      assertEquals(
          target, workEntities.transition(Archetype.EPIC, epic.id, target, "t").entity().status);
    }
    // DONE is final: every target is refused, the step back to VERIFIED and supersede included.
    for (String target :
        List.of(
            "REPORTED",
            "REFINED",
            "IMPLEMENTED",
            "VERIFIED",
            "DONE",
            "DROPPED",
            WorkEntityService.SUPERSEDE)) {
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> workEntities.transition(Archetype.EPIC, epic.id, target, "t"),
              "DONE -> " + target);
      assertTrue(refusal.getMessage().contains("DONE is final"), refusal.getMessage());
    }
    // A transition asserts a status on the node it is about and nothing else — no child is moved.
    assertEquals(EntityStatus.DONE.name(), workEntities.get(Archetype.EPIC, epic.id).status);
  }

  @Test
  void aDoneEpicsScopeIsFrozenForGoodAndTheRefusalSaysSo() {
    WorkEntity epic = epic();
    for (String target : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE")) {
      workEntities.transition(Archetype.EPIC, epic.id, target, "t");
    }
    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> EntityLifecycle.requireReported(workEntities.get(Archetype.EPIC, epic.id)));
    assertTrue(refusal.getMessage().contains("frozen for good"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("DONE is final"), refusal.getMessage());
  }

  @Test
  void aDroppedEpicReopensToReportedAndNowhereElse() {
    WorkEntity epic = frozen();
    workEntities.transition(Archetype.EPIC, epic.id, "DROPPED", "t");
    for (String target : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED")) {
      assertThrows(
          ConflictException.class,
          () -> workEntities.transition(Archetype.EPIC, epic.id, target, "t"));
    }
    assertEquals(
        "REPORTED",
        workEntities.transition(Archetype.EPIC, epic.id, "REPORTED", "t").entity().status);
  }

  @Test
  void transitionIsAuditedAsAnUpdate() {
    WorkEntity epic = epic();
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "alice");

    var history = auditService.listForEntity(AuditEntityType.EPIC, epic.id);
    assertEquals(AuditOperation.UPDATE, history.get(0).operation);
    assertEquals("alice", history.get(0).changedBy);
    assertTrue(history.get(0).snapshot.contains("\"status\":\"REFINED\""));
  }

  // --- marking implemented -------------------------------------------------------------------

  @Test
  void aFeaturelessEpicCanBeMarkedImplemented() {
    // The motivating case: an epic implemented straight from its description has nothing for the
    // feature derivation to fire on, and used to sit in implementation forever.
    WorkEntity epic = scheduled();
    var result = workEntities.transition(Archetype.EPIC, epic.id, "IMPLEMENTED", "alice");
    assertEquals(EntityStatus.IMPLEMENTED.name(), result.entity().status);
    assertNull(result.successor());
  }

  @Test
  void markingImplementedStampsTheUnstampedAndKeepsEarlierTimestamps() {
    WorkEntity epic = epic();
    Nested done =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Shipped in June", null, null), "t");
    Nested open =
        workEntities.create(
            Archetype.FEATURE,
            epic.id,
            EntityWrite.feature("Finished by the declaration", null, null),
            "t");
    Nested task =
        workEntities.create(
            Archetype.TASK,
            open.entity().id,
            EntityWrite.task("repo-1", "Loose end", null, null),
            "t");
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, epic.id, "READY_FOR_DEV", "t");
    java.time.Instant june = java.time.Instant.parse("2026-06-01T12:00:00Z");
    workEntities.update(
        Archetype.FEATURE,
        done.entity().id,
        EntityWrite.nodeEdit(null, null, null, false, june, false),
        "t");

    workEntities.transition(Archetype.EPIC, epic.id, "IMPLEMENTED", "alice");

    assertEquals(
        june,
        workEntities.nested(Archetype.FEATURE, done.entity().id).entity().implementedAt,
        "history is not rewritten");
    assertNotNull(workEntities.nested(Archetype.FEATURE, open.entity().id).entity().implementedAt);
    assertNotNull(workEntities.nested(Archetype.TASK, task.entity().id).entity().implementedAt);
  }

  @Test
  void implementedFreezesEverythingAndMovesOnlyAlongTheWalk() {
    WorkEntity epic = epic();
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("The one feature", null, null), "t");
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, epic.id, "READY_FOR_DEV", "t");
    workEntities.transition(Archetype.EPIC, epic.id, "IMPLEMENTED", "t");

    // Structural changes and marker changes are both rejected — the guards' status checks.
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE, epic.id, EntityWrite.feature("Late scope", null, null), "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                feature.entity().id,
                EntityWrite.nodeEdit(null, null, null, false, null, true),
                "t"));
    // Adjacent-only: not back to the draft in one jump, not to where it already is, not to DONE.
    for (String target : List.of("REPORTED", "IMPLEMENTED", "DONE")) {
      assertThrows(
          ConflictException.class,
          () -> workEntities.transition(Archetype.EPIC, epic.id, target, "t"));
    }
    var superseded =
        workEntities.transition(Archetype.EPIC, epic.id, WorkEntityService.SUPERSEDE, "t");
    assertEquals(EntityStatus.DROPPED.name(), superseded.entity().status);
    assertNotNull(superseded.successor());
  }

  // --- illegal moves -------------------------------------------------------------------------

  @Test
  void everyOtherMoveIsRejected() {
    WorkEntity draft = epic();
    // A draft cannot move to where it already is, and nothing is skipped: IMPLEMENTED is reached
    // through REFINED alone.
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.EPIC, draft.id, "REPORTED", "t"));
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.EPIC, draft.id, "IMPLEMENTED", "t"));
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.EPIC, draft.id, "VERIFIED", "t"));
    // A draft has no frozen scope to supersede — the operation's own refusal, not the graph's.
    ConflictException noScope =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.transition(
                    Archetype.EPIC, draft.id, WorkEntityService.SUPERSEDE, "t"));
    assertTrue(noScope.getMessage().contains("REPORTED"), noScope.getMessage());
    assertEquals(EntityStatus.REPORTED.name(), workEntities.get(Archetype.EPIC, draft.id).status);

    WorkEntity implementing = frozen();
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.EPIC, implementing.id, "REFINED", "t"));
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.EPIC, implementing.id, "VERIFIED", "t"));
  }

  @Test
  void aSupersededEpicIsDroppedAndCannotBeSupersededAgain() {
    WorkEntity superseded = frozen();
    workEntities.transition(Archetype.EPIC, superseded.id, WorkEntityService.SUPERSEDE, "t");
    for (String target :
        List.of(
            "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED", WorkEntityService.SUPERSEDE)) {
      assertThrows(
          ConflictException.class,
          () -> workEntities.transition(Archetype.EPIC, superseded.id, target, "t"));
    }
  }

  @Test
  void anUnknownTargetIsRejected() {
    WorkEntity epic = epic();
    // The retired epic words name no status any more — a missed caller gets a 409, not a write.
    for (String retired : List.of("REFINING", "IMPLEMENTATION", "ABANDONED")) {
      assertThrows(
          ConflictException.class,
          () -> workEntities.transition(Archetype.EPIC, epic.id, retired, "t"));
    }
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.EPIC, epic.id, "reported", "t"));
  }

  // --- the preview ---------------------------------------------------------------------------

  /**
   * The preview an assembling layer asks before tearing down what the epic still holds. Every
   * refusal is the transition's own and lands here, one step early — the whole point being that a
   * move about to be refused must not have discarded anything first.
   */
  @Test
  void planTransitionAnswersWhatTheMoveWouldBeAndRefusesWhatTheMoveWould() {
    WorkEntity epic = epic();

    var freeze = workEntities.planTransition(Archetype.EPIC, epic.id, "REFINED");
    assertEquals(EntityStatus.REFINED, freeze.target());
    assertEquals(epic.id, freeze.entity().id);
    assertFalse(freeze.resolving(), "the scope freeze does not resolve the epic");

    assertTrue(workEntities.planTransition(Archetype.EPIC, epic.id, "DROPPED").resolving());
    assertThrows(
        ConflictException.class,
        () -> workEntities.planTransition(Archetype.EPIC, epic.id, "IMPLEMENTED"));
    assertThrows(
        ConflictException.class,
        () -> workEntities.planTransition(Archetype.EPIC, epic.id, "DONE"));
    assertThrows(
        ConflictException.class,
        () -> workEntities.planTransition(Archetype.EPIC, epic.id, "REFINING"));
    // The preview reserves nothing: the epic is where it was, and the move still runs.
    assertEquals(EntityStatus.REPORTED.name(), workEntities.get(Archetype.EPIC, epic.id).status);

    WorkEntity frozen = frozen();
    // qits-887: a REFINED epic is not implemented before a person schedules it.
    assertThrows(
        ConflictException.class,
        () -> workEntities.planTransition(Archetype.EPIC, frozen.id, "IMPLEMENTED"));
    assertTrue(
        workEntities.planTransition(Archetype.EPIC, scheduled().id, "IMPLEMENTED").resolving());
    assertFalse(
        workEntities.planTransition(Archetype.EPIC, frozen.id, "REPORTED").resolving(),
        "reopening the scope resolves nothing");
    var supersede =
        workEntities.planTransition(Archetype.EPIC, frozen.id, WorkEntityService.SUPERSEDE);
    assertEquals(EntityStatus.DROPPED, supersede.target());
    assertTrue(supersede.resolving());
  }

  // --- the supersede copy --------------------------------------------------------------------

  @Test
  void supersedingCopiesTheWholeScopeIntoAFreshDraft() {
    WorkEntity old = epic();
    Nested a =
        workEntities.create(
            Archetype.FEATURE, old.id, EntityWrite.feature("Feature A", "body A", null), "t");
    Nested b =
        workEntities.create(
            Archetype.FEATURE, old.id, EntityWrite.feature("Feature B", null, a.entity().id), "t");
    Nested t1 =
        workEntities.create(
            Archetype.TASK,
            a.entity().id,
            EntityWrite.task("repo-1", "Task one", "body 1", null),
            "t");
    Nested t2 =
        workEntities.create(
            Archetype.TASK,
            a.entity().id,
            EntityWrite.task("repo-2", "Task two", null, t1.entity().id),
            "t");
    workEntities.transition(Archetype.EPIC, old.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, old.id, "READY_FOR_DEV", "t");
    workEntities.update(
        Archetype.FEATURE,
        a.entity().id,
        EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
        "t");
    workEntities.update(
        Archetype.TASK,
        t1.entity().id,
        EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
        "t");

    var result =
        workEntities.transition(Archetype.EPIC, old.id, WorkEntityService.SUPERSEDE, "carol");
    WorkEntity successor = result.successor();

    assertNotNull(successor);
    // SUPERSEDED is an operation now, not a status: the row lands DROPPED, and the successor
    // pointer is what says it was superseded rather than abandoned.
    assertEquals(EntityStatus.DROPPED.name(), result.entity().status);
    assertEquals(successor.id, result.entity().supersededByEntityId);
    assertEquals(EntityStatus.REPORTED.name(), successor.status);
    assertNotEquals(old.id, successor.id);
    assertEquals(old.projectId, successor.projectId);
    assertEquals("Planning domain", successor.title);
    assertEquals("The spine", successor.description);
    // The epic's slug is unique per project and the old row still holds it, so the successor mints
    // the next free one; the copied features and tasks keep theirs (new epic, new scope).
    assertEquals("planning-domain-2", successor.slug);

    List<Nested> features = workEntities.listChildren(Archetype.FEATURE, successor.id);
    assertEquals(2, features.size());
    Nested copyA = features.get(0);
    Nested copyB = features.get(1);
    assertEquals(List.of("feature-a", "feature-b"), features.stream().map(f -> f.entity().slug).toList());
    assertNotEquals(a.entity().id, copyA.entity().id);
    assertEquals("body A", copyA.entity().description);
    // The marker resets — nothing is implemented in a draft.
    assertNull(copyA.entity().implementedAt);
    // The dependency points at the copy, never back at the old tree.
    assertEquals(copyA.entity().id, copyB.entity().dependsOnEntityId);

    List<Nested> tasks = workEntities.listChildren(Archetype.TASK, copyA.entity().id);
    assertEquals(List.of("task-one", "task-two"), tasks.stream().map(x -> x.entity().slug).toList());
    assertEquals("repo-1", tasks.get(0).entity().repositoryId);
    assertEquals("repo-2", tasks.get(1).entity().repositoryId);
    assertNull(tasks.get(0).entity().implementedAt);
    assertEquals(tasks.get(0).entity().id, tasks.get(1).entity().dependsOnEntityId);
    assertNotEquals(t2.entity().id, tasks.get(1).entity().id);

    // The old tree is untouched: it is the record of what was discarded.
    assertEquals(
        WHEN, workEntities.nested(Archetype.FEATURE, a.entity().id).entity().implementedAt);
    assertEquals(2, workEntities.listChildren(Archetype.FEATURE, old.id).size());
  }

  @Test
  void everyCopiedRowIsAuditedAsACreate() {
    WorkEntity old = epic();
    Nested a =
        workEntities.create(
            Archetype.FEATURE, old.id, EntityWrite.feature("Feature A", null, null), "t");
    workEntities.create(
        Archetype.TASK, a.entity().id, EntityWrite.task("repo-1", "Task one", null, null), "t");
    workEntities.transition(Archetype.EPIC, old.id, "REFINED", "t");

    WorkEntity successor =
        workEntities
            .transition(Archetype.EPIC, old.id, WorkEntityService.SUPERSEDE, "carol")
            .successor();

    var history = auditService.listForEpic(successor.id);
    assertEquals(3, history.size());
    assertTrue(history.stream().allMatch(e -> e.operation == AuditOperation.CREATE));
    assertTrue(history.stream().allMatch(e -> "carol".equals(e.changedBy)));
    assertEquals(
        Set.of(AuditEntityType.EPIC, AuditEntityType.FEATURE, AuditEntityType.TASK),
        history.stream().map(e -> e.entityType).collect(Collectors.toSet()));
  }

  @Test
  void supersedingAnEmptyEpicYieldsAnEmptyDraft() {
    WorkEntity old = frozen();
    WorkEntity successor =
        workEntities
            .transition(Archetype.EPIC, old.id, WorkEntityService.SUPERSEDE, "t")
            .successor();
    assertNotNull(successor);
    assertTrue(workEntities.listChildren(Archetype.FEATURE, successor.id).isEmpty());
  }

  // --- the freeze ----------------------------------------------------------------------------

  @Test
  void structuralChangesAreRejectedOnceTheScopeIsFrozen() {
    WorkEntity epic = epic();
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
    Nested task =
        workEntities.create(
            Archetype.TASK,
            feature.entity().id,
            EntityWrite.task("repo-1", "Task", null, null),
            "t");
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities
                    .update(Archetype.EPIC, epic.id, EntityWrite.epic("Renamed", null).withAcceptanceCriteria(EntitiesTestSupport.CRITERIA), "t")
                    .entity());
    // The refusal says why: the status it is in, the one scope needs, and the way back.
    assertTrue(refused.getMessage().contains("REFINED"), refused.getMessage());
    assertTrue(refused.getMessage().contains("REPORTED"), refused.getMessage());
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE, epic.id, EntityWrite.feature("Another", null, null), "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                feature.entity().id,
                EntityWrite.nodeEdit("Renamed", null, null, false, null, false),
                "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                feature.entity().id,
                EntityWrite.nodeEdit(null, null, null, true, null, false),
                "t"));
    assertThrows(
        ConflictException.class,
        () -> workEntities.delete(Archetype.FEATURE, feature.entity().id, "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.create(
                Archetype.TASK,
                feature.entity().id,
                EntityWrite.task("repo-1", "Another", null, null),
                "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.TASK,
                task.entity().id,
                EntityWrite.nodeEdit("Renamed", null, null, false, null, false),
                "t"));
    assertThrows(
        ConflictException.class, () -> workEntities.delete(Archetype.TASK, task.entity().id, "t"));
  }

  /** The freeze is reversible in the ordinary way: back to REPORTED, and scope moves again. */
  @Test
  void movingAFrozenEpicBackToReportedReopensItsScope() {
    WorkEntity epic = epic();
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.create(
                Archetype.FEATURE, epic.id, EntityWrite.feature("Late", null, null), "t"));

    workEntities.transition(Archetype.EPIC, epic.id, "REPORTED", "t");

    assertEquals(
        "Renamed",
        workEntities
            .update(Archetype.EPIC, epic.id, EntityWrite.epic("Renamed", null).withAcceptanceCriteria(EntitiesTestSupport.CRITERIA), "t")
            .entity()
            .title);
    workEntities.create(Archetype.FEATURE, epic.id, EntityWrite.feature("Late", null, null), "t");
    workEntities.update(
        Archetype.FEATURE,
        feature.entity().id,
        EntityWrite.nodeEdit("Renamed too", null, null, false, null, false),
        "t");
    assertEquals(2, workEntities.listChildren(Archetype.FEATURE, epic.id).size());
    // And the markers are closed again — they move only at READY_FOR_DEV or IMPLEMENTING.
    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.update(
                    Archetype.FEATURE,
                    feature.entity().id,
                    EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
                    "t"));
    assertTrue(refused.getMessage().contains("READY_FOR_DEV"), refused.getMessage());
  }

  @Test
  void implementedMarkersAreRejectedWhileTheEpicIsStillADraft() {
    WorkEntity epic = epic();
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
    Nested task =
        workEntities.create(
            Archetype.TASK,
            feature.entity().id,
            EntityWrite.task("repo-1", "Task", null, null),
            "t");

    // Nothing ships from a draft — neither setting the marker nor clearing it.
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                feature.entity().id,
                EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
                "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                feature.entity().id,
                EntityWrite.nodeEdit(null, null, null, false, null, true),
                "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.TASK,
                task.entity().id,
                EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
                "t"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.TASK,
                task.entity().id,
                EntityWrite.nodeEdit(null, null, null, false, null, true),
                "t"));
  }

  // --- the implementing marker and the IMPLEMENTING status (qits-749) -------------------------

  /** An epic with one feature holding one task, frozen at REFINED and scheduled (READY_FOR_DEV). */
  private Nested scheduledTask(WorkEntity epic) {
    Nested task = plannedTask(epic);
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, epic.id, "READY_FOR_DEV", "t");
    return task;
  }

  /** An epic with one feature holding one task, still a draft. */
  private Nested plannedTask(WorkEntity epic) {
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
    Nested task =
        workEntities.create(
            Archetype.TASK,
            feature.entity().id,
            EntityWrite.task("repo-1", "Task", null, null),
            "t");
    return task;
  }

  @Test
  void markingATaskImplementingStampsItAndItsFeatureAndMovesAScheduledEpic() {
    WorkEntity epic = epic();
    Nested task = scheduledTask(epic);

    Nested marked = workEntities.markImplementing(task.entity().id, "agent");

    Instant at = marked.entity().implementingAt;
    assertNotNull(at, "the task is stamped");
    assertNull(marked.entity().implementedAt, "implementing is not implemented");
    assertEquals(
        at,
        workEntities.nested(Archetype.FEATURE, task.parentId()).entity().implementingAt,
        "the feature is stamped by the first of its tasks");
    assertEquals(
        EntityStatus.IMPLEMENTING.name(), workEntities.get(Archetype.EPIC, epic.id).status);
  }

  @Test
  void markingATaskImplementingTwiceKeepsTheFirstTimeAndMovesNothingFurther() {
    WorkEntity epic = epic();
    Nested task = scheduledTask(epic);
    Instant first = workEntities.markImplementing(task.entity().id, "agent").entity().implementingAt;

    Instant again = workEntities.markImplementing(task.entity().id, "agent").entity().implementingAt;

    assertEquals(first, again, "idempotent: the marker records when the work started");
    assertEquals(
        EntityStatus.IMPLEMENTING.name(), workEntities.get(Archetype.EPIC, epic.id).status);
  }

  @Test
  void markingATaskImplementingIsRefusedOnADraftAndOnAnImplementedEpic() {
    WorkEntity draft = epic();
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, draft.id, EntityWrite.feature("Feature", null, null), "t");
    Nested task =
        workEntities.create(
            Archetype.TASK, feature.entity().id, EntityWrite.task("repo-1", "T", null, null), "t");
    assertThrows(
        ConflictException.class, () -> workEntities.markImplementing(task.entity().id, "agent"));

    WorkEntity shipped = epic();
    Nested shippedTask = scheduledTask(shipped);
    workEntities.transition(Archetype.EPIC, shipped.id, "IMPLEMENTED", "t");
    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> workEntities.markImplementing(shippedTask.entity().id, "agent"));
    assertTrue(
        refused.getMessage().contains("READY_FOR_DEV or IMPLEMENTING"), refused.getMessage());

    // qits-887: a REFINED epic is not being implemented until a person schedules it.
    WorkEntity unscheduled = epic();
    Nested unscheduledTask = plannedTask(unscheduled);
    workEntities.transition(Archetype.EPIC, unscheduled.id, "REFINED", "t");
    ConflictException waiting =
        assertThrows(
            ConflictException.class,
            () -> workEntities.markImplementing(unscheduledTask.entity().id, "agent"));
    assertTrue(waiting.getMessage().contains("schedule"), waiting.getMessage());
    assertEquals(
        EntityStatus.REFINED.name(), workEntities.get(Archetype.EPIC, unscheduled.id).status);
  }

  @Test
  void theImplementedMarkerMovesWhileTheEpicIsImplementingWithOrWithoutAnImplementingMark() {
    WorkEntity epic = epic();
    Nested marked = plannedTask(epic);
    Nested unmarked =
        workEntities.create(
            Archetype.TASK,
            marked.parentId(),
            EntityWrite.task("repo-1", "Skipped the mark", null, null),
            "t");
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, epic.id, "READY_FOR_DEV", "t");
    // An agent may move the epic itself; the platform's moves are tested one module up.
    workEntities.transition(Archetype.EPIC, epic.id, "IMPLEMENTING", "t");

    workEntities.update(Archetype.TASK, marked.entity().id, EntityWrite.implementedAt(WHEN), "t");
    Nested skipped =
        workEntities.update(
            Archetype.TASK, unmarked.entity().id, EntityWrite.implementedAt(WHEN), "t");

    assertEquals(WHEN, workEntities.nested(Archetype.TASK, marked.entity().id).entity().implementedAt);
    assertEquals(WHEN, skipped.entity().implementedAt);
    assertNull(skipped.entity().implementingAt, "the skip leaves the implementing marker empty");
  }

  @Test
  void enteringImplementingStampsNothingAndEnteringImplementedKeepsTheImplementingMarker() {
    WorkEntity epic = epic();
    Nested task = plannedTask(epic);
    Nested other =
        workEntities.create(
            Archetype.TASK, task.parentId(), EntityWrite.task("repo-1", "Other", null, null), "t");
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
    workEntities.transition(Archetype.EPIC, epic.id, "READY_FOR_DEV", "t");
    workEntities.transition(Archetype.EPIC, epic.id, "IMPLEMENTING", "t");
    assertNull(workEntities.nested(Archetype.TASK, other.entity().id).entity().implementingAt);

    Instant started = workEntities.markImplementing(task.entity().id, "agent").entity().implementingAt;
    workEntities.transition(Archetype.EPIC, epic.id, "IMPLEMENTED", "t");

    WorkEntity shipped = workEntities.nested(Archetype.TASK, task.entity().id).entity();
    assertNotNull(shipped.implementedAt);
    assertEquals(started, shipped.implementingAt, "the implementing marker stays as history");
  }

  @Test
  void transitionFromMovesOnlyARowStillAtTheExpectedStatus() {
    WorkEntity epic = scheduled();
    workEntities.transition(Archetype.EPIC, epic.id, "IMPLEMENTED", "t"); // the skip

    assertTrue(
        workEntities
            .transitionFrom(
                Archetype.EPIC,
                epic.id,
                EntityStatus.READY_FOR_DEV,
                EntityStatus.IMPLEMENTING,
                "t")
            .isEmpty(),
        "a platform move made late must not walk an IMPLEMENTED epic back");
    assertEquals(EntityStatus.IMPLEMENTED.name(), workEntities.get(Archetype.EPIC, epic.id).status);
  }

  @Test
  void aCampaignNeverMovesToImplementing() {
    WorkEntity campaign =
        workEntities.createCampaign("proj-1", "Ordering", null, "t");
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "REFINED", "t");

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.CAMPAIGN, campaign.id, "IMPLEMENTING", "t"));
    assertTrue(refused.getMessage().contains("never moves to IMPLEMENTING"), refused.getMessage());
    assertEquals(
        EntityStatus.REFINED.name(), workEntities.get(Archetype.CAMPAIGN, campaign.id).status);
    // qits-887: it walks READY_FOR_DEV, then reaches IMPLEMENTED in one step, and IMPLEMENTED ->
    // READY_FOR_DEV is its BACK move (the walk with the elided states removed).
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.CAMPAIGN, campaign.id, "IMPLEMENTED", "t"));
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", "t");
    assertEquals(
        EntityStatus.IMPLEMENTED.name(),
        workEntities.transition(Archetype.CAMPAIGN, campaign.id, "IMPLEMENTED", "t").entity().status);
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.CAMPAIGN, campaign.id, "IMPLEMENTING", "t"));
    assertEquals(
        EntityStatus.READY_FOR_DEV.name(),
        workEntities
            .transition(Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", "t")
            .entity()
            .status);
    // VERIFYING the same (qits-749), and VERIFIED -> IMPLEMENTED stays its BACK move.
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "IMPLEMENTED", "t");
    ConflictException verifying =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.CAMPAIGN, campaign.id, "VERIFYING", "t"));
    assertTrue(verifying.getMessage().contains("never moves to VERIFYING"), verifying.getMessage());
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "VERIFIED", "t");
    assertEquals(
        EntityStatus.IMPLEMENTED.name(),
        workEntities.transition(Archetype.CAMPAIGN, campaign.id, "IMPLEMENTED", "t").entity().status);
  }

  @Test
  void oneCallCannotMixScopeAndMarkers() {
    WorkEntity epic = epic();
    Nested feature =
        workEntities.create(
            Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");

    // No phase allows both, so the pair is refused whichever phase the epic is in.
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.FEATURE,
                feature.entity().id,
                EntityWrite.nodeEdit("Renamed", null, null, false, WHEN, false),
                "t"));
  }

  @Test
  void resolvedEpicsRejectEveryWrite() {
    for (List<String> walk :
        List.of(
            List.of(WorkEntityService.SUPERSEDE),
            List.of("DROPPED"),
            List.of("READY_FOR_DEV", "IMPLEMENTED", "VERIFIED"),
            List.of("READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE"))) {
      WorkEntity epic = epic();
      Nested feature =
          workEntities.create(
              Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
      Nested task =
          workEntities.create(
              Archetype.TASK,
              feature.entity().id,
              EntityWrite.task("repo-1", "Task", null, null),
              "t");
      workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "t");
      for (String target : walk) {
        workEntities.transition(Archetype.EPIC, epic.id, target, "t");
      }

      assertThrows(
          ConflictException.class,
          () ->
              workEntities
                  .update(Archetype.EPIC, epic.id, EntityWrite.epic("Renamed", null).withAcceptanceCriteria(EntitiesTestSupport.CRITERIA), "t")
                  .entity());
      assertThrows(
          ConflictException.class,
          () ->
              workEntities.create(
                  Archetype.FEATURE, epic.id, EntityWrite.feature("Another", null, null), "t"));
      assertThrows(
          ConflictException.class,
          () ->
              workEntities.update(
                  Archetype.FEATURE,
                  feature.entity().id,
                  EntityWrite.nodeEdit("Renamed", null, null, false, null, false),
                  "t"));
      assertThrows(
          ConflictException.class,
          () ->
              workEntities.update(
                  Archetype.FEATURE,
                  feature.entity().id,
                  EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
                  "t"));
      assertThrows(
          ConflictException.class,
          () -> workEntities.delete(Archetype.FEATURE, feature.entity().id, "t"));
      assertThrows(
          ConflictException.class,
          () ->
              workEntities.update(
                  Archetype.TASK,
                  task.entity().id,
                  EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
                  "t"));
      assertThrows(
          ConflictException.class,
          () -> workEntities.delete(Archetype.TASK, task.entity().id, "t"));
    }
  }

  @Test
  void deletingAnEpicStaysAllowedInEveryStatus() {
    for (String target : List.of("REFINED", "DROPPED")) {
      WorkEntity epic = epic();
      workEntities.create(
          Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t");
      workEntities.transition(Archetype.EPIC, epic.id, target, "t");
      // Deleting removes the row rather than editing a frozen scope; the audit log outlives it.
      workEntities.delete(Archetype.EPIC, epic.id, "t");
      inFreshTx(
          () -> assertTrue(workEntities.listByProject(Archetype.EPIC, "proj-1", target).isEmpty()));
    }
  }

  // --- the status filter ---------------------------------------------------------------------

  @Test
  void listByProjectFiltersByStatus() {
    WorkEntity draft = epic();
    WorkEntity implementing = frozen();
    WorkEntity abandoned = epic();
    workEntities.transition(Archetype.EPIC, abandoned.id, "DROPPED", "t");

    assertEquals(3, workEntities.listByProject(Archetype.EPIC, "proj-1").size());
    assertEquals(
        List.of(draft.id),
        workEntities.listByProject(Archetype.EPIC, "proj-1", "REPORTED").stream()
            .map(e -> e.id)
            .toList());
    assertEquals(
        List.of(implementing.id),
        workEntities.listByProject(Archetype.EPIC, "proj-1", "REFINED").stream()
            .map(e -> e.id)
            .toList());
    assertEquals(
        List.of(abandoned.id),
        workEntities.listByProject(Archetype.EPIC, "proj-1", "DROPPED").stream()
            .map(e -> e.id)
            .toList());
    assertTrue(workEntities.listByProject(Archetype.EPIC, "proj-1", "DONE").isEmpty());
    // A blank filter is no filter.
    assertEquals(3, workEntities.listByProject(Archetype.EPIC, "proj-1", "  ").size());
  }
}
