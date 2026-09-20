package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.TicketStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The archetype registry's own rules, which are pure functions over declarations.
 *
 * <p><b>Plain JUnit and no Quarkus application.</b> Nothing here touches a database or a bean, and a
 * {@code @QuarkusTest} would boot a whole application to ask a question about a static map — which
 * this repository's test-profile budget rule (a profile is ~125 MB of retained metaspace inside a
 * 4 GB CI step) says not to spend. The persistence half of the same feature is where a
 * {@code @QuarkusTest} earns its cost.
 */
class ArchetypesTest {

  // ---- the declarations themselves -------------------------------------------------------------

  @Test
  void requiredIsASubsetOfPermittedForEveryArchetype() {
    // The invariant Archetypes asserts at class-initialisation time. Asserted here too, because the
    // class-init check fires only if something loads the class and a rule nobody exercises is a
    // rule nobody notices breaking.
    for (Archetype archetype : Archetype.values()) {
      ArchetypeSpec spec = Archetypes.spec(archetype);
      assertTrue(
          spec.permitted().containsAll(spec.required()),
          archetype + " requires properties it does not permit: " + spec.required());
    }
  }

  @Test
  void theTwoRootsShareADepthAndTheNestedKindsGoDeeper() {
    // Epic and ticket are siblings, which is what makes ticket-under-epic refusable by the ordinary
    // rule rather than by a special case naming the two words.
    assertEquals(Archetypes.depth(Archetype.EPIC), Archetypes.depth(Archetype.TICKET));
    assertTrue(Archetypes.depth(Archetype.EPIC) < Archetypes.depth(Archetype.FEATURE));
    assertTrue(Archetypes.depth(Archetype.FEATURE) < Archetypes.depth(Archetype.TASK));
  }

  @Test
  void rootnessIsDeclaredAndTodayItIsTheTwoRoots() {
    assertTrue(Archetypes.mayBeRoot(Archetype.EPIC));
    assertTrue(Archetypes.mayBeRoot(Archetype.TICKET));
    assertFalse(Archetypes.mayBeRoot(Archetype.FEATURE));
    assertFalse(Archetypes.mayBeRoot(Archetype.TASK));
  }

  @Test
  void statusVocabulariesAreTheTwoLifecyclesAndNothingElseHasOne() {
    assertEquals(
        EnumSet.allOf(EpicStatus.class).stream().map(Enum::name).collect(Collectors.toSet()),
        Archetypes.legalStatuses(Archetype.EPIC));
    assertEquals(
        EnumSet.allOf(TicketStatus.class).stream().map(Enum::name).collect(Collectors.toSet()),
        Archetypes.legalStatuses(Archetype.TICKET));
    assertTrue(Archetypes.legalStatuses(Archetype.FEATURE).isEmpty());
    assertTrue(Archetypes.legalStatuses(Archetype.TASK).isEmpty());
  }

  // ---- what a well-formed candidate looks like -------------------------------------------------

  @Test
  void theFourWellFormedCandidatesPass() {
    assertEquals(List.of(), Archetypes.validate(epic(EpicStatus.REFINING.name())));
    assertEquals(List.of(), Archetypes.validate(ticket(TicketStatus.REPORTED.name())));
    assertEquals(
        List.of(),
        Archetypes.validate(EntityState.of(Archetype.FEATURE, properties(EntityProperty.TITLE))));
    assertEquals(
        List.of(),
        Archetypes.validate(
            EntityState.of(
                Archetype.TASK,
                properties(EntityProperty.TITLE, EntityProperty.REPOSITORY_ID))));
  }

  // ---- the refusals ----------------------------------------------------------------------------

  @Test
  void aTicketWithoutAnImpetusIsRefusedAndTheRefusalNamesImpetus() {
    // The impetus is the intake field — a REPORTED ticket consists of it and nothing else — so its
    // absence is the one refusal an intake surface has to be able to point at a box for.
    EntityState candidate =
        new EntityState(
            Archetype.TICKET,
            TicketStatus.REPORTED.name(),
            properties(EntityProperty.TITLE, EntityProperty.TICKET_TYPE));

    List<ArchetypeViolation> violations = Archetypes.validate(candidate);

    assertEquals(1, violations.size(), () -> violations.toString());
    ArchetypeViolation only = violations.get(0);
    assertEquals(EntityProperty.IMPETUS, only.property());
    assertEquals(ArchetypeViolation.Reason.MISSING_REQUIRED, only.reason());
    assertTrue(only.message().contains("impetus"), only.message());
  }

