package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The served registry document — that it is <em>derived</em> from {@code Archetypes} and that every
 * ordering in it is decided rather than incidental.
 *
 * <p><b>Plain JUnit and no Quarkus application</b>, for {@code ArchetypesTest}' reason: this is a
 * pure function over a static map, and a {@code @TestProfile} is ~125 MB of retained metaspace
 * inside a 4 GB CI step. {@code EntityArchetypesApiTest} is where the route itself is asserted, once.
 */
class ArchetypeRegistryDocumentTest {

  private static ArchetypeRegistryDocument.DeclaredArchetype declared(Archetype archetype) {
    return ArchetypeRegistryDocument.describe().archetypes().stream()
        .filter(entry -> entry.archetype() == archetype)
        .findFirst()
        .orElseThrow(() -> new AssertionError(archetype + " is not in the served document"));
  }

  // ---- it is the registry, not a copy of it ----------------------------------------------------

  @Test
  void everyArchetypeIsDescribedAndInEnumOrder() {
    // The document is built by walking Archetype.values(), so a fifth kind appears here the day it
    // is declared and with no edit to the document. Asserting the order as well as the membership
    // is what makes that a contract rather than a coincidence of the map's iteration.
    assertEquals(
        List.of(Archetype.values()),
        ArchetypeRegistryDocument.describe().archetypes().stream()
            .map(ArchetypeRegistryDocument.DeclaredArchetype::archetype)
            .toList());
  }

  @Test
  void everyDeclarationIsReadOffTheRegistryRatherThanRestated() {
    // The point of the route: nothing here is written down a second time. Compared set-wise,
    // because the document's ordering is its own contract and is asserted below.
    for (Archetype archetype : Archetype.values()) {
      ArchetypeSpec spec = Archetypes.spec(archetype);
      ArchetypeRegistryDocument.DeclaredArchetype served = declared(archetype);
      assertEquals(spec.depth(), served.depth(), archetype + " depth");
      assertEquals(spec.mayBeRoot(), served.mayBeRoot(), archetype + " mayBeRoot");
      assertEquals(spec.gathers(), served.gathers(), archetype + " gathers");
      assertEquals(spec.required(), Set.copyOf(served.required()), archetype + " required");
      assertEquals(
          spec.requiredAtCreate(),
          Set.copyOf(served.requiredAtCreate()),
          archetype + " requiredAtCreate");
      assertEquals(spec.permitted(), Set.copyOf(served.permitted()), archetype + " permitted");
      assertEquals(
          spec.legalStatuses(), Set.copyOf(served.legalStatuses()), archetype + " legalStatuses");
    }
  }

