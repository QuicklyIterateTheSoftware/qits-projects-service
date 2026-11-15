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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code CHILDREN_VERIFIED} (qits-2267): an epic or a feature moves into VERIFIED — VERIFYING →
 * VERIFIED, or the SKIP from IMPLEMENTED — only once every piece under it is VERIFIED, DONE or
 * DROPPED. Every open piece is named, tasks before their feature; an item with no children passes;
 * the gate moves nothing; and the bulk door, which states no gated move, refuses the same status
 * change naming it.
 */
@QuarkusTest
class ChildrenVerifiedGateTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-children-verified";
  private static final String WHO = "ada";
  private static final Mover PERSON = Mover.person(WHO);

  @Inject WorkEntityService workEntities;
  @Inject EntityTransitionService transitions;

  private final ChildrenVerifiedGate gate = new ChildrenVerifiedGate();

  // --- the pieces ----------------------------------------------------------------------------------

  /** Every piece is read, not only the direct children: a VERIFIED feature over an open task. */
  @Test
  void anOpenTaskUnderAVerifiedFeatureRefusesTheEpic() {
    WorkEntity epic = epic("Planned");
    WorkEntity feature = feature(epic, "Part");
    WorkEntity task = task(feature, "Piece");
    WorkEntity reopened = task(feature, "Reopened piece");
    walk(epic, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFYING");
    walk(task, "VERIFIED");
    walk(reopened, "VERIFIED");
    walk(feature, "VERIFIED");
    walk(reopened, "VERIFYING"); // a BACK: a correction no gate judges

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.EPIC, epic.id, "VERIFIED", PERSON));

    String message = refusal.getMessage();
    assertTrue(
        message.contains(
            "CHILDREN_VERIFIED: a piece is not verified yet: #"
                + reopened.number
                + " (VERIFYING) — verify or drop it first, tasks before features"),
        message);
    assertFalse(message.contains("#" + feature.number + " "), "a verified feature: " + message);
    assertFalse(message.contains("#" + task.number + " "), "a verified task: " + message);
    assertEquals("VERIFYING", status(epic));
  }

  /** The open pieces are named deepest first — a feature's tasks, then the feature. */
  @Test
  void everyOpenPieceIsNamedTasksBeforeTheirFeature() {
    WorkEntity epic = epic("Planned");
    WorkEntity first = feature(epic, "First part");
    WorkEntity firstTask = task(first, "First piece");
    WorkEntity second = feature(epic, "Second part");
    WorkEntity secondTask = task(second, "Second piece");
    walk(epic, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFYING");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.EPIC, epic.id, "VERIFIED", PERSON));

    assertTrue(
        refusal
            .getMessage()
            .contains(
                "4 pieces are not verified yet: #"
                    + firstTask.number
                    + " (IMPLEMENTED), #"
                    + first.number
                    + " (IMPLEMENTED), #"
                    + secondTask.number
                    + " (IMPLEMENTED), #"
                    + second.number
                    + " (IMPLEMENTED) — verify or drop them first, tasks before features"),
        refusal.getMessage());
  }

  /** DONE and DROPPED pieces are settled beside VERIFIED ones, and the epic's move drags none. */
  @Test
  void doneDroppedAndVerifiedPiecesPassAndNothingMoves() {
    WorkEntity epic = epic("Planned");
    WorkEntity closed = feature(epic, "Closed part");
    WorkEntity closedTask = task(closed, "Dropped piece");
    WorkEntity dropped = feature(epic, "Dropped part");
    WorkEntity verified = feature(epic, "Verified part");
    WorkEntity verifiedTask = task(verified, "Verified piece");
    walk(epic, "REFINED");
    walk(closedTask, "DROPPED");
    walk(dropped, "DROPPED");
    walk(epic, "READY_FOR_DEV");
    walk(closed, "DONE"); // the CLOSE: every child of it is closed
    walk(epic, "IMPLEMENTED");
    walk(verifiedTask, "VERIFIED");
    walk(verified, "VERIFIED");

    workEntities.transition(Archetype.EPIC, epic.id, "VERIFIED", Mover.machine("agent-7"));

    assertEquals("VERIFIED", status(epic));
    assertEquals("DONE", status(closed));
    assertEquals("DROPPED", status(closedTask));
    assertEquals("DROPPED", status(dropped));
    assertEquals("VERIFIED", status(verified));
    assertEquals("VERIFIED", status(verifiedTask));
  }

  @Test
  void anEpicWithNoChildrenPasses() {
    WorkEntity epic = epic("Bare");
    walk(epic, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFYING");

    workEntities.transition(Archetype.EPIC, epic.id, "VERIFIED", Mover.machine("agent-7"));

    assertEquals("VERIFIED", status(epic));
  }

  /** IMPLEMENTED → VERIFIED is the walk's SKIP, and as gated as the FORWARD. */
  @Test
  void theSkipFromImplementedIsGated() {
    WorkEntity epic = epic("Planned");
    WorkEntity feature = feature(epic, "Part");
    walk(epic, "REFINED", "READY_FOR_DEV", "IMPLEMENTED");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.EPIC, epic.id, "VERIFIED", PERSON));

    assertTrue(
        refusal
            .getMessage()
            .contains(
                "CHILDREN_VERIFIED: a piece is not verified yet: #"
                    + feature.number
                    + " (IMPLEMENTED) — verify or drop it first"),
        refusal.getMessage());
    assertEquals("IMPLEMENTED", status(epic));
  }

  @Test
  void aFeatureOverAnOpenTaskIsRefused() {
    WorkEntity epic = epic("Planned");
    WorkEntity feature = feature(epic, "Part");
    WorkEntity open = task(feature, "Open piece");
    WorkEntity done = task(feature, "Verified piece");
    walk(epic, "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    walk(done, "VERIFIED");
    walk(feature, "VERIFYING");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.FEATURE, feature.id, "VERIFIED", PERSON));

    assertTrue(
        refusal
            .getMessage()
            .contains("CHILDREN_VERIFIED: a piece is not verified yet: #" + open.number + " (IMPLEMENTED)"),
        refusal.getMessage());
    assertEquals("VERIFYING", status(feature));

    walk(open, "VERIFIED");
    walk(feature, "VERIFIED");

    assertEquals("VERIFIED", status(feature));
    assertEquals("IMPLEMENTED", status(epic), "verifying the last feature moves no epic");
  }

  // --- the bulk door -------------------------------------------------------------------------------

  /** The bulk door states no gated move: an epic's or a feature's VERIFIED is the status door's. */
  @Test
  void theBulkDoorRefusesAnEpicsAndAFeaturesVerifiedNamingTheGate() {
    WorkEntity epic = epic("Planned");
    WorkEntity feature = feature(epic, "Part");
    walk(epic, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFYING");

    Map<String, EntityTransition> epicVerified = new LinkedHashMap<>();
    epicVerified.put(
        epic.id,
        new EntityTransition(
            Archetype.EPIC,
            new EntityTransition.Membership(null, null),
            "Planned",
            null,
            EntityStatus.VERIFIED.name(),
            null,
            null,
            null,
            null,
            null,
            null,
            CRITERIA));
    ConflictException byEpic =
        assertThrows(ConflictException.class, () -> transitions.transition(epicVerified, WHO));
    assertTrue(
        byEpic.getMessage().contains("that move has quality gates (" + ChildrenVerifiedGate.NAME + ")"),
        byEpic.getMessage());

    Map<String, EntityTransition> featureVerified = new LinkedHashMap<>();
    featureVerified.put(
        feature.id,
        new EntityTransition(
            Archetype.FEATURE,
            new EntityTransition.Membership(epic.id, 0),
            "Part",
            null,
            EntityStatus.VERIFIED.name(),
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    ConflictException byFeature =
        assertThrows(ConflictException.class, () -> transitions.transition(featureVerified, WHO));
    assertTrue(
        byFeature
            .getMessage()
            .contains("that move has quality gates (" + ChildrenVerifiedGate.NAME + ")"),
        byFeature.getMessage());

    assertEquals("VERIFYING", status(epic));
    assertEquals("IMPLEMENTED", status(feature));
  }

  // --- what it judges -----------------------------------------------------------------------------

  @Test
  void itJudgesAnEpicsAndAFeaturesMoveIntoVerifiedAndNothingElse() {
    for (Archetype archetype : Archetype.values()) {
      for (EntityStatus from : EntityStatus.values()) {
        for (EntityStatus to : EntityStatus.values()) {
          boolean judged =
              (archetype == Archetype.EPIC || archetype == Archetype.FEATURE)
                  && to == EntityStatus.VERIFIED
                  && from != EntityStatus.VERIFIED
                  && from != EntityStatus.DONE
                  && from != EntityStatus.DROPPED;
          assertEquals(
              judged, gate.appliesTo(archetype, from, to), archetype + " " + from + " -> " + to);
        }
      }
    }
    for (Archetype archetype : new Archetype[] {Archetype.EPIC, Archetype.FEATURE}) {
      for (EntityStatus from : EntityStateMachine.states(archetype)) {
        for (EntityStateMachine.Transition move : EntityStateMachine.transitionsFrom(archetype, from)) {
          boolean gated =
              TransitionGates.namesOf(List.of(gate), archetype, move)
                  .contains(ChildrenVerifiedGate.NAME);
          boolean intoVerified =
              move.to() == EntityStatus.VERIFIED
                  && (move.from() == EntityStatus.VERIFYING
                      || move.from() == EntityStatus.IMPLEMENTED);
          assertEquals(intoVerified, gated, archetype + " " + move);
        }
      }
    }
  }

  // --- fixtures ------------------------------------------------------------------------------------

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