  @Test
  void anEpicCarryingARepositoryIdIsRefusedRatherThanHavingItDropped() {
    // The whole point of the gate. A value arriving on a kind that has no slot for it means the
    // caller and the model disagree about what is being written; dropping it silently would lose
    // the value and the disagreement together, and the row would look correct afterwards.
    EntityState candidate =
        new EntityState(
            Archetype.EPIC,
            EpicStatus.REFINING.name(),
            properties(EntityProperty.TITLE, EntityProperty.REPOSITORY_ID));

    List<ArchetypeViolation> violations = Archetypes.validate(candidate);

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(EntityProperty.REPOSITORY_ID, violations.get(0).property());
    assertEquals(ArchetypeViolation.Reason.NOT_PERMITTED, violations.get(0).reason());
  }

  @Test
  void anEpicCarryingATicketStatusIsRefused() {
    // The check the database cannot make: ck_entity_status is the union of both enums, because a
    // check constraint has no way to say "these five when the archetype is EPIC".
    EntityState candidate = epic(TicketStatus.REPORTED.name());

    List<ArchetypeViolation> violations = Archetypes.validate(candidate);

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(EntityProperty.STATUS, violations.get(0).property());
    assertEquals(ArchetypeViolation.Reason.ILLEGAL_STATUS, violations.get(0).reason());
    assertEquals(TicketStatus.REPORTED.name(), violations.get(0).detail());
  }

  @Test
  void aTicketCarryingAnEpicStatusIsRefusedTheSameWay() {
    List<ArchetypeViolation> violations =
        Archetypes.validate(
            new EntityState(
                Archetype.TICKET,
                EpicStatus.REFINING.name(),
                properties(
                    EntityProperty.TITLE, EntityProperty.TICKET_TYPE, EntityProperty.IMPETUS)));

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(ArchetypeViolation.Reason.ILLEGAL_STATUS, violations.get(0).reason());
  }

  @Test
  void aFeatureCarryingAStatusAtAllIsRefusedAsAForeignProperty() {
    // A feature has no lifecycle of its own — its phase is its epic's — so a status on one is not
    // an illegal word but a property the kind has no slot for.
    List<ArchetypeViolation> violations =
        Archetypes.validate(
            new EntityState(
                Archetype.FEATURE, EpicStatus.REFINING.name(), properties(EntityProperty.TITLE)));

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(EntityProperty.STATUS, violations.get(0).property());
    assertEquals(ArchetypeViolation.Reason.NOT_PERMITTED, violations.get(0).reason());
  }

  @Test
  void everyViolationComesBackTogetherRatherThanOneRoundTripEachToFix() {
    // The failure mode the API exists to avoid: a caller told about the second missing field only
    // after it has supplied the first. Five problems at once, on one candidate: two required
    // properties missing and three foreign ones carried.
    EntityState candidate =
        new EntityState(
            Archetype.TASK,
            TicketStatus.DONE.name(),
            properties(EntityProperty.IMPETUS, EntityProperty.ASSIGNEE));

    List<ArchetypeViolation> violations = Archetypes.validate(candidate);

    assertEquals(5, violations.size(), () -> violations.toString());
    assertEquals(
        Set.of(
            EntityProperty.TITLE,
            EntityProperty.REPOSITORY_ID,
            EntityProperty.STATUS,
            EntityProperty.IMPETUS,
            EntityProperty.ASSIGNEE),
        violations.stream().map(ArchetypeViolation::property).collect(Collectors.toSet()),
        () -> violations.toString());
  }

  // ---- fixtures --------------------------------------------------------------------------------

  private static EntityState epic(String status) {
    return new EntityState(Archetype.EPIC, status, properties(EntityProperty.TITLE));
  }

  private static EntityState ticket(String status) {
    return new EntityState(
        Archetype.TICKET,
        status,
        properties(EntityProperty.TITLE, EntityProperty.TICKET_TYPE, EntityProperty.IMPETUS));
  }

  private static Set<EntityProperty> properties(EntityProperty... properties) {
    return properties.length == 0 ? Set.of() : Set.of(properties);
  }
}
