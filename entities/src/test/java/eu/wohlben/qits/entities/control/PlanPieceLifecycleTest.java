package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A feature's and a task's own status (qits-763): minted REPORTED, moved by the markers and by its
 * own door, carried by five of its epic's moves (two of them the scheduling, qits-887 — see {@code
 * ScheduleCascadeTest}) and by no other, refused while its epic is a draft
 * — and every move of one announced like any other move.
 *
 * <p>The epic's side of the same rules — that VERIFYING and VERIFIED no longer drag the tasks along —
 * is the regression this ticket fixes, and it is asserted here rather than one module up because it
 * is a property of {@link WorkEntityService#transition} alone.
 */
@QuarkusTest
class PlanPieceLifecycleTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;
  @Inject RecordingTransitionAnnouncer announcer;

  private static final Instant WHEN = Instant.parse("2026-07-25T10:15:30.00Z");

  @BeforeEach
  void quiet() {
    announcer.clear();
  }

  /** An epic holding one feature holding two tasks, every row a draft. */
  private record Plan(String epic, String feature, String task, String sibling) {}

  private Plan plan() {
    WorkEntity epic =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Plan", "The spine").withAcceptanceCriteria(EntitiesTestSupport.CRITERIA), "t")
            .entity();
    String feature =
        workEntities
            .create(Archetype.FEATURE, epic.id, EntityWrite.feature("Feature", null, null), "t")
            .entity()
            .id;
    String task =
        workEntities
            .create(Archetype.TASK, feature, EntityWrite.task("repo-1", "Task", null, null), "t")
            .entity()
            .id;
    String sibling =
        workEntities
            .create(Archetype.TASK, feature, EntityWrite.task("repo-1", "Sibling", null, null), "t")
            .entity()
            .id;
    return new Plan(epic.id, feature, task, sibling);
  }

  private void moveEpic(Plan plan, String... targets) {
    for (String target : targets) {
      workEntities.transition(Archetype.EPIC, plan.epic(), target, "t");
    }
  }

  /**
   * The row's status as committed, read in a transaction of its own: outside one, a test keeps one
   * persistence context and a second read would answer the row as the first read saw it.
   */
  private String status(Archetype archetype, String id) {
    return row(archetype, id).status;
  }

  private WorkEntity row(Archetype archetype, String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.get(archetype, id));
  }

  // --- minting ---------------------------------------------------------------------------------

  @Test
  void aNewFeatureAndANewTaskAreReported() {
    Plan plan = plan();
    assertEquals("REPORTED", status(Archetype.FEATURE, plan.feature()));
    assertEquals("REPORTED", status(Archetype.TASK, plan.task()));
  }

  // --- the cascades ----------------------------------------------------------------------------

  @Test
  void refiningTheEpicRefinesItsReportedPiecesAndReopeningItTakesThemBack() {
    Plan plan = plan();

    moveEpic(plan, "REFINED");
    assertEquals("REFINED", status(Archetype.FEATURE, plan.feature()));
    assertEquals("REFINED", status(Archetype.TASK, plan.task()));
    assertEquals("REFINED", status(Archetype.TASK, plan.sibling()));

    // A piece no longer REFINED is not dragged back by the reopen: only REFINED pieces return.
    workEntities.transition(Archetype.TASK, plan.task(), "DROPPED", "agent");
    moveEpic(plan, "REPORTED");

    assertEquals("DROPPED", status(Archetype.TASK, plan.task()), "not REFINED, left alone");
    assertEquals("REPORTED", status(Archetype.TASK, plan.sibling()));
  }

  @Test
  void theEpicsMoveToImplementedCarriesEveryPieceBeforeItAndStampsTheMarkers() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    // One task already further on, verified on its own; the sibling never started.
    workEntities.update(Archetype.TASK, plan.task(), EntityWrite.implementedAt(WHEN), "agent");
    moveEpic(plan, "IMPLEMENTING");
    workEntities.transition(Archetype.TASK, plan.task(), "VERIFIED", "agent");

    moveEpic(plan, "IMPLEMENTED");

    assertEquals("IMPLEMENTED", status(Archetype.FEATURE, plan.feature()));
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.sibling()));
    assertNotNull(row(Archetype.TASK, plan.sibling()).implementedAt);
    assertEquals("VERIFIED", status(Archetype.TASK, plan.task()), "past IMPLEMENTED: left alone");
    assertEquals(WHEN, row(Archetype.TASK, plan.task()).implementedAt);
  }

  @Test
  void aDroppedPieceIsNeitherCarriedNorStampedByTheMoveToImplemented() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    workEntities.transition(Archetype.TASK, plan.sibling(), "DROPPED", "t");

    moveEpic(plan, "IMPLEMENTED");

    WorkEntity dropped = row(Archetype.TASK, plan.sibling());
    assertEquals("DROPPED", dropped.status);
    assertNull(dropped.implementedAt, "nothing of a dropped piece was implemented");
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.task()));
  }

  /** The bug qits-763 fixes: verifying an epic dragged every task with it. */
  @Test
  void theEpicGoingToVerifyingAndVerifiedLeavesItsTasksWhereTheyAre() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV", "IMPLEMENTED");

    moveEpic(plan, "VERIFYING", "VERIFIED", "DONE");

    assertEquals("IMPLEMENTED", status(Archetype.FEATURE, plan.feature()));
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.task()));
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.sibling()));
  }

  @Test
  void noOtherEpicMoveTouchesAChild() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV", "IMPLEMENTING");
    assertEquals(
        "READY_FOR_DEV", status(Archetype.TASK, plan.task()), "entering IMPLEMENTING moves none");
    moveEpic(plan, "DROPPED");
    assertEquals(
        "READY_FOR_DEV", status(Archetype.TASK, plan.task()), "dropping the epic moves none");
    moveEpic(plan, "REPORTED");
    assertEquals(
        "READY_FOR_DEV",
        status(Archetype.TASK, plan.task()),
        "nor does reopening it from DROPPED");
  }

  // --- a piece's own move ----------------------------------------------------------------------

  @Test
  void aTaskIsVerifiedOnItsOwnWhileItsEpicAndSiblingStayImplemented() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    announcer.clear();

    workEntities.transition(Archetype.TASK, plan.task(), "VERIFYING", "agent");
    WorkEntityService.Transition verified =
        workEntities.transition(Archetype.TASK, plan.task(), "VERIFIED", "agent");

    assertEquals("VERIFYING", verified.statusBefore());
    assertEquals("VERIFIED", status(Archetype.TASK, plan.task()));
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.sibling()));
    assertEquals("IMPLEMENTED", status(Archetype.FEATURE, plan.feature()));
    assertEquals("IMPLEMENTED", status(Archetype.EPIC, plan.epic()));
    // Each move is its own announcement, of the task alone, with the archetype on it.
    assertEquals(2, announcer.batches().size());
    for (RecordingTransitionAnnouncer.Batch batch : announcer.batches()) {
      assertEquals(1, batch.entities().size());
      assertEquals(Archetype.TASK, batch.entities().get(0).archetype());
      assertNull(batch.entities().get(0).blocked(), "a piece carries no block flag");
    }
  }

  @Test
  void aPieceDoesNotMoveOnItsOwnWhileItsEpicIsADraft() {
    Plan plan = plan();

    ConflictException task =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TASK, plan.task(), "REFINED", "agent"));
    ConflictException feature =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.FEATURE, plan.feature(), "DROPPED", "agent"));

    assertTrue(task.getMessage().contains("is REPORTED"), task.getMessage());
    assertTrue(feature.getMessage().contains(plan.epic()), feature.getMessage());
    assertEquals("REPORTED", status(Archetype.TASK, plan.task()));
    assertEquals(List.of(), announcer.batches(), "a refused move is announced to nobody");
  }

  @Test
  void aPiecesOwnMoveFollowsTheEpicsGraph() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TASK, plan.task(), "VERIFIED", "agent"));
    assertTrue(refused.getMessage().startsWith("A task"), refused.getMessage());
    // Scheduled with its epic (qits-887); the skip READY_FOR_DEV -> IMPLEMENTED is the epic's, and
    // so a task's.
    assertEquals("READY_FOR_DEV", status(Archetype.TASK, plan.task()));
    workEntities.transition(Archetype.TASK, plan.task(), "IMPLEMENTED", "agent");
    WorkEntity task = row(Archetype.TASK, plan.task());
    assertEquals("IMPLEMENTED", task.status);
    assertNotNull(task.implementedAt, "the status and the marker say the same thing");
  }

  @Test
  void aFeaturesOwnMoveToImplementedCarriesItsTasks() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    announcer.clear();

    workEntities.transition(Archetype.FEATURE, plan.feature(), "IMPLEMENTED", "t");

    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.task()));
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.sibling()));
    assertEquals(
        "READY_FOR_DEV", status(Archetype.EPIC, plan.epic()), "nothing is derived upwards");
    List<TransitionedEntity> batch = announcer.batches().get(0).entities();
    assertEquals(
        List.of(plan.feature(), plan.task(), plan.sibling()),
        batch.stream().map(TransitionedEntity::id).toList());
  }

  // --- the markers -----------------------------------------------------------------------------

  @Test
  void markingATaskImplementingMovesItAndItsFeatureAndTheEpic() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    announcer.clear();

    workEntities.markImplementing(plan.task(), "agent");

    assertEquals("IMPLEMENTING", status(Archetype.TASK, plan.task()));
    assertEquals("IMPLEMENTING", status(Archetype.FEATURE, plan.feature()));
    assertEquals("READY_FOR_DEV", status(Archetype.TASK, plan.sibling()));
    assertEquals("IMPLEMENTING", status(Archetype.EPIC, plan.epic()));
    // The pieces' moves first, in one announcement; then the epic's own.
    List<RecordingTransitionAnnouncer.Batch> batches = announcer.batches();
    assertEquals(2, batches.size());
    assertEquals(
        Map.of(plan.task(), "READY_FOR_DEV", plan.feature(), "READY_FOR_DEV"),
        batches.get(0).entities().stream()
            .collect(
                Collectors.toMap(TransitionedEntity::id, TransitionedEntity::statusBefore)));
    assertEquals(plan.epic(), batches.get(1).entities().get(0).id());
  }

  @Test
  void theImplementedMarkerMovesTheTaskForwardOrBySkipAndNeverBack() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    workEntities.markImplementing(plan.task(), "agent");

    workEntities.update(Archetype.TASK, plan.task(), EntityWrite.implementedAt(WHEN), "agent");
    workEntities.update(Archetype.TASK, plan.sibling(), EntityWrite.implementedAt(WHEN), "agent");

    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.task()), "forward from IMPLEMENTING");
    assertEquals(
        "IMPLEMENTED", status(Archetype.TASK, plan.sibling()), "the skip from READY_FOR_DEV");

    workEntities.transition(Archetype.TASK, plan.task(), "VERIFIED", "agent");
    workEntities.update(Archetype.TASK, plan.task(), EntityWrite.implementedAt(WHEN), "agent");
    assertEquals("VERIFIED", status(Archetype.TASK, plan.task()), "a second marking moves nothing");
  }

  @Test
  void theImplementedMarkerThroughAFeatureEditMovesTheFeatureAndIsAnnounced() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    announcer.clear();

    workEntities.update(
        Archetype.FEATURE,
        plan.feature(),
        EntityWrite.nodeEdit(null, null, null, false, WHEN, false),
        "person");

    assertEquals("IMPLEMENTED", status(Archetype.FEATURE, plan.feature()));
    assertEquals(1, announcer.batches().size());
    TransitionedEntity moved = announcer.batches().get(0).entities().get(0);
    assertEquals("READY_FOR_DEV", moved.statusBefore());
    assertEquals("IMPLEMENTED", moved.status());
    assertEquals("person", moved.changedBy());
  }

  @Test
  void clearingTheImplementedMarkerTakesTheStatusBackToWhereTheOtherMarkerSays() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    workEntities.markImplementing(plan.task(), "agent");
    workEntities.update(Archetype.TASK, plan.task(), EntityWrite.implementedAt(WHEN), "agent");
    workEntities.update(Archetype.TASK, plan.sibling(), EntityWrite.implementedAt(WHEN), "agent");

    EntityWrite clear = EntityWrite.nodeEdit(null, null, null, false, null, true);
    workEntities.update(Archetype.TASK, plan.task(), clear, "person");
    workEntities.update(Archetype.TASK, plan.sibling(), clear, "person");

    assertEquals("IMPLEMENTING", status(Archetype.TASK, plan.task()));
    assertEquals(
        "READY_FOR_DEV",
        status(Archetype.TASK, plan.sibling()),
        "never started, so back to where its epic's scheduling put it");
  }

  @Test
  void aDroppedTaskTakesNoMarker() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    workEntities.transition(Archetype.TASK, plan.task(), "DROPPED", "t");

    assertThrows(
        ConflictException.class, () -> workEntities.markImplementing(plan.task(), "agent"));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.update(
                Archetype.TASK, plan.task(), EntityWrite.implementedAt(WHEN), "agent"));
    assertEquals("DROPPED", status(Archetype.TASK, plan.task()));
  }

  // --- supersede and audit ---------------------------------------------------------------------

  @Test
  void aSupersededPlansCopiedPiecesAreReported() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV", "IMPLEMENTED");

    WorkEntity successor =
        workEntities
            .transition(Archetype.EPIC, plan.epic(), WorkEntityService.SUPERSEDE, "t")
            .successor();

    Nested feature = workEntities.listChildren(Archetype.FEATURE, successor.id).get(0);
    assertEquals("REPORTED", feature.entity().status);
    assertNull(feature.entity().implementedAt);
    for (Nested task : workEntities.listChildren(Archetype.TASK, feature.entity().id)) {
      assertEquals("REPORTED", task.entity().status);
    }
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.task()), "the old tree is untouched");
  }

  @Test
  void aCarriedPieceIsAuditedOnceAndJoinsTheEpicsAnnouncement() {
    Plan plan = plan();
    moveEpic(plan, "REFINED", "READY_FOR_DEV");
    announcer.clear();
    int before = auditService.listForEpic(plan.epic()).size();

    moveEpic(plan, "IMPLEMENTED");

    // The epic, the feature and both tasks: one UPDATE each, the stamp and the move together.
    var written = auditService.listForEpic(plan.epic());
    assertEquals(before + 4, written.size());
    assertTrue(written.stream().allMatch(e -> e.operation != AuditOperation.DELETE));
    List<TransitionedEntity> batch = announcer.batches().get(0).entities();
    assertEquals(plan.epic(), batch.get(0).id(), "the epic first");
    assertEquals(
        List.of(plan.feature(), plan.task(), plan.sibling()),
        batch.subList(1, batch.size()).stream().map(TransitionedEntity::id).toList());
    assertTrue(
        batch.subList(1, batch.size()).stream()
            .allMatch(
                child ->
                    "READY_FOR_DEV".equals(child.statusBefore())
                        && EntityStatus.IMPLEMENTED.name().equals(child.status())));
  }
}