  @Test
  void theCampaignIsServedAsAGatheringRootAboveTheEpic() {
    ArchetypeRegistryDocument.DeclaredArchetype campaign = declared(Archetype.CAMPAIGN);
    assertEquals(-1, campaign.depth());
    assertEquals(true, campaign.mayBeRoot());
    assertEquals(true, campaign.gathers());
    assertEquals(List.of(EntityProperty.TITLE), campaign.required());
    assertEquals(List.of(EntityProperty.TITLE), campaign.requiredAtCreate());
    // A lifecycle kind, so the transition asks for its status as it does an epic's.
    assertEquals(
        List.of(EntityProperty.TITLE, EntityProperty.STATUS), campaign.requiredOnTransition());
    assertEquals(
        List.of(
            EntityProperty.TITLE,
            EntityProperty.SLUG,
            EntityProperty.DESCRIPTION,
            EntityProperty.STATUS),
        campaign.permitted());
    assertEquals(
        List.of(
            "DONE", "DROPPED", "IMPLEMENTED", "READY_FOR_DEV", "REFINED", "REPORTED", "VERIFIED"),
        campaign.legalStatuses());
    // Its lifecycle elides IMPLEMENTING and VERIFYING (qits-749) and keeps READY_FOR_DEV (qits-887):
    // the walk with the elided states removed, so IMPLEMENTED -> READY_FOR_DEV and VERIFIED ->
    // IMPLEMENTED are its BACK moves.
    assertEquals(
        List.of(
            "REPORTED", "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED"),
        campaign.lifecycle());
    assertEquals(
        List.of(
            move("READY_FOR_DEV", EntityStateMachine.TransitionKind.FORWARD),
            move("REPORTED", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        campaign.transitions().get("REFINED"));
    assertEquals(
        List.of(
            move("IMPLEMENTED", EntityStateMachine.TransitionKind.FORWARD),
            move("REFINED", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        campaign.transitions().get("READY_FOR_DEV"));
    assertEquals(
        List.of(
            move("VERIFIED", EntityStateMachine.TransitionKind.FORWARD),
            move("READY_FOR_DEV", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        campaign.transitions().get("IMPLEMENTED"));
    assertEquals(false, campaign.transitions().containsKey("IMPLEMENTING"));
    assertEquals(false, campaign.transitions().containsKey("VERIFYING"));
    assertEquals(
        List.of(
            move("DONE", EntityStateMachine.TransitionKind.FORWARD),
            move("IMPLEMENTED", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        campaign.transitions().get("VERIFIED"));
    for (Archetype other : List.of(Archetype.EPIC, Archetype.TICKET, Archetype.FEATURE, Archetype.TASK)) {
      assertEquals(false, declared(other).gathers(), other + " gathers");
    }
  }

  @Test
  void thePropertyVocabularyIsServedWholeAndInDeclarationOrder() {
    // It is the language the rest of the document is written in, and a client derives a violation's
    // spelling from it rather than carrying its own table of field names.
    assertEquals(
        List.of(EntityProperty.values()), ArchetypeRegistryDocument.describe().properties());
  }

  @Test
  void theServedRequiredListIsWhatAnUPDATEIsJUDGEDAgainstAndTheCreateListIsWiderByTheImpetus() {
    // The settlement, as the document says it. A client drawing an edit reads `required` and a
    // client drawing an intake form reads `requiredAtCreate`, and each is exactly what the server
    // enforces at that moment — which is the property this document exists to have and did not:
    // with one list saying what intake demands, every update path advertised a demand it did not
    // make, and undid it with a named concession nobody reading this document could see.
    assertEquals(
        List.of(EntityProperty.TITLE, EntityProperty.STATUS, EntityProperty.TICKET_TYPE),
        declared(Archetype.TICKET).required());
    assertEquals(
        List.of(
            EntityProperty.TITLE,
            EntityProperty.STATUS,
            EntityProperty.TICKET_TYPE,
            EntityProperty.IMPETUS),
        declared(Archetype.TICKET).requiredAtCreate());
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.FEATURE, Archetype.TASK)) {
      assertEquals(
          declared(archetype).required(),
          declared(archetype).requiredAtCreate(),
          archetype + " demands the same properties at both moments");
    }
  }

  @Test
  void permittedIsASupersetOfRequiredInTheSERVEDDocument() {
    // Archetypes asserts this of the declarations at class-initialisation time. The document is a
    // different artifact built by a different method, and a client that renders a required field
    // the archetype does not permit would ask for a value the server refuses.
    for (Archetype archetype : Archetype.values()) {
      ArchetypeRegistryDocument.DeclaredArchetype served = declared(archetype);
      assertTrue(
          served.permitted().containsAll(served.requiredAtCreate()),
          archetype
              + " requires "
              + served.requiredAtCreate()
              + " and permits only "
              + served.permitted());
      assertTrue(
          served.requiredAtCreate().containsAll(served.required()),
          archetype + " requires after create what it does not require at create");
    }
  }

  // ---- the two server-owned properties ---------------------------------------------------------

  @Test
  void serverOwnedIsExactlyTheSlugAndTheReporter() {
    // The same constant EntityTransitionService.propertyViolations reads, which is the whole reason
    // it is a constant: a client renders no field for either, and the server clears neither.
    assertEquals(
        List.of(EntityProperty.SLUG, EntityProperty.CREATED_BY),
        ArchetypeRegistryDocument.describe().serverOwned());
    assertEquals(
        EntityTransitionService.SERVER_OWNED,
        Set.copyOf(ArchetypeRegistryDocument.describe().serverOwned()));
  }

  // ---- requiredOnTransition, which is the transition's rule ------------------------------------

  @Test
  void anEpicsTransitionAddsStatusToWhatTheRegistryRequires() {
    // The asymmetry decision 8 records, served: the registry permits an epic a status because the
    // writer mints the first one, and a transition mints nothing, so omitting it would clear one.
    assertEquals(List.of(EntityProperty.TITLE), declared(Archetype.EPIC).required());
    assertEquals(
        List.of(EntityProperty.TITLE, EntityProperty.STATUS),
        declared(Archetype.EPIC).requiredOnTransition());
  }

  @Test
  void aTicketAlreadyRequiredAStatusSoTheTwoListsAgree() {
    // The addition is idempotent: a ticket's status is required by the registry outright.
    assertEquals(
        declared(Archetype.TICKET).required(), declared(Archetype.TICKET).requiredOnTransition());
  }

  @Test
  void aFeatureAndATaskAskForAStatusOnTransitionAsAnEpicDoes() {
    // qits-763: they hold the one lifecycle now, minted by the writer and permitted rather than
    // required — so the transition, which mints nothing, must be handed one, exactly as for an epic.
    for (Archetype archetype : List.of(Archetype.FEATURE, Archetype.TASK)) {
      ArchetypeRegistryDocument.DeclaredArchetype served = declared(archetype);
      assertEquals(
          declared(Archetype.EPIC).legalStatuses(), served.legalStatuses(), archetype.name());
      assertFalse(served.required().contains(EntityProperty.STATUS), archetype.name());
      assertTrue(served.requiredOnTransition().contains(EntityProperty.STATUS), archetype.name());
    }
  }

  @Test
  void theAdditionFollowsTheTransitionsOwnPredicateAndNotASecondCondition() {
    // requiredOnTransition and the check EntityTransitionService makes after the press are one
    // rule, asked in one place. This is what fails if either end grows a spelling of its own.
    for (Archetype archetype : Archetype.values()) {
      assertEquals(
          EntityTransitionService.requiresStatusOnTransition(Archetypes.spec(archetype)),
          declared(archetype).requiredOnTransition().contains(EntityProperty.STATUS),
          archetype + ": the served list and the enforced check must agree about STATUS");
    }
  }

  // ---- the orderings ---------------------------------------------------------------------------

  @Test
  void everyPropertyListIsInVocabularyOrder() {
    // The same order Archetypes.validate reports violations in, so a form's fields and a refusal's
    // complaints read in one sequence — and, more plainly, so that two runs answer one document.
    for (Archetype archetype : Archetype.values()) {
      ArchetypeRegistryDocument.DeclaredArchetype served = declared(archetype);
      assertInVocabularyOrder(archetype + " required", served.required());
      assertInVocabularyOrder(archetype + " requiredAtCreate", served.requiredAtCreate());
      assertInVocabularyOrder(archetype + " requiredOnTransition", served.requiredOnTransition());
      assertInVocabularyOrder(archetype + " permitted", served.permitted());
    }
  }

  @Test
  void theStatusWordsAreAlphabeticalAndThatOrderMeansNothing() {
    // ArchetypeSpec holds a Set<String>, so the source enum's order is not recoverable; sorting is
    // what makes the answer deterministic and is deliberately NOT a lifecycle.
    for (Archetype archetype : Archetype.values()) {
      List<String> statuses = declared(archetype).legalStatuses();
      assertEquals(statuses.stream().sorted().toList(), statuses, archetype.name());
    }
    assertEquals(List.of(
            "DONE",
            "DROPPED",
            "IMPLEMENTED",
            "IMPLEMENTING",
            "READY_FOR_DEV",
            "REFINED",
            "REPORTED",
            "VERIFIED",
            "VERIFYING"),
        declared(Archetype.EPIC).legalStatuses());
    // One vocabulary since qits-392: the epic and the ticket serve the same six words.
    assertEquals(
        declared(Archetype.EPIC).legalStatuses(), declared(Archetype.TICKET).legalStatuses());
  }

  // ---- the legal moves, served off the state machine -------------------------------------------

  @Test
  void theServedMovesAreTheMachinesDeclarationExactly() {
    // Rebuilt from EntityStateMachine.transitions() — the declaration itself, not transitionsFrom —
    // so a served move the machine does not declare, or a declared move the document drops, fails.
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.TICKET)) {
      ArchetypeRegistryDocument.DeclaredArchetype served = declared(archetype);
      Map<String, List<ArchetypeRegistryDocument.LegalMove>> expected = new LinkedHashMap<>();
      for (EntityStatus state : EntityStateMachine.states()) {
        expected.put(state.name(), new ArrayList<>());
      }
      for (EntityStateMachine.Transition move : EntityStateMachine.transitions()) {
        expected
            .get(move.from().name())
            .add(
                new ArchetypeRegistryDocument.LegalMove(
                    move.to().name(), move.kind(), List.of()));
      }
      assertEquals(expected, served.transitions(), archetype.name());
      assertEquals(
          List.copyOf(expected.keySet()),
          List.copyOf(served.transitions().keySet()),
          archetype + " keys in lifecycle order");
      assertEquals(
          EntityStateMachine.states().stream().map(EntityStatus::name).toList(),
          served.lifecycle(),
          archetype.name());
      assertEquals(
          Set.copyOf(served.legalStatuses()),
          Set.copyOf(served.lifecycle()),
          archetype + ": the ordered lifecycle and the alphabetical list name the same words");
    }
  }

  @Test
  void theTicketsServedMovesReadAsSpecified() {
    Map<String, List<ArchetypeRegistryDocument.LegalMove>> moves =
        declared(Archetype.TICKET).transitions();
    assertEquals(
        List.of(
            "REPORTED",
            "REFINED",
            "READY_FOR_DEV",
            "IMPLEMENTING",
            "IMPLEMENTED",
            "VERIFYING",
            "VERIFIED",
            "DONE",
            "DROPPED"),
        List.copyOf(moves.keySet()));
    assertEquals(
        List.of(move("REFINED", EntityStateMachine.TransitionKind.FORWARD), move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        moves.get("REPORTED"));
    assertEquals(
        List.of(
            move("READY_FOR_DEV", EntityStateMachine.TransitionKind.FORWARD),
            move("REPORTED", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        moves.get("REFINED"));
    assertEquals(
        List.of(
            move("IMPLEMENTING", EntityStateMachine.TransitionKind.FORWARD),
            move("IMPLEMENTED", EntityStateMachine.TransitionKind.SKIP),
            move("REFINED", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        moves.get("READY_FOR_DEV"));
    assertEquals(
        List.of(
            move("IMPLEMENTED", EntityStateMachine.TransitionKind.FORWARD),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        moves.get("IMPLEMENTING"));
    assertEquals(
        List.of(
            move("VERIFYING", EntityStateMachine.TransitionKind.FORWARD),
            move("VERIFIED", EntityStateMachine.TransitionKind.SKIP),
            move("IMPLEMENTING", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        moves.get("IMPLEMENTED"));
    assertEquals(
        List.of(
            move("VERIFIED", EntityStateMachine.TransitionKind.FORWARD),
            move("IMPLEMENTED", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        moves.get("VERIFYING"));
    assertEquals(
        List.of(
            move("DONE", EntityStateMachine.TransitionKind.FORWARD),
            move("VERIFYING", EntityStateMachine.TransitionKind.BACK),
            move("DROPPED", EntityStateMachine.TransitionKind.DROP)),
        moves.get("VERIFIED"));
    assertEquals(List.of(), moves.get("DONE"), "DONE is final");
    assertEquals(List.of(move("REPORTED", EntityStateMachine.TransitionKind.REOPEN)), moves.get("DROPPED"));
  }

  @Test
  void aFeatureAndATaskServeTheEpicsMovesAndLifecycle() {
    // One graph, both skips included (qits-763): what a client draws for a task is an epic's walk.
    for (Archetype archetype : List.of(Archetype.FEATURE, Archetype.TASK)) {
      assertEquals(
          declared(Archetype.EPIC).transitions(), declared(archetype).transitions(), archetype.name());
      assertEquals(
          declared(Archetype.EPIC).lifecycle(), declared(archetype).lifecycle(), archetype.name());
    }
  }

  private static ArchetypeRegistryDocument.LegalMove move(
      String to, EntityStateMachine.TransitionKind kind) {
    return new ArchetypeRegistryDocument.LegalMove(to, kind, List.of());
  }

  // ---- quality gates (qits-887) ------------------------------------------------------------------

  /** The gates a served move names, read off a document built over {@code gates}. */
  private static List<String> gatesOf(
      List<? extends TransitionGate> gates, Archetype archetype, String from, String to) {
    return ArchetypeRegistryDocument.describe(gates).archetypes().stream()
        .filter(declared -> declared.archetype() == archetype)
        .findFirst()
        .orElseThrow()
        .transitions()
        .get(from)
        .stream()
        .filter(move -> move.to().equals(to))
        .findFirst()
        .orElseThrow()
        .gates();
  }

  @Test
  void aServedMoveNamesTheGatesItHasForwardAndSkipOnly() {
    List<TransitionGate> gates = List.of(new AcceptanceCriteriaGate());
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.TICKET)) {
      assertEquals(
          List.of("ACCEPTANCE_CRITERIA"), gatesOf(gates, archetype, "REPORTED", "REFINED"));
      assertEquals(
          List.of("ACCEPTANCE_CRITERIA"), gatesOf(gates, archetype, "REFINED", "READY_FOR_DEV"));
      // A BACK into REFINED is a correction: no gate, though the gate applies to the pair.
      assertEquals(List.of(), gatesOf(gates, archetype, "READY_FOR_DEV", "REFINED"));
      assertEquals(List.of(), gatesOf(gates, archetype, "READY_FOR_DEV", "IMPLEMENTING"));
      assertEquals(List.of(), gatesOf(gates, archetype, "REFINED", "DROPPED"));
    }
    // A feature and a task hold no criteria, so the criteria gate names none of their moves.
    assertEquals(List.of(), gatesOf(gates, Archetype.FEATURE, "REPORTED", "REFINED"));
    assertEquals(List.of(), gatesOf(gates, Archetype.TASK, "REFINED", "READY_FOR_DEV"));
    // With the person gate beside it (qits-937): scheduling needs both, a piece's needs a person.
    List<TransitionGate> both = List.of(new PersonApprovalGate(), new AcceptanceCriteriaGate());
    assertEquals(
        List.of("ACCEPTANCE_CRITERIA", "PERSON_APPROVAL"),
        gatesOf(both, Archetype.EPIC, "REFINED", "READY_FOR_DEV"));
    assertEquals(
        List.of("ACCEPTANCE_CRITERIA", "PERSON_APPROVAL"),
        gatesOf(both, Archetype.TICKET, "REFINED", "READY_FOR_DEV"));
    assertEquals(List.of("PERSON_APPROVAL"), gatesOf(both, Archetype.TASK, "REFINED", "READY_FOR_DEV"));
    assertEquals(
        List.of("PERSON_APPROVAL"), gatesOf(both, Archetype.CAMPAIGN, "REFINED", "READY_FOR_DEV"));
    assertEquals(List.of(), gatesOf(both, Archetype.EPIC, "READY_FOR_DEV", "REFINED"));
    // And a campaign's own (qits-942): its members are scheduled, beside the person.
    List<TransitionGate> all =
        List.of(
            new PersonApprovalGate(),
            new AcceptanceCriteriaGate(),
            new eu.wohlben.qits.entities.campaign.MembersScheduledGate());
    assertEquals(
        List.of("MEMBERS_SCHEDULED", "PERSON_APPROVAL"),
        gatesOf(all, Archetype.CAMPAIGN, "REFINED", "READY_FOR_DEV"));
    assertEquals(
        List.of("ACCEPTANCE_CRITERIA", "PERSON_APPROVAL"),
        gatesOf(all, Archetype.EPIC, "REFINED", "READY_FOR_DEV"));
    assertEquals(List.of(), gatesOf(all, Archetype.CAMPAIGN, "READY_FOR_DEV", "REFINED"));
    // With no gate handed in, nothing is named.
    assertEquals(List.of(), gatesOf(List.of(), Archetype.EPIC, "REFINED", "READY_FOR_DEV"));
  }

  @Test
  void twoReadsAnswerTheSameDocument() {
    assertEquals(ArchetypeRegistryDocument.describe(), ArchetypeRegistryDocument.describe());
  }

  // ---- dispatch phases --------------------------------------------------------------------------

  /**
   * {@code phases} is the state machine's dispatch logic, served: per status, the phase a PHASE
   * press runs ({@link EntityStateMachine#phaseRunFrom}) and the phases a FLOW press chains ({@link
   * EntityStateMachine#flowFrom}), keyed by every lifecycle status in order.
   */
  @Test
  void phasesAreTheStateMachinesDispatchLogic() {
    for (Archetype archetype : List.of(Archetype.TICKET, Archetype.EPIC)) {
      Map<String, ArchetypeRegistryDocument.DispatchPhases> phases = declared(archetype).phases();
      assertEquals(declared(archetype).lifecycle(), List.copyOf(phases.keySet()), archetype + "");
      for (EntityStatus status : EntityStateMachine.states(archetype)) {
        ArchetypeRegistryDocument.DispatchPhases served = phases.get(status.name());
        assertEquals(
            EntityStateMachine.phaseRunFrom(archetype, status)
                .map(ArchetypeRegistryDocument.DispatchPhase::of)
                .orElse(null),
            served.next(),
            archetype + " " + status);
        assertEquals(
            EntityStateMachine.flowFrom(archetype, status).stream()
                .map(ArchetypeRegistryDocument.DispatchPhase::of)
                .toList(),
            served.flow(),
            archetype + " " + status);
        // A PHASE press runs the first phase of the FLOW press from the same status.
        assertEquals(
            served.flow().isEmpty() ? null : served.flow().get(0), served.next(), status + "");
      }
    }
  }

  /** The ticket's served phases, spelled out once, so a change to the logic shows here. */
  @Test
  void theTicketsServedPhasesReadAsSpecified() {
    Map<String, ArchetypeRegistryDocument.DispatchPhases> phases =
        declared(Archetype.TICKET).phases();
    var refine = new ArchetypeRegistryDocument.DispatchPhase("refine", "REPORTED", null, "REFINED");
    var implement =
        new ArchetypeRegistryDocument.DispatchPhase(
            "implement", "READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED");
    var resume =
        new ArchetypeRegistryDocument.DispatchPhase(
            "implement", "IMPLEMENTING", null, "IMPLEMENTED");
    var verify =
        new ArchetypeRegistryDocument.DispatchPhase(
            "verify", "IMPLEMENTED", "VERIFYING", "VERIFIED");
    var reverify =
        new ArchetypeRegistryDocument.DispatchPhase("verify", "VERIFYING", null, "VERIFIED");

    assertEquals(refine, phases.get("REPORTED").next());
    // qits-887: a FLOW from REPORTED stops at REFINED, which waits for a person to schedule it.
    assertEquals(List.of(refine), phases.get("REPORTED").flow());
    assertEquals(null, phases.get("REFINED").next());
    assertEquals(List.of(), phases.get("REFINED").flow());
    assertEquals(implement, phases.get("READY_FOR_DEV").next());
    assertEquals(List.of(implement, verify), phases.get("READY_FOR_DEV").flow());
    assertEquals(List.of(resume, verify), phases.get("IMPLEMENTING").flow());
    assertEquals(List.of(verify), phases.get("IMPLEMENTED").flow());
    assertEquals(List.of(reverify), phases.get("VERIFYING").flow());
    for (String none : List.of("VERIFIED", "DONE", "DROPPED")) {
      assertEquals(null, phases.get(none).next(), none);
      assertEquals(List.of(), phases.get(none).flow(), none);
    }
  }

  /**
   * A campaign's press is its start, and a feature or a task walks the lifecycle with no phase of
   * its own: no phases.
   */
  @Test
  void aKindADispatchRunsNoPhasesOnServesNone() {
    for (Archetype archetype : List.of(Archetype.CAMPAIGN, Archetype.FEATURE, Archetype.TASK)) {
      assertEquals(Map.of(), declared(archetype).phases(), archetype + "");
      assertFalse(EntityStateMachine.runsPhases(archetype), archetype + "");
    }
  }

  private static void assertInVocabularyOrder(String what, List<EntityProperty> properties) {
    List<EntityProperty> expected = new ArrayList<>(properties);
    expected.sort((left, right) -> Integer.compare(left.ordinal(), right.ordinal()));
    assertEquals(expected, properties, what + " must be in EntityProperty declaration order");
  }
}
