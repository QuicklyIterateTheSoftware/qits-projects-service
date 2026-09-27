package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
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
  void requiredIsASubsetOfRequiredAtCreateIsASubsetOfPermittedForEveryArchetype() {
    // The invariant Archetypes asserts at class-initialisation time. Asserted here too, because the
    // class-init check fires only if something loads the class and a rule nobody exercises is a
    // rule nobody notices breaking.
    //
    // The middle term is the create axis: a property owed at every moment but not at birth would be
    // a rule no writer could satisfy, since every row is created before it is updated.
    for (Archetype archetype : Archetype.values()) {
      ArchetypeSpec spec = Archetypes.spec(archetype);
      assertTrue(
          spec.requiredAtCreate().containsAll(spec.required()),
          archetype + " requires after create what it does not require at create: " + spec.required());
      assertTrue(
          spec.permitted().containsAll(spec.requiredAtCreate()),
          archetype + " requires properties it does not permit: " + spec.requiredAtCreate());
    }
  }

  @Test
  void theTicketIsTheONLYKindWhoseTwoRequiredSetsDifferAndTheImpetusIsTheWholeDifference() {
    // The declaration this settlement is. Intake demands an impetus — a REPORTED ticket consists of
    // one — and entity.impetus is nullable because clearing one afterwards is a thing a person does
    // (TicketServiceTest/TicketApiTest.theClearFlagsAreWhatEmptyTheNullableFields). Two axes could
    // say only one of those; the third says both.
    for (Archetype archetype : Archetype.values()) {
      ArchetypeSpec spec = Archetypes.spec(archetype);
      Set<EntityProperty> onlyAtCreate = EnumSet.copyOf(spec.requiredAtCreate());
      onlyAtCreate.removeAll(spec.required());
      assertEquals(
          archetype == Archetype.TICKET ? Set.of(EntityProperty.IMPETUS) : Set.of(),
          onlyAtCreate,
          archetype + " requires these at create and not afterwards");
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
  void rootnessIsDeclaredAndTodayItIsTheTwoRootsAndTheCampaign() {
    assertTrue(Archetypes.mayBeRoot(Archetype.EPIC));
    assertTrue(Archetypes.mayBeRoot(Archetype.TICKET));
    assertTrue(Archetypes.mayBeRoot(Archetype.CAMPAIGN));
    assertFalse(Archetypes.mayBeRoot(Archetype.FEATURE));
    assertFalse(Archetypes.mayBeRoot(Archetype.TASK));
  }

  @Test
  void epicTicketAndCampaignDeclareTheOneLifecycleAndNothingElseHasOne() {
    var six = EnumSet.allOf(EntityStatus.class).stream().map(Enum::name).collect(Collectors.toSet());
    assertEquals(
        Set.of("REPORTED", "REFINED", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED"), six);
    assertEquals(six, Archetypes.legalStatuses(Archetype.EPIC));
    assertEquals(six, Archetypes.legalStatuses(Archetype.TICKET));
    assertEquals(six, Archetypes.legalStatuses(Archetype.CAMPAIGN));
    assertTrue(Archetypes.legalStatuses(Archetype.FEATURE).isEmpty());
    assertTrue(Archetypes.legalStatuses(Archetype.TASK).isEmpty());
  }

  // ---- the campaign (qits-411) -----------------------------------------------------------------

  @Test
  void theCampaignIsDeclaredAboveTheEpicAtMinusOneAndNoOtherDepthMoved() {
    ArchetypeSpec spec = Archetypes.spec(Archetype.CAMPAIGN);
    assertEquals(Archetype.CAMPAIGN, spec.archetype());
    assertEquals(-1, Archetypes.depth(Archetype.CAMPAIGN));
    // The existing three numbers are the decision ("Depth" in Archetypes): they do not move.
    assertEquals(0, Archetypes.depth(Archetype.EPIC));
    assertEquals(0, Archetypes.depth(Archetype.TICKET));
    assertEquals(1, Archetypes.depth(Archetype.FEATURE));
    assertEquals(2, Archetypes.depth(Archetype.TASK));
  }

  @Test
  void theCampaignRequiresATitleAndPermitsTheEpicsWordsWithoutSupersede() {
    ArchetypeSpec spec = Archetypes.spec(Archetype.CAMPAIGN);
    assertEquals(Set.of(EntityProperty.TITLE), spec.required());
    // The status is minted by the writer at REPORTED, as an epic's is — not required of a candidate.
    assertEquals(Set.of(EntityProperty.TITLE), spec.requiredAtCreate());
    assertEquals(
        Set.of(
            EntityProperty.TITLE,
            EntityProperty.SLUG,
            EntityProperty.DESCRIPTION,
            EntityProperty.STATUS),
        spec.permitted());
  }

  @Test
  void onlyTheCampaignGathers() {
    for (Archetype archetype : Archetype.values()) {
      assertEquals(
          archetype == Archetype.CAMPAIGN,
          Archetypes.gathers(archetype),
          archetype + " gathers");
      assertEquals(Archetypes.gathers(archetype), Archetypes.spec(archetype).gathers());
    }
  }

  @Test
  void aCampaignCarryingATicketsIntakeIsRefusedRatherThanHavingItDropped() {
    List<ArchetypeViolation> violations =
        Archetypes.validate(
            new EntityState(
                Archetype.CAMPAIGN,
                EntityStatus.REPORTED.name(),
                properties(EntityProperty.TITLE, EntityProperty.IMPETUS)),
            Demand.AT_CREATE);

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(EntityProperty.IMPETUS, violations.get(0).property());
    assertEquals(ArchetypeViolation.Reason.NOT_PERMITTED, violations.get(0).reason());
  }

  // ---- what a well-formed candidate looks like -------------------------------------------------

  @Test
  void theFiveWellFormedCandidatesPassAtEitherMoment() {
    for (Demand demand : Demand.values()) {
      assertEquals(List.of(), Archetypes.validate(epic(EntityStatus.REPORTED.name()), demand), demand.name());
      assertEquals(
          List.of(), Archetypes.validate(ticket(EntityStatus.REPORTED.name()), demand), demand.name());
      assertEquals(
          List.of(),
          Archetypes.validate(
              new EntityState(
                  Archetype.CAMPAIGN, EntityStatus.REPORTED.name(), properties(EntityProperty.TITLE)),
              demand),
          demand.name());
      // Status-less, as a candidate is before the writer mints REPORTED.
      assertEquals(
          List.of(),
          Archetypes.validate(
              EntityState.of(Archetype.CAMPAIGN, properties(EntityProperty.TITLE)), demand),
          demand.name());
      assertEquals(
          List.of(),
          Archetypes.validate(
              EntityState.of(Archetype.FEATURE, properties(EntityProperty.TITLE)), demand),
          demand.name());
      assertEquals(
          List.of(),
          Archetypes.validate(
              EntityState.of(
                  Archetype.TASK, properties(EntityProperty.TITLE, EntityProperty.REPOSITORY_ID)),
              demand),
          demand.name());
    }
  }

  // ---- the refusals ----------------------------------------------------------------------------

  @Test
  void aTicketFiledWithoutAnImpetusIsRefusedAndTheRefusalNamesImpetus() {
    // The impetus is the intake field — a REPORTED ticket consists of it and nothing else — so its
    // absence is the one refusal an intake surface has to be able to point at a box for. AT_CREATE
    // is where that demand lives and is the only place it lives.
    EntityState candidate =
        new EntityState(
            Archetype.TICKET,
            EntityStatus.REPORTED.name(),
            properties(EntityProperty.TITLE, EntityProperty.TICKET_TYPE));

    List<ArchetypeViolation> violations = Archetypes.validate(candidate, Demand.AT_CREATE);

    assertEquals(1, violations.size(), () -> violations.toString());
    ArchetypeViolation only = violations.get(0);
    assertEquals(EntityProperty.IMPETUS, only.property());
    assertEquals(ArchetypeViolation.Reason.MISSING_REQUIRED, only.reason());
    assertTrue(only.message().contains("impetus"), only.message());
  }

  @Test
  void anExistingTicketWithNoImpetusIsNotRefusedBecauseTheColumnAndThePersonBothAllowIt() {
    // The other half, and the reason the create axis exists. entity.impetus is nullable: rows that
    // predate V7 have none and a person may clear one, which TicketServiceTest and TicketApiTest
    // both assert of the live surface. An update judged by the intake set would refuse a write the
    // product performs, which is what the registry used to say and what a named concession on every
    // update path used to undo.
    EntityState candidate =
        new EntityState(
            Archetype.TICKET,
            EntityStatus.REPORTED.name(),
            properties(EntityProperty.TITLE, EntityProperty.TICKET_TYPE));

    assertEquals(List.of(), Archetypes.validate(candidate, Demand.ON_UPDATE));
  }

  @Test
  void anEpicCarryingARepositoryIdIsRefusedRatherThanHavingItDropped() {
    // The whole point of the gate. A value arriving on a kind that has no slot for it means the
    // caller and the model disagree about what is being written; dropping it silently would lose
    // the value and the disagreement together, and the row would look correct afterwards.
    EntityState candidate =
        new EntityState(
            Archetype.EPIC,
            EntityStatus.REPORTED.name(),
            properties(EntityProperty.TITLE, EntityProperty.REPOSITORY_ID));

    List<ArchetypeViolation> violations = Archetypes.validate(candidate, Demand.ON_UPDATE);

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(EntityProperty.REPOSITORY_ID, violations.get(0).property());
    assertEquals(ArchetypeViolation.Reason.NOT_PERMITTED, violations.get(0).reason());
  }

  @Test
  void anEpicMayCarryEveryWordOfTheOneLifecycle() {
    // Until qits-392 an epic carrying REPORTED was refused as a ticket word; there is one
    // vocabulary now, and VERIFIED and DONE are exactly what an epic could not reach before.
    for (EntityStatus status : EntityStatus.values()) {
      assertEquals(
          List.of(),
          Archetypes.validate(epic(status.name()), Demand.ON_UPDATE),
          status.name());
    }
  }

  @Test
  void aRetiredEpicWordIsRefusedOnEveryKindWithALifecycle() {
    for (String retired : List.of("REFINING", "IMPLEMENTATION", "ABANDONED", "SUPERSEDED")) {
      List<ArchetypeViolation> onEpic = Archetypes.validate(epic(retired), Demand.ON_UPDATE);
      assertEquals(1, onEpic.size(), () -> onEpic.toString());
      assertEquals(EntityProperty.STATUS, onEpic.get(0).property());
      assertEquals(ArchetypeViolation.Reason.ILLEGAL_STATUS, onEpic.get(0).reason());
      assertEquals(retired, onEpic.get(0).detail());

      List<ArchetypeViolation> onTicket =
          Archetypes.validate(
              new EntityState(
                  Archetype.TICKET,
                  retired,
                  properties(
                      EntityProperty.TITLE, EntityProperty.TICKET_TYPE, EntityProperty.IMPETUS)),
              Demand.AT_CREATE);
      assertEquals(1, onTicket.size(), () -> onTicket.toString());
      assertEquals(ArchetypeViolation.Reason.ILLEGAL_STATUS, onTicket.get(0).reason());
    }
  }

  @Test
  void aFeatureCarryingAStatusAtAllIsRefusedAsAForeignProperty() {
    // A feature has no lifecycle of its own — its phase is its epic's — so a status on one is not
    // an illegal word but a property the kind has no slot for.
    List<ArchetypeViolation> violations =
        Archetypes.validate(
            new EntityState(
                Archetype.FEATURE, EntityStatus.REPORTED.name(), properties(EntityProperty.TITLE)),
            Demand.AT_CREATE);

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
            EntityStatus.DONE.name(),
            properties(EntityProperty.IMPETUS, EntityProperty.ASSIGNEE));

    List<ArchetypeViolation> violations = Archetypes.validate(candidate, Demand.AT_CREATE);

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
