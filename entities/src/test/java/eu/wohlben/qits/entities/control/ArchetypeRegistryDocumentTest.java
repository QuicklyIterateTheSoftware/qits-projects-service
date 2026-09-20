package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import java.util.ArrayList;
import java.util.List;
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
  void aKindWithNoLifecycleAsksForNoStatusEitherWay() {
    for (Archetype archetype : List.of(Archetype.FEATURE, Archetype.TASK)) {
      ArchetypeRegistryDocument.DeclaredArchetype served = declared(archetype);
      assertTrue(served.legalStatuses().isEmpty(), archetype + " has no lifecycle");
      assertEquals(served.required(), served.requiredOnTransition(), archetype.name());
      assertFalse(served.requiredOnTransition().contains(EntityProperty.STATUS), archetype.name());
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
    assertEquals(
        List.of("ABANDONED", "IMPLEMENTATION", "IMPLEMENTED", "REFINING", "SUPERSEDED"),
        declared(Archetype.EPIC).legalStatuses());
  }

  @Test
  void twoReadsAnswerTheSameDocument() {
    assertEquals(ArchetypeRegistryDocument.describe(), ArchetypeRegistryDocument.describe());
  }

  private static void assertInVocabularyOrder(String what, List<EntityProperty> properties) {
    List<EntityProperty> expected = new ArrayList<>(properties);
    expected.sort((left, right) -> Integer.compare(left.ordinal(), right.ordinal()));
    assertEquals(expected, properties, what + " must be in EntityProperty declaration order");
  }
}
