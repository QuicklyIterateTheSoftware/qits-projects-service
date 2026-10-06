package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ACCEPTANCE_CRITERIA} (qits-887): an epic or a ticket enters neither REFINED nor
 * READY_FOR_DEV without criteria; a feature and a task are never asked; unscheduling is never
 * refused.
 */
@QuarkusTest
class AcceptanceCriteriaGateTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;

  private final AcceptanceCriteriaGate gate = new AcceptanceCriteriaGate();

  private String create(Archetype archetype, List<String> criteria) {
    EntityWrite write =
        archetype == Archetype.EPIC
            ? EntityWrite.epic("Planned", null)
            : EntityWrite.ticket("Filed", "it occurs", null, "BUG", null);
    return workEntities
        .create(archetype, "proj-1", write.withAcceptanceCriteria(criteria), "t")
        .entity()
        .id;
  }

  private String status(Archetype archetype, String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.get(archetype, id)).status;
  }

  @Test
  void anEpicAndATicketWithoutCriteriaAreNotRefined() {
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.TICKET)) {
      String id = create(archetype, List.of());
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> workEntities.transition(archetype, id, "REFINED", "agent"),
              archetype.name());
      assertTrue(
          refusal.getMessage().contains("cannot move to REFINED: ACCEPTANCE_CRITERIA: it has no"
              + " acceptance criteria"),
          refusal.getMessage());
      assertTrue(
          refusal.getMessage().contains(archetype == Archetype.EPIC ? "update_epic" : "update_ticket"),
          refusal.getMessage());
      assertEquals("REPORTED", status(archetype, id));
    }
  }

  @Test
  void aRefinedEntityThatLostItsCriteriaIsNotScheduledAndUnschedulingIsNeverRefused() {
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.TICKET)) {
      String id = create(archetype, CRITERIA);
      workEntities.transition(archetype, id, "REFINED", "agent");
      // Editable at REFINED — as a REFINED row that predates the gate has none.
      workEntities.update(archetype, id, EntityWrite.epic(null, null).withAcceptanceCriteria(List.of()), "t");

      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> workEntities.transition(archetype, id, "READY_FOR_DEV", Mover.person("ada")),
              archetype.name());
      assertTrue(refusal.getMessage().contains("ACCEPTANCE_CRITERIA"), refusal.getMessage());
      assertEquals("REFINED", status(archetype, id));

      workEntities.update(archetype, id, EntityWrite.epic(null, null).withAcceptanceCriteria(CRITERIA), "t");
      workEntities.transition(archetype, id, "READY_FOR_DEV", Mover.person("ada"));
      assertEquals("READY_FOR_DEV", status(archetype, id));
      workEntities.transition(archetype, id, "REFINED", "agent"); // BACK: never refused
      assertEquals("REFINED", status(archetype, id));
    }
  }

  @Test
  void itJudgesOnlyAnEpicOrATicketEnteringRefinedOrReadyForDev() {
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.TICKET)) {
      assertTrue(gate.appliesTo(archetype, EntityStatus.REPORTED, EntityStatus.REFINED));
      assertTrue(gate.appliesTo(archetype, EntityStatus.REFINED, EntityStatus.READY_FOR_DEV));
      assertFalse(gate.appliesTo(archetype, EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING));
    }
    for (Archetype archetype : List.of(Archetype.FEATURE, Archetype.TASK, Archetype.CAMPAIGN)) {
      assertFalse(gate.appliesTo(archetype, EntityStatus.REPORTED, EntityStatus.REFINED));
      assertFalse(gate.appliesTo(archetype, EntityStatus.REFINED, EntityStatus.READY_FOR_DEV));
    }
  }

  @Test
  void aFeatureAndATaskAreCarriedWithoutCriteriaOfTheirOwn() {
    String epic = create(Archetype.EPIC, CRITERIA);
    String feature =
        workEntities
            .create(Archetype.FEATURE, epic, EntityWrite.feature("Part", null, null), "t")
            .entity()
            .id;
    workEntities.transition(Archetype.EPIC, epic, "REFINED", "agent");
    workEntities.transition(Archetype.EPIC, epic, "READY_FOR_DEV", Mover.person("ada"));
    assertEquals("READY_FOR_DEV", status(Archetype.FEATURE, feature));
  }
}
