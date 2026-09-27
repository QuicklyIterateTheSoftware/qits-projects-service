package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject AuditService auditService;

  private static final Instant WHEN = Instant.parse("2026-07-25T10:15:30.00Z");

  private WorkEntity epic() {
    return epicService.create("proj-1", "Planning domain", "The spine", "t");
  }

  private WorkEntity frozen() {
    WorkEntity epic = epic();
    return epicService.transition(epic.id, "REFINED", "t").epic();
  }

  // --- legal moves ---------------------------------------------------------------------------

  @Test
  void aNewEpicIsReportedAndFreezesToRefined() {
    WorkEntity epic = epic();
    assertEquals(EntityStatus.REPORTED.name(), epic.status);
    var result = epicService.transition(epic.id, "REFINED", "alice");
    assertEquals(EntityStatus.REFINED.name(), result.epic().status);
    assertNull(result.successor());
    assertNull(result.epic().supersededByEntityId);
  }

  @Test
  void aDraftCanBeAbandoned() {
    WorkEntity epic = epic();
    assertEquals(EntityStatus.DROPPED.name(), epicService.transition(epic.id, "DROPPED", "t").epic().status);
  }

  @Test
  void aFrozenEpicCanBeDroppedAndADropCarriesNoSuccessor() {
    WorkEntity epic = frozen();
    var result = epicService.transition(epic.id, "DROPPED", "t");
    assertEquals(EntityStatus.DROPPED.name(), result.epic().status);
    assertNull(result.successor());
    assertNull(result.epic().supersededByEntityId, "an abandoned epic names no successor");
  }

  /** The point of qits-392: an epic can be VERIFIED and DONE, which it could not before. */
  @Test
  void anEpicWalksAllTheWayToVerifiedAndDoneAndBack() {
    WorkEntity epic = epic();
    for (String target : List.of("REFINED", "IMPLEMENTED", "VERIFIED", "DONE")) {
      assertEquals(target, epicService.transition(epic.id, target, "t").epic().status);
    }
    // Nothing is terminal: DONE reopens to VERIFIED like every other move goes back.
    assertEquals("VERIFIED", epicService.transition(epic.id, "VERIFIED", "t").epic().status);
    // A transition asserts a status on the node it is about and nothing else — no child is moved.
    assertEquals(EntityStatus.VERIFIED.name(), epicService.get(epic.id).status);
  }

  @Test
  void aDroppedEpicReopensToReportedAndNowhereElse() {
    WorkEntity epic = frozen();
    epicService.transition(epic.id, "DROPPED", "t");
    for (String target : List.of("REFINED", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED")) {
      assertThrows(ConflictException.class, () -> epicService.transition(epic.id, target, "t"));
    }
    assertEquals("REPORTED", epicService.transition(epic.id, "REPORTED", "t").epic().status);
  }

  @Test
  void transitionIsAuditedAsAnUpdate() {
    WorkEntity epic = epic();
    epicService.transition(epic.id, "REFINED", "alice");

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
    WorkEntity epic = frozen();
    var result = epicService.transition(epic.id, "IMPLEMENTED", "alice");
    assertEquals(EntityStatus.IMPLEMENTED.name(), result.epic().status);
    assertNull(result.successor());
  }

  @Test
  void markingImplementedStampsTheUnstampedAndKeepsEarlierTimestamps() {
    WorkEntity epic = epic();
    Nested done = featureService.create(epic.id, "Shipped in June", null, null, "t");
    Nested open = featureService.create(epic.id, "Finished by the declaration", null, null, "t");
    Nested task = taskService.create(open.entity().id, "repo-1", "Loose end", null, null, "t");
    epicService.transition(epic.id, "REFINED", "t");
    java.time.Instant june = java.time.Instant.parse("2026-06-01T12:00:00Z");
    featureService.update(done.entity().id, null, null, null, false, june, false, "t");

    epicService.transition(epic.id, "IMPLEMENTED", "alice");

    assertEquals(june, featureService.get(done.entity().id).entity().implementedAt, "history is not rewritten");
    assertNotNull(featureService.get(open.entity().id).entity().implementedAt);
    assertNotNull(taskService.get(task.entity().id).entity().implementedAt);
  }

  @Test
  void implementedFreezesEverythingAndMovesOnlyAlongTheWalk() {
    WorkEntity epic = epic();
    Nested feature = featureService.create(epic.id, "The one feature", null, null, "t");
    epicService.transition(epic.id, "REFINED", "t");
    epicService.transition(epic.id, "IMPLEMENTED", "t");

    // Structural changes and marker changes are both rejected — the guards' status checks.
    assertThrows(
        ConflictException.class, () -> featureService.create(epic.id, "Late scope", null, null, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.entity().id, null, null, null, false, null, true, "t"));
    // Adjacent-only: not back to the draft in one jump, not to where it already is, not to DONE.
    for (String target : List.of("REPORTED", "IMPLEMENTED", "DONE")) {
      assertThrows(ConflictException.class, () -> epicService.transition(epic.id, target, "t"));
    }
    var superseded = epicService.transition(epic.id, EpicService.SUPERSEDE, "t");
    assertEquals(EntityStatus.DROPPED.name(), superseded.epic().status);
    assertNotNull(superseded.successor());
  }

  // --- illegal moves -------------------------------------------------------------------------

  @Test
  void everyOtherMoveIsRejected() {
    WorkEntity draft = epic();
    // A draft cannot move to where it already is, and nothing is skipped: IMPLEMENTED is reached
    // through REFINED alone.
    assertThrows(ConflictException.class, () -> epicService.transition(draft.id, "REPORTED", "t"));
    assertThrows(
        ConflictException.class, () -> epicService.transition(draft.id, "IMPLEMENTED", "t"));
    assertThrows(ConflictException.class, () -> epicService.transition(draft.id, "VERIFIED", "t"));
    // A draft has no frozen scope to supersede — the operation's own refusal, not the graph's.
    ConflictException noScope =
        assertThrows(
            ConflictException.class,
            () -> epicService.transition(draft.id, EpicService.SUPERSEDE, "t"));
    assertTrue(noScope.getMessage().contains("REPORTED"), noScope.getMessage());
    assertEquals(EntityStatus.REPORTED.name(), epicService.get(draft.id).status);

    WorkEntity implementing = frozen();
    assertThrows(
        ConflictException.class, () -> epicService.transition(implementing.id, "REFINED", "t"));
    assertThrows(
        ConflictException.class, () -> epicService.transition(implementing.id, "VERIFIED", "t"));
  }

  @Test
  void aSupersededEpicIsDroppedAndCannotBeSupersededAgain() {
    WorkEntity superseded = frozen();
    epicService.transition(superseded.id, EpicService.SUPERSEDE, "t");
    for (String target :
        List.of("REFINED", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED", EpicService.SUPERSEDE)) {
      assertThrows(
          ConflictException.class, () -> epicService.transition(superseded.id, target, "t"));
    }
  }

  @Test
  void anUnknownTargetIsRejected() {
    WorkEntity epic = epic();
    // The retired epic words name no status any more — a missed caller gets a 409, not a write.
    for (String retired : List.of("REFINING", "IMPLEMENTATION", "ABANDONED")) {
      assertThrows(ConflictException.class, () -> epicService.transition(epic.id, retired, "t"));
    }
    assertThrows(ConflictException.class, () -> epicService.transition(epic.id, "reported", "t"));
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

    var freeze = epicService.planTransition(epic.id, "REFINED");
    assertEquals(EntityStatus.REFINED, freeze.target());
    assertEquals(epic.id, freeze.epic().id);
    assertFalse(freeze.resolving(), "the scope freeze does not resolve the epic");

    assertTrue(epicService.planTransition(epic.id, "DROPPED").resolving());
    assertThrows(
        ConflictException.class, () -> epicService.planTransition(epic.id, "IMPLEMENTED"));
    assertThrows(ConflictException.class, () -> epicService.planTransition(epic.id, "DONE"));
    assertThrows(ConflictException.class, () -> epicService.planTransition(epic.id, "REFINING"));
    // The preview reserves nothing: the epic is where it was, and the move still runs.
    assertEquals(EntityStatus.REPORTED.name(), epicService.get(epic.id).status);

    WorkEntity frozen = frozen();
    assertTrue(epicService.planTransition(frozen.id, "IMPLEMENTED").resolving());
    assertFalse(
        epicService.planTransition(frozen.id, "REPORTED").resolving(),
        "reopening the scope resolves nothing");
    var supersede = epicService.planTransition(frozen.id, EpicService.SUPERSEDE);
    assertEquals(EntityStatus.DROPPED, supersede.target());
    assertTrue(supersede.resolving());
  }

  // --- the supersede copy --------------------------------------------------------------------

  @Test
  void supersedingCopiesTheWholeScopeIntoAFreshDraft() {
    WorkEntity old = epic();
    Nested a = featureService.create(old.id, "Feature A", "body A", null, "t");
    Nested b = featureService.create(old.id, "Feature B", null, a.entity().id, "t");
    Nested t1 = taskService.create(a.entity().id, "repo-1", "Task one", "body 1", null, "t");
    Nested t2 = taskService.create(a.entity().id, "repo-2", "Task two", null, t1.entity().id, "t");
    epicService.transition(old.id, "REFINED", "t");
    featureService.update(a.entity().id, null, null, null, false, WHEN, false, "t");
    taskService.update(t1.entity().id, null, null, null, false, WHEN, false, "t");

    var result = epicService.transition(old.id, EpicService.SUPERSEDE, "carol");
    WorkEntity successor = result.successor();

    assertNotNull(successor);
    // SUPERSEDED is an operation now, not a status: the row lands DROPPED, and the successor
    // pointer is what says it was superseded rather than abandoned.
    assertEquals(EntityStatus.DROPPED.name(), result.epic().status);
    assertEquals(successor.id, result.epic().supersededByEntityId);
    assertEquals(EntityStatus.REPORTED.name(), successor.status);
    assertNotEquals(old.id, successor.id);
    assertEquals(old.projectId, successor.projectId);
    assertEquals("Planning domain", successor.title);
    assertEquals("The spine", successor.description);
    // The epic's slug is unique per project and the old row still holds it, so the successor mints
    // the next free one; the copied features and tasks keep theirs (new epic, new scope).
    assertEquals("planning-domain-2", successor.slug);

    List<Nested> features = featureService.listByEpic(successor.id);
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

    List<Nested> tasks = taskService.listByFeature(copyA.entity().id);
    assertEquals(List.of("task-one", "task-two"), tasks.stream().map(x -> x.entity().slug).toList());
    assertEquals("repo-1", tasks.get(0).entity().repositoryId);
    assertEquals("repo-2", tasks.get(1).entity().repositoryId);
    assertNull(tasks.get(0).entity().implementedAt);
    assertEquals(tasks.get(0).entity().id, tasks.get(1).entity().dependsOnEntityId);
    assertNotEquals(t2.entity().id, tasks.get(1).entity().id);

    // The old tree is untouched: it is the record of what was discarded.
    assertEquals(WHEN, featureService.get(a.entity().id).entity().implementedAt);
    assertEquals(2, featureService.listByEpic(old.id).size());
  }

  @Test
  void everyCopiedRowIsAuditedAsACreate() {
    WorkEntity old = epic();
    Nested a = featureService.create(old.id, "Feature A", null, null, "t");
    taskService.create(a.entity().id, "repo-1", "Task one", null, null, "t");
    epicService.transition(old.id, "REFINED", "t");

    WorkEntity successor =
        epicService.transition(old.id, EpicService.SUPERSEDE, "carol").successor();

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
    WorkEntity successor = epicService.transition(old.id, EpicService.SUPERSEDE, "t").successor();
    assertNotNull(successor);
    assertTrue(featureService.listByEpic(successor.id).isEmpty());
  }

  // --- the freeze ----------------------------------------------------------------------------

  @Test
  void structuralChangesAreRejectedOnceTheScopeIsFrozen() {
    WorkEntity epic = epic();
    Nested feature = featureService.create(epic.id, "Feature", null, null, "t");
    Nested task = taskService.create(feature.entity().id, "repo-1", "Task", null, null, "t");
    epicService.transition(epic.id, "REFINED", "t");

    ConflictException refused =
        assertThrows(
            ConflictException.class, () -> epicService.update(epic.id, "Renamed", null, "t"));
    // The refusal says why: the status it is in, the one scope needs, and the way back.
    assertTrue(refused.getMessage().contains("REFINED"), refused.getMessage());
    assertTrue(refused.getMessage().contains("REPORTED"), refused.getMessage());
    assertThrows(
        ConflictException.class, () -> featureService.create(epic.id, "Another", null, null, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.entity().id, "Renamed", null, null, false, null, false, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.entity().id, null, null, null, true, null, false, "t"));
    assertThrows(ConflictException.class, () -> featureService.delete(feature.entity().id, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.create(feature.entity().id, "repo-1", "Another", null, null, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.update(task.entity().id, "Renamed", null, null, false, null, false, "t"));
    assertThrows(ConflictException.class, () -> taskService.delete(task.entity().id, "t"));
  }

  /** The freeze is reversible in the ordinary way: back to REPORTED, and scope moves again. */
  @Test
  void movingAFrozenEpicBackToReportedReopensItsScope() {
    WorkEntity epic = epic();
    Nested feature = featureService.create(epic.id, "Feature", null, null, "t");
    epicService.transition(epic.id, "REFINED", "t");
    assertThrows(
        ConflictException.class, () -> featureService.create(epic.id, "Late", null, null, "t"));

    epicService.transition(epic.id, "REPORTED", "t");

    assertEquals("Renamed", epicService.update(epic.id, "Renamed", null, "t").title);
    featureService.create(epic.id, "Late", null, null, "t");
    featureService.update(feature.entity().id, "Renamed too", null, null, false, null, false, "t");
    assertEquals(2, featureService.listByEpic(epic.id).size());
    // And the markers are closed again — they move only at REFINED.
    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () ->
                featureService.update(
                    feature.entity().id, null, null, null, false, WHEN, false, "t"));
    assertTrue(refused.getMessage().contains("REFINED"), refused.getMessage());
  }

  @Test
  void implementedMarkersAreRejectedWhileTheEpicIsStillADraft() {
    WorkEntity epic = epic();
    Nested feature = featureService.create(epic.id, "Feature", null, null, "t");
    Nested task = taskService.create(feature.entity().id, "repo-1", "Task", null, null, "t");

    // Nothing ships from a draft — neither setting the marker nor clearing it.
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.entity().id, null, null, null, false, WHEN, false, "t"));
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.entity().id, null, null, null, false, null, true, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.update(task.entity().id, null, null, null, false, WHEN, false, "t"));
    assertThrows(
        ConflictException.class,
        () -> taskService.update(task.entity().id, null, null, null, false, null, true, "t"));
  }

  @Test
  void oneCallCannotMixScopeAndMarkers() {
    WorkEntity epic = epic();
    Nested feature = featureService.create(epic.id, "Feature", null, null, "t");
    epicService.transition(epic.id, "REFINED", "t");

    // No phase allows both, so the pair is refused whichever phase the epic is in.
    assertThrows(
        ConflictException.class,
        () -> featureService.update(feature.entity().id, "Renamed", null, null, false, WHEN, false, "t"));
  }

  @Test
  void resolvedEpicsRejectEveryWrite() {
    for (List<String> walk :
        List.of(
            List.of(EpicService.SUPERSEDE),
            List.of("DROPPED"),
            List.of("IMPLEMENTED", "VERIFIED"),
            List.of("IMPLEMENTED", "VERIFIED", "DONE"))) {
      WorkEntity epic = epic();
      Nested feature = featureService.create(epic.id, "Feature", null, null, "t");
      Nested task = taskService.create(feature.entity().id, "repo-1", "Task", null, null, "t");
      epicService.transition(epic.id, "REFINED", "t");
      for (String target : walk) {
        epicService.transition(epic.id, target, "t");
      }

      assertThrows(
          ConflictException.class, () -> epicService.update(epic.id, "Renamed", null, "t"));
      assertThrows(
          ConflictException.class, () -> featureService.create(epic.id, "Another", null, null, "t"));
      assertThrows(
          ConflictException.class,
          () -> featureService.update(feature.entity().id, "Renamed", null, null, false, null, false, "t"));
      assertThrows(
          ConflictException.class,
          () -> featureService.update(feature.entity().id, null, null, null, false, WHEN, false, "t"));
      assertThrows(ConflictException.class, () -> featureService.delete(feature.entity().id, "t"));
      assertThrows(
          ConflictException.class,
          () -> taskService.update(task.entity().id, null, null, null, false, WHEN, false, "t"));
      assertThrows(ConflictException.class, () -> taskService.delete(task.entity().id, "t"));
    }
  }

  @Test
  void deletingAnEpicStaysAllowedInEveryStatus() {
    for (String target : List.of("REFINED", "DROPPED")) {
      WorkEntity epic = epic();
      featureService.create(epic.id, "Feature", null, null, "t");
      epicService.transition(epic.id, target, "t");
      // Deleting removes the row rather than editing a frozen scope; the audit log outlives it.
      epicService.delete(epic.id, "t");
      inFreshTx(() -> assertTrue(epicService.listByProject("proj-1", target).isEmpty()));
    }
  }

  // --- the status filter ---------------------------------------------------------------------

  @Test
  void listByProjectFiltersByStatus() {
    WorkEntity draft = epic();
    WorkEntity implementing = frozen();
    WorkEntity abandoned = epic();
    epicService.transition(abandoned.id, "DROPPED", "t");

    assertEquals(3, epicService.listByProject("proj-1").size());
    assertEquals(
        List.of(draft.id),
        epicService.listByProject("proj-1", "REPORTED").stream().map(e -> e.id).toList());
    assertEquals(
        List.of(implementing.id),
        epicService.listByProject("proj-1", "REFINED").stream().map(e -> e.id).toList());
    assertEquals(
        List.of(abandoned.id),
        epicService.listByProject("proj-1", "DROPPED").stream().map(e -> e.id).toList());
    assertTrue(epicService.listByProject("proj-1", "DONE").isEmpty());
    // A blank filter is no filter.
    assertEquals(3, epicService.listByProject("proj-1", "  ").size());
  }
}
