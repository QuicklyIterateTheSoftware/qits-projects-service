package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Scheduling an epic carries its plan</b> (qits-887): REFINED → READY_FOR_DEV schedules every
 * REFINED feature and task beneath it, READY_FOR_DEV → REFINED (unscheduling) takes back the ones not
 * yet started, and a piece is never scheduled or unscheduled on its own. The carried rows are the
 * person's move: audited under the same {@code changedBy} and announced in the epic's one batch.
 */
@QuarkusTest
class ScheduleCascadeTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;
  @Inject RecordingTransitionAnnouncer announcer;

  @BeforeEach
  void quiet() {
    announcer.clear();
  }

  /** An epic holding one feature holding two tasks, frozen at REFINED. */
  private record Plan(String epic, String feature, String task, String sibling) {}

  private Plan refinedPlan() {
    String epic =
        workEntities
            .create(Archetype.EPIC, "proj-1", EntityWrite.epic("Plan", "The spine"), "t")
            .entity()
            .id;
    String feature =
        workEntities
            .create(Archetype.FEATURE, epic, EntityWrite.feature("Feature", null, null), "t")
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
    workEntities.transition(Archetype.EPIC, epic, "REFINED", "t");
    announcer.clear();
    return new Plan(epic, feature, task, sibling);
  }

  private String status(Archetype archetype, String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.get(archetype, id)).status;
  }

  @Test
  void schedulingTheEpicSchedulesItsRefinedPiecesAndLeavesADroppedOne() {
    Plan plan = refinedPlan();
    workEntities.transition(Archetype.TASK, plan.sibling(), "DROPPED", "t");
    announcer.clear();

    workEntities.transition(Archetype.EPIC, plan.epic(), "READY_FOR_DEV", "dana");

    assertEquals("READY_FOR_DEV", status(Archetype.EPIC, plan.epic()));
    assertEquals("READY_FOR_DEV", status(Archetype.FEATURE, plan.feature()));
    assertEquals("READY_FOR_DEV", status(Archetype.TASK, plan.task()));
    assertEquals("DROPPED", status(Archetype.TASK, plan.sibling()), "decided against, so left");
  }

  @Test
  void oneAnnouncementCarriesTheEpicAndEveryCarriedPieceUnderThePersonsName() {
    Plan plan = refinedPlan();
    int audited = auditService.listForEpic(plan.epic()).size();

    workEntities.transition(Archetype.EPIC, plan.epic(), "READY_FOR_DEV", "dana");

    assertEquals(1, announcer.batches().size(), "one move, one announcement");
    List<TransitionedEntity> batch = announcer.batches().get(0).entities();
    assertEquals(
        List.of(plan.epic(), plan.feature(), plan.task(), plan.sibling()),
        batch.stream().map(TransitionedEntity::id).toList(),
        "the epic first, then its pieces in tree order");
    assertTrue(
        batch.stream()
            .allMatch(
                moved ->
                    "REFINED".equals(moved.statusBefore())
                        && "READY_FOR_DEV".equals(moved.status())
                        && "dana".equals(moved.changedBy())),
        batch.toString());
    var written = auditService.listForEpic(plan.epic());
    assertEquals(audited + 4, written.size(), "one UPDATE for the epic and one per carried piece");
    var byDana = written.stream().filter(entry -> "dana".equals(entry.changedBy)).toList();
    assertEquals(4, byDana.size(), "each carried row is audited as the person's own move");
    assertTrue(byDana.stream().allMatch(entry -> entry.operation == AuditOperation.UPDATE));
  }

  @Test
  void unschedulingReturnsThePiecesNotYetStartedAndLeavesAStartedOne() {
    Plan plan = refinedPlan();
    workEntities.transition(Archetype.EPIC, plan.epic(), "READY_FOR_DEV", "dana");
    // One task started on its own (its move to IMPLEMENTING is not scheduling anything).
    workEntities.transition(Archetype.TASK, plan.task(), "IMPLEMENTING", "agent");
    announcer.clear();

    workEntities.transition(Archetype.EPIC, plan.epic(), "REFINED", "dana");

    assertEquals("REFINED", status(Archetype.EPIC, plan.epic()));
    assertEquals("REFINED", status(Archetype.FEATURE, plan.feature()));
    assertEquals("REFINED", status(Archetype.TASK, plan.sibling()));
    assertEquals("IMPLEMENTING", status(Archetype.TASK, plan.task()), "started, so it stays");
    Map<String, String> carried =
        announcer.batches().get(0).entities().stream()
            .collect(Collectors.toMap(TransitionedEntity::id, TransitionedEntity::status));
    assertEquals(
        Map.of(plan.epic(), "REFINED", plan.feature(), "REFINED", plan.sibling(), "REFINED"),
        carried,
        "the started task is not in the batch");
  }

  @Test
  void aScheduledEpicIsNeverSentBackToReported() {
    Plan plan = refinedPlan();
    workEntities.transition(Archetype.EPIC, plan.epic(), "READY_FOR_DEV", "dana");

    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.EPIC, plan.epic(), "REPORTED", "dana"));
    assertEquals("READY_FOR_DEV", status(Archetype.TASK, plan.task()));
  }

  @Test
  void aPieceIsNotScheduledOrUnscheduledOnItsOwn() {
    Plan plan = refinedPlan();

    ConflictException schedule =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TASK, plan.task(), "READY_FOR_DEV", "agent"));
    assertTrue(schedule.getMessage().contains(plan.epic()), schedule.getMessage());
    assertTrue(schedule.getMessage().contains("schedule the epic"), schedule.getMessage());
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.transition(Archetype.FEATURE, plan.feature(), "READY_FOR_DEV", "agent"));
    assertEquals("REFINED", status(Archetype.TASK, plan.task()));

    workEntities.transition(Archetype.EPIC, plan.epic(), "READY_FOR_DEV", "dana");
    ConflictException unschedule =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TASK, plan.task(), "REFINED", "agent"));
    assertTrue(unschedule.getMessage().contains("unschedule the epic"), unschedule.getMessage());
    assertEquals("READY_FOR_DEV", status(Archetype.TASK, plan.task()));
  }
}
