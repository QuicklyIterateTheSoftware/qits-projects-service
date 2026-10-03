package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityStateMachine.TransitionKind;
import eu.wohlben.qits.entities.control.EntityStateMachine.Phase;
import eu.wohlben.qits.entities.control.EntityStateMachine.Transition;
import eu.wohlben.qits.entities.entity.EntityStatus;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The state machine itself: its declaration, the properties the graph must have, and the operations
 * every other surface derives from it.
 *
 * <p><b>Plain JUnit and no Quarkus application</b> — a pure function over static declarations, and
 * a {@code @TestProfile} is ~125 MB of retained metaspace inside a 4 GB CI step.
 */
class EntityStateMachineTest {

  // ---- the declaration -------------------------------------------------------------------------

  @Test
  void everyDeclaredTransitionIsLegal() {
    for (Transition move : EntityStateMachine.transitions()) {
      assertTrue(EntityStateMachine.allows(move.from(), move.to()), move.toString());
      assertEquals(Optional.of(move), EntityStateMachine.transition(move.from(), move.to()));
      assertEquals(
          Optional.empty(), EntityStateMachine.refusal(move.from(), move.to()), move.toString());
    }
  }

  @Test
  void everyUndeclaredPairIsRefusedWithAReason() {
    int refused = 0;
    for (EntityStatus from : EntityStatus.values()) {
      for (EntityStatus to : EntityStatus.values()) {
        boolean declared =
            EntityStateMachine.transitions().stream()
                .anyMatch(move -> move.from() == from && move.to() == to);
        if (declared) {
          continue;
        }
        refused++;
        assertFalse(EntityStateMachine.allows(from, to), from + " -> " + to);
        String reason = EntityStateMachine.refusal(from, to).orElseThrow();
        assertTrue(reason.startsWith("cannot move from " + from + " to " + to), reason);
      }
    }
    // 49 ordered pairs, 16 declared moves.
    assertEquals(49 - 16, refused);
  }

