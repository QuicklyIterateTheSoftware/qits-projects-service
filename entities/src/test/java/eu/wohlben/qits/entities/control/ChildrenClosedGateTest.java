package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code CHILDREN_CLOSED} (qits-1757): an epic or a feature closes straight to DONE — the CLOSE move
 * — from every open status it holds but VERIFIED, when every direct child is DONE or DROPPED and a
 * person or the project's delivery desk makes the move. An open child is named in the 409, an item
 * with no children is refused, VERIFIED is not closed, and VERIFIED → DONE stays the walk's ungated
 * FORWARD.
 */
@QuarkusTest
class ChildrenClosedGateTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-children-closed";
  private static final String WHO = "ada";
  private static final Mover PERSON = Mover.person(WHO);

  @Inject WorkEntityService workEntities;

  private final ChildrenClosedGate gate = new ChildrenClosedGate();

  // --- every open status ---------------------------------------------------------------------------

  @Test
  void anEpicClosesFromEveryOpenStatusButVerified() {
    for (List<String> walk :
        List.of(
            List.of("REPORTED"),
            List.<String>of(),
            List.of("READY_FOR_DEV"),
            List.of("READY_FOR_DEV", "IMPLEMENTING"),
            List.of("READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED"),
            List.of("READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED", "VERIFYING"))) {
      WorkEntity epic = epic("Planned");
      WorkEntity feature = feature(epic, "Part");
      walk(epic, "REFINED");
      walk(feature, "DROPPED");
      epic = walk(epic, walk.toArray(String[]::new));
      String from = epic.status;

      workEntities.transition(Archetype.EPIC, epic.id, "DONE", PERSON);

      assertEquals("DONE", status(epic), "from " + from);
      assertEquals("DROPPED", status(feature), "a dropped child stays dropped");
    }
  }

  @Test
  void aFeatureClosesFromEveryOpenStatusButVerified() {
    for (List<String> walk :
        List.of(
            List.of("DROPPED", "REPORTED"),
            List.<String>of(),
            List.of("@READY_FOR_DEV"),
            List.of("@READY_FOR_DEV", "IMPLEMENTING"),
            List.of("@READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED"),
            List.of("@READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED", "VERIFYING"))) {
      WorkEntity epic = epic("Planned");
      WorkEntity feature = feature(epic, "Part");
      WorkEntity task = task(feature, "Piece");
      walk(epic, "REFINED");
      walk(task, "DROPPED");
      for (String step : walk) {
        if (step.startsWith("@")) {
          walk(epic, step.substring(1)); // the epic's scheduling carries the feature
        } else {
          walk(feature, step);
        }
      }
      String from = status(feature);

      workEntities.transition(Archetype.FEATURE, feature.id, "DONE", PERSON);

      assertEquals("DONE", status(feature), "from " + from);
    }
  }

  /** A feature closed as done counts as a closed child of its epic, which then closes too. */
  @Test
  void aDoneChildCountsAsClosed() {
    WorkEntity epic = epic("Planned");
    WorkEntity feature = feature(epic, "Part");
    WorkEntity task = task(feature, "Piece");
    walk(epic, "REFINED");
    walk(task, "DROPPED");
    workEntities.transition(Archetype.FEATURE, feature.id, "DONE", PERSON);

    workEntities.transition(Archetype.EPIC, epic.id, "DONE", PERSON);

    assertEquals("DONE", status(epic));
  }

  // --- the children ------------------------------------------------------------------------------

  @Test
  void anOpenChildIsRefusedAndNamed() {
    WorkEntity epic = epic("Planned");
    WorkEntity open = feature(epic, "Open part");
    WorkEntity gone = feature(epic, "Dropped part");
    walk(epic, "REFINED");
    walk(gone, "DROPPED");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.EPIC, epic.id, "DONE", PERSON));

    String message = refusal.getMessage();
    assertTrue(
        message.contains(
            "CHILDREN_CLOSED: a child is not closed yet: #" + open.number + " (REFINED)"),
        message);
    assertFalse(message.contains("#" + gone.number + " "), "a dropped child is closed: " + message);
    assertEquals("REFINED", status(epic));
  }

  @Test
  void everyOpenChildIsNamedWithItsStatus() {
    WorkEntity epic = epic("Planned");
    WorkEntity closed = feature(epic, "Closed part");
    WorkEntity implemented = feature(epic, "Implemented part");
    WorkEntity scheduled = feature(epic, "Scheduled part");
    walk(epic, "REFINED");
    walk(closed, "DROPPED");
    walk(epic, "READY_FOR_DEV");
    walk(implemented, "IMPLEMENTED");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.EPIC, epic.id, "DONE", PERSON));

    String message = refusal.getMessage();
    assertTrue(
        message.contains(
            "2 children are not closed yet: #"
                + implemented.number
                + " (IMPLEMENTED), #"
                + scheduled.number
                + " (READY_FOR_DEV) — close or drop them first"),
        message);
    assertFalse(message.contains("#" + closed.number + " "), message);
    assertFalse(message.contains(PersonApprovalGate.NAME), message);
    assertEquals("READY_FOR_DEV", status(epic));
  }

  @Test
  void aVerifiedChildIsNotClosed() {
    WorkEntity epic = epic("Planned");
    WorkEntity verified = feature(epic, "Verified part");
    walk(epic, "REFINED", "READY_FOR_DEV");
    walk(verified, "IMPLEMENTED", "VERIFIED");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.EPIC, epic.id, "DONE", PERSON));

    assertTrue(
        refusal.getMessage().contains("#" + verified.number + " (VERIFIED)"), refusal.getMessage());
    assertEquals("READY_FOR_DEV", status(epic));
  }

  @Test
  void anItemWithNoChildrenIsRefused() {
    WorkEntity epic = walk(epic("Bare"), "REFINED");

    for (WorkEntity row : List.of(epic)) {
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> workEntities.transition(row.archetype, row.id, "DONE", PERSON));
      assertTrue(
          refusal
              .getMessage()
              .contains(
                  "#"
                      + row.number
                      + " has no children — closing to DONE needs at least one closed child"),
          refusal.getMessage());
      assertEquals("REFINED", status(row));
    }
  }

  /**
   * Only the direct child's own status is read: an epic holding a feature whose task is still open
   * is refused naming the feature, because the feature is not DONE.
   */
  @Test
  void anEpicHoldingAFeatureWithAnOpenTaskIsRefusedNamingTheFeature() {
    WorkEntity epic = epic("Nested");
    WorkEntity feature = feature(epic, "Still open");
    WorkEntity task = task(feature, "Open piece");
    walk(epic, "REFINED");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.EPIC, epic.id, "DONE", PERSON));

    String message = refusal.getMessage();
    assertTrue(
        message.contains("a child is not closed yet: #" + feature.number + " (REFINED)"), message);
    assertFalse(message.contains("#" + task.number + " "), "nothing deeper is read: " + message);
    assertEquals("REFINED", status(epic));
  }

  // --- who may close ------------------------------------------------------------------------------

  @Test
  void aMachineIsRefusedAndTheProjectsDeskMayClose() {
    WorkEntity epic = epic("Planned");
    WorkEntity part = feature(epic, "Dropped part");
    walk(epic, "REFINED");
    walk(part, "DROPPED");

    ConflictException byAgent =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.transition(
                    Archetype.EPIC, epic.id, "DONE", Mover.machine("agent-7")));
    assertTrue(
        byAgent
            .getMessage()
            .contains("CHILDREN_CLOSED: closing to DONE needs a person or the delivery desk; agent-7"),
        byAgent.getMessage());
    ConflictException byAnotherDesk =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.transition(
                    Archetype.EPIC,
                    epic.id,
                    "DONE",
                    Mover.desk("desk-elsewhere", "another-project")));
    assertTrue(
        byAnotherDesk.getMessage().contains("needs a person or the delivery desk"),
        byAnotherDesk.getMessage());
    assertEquals("REFINED", status(epic));

    workEntities.transition(
        Archetype.EPIC, epic.id, "DONE", Mover.desk("desk-of-" + PROJECT, PROJECT));

    assertEquals("DONE", status(epic));
  }

  // --- what it judges -----------------------------------------------------------------------------

  @Test
  void itJudgesTheCloseMovesAndNothingElse() {
    for (Archetype archetype : Archetype.values()) {
      for (EntityStatus from : EntityStatus.values()) {
        for (EntityStatus to : EntityStatus.values()) {
          boolean close =
              EntityStateMachine.closes(archetype)
                  && to == EntityStatus.DONE
                  && EntityStateMachine.states(archetype).contains(from)
                  && from != EntityStatus.VERIFIED
                  && from != EntityStatus.DONE
                  && from != EntityStatus.DROPPED;
          assertEquals(
              close, gate.appliesTo(archetype, from, to), archetype + " " + from + " -> " + to);
        }
      }
    }
  }

  /** PERSON_APPROVAL judges the scheduling only, never a CLOSE. */
  @Test
  void noSchedulingGateJudgesAClose() {
    PersonApprovalGate person = new PersonApprovalGate();
    for (Archetype archetype : Archetype.values()) {
      for (EntityStatus from : EntityStatus.values()) {
        if (EntityStateMachine.isClose(archetype, from, EntityStatus.DONE)) {
          assertFalse(person.appliesTo(archetype, from, EntityStatus.DONE), archetype + " " + from);
        }
      }
    }
  }

  /** VERIFIED → DONE is the walk's FORWARD: ungated, by a machine, with no children at all. */
  @Test
  void verifiedToDoneStaysUngated() {
    WorkEntity epic = walk(epic("Walked"), "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED");

    workEntities.transition(Archetype.EPIC, epic.id, "DONE", Mover.machine("agent-7"));

    assertEquals("DONE", status(epic));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private WorkEntity done(WorkEntity ticket) {
    return walk(ticket, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");
  }

  private WorkEntity ticket(String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            PROJECT,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null)
                .withAcceptanceCriteria(CRITERIA),
            WHO)
        .entity();
  }

  private WorkEntity epic(String title) {
    return workEntities
        .create(
            Archetype.EPIC,
            PROJECT,
            EntityWrite.epic(title, null).withAcceptanceCriteria(CRITERIA),
            WHO)
        .entity();
  }

  private WorkEntity feature(WorkEntity epic, String title) {
    return workEntities
        .create(Archetype.FEATURE, epic.id, EntityWrite.feature(title, null), WHO)
        .entity();
  }

  private WorkEntity task(WorkEntity feature, String title) {
    return workEntities
        .create(Archetype.TASK, feature.id, EntityWrite.task("repo-1", title, null), WHO)
        .entity();
  }

  private WorkEntity walk(WorkEntity row, String... statuses) {
    WorkEntity moved = row;
    for (String status : statuses) {
      moved =
          workEntities
              .transition(row.archetype, row.id, status, PERSON, TestDrops.of(status))
              .entity();
    }
    return moved;
  }

  private String status(WorkEntity row) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workEntities.get(row.archetype, row.id))
        .status;
  }
}
