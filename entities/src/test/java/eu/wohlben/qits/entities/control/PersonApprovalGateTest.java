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

  /**
   * The delivery desk (epic qits-1440) is a machine: refused like any other, unless the dispatch
   * path stamped it with its project's start-refined declaration, which the gate accepts — and
   * only on its own project's row. qits-1596 kept {@code start_refined} as the owner's switch.
   */
  @Test
  void aDeskSchedulesOnlyUnderADeclaredApproval() {
    String id = refined(Archetype.TICKET);
    Mover desk = Mover.desk("tok-desk", "proj-1");
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.TICKET, id, "READY_FOR_DEV", desk));
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.transition(
                Archetype.TICKET,
                id,
                "READY_FOR_DEV",
                Mover.desk("tok-desk-2", "proj-2").withDeclaredApproval("start-refined")));
    assertEquals("REFINED", status(Archetype.TICKET, id));
    assertThrows(IllegalStateException.class, () -> Mover.machine("x").withDeclaredApproval("r"));

    workEntities.transition(
        Archetype.TICKET, id, "READY_FOR_DEV", desk.withDeclaredApproval("start-refined"));
    assertEquals("READY_FOR_DEV", status(Archetype.TICKET, id));
  }

  /** A REFINED ticket of {@code proj-1} assigned to {@code assignee}. */
  private String refinedTicketOf(String assignee) {
    String id =
        workEntities
            .create(
                Archetype.TICKET,
                "proj-1",
                EntityWrite.ticket("Filed", "it occurs", null, "BUG", assignee)
                    .withAcceptanceCriteria(CRITERIA),
                "t")
            .entity()
            .id;
    workEntities.transition(Archetype.TICKET, id, "REFINED", "agent");
    return id;
  }

  /**
   * A project agent (qits-2672) schedules on the desk's terms: stamped with its project's
   * start-refined declaration, on its own project's row assigned to it by name. Without the stamp
   * the refusal names the switch.
   */
  @Test
  void aProjectAgentSchedulesItsOwnItemUnderStartRefined() {
    Mover agent = Mover.projectAgent("tok-project-agent-p-developer", "proj-1", "developer");
    String own = refinedTicketOf("developer");
    workEntities.transition(
        Archetype.TICKET, own, "READY_FOR_DEV", agent.withDeclaredApproval("start-refined"));
    assertEquals("READY_FOR_DEV", status(Archetype.TICKET, own));
  }

  @Test
  void aProjectAgentIsRefusedWithStartRefinedOff() {
    Mover agent = Mover.projectAgent("tok-project-agent-p-developer", "proj-1", "developer");
    String own = refinedTicketOf("developer");
    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TICKET, own, "READY_FOR_DEV", agent));
    assertTrue(
        refusal
            .getMessage()
            .contains(
                "a project agent schedules only under front_desk.delivery.start_refined"),
        refusal.getMessage());
    assertEquals("REFINED", status(Archetype.TICKET, own));
  }

  @Test
  void aProjectAgentIsRefusedAnotherAssigneesItem() {
    Mover agent =
        Mover.projectAgent("tok-project-agent-p-developer", "proj-1", "developer")
            .withDeclaredApproval("start-refined");
    for (String assignee : java.util.Arrays.asList("ada", null)) {
      String theirs = refinedTicketOf(assignee);
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> workEntities.transition(Archetype.TICKET, theirs, "READY_FOR_DEV", agent));
      assertTrue(
          refusal.getMessage().contains("a project agent schedules only its own project's items"),
          refusal.getMessage());
      assertEquals("REFINED", status(Archetype.TICKET, theirs));
    }
  }

  @Test
  void aProjectAgentIsRefusedAnotherProjectsItem() {
    String own = refinedTicketOf("developer");
    Mover elsewhere =
        Mover.projectAgent("tok-project-agent-q-developer", "proj-2", "developer")
            .withDeclaredApproval("start-refined");
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.TICKET, own, "READY_FOR_DEV", elsewhere));
    // An unbound one is nobody's project's.
    Mover unbound =
        Mover.projectAgent("tok-project-agent-x", null, "developer")
            .withDeclaredApproval("start-refined");
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.TICKET, own, "READY_FOR_DEV", unbound));
    assertEquals("REFINED", status(Archetype.TICKET, own));
  }

  /** Every other gate still runs for a project agent: no criteria is still a refusal. */
  @Test
  void aProjectAgentStillMeetsTheOtherGates() {
    String own = refinedTicketOf("developer");
    workEntities.update(
        Archetype.TICKET, own, EntityWrite.epic(null, null).withAcceptanceCriteria(List.of()), "t");
    Mover agent =
        Mover.projectAgent("tok-project-agent-p-developer", "proj-1", "developer")
            .withDeclaredApproval("start-refined");
    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TICKET, own, "READY_FOR_DEV", agent));
    assertTrue(
        refusal.getMessage().contains("ACCEPTANCE_CRITERIA: it has no acceptance criteria"),
        refusal.getMessage());
    assertFalse(refusal.getMessage().contains("PERSON_APPROVAL"), refusal.getMessage());
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
  void itJudgesRefinedToReadyForDevOfEveryKindAndNothingElse() {
    for (Archetype archetype : Archetype.values()) {
      assertTrue(
          gate.appliesTo(archetype, EntityStatus.REFINED, EntityStatus.READY_FOR_DEV),
          archetype.name());
      assertFalse(gate.appliesTo(archetype, EntityStatus.READY_FOR_DEV, EntityStatus.REFINED));
      assertFalse(gate.appliesTo(archetype, EntityStatus.REPORTED, EntityStatus.REFINED));
      assertFalse(gate.appliesTo(archetype, EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTING));
    }
    assertTrue(gate.refusal(new eu.wohlben.qits.entities.entity.WorkEntity(), Mover.person("ada")).isEmpty());
  }
}
