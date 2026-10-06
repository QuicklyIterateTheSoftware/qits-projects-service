package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code PERSON_APPROVAL} (qits-887): REFINED → READY_FOR_DEV is a person's move, of every kind; a
 * machine mover is refused and moves nothing, a person's move is audited under the person's name,
 * and unscheduling is never refused.
 */
@QuarkusTest
class PersonApprovalGateTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject AuditService auditService;

  private final PersonApprovalGate gate = new PersonApprovalGate();

  private String refined(Archetype archetype) {
    EntityWrite write =
        archetype == Archetype.EPIC
            ? EntityWrite.epic("Planned", null)
            : EntityWrite.ticket("Filed", "it occurs", null, "BUG", null);
    String id =
        workEntities
            .create(archetype, "proj-1", write.withAcceptanceCriteria(CRITERIA), "t")
            .entity()
            .id;
    workEntities.transition(archetype, id, "REFINED", "agent");
    return id;
  }

  private String status(Archetype archetype, String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.get(archetype, id)).status;
  }

  @Test
  void aMachineIsRefusedSchedulingAndNothingMoves() {
    for (Archetype archetype : List.of(Archetype.EPIC, Archetype.TICKET)) {
      String id = refined(archetype);
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> workEntities.transition(archetype, id, "READY_FOR_DEV", Mover.machine("agent-7")));
      assertTrue(
          refusal.getMessage().contains("PERSON_APPROVAL: scheduling (REFINED → READY_FOR_DEV) needs"
              + " a person; agent-7 is a machine credential"),
          refusal.getMessage());
      // The String overload is a machine too: a door that says nothing is refused.
      assertThrows(
          ConflictException.class,
          () -> workEntities.transition(archetype, id, "READY_FOR_DEV", "ada"));
      assertEquals("REFINED", status(archetype, id));
    }
  }

  @Test
  void aPersonSchedulesAndTheMoveIsAuditedUnderThePersonsName() {
    String id = refined(Archetype.EPIC);
    workEntities.transition(Archetype.EPIC, id, "READY_FOR_DEV", Mover.person("ada"));
    assertEquals("READY_FOR_DEV", status(Archetype.EPIC, id));
    List<AuditEntry> history =
        QuarkusTransaction.requiringNew()
            .call(() -> auditService.listForEntity(AuditEntityType.EPIC, id));
    assertEquals("ada", history.get(0).changedBy);
  }

  @Test
  void unschedulingIsNeverRefusedAndBothGatesAreReportedTogether() {
    String id = refined(Archetype.TICKET);
    workEntities.transition(Archetype.TICKET, id, "READY_FOR_DEV", Mover.person("ada"));
    workEntities.transition(Archetype.TICKET, id, "REFINED", Mover.machine("agent-7"));
    assertEquals("REFINED", status(Archetype.TICKET, id));

    workEntities.update(
        Archetype.TICKET, id, EntityWrite.epic(null, null).withAcceptanceCriteria(List.of()), "t");
    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TICKET, id, "READY_FOR_DEV", Mover.machine(null)));
    assertTrue(
        refusal.getMessage().contains("ACCEPTANCE_CRITERIA: it has no acceptance criteria"),
        refusal.getMessage());
    assertTrue(
        refusal.getMessage().contains("PERSON_APPROVAL: scheduling (REFINED → READY_FOR_DEV) needs"
            + " a person; an anonymous caller is a machine credential"),
        refusal.getMessage());
  }

  @Test
  void aCampaignsSchedulingIsAPersonsToo() {
    eu.wohlben.qits.entities.entity.WorkEntity campaign = workEntities.createCampaign("proj-1", "Order", null, "t");
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "REFINED", "t");
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", "t"));
    workEntities.transition(Archetype.CAMPAIGN, campaign.id, "READY_FOR_DEV", Mover.person("ada"));
    assertEquals("READY_FOR_DEV", status(Archetype.CAMPAIGN, campaign.id));
  }

  @Test
  void itJudgesRefinedToReadyForDevOfEveryKindAndNothingElse() {
    for (Archetype archetype : Archetype.values()) {
      assertTrue(gate.appliesTo(archetype, EntityStatus.REFINED, EntityStatus.READY_FOR_DEV));
      assertFalse(gate.appliesTo(archetype, EntityStatus.READY_FOR_DEV, EntityStatus.REFINED));
      assertFalse(gate.appliesTo(archetype, EntityStatus.REPORTED, EntityStatus.REFINED));
      assertFalse(gate.appliesTo(archetype, EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING));
    }
    assertTrue(gate.refusal(new eu.wohlben.qits.entities.entity.WorkEntity(), Mover.person("ada")).isEmpty());
  }
}
