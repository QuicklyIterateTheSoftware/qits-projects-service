package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityStateMachine.TransitionKind;
import eu.wohlben.qits.entities.control.EntityStateMachine.Phase;
import eu.wohlben.qits.entities.control.EntityStateMachine.Transition;
import eu.wohlben.qits.entities.entity.Archetype;
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
    // 81 ordered pairs, 22 declared moves.
    assertEquals(81 - 22, refused);
  }

  @Test
  void theDeclarationIsExactlyTheSpecifiedGraph() {
    assertEquals(
        List.of(
            new Transition(EntityStatus.REPORTED, EntityStatus.REFINED, TransitionKind.FORWARD),
            new Transition(EntityStatus.REPORTED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.REFINED, EntityStatus.READY_FOR_DEV, TransitionKind.FORWARD),
            new Transition(EntityStatus.REFINED, EntityStatus.REPORTED, TransitionKind.BACK),
            new Transition(EntityStatus.REFINED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING, TransitionKind.FORWARD),
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED, TransitionKind.SKIP),
            new Transition(EntityStatus.READY_FOR_DEV, EntityStatus.REFINED, TransitionKind.BACK),
            new Transition(EntityStatus.READY_FOR_DEV, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(
                EntityStatus.IMPLEMENTING, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD),
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(
                EntityStatus.IMPLEMENTED, EntityStatus.VERIFYING, TransitionKind.FORWARD),
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, TransitionKind.SKIP),
            new Transition(
                EntityStatus.IMPLEMENTED, EntityStatus.IMPLEMENTING, TransitionKind.BACK),
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.VERIFYING, EntityStatus.VERIFIED, TransitionKind.FORWARD),
            new Transition(EntityStatus.VERIFYING, EntityStatus.IMPLEMENTED, TransitionKind.BACK),
            new Transition(EntityStatus.VERIFYING, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.VERIFIED, EntityStatus.DONE, TransitionKind.FORWARD),
            new Transition(EntityStatus.VERIFIED, EntityStatus.VERIFYING, TransitionKind.BACK),
            new Transition(EntityStatus.VERIFIED, EntityStatus.DROPPED, TransitionKind.DROP),
            new Transition(EntityStatus.DROPPED, EntityStatus.REPORTED, TransitionKind.REOPEN)),
        EntityStateMachine.transitions());
  }

  // ---- the skip (qits-749) ---------------------------------------------------------------------

  @Test
  void theTwoSkipsJumpExactlyTheTwoIngStatuses() {
    assertEquals(
        List.of(
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED, TransitionKind.SKIP),
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, TransitionKind.SKIP)),
        EntityStateMachine.transitions().stream()
            .filter(move -> move.kind() == TransitionKind.SKIP)
            .toList());
    // No other step can be jumped.
    assertFalse(EntityStateMachine.allows(EntityStatus.IMPLEMENTING, EntityStatus.VERIFYING));
    assertFalse(EntityStateMachine.allows(EntityStatus.VERIFYING, EntityStatus.DONE));
    assertFalse(EntityStateMachine.allows(EntityStatus.REPORTED, EntityStatus.IMPLEMENTING));
    // qits-887: the skip cannot bypass the person's scheduling.
    assertFalse(EntityStateMachine.allows(EntityStatus.REFINED, EntityStatus.IMPLEMENTED));
    assertFalse(EntityStateMachine.allows(EntityStatus.REFINED, EntityStatus.IMPLEMENTING));
  }

  @Test
  void theSelfCheckAcceptsTheSkipOnlyAsTwoStepsFromReadyForDevOrImplemented() {
    assertTrue(
        EntityStateMachine.kindMatches(
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED, TransitionKind.SKIP)));
    assertTrue(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, TransitionKind.SKIP)));
    // Two steps, but not from READY_FOR_DEV or IMPLEMENTED.
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.REFINED, EntityStatus.IMPLEMENTING, TransitionKind.SKIP)));
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.VERIFYING, TransitionKind.SKIP)));
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.VERIFYING, EntityStatus.DONE, TransitionKind.SKIP)));
    // From READY_FOR_DEV, but one step or three.
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING, TransitionKind.SKIP)));
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.READY_FOR_DEV, EntityStatus.VERIFYING, TransitionKind.SKIP)));
    // FORWARD was not loosened to make room for it.
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD)));
  }

  @Test
  void implementingIsLeftForwardOrDroppedOnlyAndEnteredBackFromImplemented() {
    // qits-887 (decision 11): IMPLEMENTING has no BACK move; the way out of started work is DROP.
    assertEquals(
        List.of(
            new Transition(
                EntityStatus.IMPLEMENTING, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD),
            new Transition(EntityStatus.IMPLEMENTING, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(EntityStatus.IMPLEMENTING));
    assertFalse(EntityStateMachine.allows(EntityStatus.IMPLEMENTING, EntityStatus.REFINED));
    assertFalse(EntityStateMachine.allows(EntityStatus.IMPLEMENTING, EntityStatus.READY_FOR_DEV));
    // IMPLEMENTED -> REFINED was replaced by IMPLEMENTED -> IMPLEMENTING.
    assertFalse(EntityStateMachine.allows(EntityStatus.IMPLEMENTED, EntityStatus.REFINED));
    assertTrue(EntityStateMachine.allows(EntityStatus.IMPLEMENTED, EntityStatus.IMPLEMENTING));
  }

  @Test
  void verifyingIsTheMirrorOfImplementingOnePhaseLater() {
    assertEquals(
        List.of(
            new Transition(EntityStatus.VERIFYING, EntityStatus.VERIFIED, TransitionKind.FORWARD),
            new Transition(EntityStatus.VERIFYING, EntityStatus.IMPLEMENTED, TransitionKind.BACK),
            new Transition(EntityStatus.VERIFYING, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(EntityStatus.VERIFYING));
    // VERIFIED -> IMPLEMENTED was replaced by VERIFIED -> VERIFYING.
    assertFalse(EntityStateMachine.allows(EntityStatus.VERIFIED, EntityStatus.IMPLEMENTED));
    assertTrue(EntityStateMachine.allows(EntityStatus.VERIFIED, EntityStatus.VERIFYING));
    // The platform's "phase started" moves, one per "-ING" status.
    assertEquals(
        Optional.of(EntityStatus.IMPLEMENTING),
        EntityStateMachine.startedStatusOf(EntityStatus.READY_FOR_DEV));
    assertEquals(
        Optional.of(EntityStatus.VERIFYING),
        EntityStateMachine.startedStatusOf(EntityStatus.IMPLEMENTED));
    for (EntityStatus none :
        List.of(
            EntityStatus.REPORTED,
            EntityStatus.REFINED,
            EntityStatus.IMPLEMENTING,
            EntityStatus.VERIFYING,
            EntityStatus.VERIFIED)) {
      assertEquals(Optional.empty(), EntityStateMachine.startedStatusOf(none), none.name());
    }
    assertTrue(
        EntityStateMachine.isStartedMove(EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING));
    assertFalse(EntityStateMachine.isStartedMove(EntityStatus.REFINED, EntityStatus.READY_FOR_DEV));
    assertTrue(EntityStateMachine.isStartedMove(EntityStatus.IMPLEMENTED, EntityStatus.VERIFYING));
    assertFalse(EntityStateMachine.isStartedMove(EntityStatus.VERIFIED, EntityStatus.VERIFYING));
  }

  @Test
  void aCampaignWalksTheMachineWithBothIngStatusesElided() {
    // qits-749 elides IMPLEMENTING and VERIFYING; qits-887 (decision 19) keeps READY_FOR_DEV. The
    // lifecycle is the walk with the elided states removed, and this is its whole table.
    assertEquals(
        List.of(
            EntityStatus.REPORTED,
            EntityStatus.REFINED,
            EntityStatus.READY_FOR_DEV,
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFIED,
            EntityStatus.DONE,
            EntityStatus.DROPPED),
        EntityStateMachine.states(Archetype.CAMPAIGN));
    assertEquals(
        List.of(
            new Transition(EntityStatus.REPORTED, EntityStatus.REFINED, TransitionKind.FORWARD),
            new Transition(EntityStatus.REPORTED, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.REPORTED));
    assertEquals(
        List.of(
            new Transition(EntityStatus.REFINED, EntityStatus.READY_FOR_DEV, TransitionKind.FORWARD),
            new Transition(EntityStatus.REFINED, EntityStatus.REPORTED, TransitionKind.BACK),
            new Transition(EntityStatus.REFINED, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.REFINED));
    assertEquals(
        List.of(
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED, TransitionKind.FORWARD),
            new Transition(EntityStatus.READY_FOR_DEV, EntityStatus.REFINED, TransitionKind.BACK),
            new Transition(EntityStatus.READY_FOR_DEV, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.READY_FOR_DEV));
    assertEquals(
        List.of(
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, TransitionKind.FORWARD),
            new Transition(
                EntityStatus.IMPLEMENTED, EntityStatus.READY_FOR_DEV, TransitionKind.BACK),
            new Transition(EntityStatus.IMPLEMENTED, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.IMPLEMENTED));
    assertEquals(
        List.of(
            new Transition(EntityStatus.VERIFIED, EntityStatus.DONE, TransitionKind.FORWARD),
            new Transition(EntityStatus.VERIFIED, EntityStatus.IMPLEMENTED, TransitionKind.BACK),
            new Transition(EntityStatus.VERIFIED, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.VERIFIED));
    assertEquals(List.of(), EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.DONE));
    assertEquals(
        List.of(new Transition(EntityStatus.DROPPED, EntityStatus.REPORTED, TransitionKind.REOPEN)),
        EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.DROPPED));
    // The elided states hold no moves at all.
    assertEquals(
        List.of(), EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.IMPLEMENTING));
    assertEquals(
        List.of(), EntityStateMachine.transitionsFrom(Archetype.CAMPAIGN, EntityStatus.VERIFYING));
    assertTrue(
        EntityStateMachine.refusal(Archetype.CAMPAIGN, EntityStatus.REFINED, EntityStatus.IMPLEMENTED)
            .isPresent());
    assertTrue(
        EntityStateMachine.refusal(
                Archetype.CAMPAIGN, EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING)
            .isPresent());
    assertTrue(
        EntityStateMachine.refusal(
                Archetype.CAMPAIGN, EntityStatus.READY_FOR_DEV, EntityStatus.REPORTED)
            .isPresent());
    // Every other archetype reads the machine unchanged.
    for (Archetype archetype :
        List.of(Archetype.EPIC, Archetype.TICKET, Archetype.FEATURE, Archetype.TASK)) {
      assertEquals(EntityStateMachine.states(), EntityStateMachine.states(archetype));
      for (EntityStatus state : EntityStatus.values()) {
        assertEquals(
            EntityStateMachine.transitionsFrom(state),
            EntityStateMachine.transitionsFrom(archetype, state));
      }
    }
  }

  // ---- READY_FOR_DEV (qits-887) ----------------------------------------------------------------

  @Test
  void readyForDevIsScheduledByAPersonAndUnscheduledOnlyToRefined() {
    assertEquals(
        List.of(
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING, TransitionKind.FORWARD),
            new Transition(
                EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED, TransitionKind.SKIP),
            new Transition(EntityStatus.READY_FOR_DEV, EntityStatus.REFINED, TransitionKind.BACK),
            new Transition(EntityStatus.READY_FOR_DEV, EntityStatus.DROPPED, TransitionKind.DROP)),
        EntityStateMachine.transitionsFrom(EntityStatus.READY_FOR_DEV));
    assertTrue(EntityStateMachine.allows(EntityStatus.REFINED, EntityStatus.READY_FOR_DEV));
    // Decision 6: once scheduled it is not sent back to REPORTED.
    assertFalse(EntityStateMachine.allows(EntityStatus.READY_FOR_DEV, EntityStatus.REPORTED));
    assertEquals(
        "cannot move from READY_FOR_DEV to REPORTED",
        EntityStateMachine.refusal(EntityStatus.READY_FOR_DEV, EntityStatus.REPORTED).orElseThrow());
  }

  @Test
  void refinedStartsNoPhaseButAPersonsFlowRunsOnPastIt() {
    assertEquals(
        Optional.empty(), EntityStateMachine.phaseRunFrom(Archetype.EPIC, EntityStatus.REFINED));
    assertEquals(
        Optional.empty(), EntityStateMachine.phaseRunFrom(Archetype.TICKET, EntityStatus.REFINED));
    // qits-1075: the press pre-approves the scheduling, so the flow continues past REFINED.
    assertEquals(
        List.of(Phase.REFINE, Phase.IMPLEMENT, Phase.VERIFY),
        EntityStateMachine.flowFrom(Archetype.EPIC, EntityStatus.REPORTED).stream()
            .map(EntityStateMachine.PhaseRun::phase)
            .toList());
    assertEquals(
        EntityStateMachine.flowFrom(Archetype.TICKET, EntityStatus.READY_FOR_DEV),
        EntityStateMachine.flowFrom(Archetype.TICKET, EntityStatus.REFINED));
    assertEquals(
        List.of(), EntityStateMachine.flowFrom(Archetype.CAMPAIGN, EntityStatus.REFINED));
    assertEquals(
        List.of(
            new EntityStateMachine.PhaseRun(
                Phase.IMPLEMENT,
                EntityStatus.READY_FOR_DEV,
                EntityStatus.IMPLEMENTING,
                EntityStatus.IMPLEMENTED),
            new EntityStateMachine.PhaseRun(
                Phase.VERIFY,
                EntityStatus.IMPLEMENTED,
                EntityStatus.VERIFYING,
                EntityStatus.VERIFIED)),
        EntityStateMachine.flowFrom(Archetype.EPIC, EntityStatus.READY_FOR_DEV));
    assertEquals(EntityStatus.REFINED, EntityStateMachine.endOf(Phase.REFINE));
    assertEquals(EntityStatus.IMPLEMENTED, EntityStateMachine.endOf(Phase.IMPLEMENT));
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
            EntityStatus.READY_FOR_DEV,
            EntityStatus.IMPLEMENTING,
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFYING,
            EntityStatus.VERIFIED,
            EntityStatus.DONE),
        EntityStateMachine.walk());
    assertEquals(
        List.of(
            EntityStatus.REPORTED,
            EntityStatus.REFINED,
            EntityStatus.READY_FOR_DEV,
            EntityStatus.IMPLEMENTING,
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFYING,
            EntityStatus.VERIFIED,
            EntityStatus.DONE,
            EntityStatus.DROPPED),
        EntityStateMachine.states());
  }

  // ---- the derived readings --------------------------------------------------------------------

  @Test
  void fiveStatesStartAPhaseAndRefinedAndTheRestStartNone() {
    assertEquals(Optional.of(Phase.REFINE), EntityStateMachine.phaseStartedBy(EntityStatus.REPORTED));
    assertEquals(Optional.empty(), EntityStateMachine.phaseStartedBy(EntityStatus.REFINED));
    assertEquals(
        Optional.of(Phase.IMPLEMENT), EntityStateMachine.phaseStartedBy(EntityStatus.READY_FOR_DEV));
    assertEquals(
        Optional.of(Phase.IMPLEMENT), EntityStateMachine.phaseStartedBy(EntityStatus.IMPLEMENTING));
    assertEquals(
        Optional.of(Phase.VERIFY), EntityStateMachine.phaseStartedBy(EntityStatus.IMPLEMENTED));
    assertEquals(
        Optional.of(Phase.VERIFY), EntityStateMachine.phaseStartedBy(EntityStatus.VERIFYING));
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
            EntityStatus.VERIFYING,
            EntityStatus.VERIFIED,
            EntityStatus.DONE,
            EntityStatus.DROPPED),
        EnumSet.copyOf(
            List.of(EntityStatus.values()).stream().filter(EntityLifecycle::resolves).toList()));
  }
}