  @Test
  void theDeclarationIsExactlyTheSpecifiedGraph() {
    assertEquals(
        List.of(
            new Transition(EntityStatus.REPORTED, EntityStatus.REFINED, TransitionKind.FORWARD),
            new Transition(EntityStatus.REPORTED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTING, TransitionKind.FORWARD),
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTED, TransitionKind.SKIP),
            new Transition(EntityStatus.REFINED, EntityStatus.REPORTED, TransitionKind.BACK),
            new Transition(EntityStatus.REFINED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(
                EntityStatus.IMPLEMENTING, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD),
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.REFINED, TransitionKind.BACK),
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, TransitionKind.FORWARD),
            new Transition(
                EntityStatus.IMPLEMENTED, EntityStatus.IMPLEMENTING, TransitionKind.BACK),
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.VERIFIED, EntityStatus.DONE, TransitionKind.FORWARD),
            new Transition(EntityStatus.VERIFIED, EntityStatus.IMPLEMENTED, TransitionKind.BACK),
            new Transition(EntityStatus.VERIFIED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.DROPPED, EntityStatus.REPORTED, TransitionKind.REOPEN)),
        EntityStateMachine.transitions());
  }

  // ---- the skip (qits-749) ---------------------------------------------------------------------

  @Test
  void refinedMayMoveStraightToImplementedAsTheOneSkip() {
    assertEquals(
        Optional.of(
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTED, TransitionKind.SKIP)),
        EntityStateMachine.transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTED));
    assertEquals(
        List.of(
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTED, TransitionKind.SKIP)),
        EntityStateMachine.transitions().stream()
            .filter(move -> move.kind() == TransitionKind.SKIP)
            .toList());
    // No other step can be jumped: IMPLEMENTING -> VERIFIED, REPORTED -> IMPLEMENTING are refused.
    assertFalse(EntityStateMachine.allows(EntityStatus.IMPLEMENTING, EntityStatus.VERIFIED));
    assertFalse(EntityStateMachine.allows(EntityStatus.REPORTED, EntityStatus.IMPLEMENTING));
  }

  @Test
  void theSelfCheckAcceptsTheSkipOnlyAsTwoStepsFromRefined() {
    assertTrue(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTED, TransitionKind.SKIP)));
    // Two steps, but not from REFINED.
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.VERIFIED, TransitionKind.SKIP)));
    // From REFINED, but one step or three.
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTING, TransitionKind.SKIP)));
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.REFINED, EntityStatus.VERIFIED, TransitionKind.SKIP)));
    // FORWARD was not loosened to make room for it.
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD)));
  }

  @Test
  void implementingIsLeftForwardBackOrDroppedAndEnteredBackFromImplemented() {
    assertEquals(
        List.of(
            new Transition(
                EntityStatus.IMPLEMENTING, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD),
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.REFINED, TransitionKind.BACK),
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(EntityStatus.IMPLEMENTING));
    // IMPLEMENTED -> REFINED was replaced by IMPLEMENTED -> IMPLEMENTING.
    assertFalse(EntityStateMachine.allows(EntityStatus.IMPLEMENTED, EntityStatus.REFINED));
    assertTrue(EntityStateMachine.allows(EntityStatus.IMPLEMENTED, EntityStatus.IMPLEMENTING));
  }

  // ---- DONE is final ---------------------------------------------------------------------------

  @Test
  void doneHasNoOutgoingTransitionsAndIsTheOnlyTerminalState() {
    assertEquals(List.of(), EntityStateMachine.transitionsFrom(EntityStatus.DONE));
    for (EntityStatus status : EntityStatus.values()) {
      assertEquals(
          status == EntityStatus.DONE, EntityStateMachine.isTerminal(status), status.name());
    }
  }

  @Test
  void aMoveOutOfDoneIsRefusedAsFinalAndNamesTheWayOn() {
    for (EntityStatus to : EntityStatus.values()) {
      String reason = EntityStateMachine.refusal(EntityStatus.DONE, to).orElseThrow();
      assertTrue(reason.contains("DONE is final and has no exits"), reason);
      assertTrue(reason.contains("a follow-up is a new ticket or epic"), reason);
    }
    // A refusal out of any other state carries no finality clause.
    String ordinary =
        EntityStateMachine.refusal(EntityStatus.REPORTED, EntityStatus.DONE).orElseThrow();
    assertEquals("cannot move from REPORTED to DONE", ordinary);
  }

  // ---- the shape of the graph ------------------------------------------------------------------

  @Test
  void everyStateIsReachableFromReported() {
    Set<EntityStatus> reached = EnumSet.of(EntityStatus.REPORTED);
    Deque<EntityStatus> frontier = new ArrayDeque<>(reached);
    while (!frontier.isEmpty()) {
      for (Transition move : EntityStateMachine.transitionsFrom(frontier.pop())) {
        if (reached.add(move.to())) {
          frontier.push(move.to());
        }
      }
    }
    assertEquals(EnumSet.allOf(EntityStatus.class), reached);
  }

  @Test
  void noStateOtherThanDoneIsADeadEnd() {
    for (EntityStatus status : EntityStatus.values()) {
      if (status == EntityStatus.DONE) {
        continue;
      }
      assertFalse(EntityStateMachine.transitionsFrom(status).isEmpty(), status + " is a dead end");
    }
  }

  @Test
  void theMovesOutOfEachStateAreForwardThenSkipThenBackThenDropOrReopen() {
    for (EntityStatus status : EntityStatus.values()) {
      List<TransitionKind> kinds =
          EntityStateMachine.transitionsFrom(status).stream().map(Transition::kind).toList();
      List<TransitionKind> sorted =
          kinds.stream()
              .sorted(
                  (a, b) ->
                      Integer.compare(
                          a == TransitionKind.REOPEN ? TransitionKind.DROP.ordinal() : a.ordinal(),
                          b == TransitionKind.REOPEN ? TransitionKind.DROP.ordinal() : b.ordinal()))
              .toList();
      assertEquals(sorted, kinds, status.name());
    }
  }

  @Test
  void theWalkAndTheStatesAreOrdered() {
    assertEquals(
        List.of(
            EntityStatus.REPORTED,
            EntityStatus.REFINED,
            EntityStatus.IMPLEMENTING,
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFIED,
            EntityStatus.DONE),
        EntityStateMachine.walk());
    assertEquals(
        List.of(
            EntityStatus.REPORTED,
            EntityStatus.REFINED,
            EntityStatus.IMPLEMENTING,
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFIED,
            EntityStatus.DONE,
            EntityStatus.DROPPED),
        EntityStateMachine.states());
  }

  // ---- the derived readings --------------------------------------------------------------------

  @Test
  void theFirstFourStatesStartAPhaseAndTheRestStartNone() {
    assertEquals(Optional.of(Phase.REFINE), EntityStateMachine.phaseStartedBy(EntityStatus.REPORTED));
    assertEquals(
        Optional.of(Phase.IMPLEMENT), EntityStateMachine.phaseStartedBy(EntityStatus.REFINED));
    assertEquals(
        Optional.of(Phase.IMPLEMENT), EntityStateMachine.phaseStartedBy(EntityStatus.IMPLEMENTING));
    assertEquals(
        Optional.of(Phase.VERIFY), EntityStateMachine.phaseStartedBy(EntityStatus.IMPLEMENTED));
    for (EntityStatus none :
        List.of(EntityStatus.VERIFIED, EntityStatus.DONE, EntityStatus.DROPPED)) {
      assertEquals(Optional.empty(), EntityStateMachine.phaseStartedBy(none), none.name());
    }
    assertEquals("refine", Phase.REFINE.word());
  }

  @Test
  void resolvingIsImplementedOrBeyondOrDropped() {
    assertEquals(
        EnumSet.of(
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFIED,
            EntityStatus.DONE,
            EntityStatus.DROPPED),
        EnumSet.copyOf(
            List.of(EntityStatus.values()).stream().filter(EntityLifecycle::resolves).toList()));
  }
}
