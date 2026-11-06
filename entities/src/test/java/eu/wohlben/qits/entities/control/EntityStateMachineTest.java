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
    for (EntityStatus from : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
      for (EntityStatus to : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
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
    // 81 ordered pairs, 28 declared moves (22, and qits-1757's six CLOSEs).
    assertEquals(81 - 28, refused);
  }

  @Test
  void theDeclarationIsExactlyTheSpecifiedGraph() {
    // Every archetype with the walk reads the machine unchanged: an epic or a feature the whole of it, a
    // ticket or a task all of it but the CLOSE moves (qits-1757).
    for (Archetype archetype :
        List.of(Archetype.EPIC, Archetype.TICKET, Archetype.FEATURE, Archetype.TASK)) {
      assertEquals(EntityStateMachine.states(), EntityStateMachine.states(archetype));
      boolean closes = archetype == Archetype.EPIC || archetype == Archetype.FEATURE;
      for (EntityStatus state : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
        assertEquals(
            EntityStateMachine.transitionsFrom(state).stream()
                .filter(move -> closes || move.kind() != TransitionKind.CLOSE)
                .toList(),
            EntityStateMachine.transitionsFrom(archetype, state));
      }
    }
  }

  // ---- CLOSE (qits-1757) -----------------------------------------------------------------------

  @Test
  void closeLeadsStraightToDoneFromEveryOpenStatusButVerified() {
    List<EntityStatus> closing =
        List.of(
            EntityStatus.REPORTED,
            EntityStatus.REFINED,
            EntityStatus.READY_FOR_DEV,
            EntityStatus.IMPLEMENTING,
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFYING);
    assertEquals(
        closing.stream()
            .map(from -> new Transition(from, EntityStatus.DONE, TransitionKind.CLOSE))
            .toList(),
        EntityStateMachine.transitions().stream()
            .filter(move -> move.kind() == TransitionKind.CLOSE)
            .toList());
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.FEATURE)) {
      for (EntityStatus from : closing) {
        assertTrue(
            EntityStateMachine.isClose(archetype, from, EntityStatus.DONE), archetype + " " + from);
      }
    }
  }

  @Test
  void verifiedToDoneStaysTheForwardMoveAndIsNoClose() {
    for (Archetype archetype : Archetype.values()) {
      if (archetype == Archetype.ISSUE) {
        continue; // an issue never reaches DONE
      }
      List<Transition> intoDone =
          EntityStateMachine.transitionsFrom(archetype, EntityStatus.VERIFIED).stream()
              .filter(move -> move.to() == EntityStatus.DONE)
              .toList();
      assertEquals(
          List.of(new Transition(EntityStatus.VERIFIED, EntityStatus.DONE, TransitionKind.FORWARD)),
          intoDone,
          archetype.name());
      assertFalse(
          EntityStateMachine.isClose(archetype, EntityStatus.VERIFIED, EntityStatus.DONE),
          archetype.name());
    }
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.VERIFIED, EntityStatus.DONE, TransitionKind.CLOSE)));
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.DROPPED, EntityStatus.DONE, TransitionKind.CLOSE)));
    assertFalse(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.REFINED, EntityStatus.VERIFIED, TransitionKind.CLOSE)));
    assertTrue(
        EntityStateMachine.kindMatches(
            new Transition(EntityStatus.REFINED, EntityStatus.DONE, TransitionKind.CLOSE)));
  }

  @Test
  void aTicketAndATaskServeNoClose() {
    for (Archetype archetype : List.of(Archetype.TICKET, Archetype.TASK)) {
      assertFalse(EntityStateMachine.closes(archetype), archetype.name());
      for (EntityStatus from : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
        assertTrue(
            EntityStateMachine.transitionsFrom(archetype, from).stream()
                .noneMatch(move -> move.kind() == TransitionKind.CLOSE),
            archetype + " " + from);
        if (from != EntityStatus.VERIFIED) {
          assertFalse(
              EntityStateMachine.allows(archetype, from, EntityStatus.DONE),
              archetype + " " + from);
          assertTrue(
              EntityStateMachine.refusal(archetype, from, EntityStatus.DONE).isPresent(),
              archetype + " " + from);
        }
      }
    }
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.FEATURE)) {
      assertTrue(EntityStateMachine.closes(archetype), archetype.name());
    }
    assertFalse(EntityStateMachine.closes(Archetype.TICKET), "a ticket has no children");
  }

  @Test
  void noTwoMovesOutOfAStateShareATarget() {
    for (Archetype archetype : Archetype.values()) {
      for (EntityStatus from : EntityStateMachine.states(archetype)) {
        List<EntityStatus> targets =
            EntityStateMachine.transitionsFrom(archetype, from).stream()
                .map(Transition::to)
                .toList();
        assertEquals(targets.stream().distinct().toList(), targets, archetype + " " + from);
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
        EntityStateMachine.transitionsFrom(Archetype.TICKET, EntityStatus.READY_FOR_DEV));
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
        List.of(), EntityStateMachine.flowFrom(Archetype.ISSUE, EntityStatus.REFINED));
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
    for (EntityStatus status : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
      assertEquals(
          status == EntityStatus.DONE, EntityStateMachine.isTerminal(status), status.name());
    }
  }

  @Test
  void aMoveOutOfDoneIsRefusedAsFinalAndNamesTheWayOn() {
    for (EntityStatus to : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
      String reason = EntityStateMachine.refusal(EntityStatus.DONE, to).orElseThrow();
      assertTrue(reason.contains("DONE is final and has no exits"), reason);
      assertTrue(reason.contains("a follow-up is a new ticket or epic"), reason);
    }
    // A refusal out of any other state carries no finality clause.
    String ordinary =
        EntityStateMachine.refusal(Archetype.TICKET, EntityStatus.REPORTED, EntityStatus.DONE)
            .orElseThrow();
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
    assertEquals(EnumSet.copyOf(EntityStateMachine.states()), reached);
  }

  @Test
  void noStateOtherThanDoneIsADeadEnd() {
    for (EntityStatus status : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
      if (status == EntityStatus.DONE) {
        continue;
      }
      assertFalse(EntityStateMachine.transitionsFrom(status).isEmpty(), status + " is a dead end");
    }
  }

  @Test
  void theMovesOutOfEachStateAreForwardThenSkipThenBackThenCloseThenDropOrReopen() {
    for (EntityStatus status : EntityStateMachine.states().toArray(EntityStatus[]::new)) {
      assertOrdered(EntityStateMachine.transitionsFrom(status), status.name());
      for (Archetype archetype : Archetype.values()) {
        assertOrdered(
            EntityStateMachine.transitionsFrom(archetype, status), archetype + " " + status);
      }
    }
  }

  /** FORWARD, SKIP, BACK, CLOSE, then DROP or REOPEN — the enum's order, DROP and REOPEN one rank. */
  private static void assertOrdered(List<Transition> moves, String what) {
    List<TransitionKind> kinds = moves.stream().map(Transition::kind).toList();
    List<TransitionKind> sorted =
        kinds.stream()
            .sorted(
                (a, b) ->
                    Integer.compare(
                        a == TransitionKind.REOPEN ? TransitionKind.DROP.ordinal() : a.ordinal(),
                        b == TransitionKind.REOPEN ? TransitionKind.DROP.ordinal() : b.ordinal()))
            .toList();
    assertEquals(sorted, kinds, what);
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
            List.of(EntityStateMachine.states().toArray(EntityStatus[]::new)).stream().filter(EntityLifecycle::resolves).toList()));
  }

  // ---- an issue's lifecycle (qits-1819) ---------------------------------------------------------

  @Test
  void anIssueHasItsOwnThreeWordsAndOneSettlingMove() {
    assertEquals(
        List.of(EntityStatus.OPEN, EntityStatus.RESOLVED, EntityStatus.OBSOLETE),
        EntityStateMachine.states(Archetype.ISSUE));
    assertEquals(EntityStatus.OPEN, EntityStateMachine.initial(Archetype.ISSUE));
    assertEquals(
        List.of(
            new Transition(EntityStatus.OPEN, EntityStatus.RESOLVED, TransitionKind.SETTLE),
            new Transition(EntityStatus.OPEN, EntityStatus.OBSOLETE, TransitionKind.SETTLE)),
        EntityStateMachine.transitionsFrom(Archetype.ISSUE, EntityStatus.OPEN));
    assertTrue(EntityStateMachine.isTerminal(EntityStatus.RESOLVED));
    assertTrue(EntityStateMachine.isTerminal(EntityStatus.OBSOLETE));
    assertEquals(List.of(), EntityStateMachine.transitionsFrom(Archetype.ISSUE, EntityStatus.RESOLVED));
  }

  @Test
  void noOtherArchetypeReachesAnIssuesWordsAndAnIssueNoneOfTheirs() {
    for (Archetype archetype : Archetype.values()) {
      if (archetype == Archetype.ISSUE) {
        continue;
      }
      assertEquals(List.of(), EntityStateMachine.transitionsFrom(archetype, EntityStatus.OPEN));
      assertFalse(EntityStateMachine.states(archetype).contains(EntityStatus.OPEN));
      assertEquals(EntityStatus.REPORTED, EntityStateMachine.initial(archetype));
    }
    assertEquals(List.of(), EntityStateMachine.transitionsFrom(Archetype.ISSUE, EntityStatus.REPORTED));
  }
}
