package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.entity.WorkTag;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code CONDITION_MET} (qits-1972): a ticket tagged {@link WorkTag#CONDITION} closes straight to
 * DONE — the ticket's CLOSE move — when a person or the project's own delivery desk makes the move.
 * An untagged ticket, a plain machine and another project's desk are refused, every reason in the
 * one 409; CHILDREN_CLOSED no longer judges a ticket.
 */
@QuarkusTest
class ConditionMetGateTest extends EntitiesTestSupport {

  private static final String PROJECT = "proj-condition-met";
  private static final String WHO = "ada";
  private static final Mover PERSON = Mover.person(WHO);
  private static final Mover OWN_DESK = Mover.desk("desk-of-" + PROJECT, PROJECT);

  @Inject WorkEntityService workEntities;

  private final ConditionMetGate gate = new ConditionMetGate();

  // --- who may close a condition ------------------------------------------------------------------

  @Test
  void theProjectsDeskClosesAReportedCondition() {
    WorkEntity condition = ticket("Bootstrap test node", true);

    workEntities.transition(Archetype.TICKET, condition.id, "DONE", OWN_DESK);

    assertEquals("DONE", status(condition));
  }

  @Test
  void aPersonClosesACondition() {
    WorkEntity condition = ticket("Bootstrap test node", true);
    workEntities.transition(Archetype.TICKET, condition.id, "REFINED", PERSON);

    workEntities.transition(Archetype.TICKET, condition.id, "DONE", PERSON);

    assertEquals("DONE", status(condition));
  }

  @Test
  void aPlainAgentIsRefused() {
    WorkEntity condition = ticket("Bootstrap test node", true);

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.transition(
                    Archetype.TICKET, condition.id, "DONE", Mover.machine("agent-7")));

    String message = refusal.getMessage();
    assertTrue(
        message.contains(
            "CONDITION_MET: closing a condition to DONE needs a person or the delivery desk;"
                + " agent-7 is neither — ask the desk with an @desk comment"),
        message);
    assertFalse(message.contains("not tagged condition"), message);
    assertFalse(message.contains(ChildrenClosedGate.NAME), message);
    assertEquals("REPORTED", status(condition));
  }

  @Test
  void anotherProjectsDeskIsRefused() {
    WorkEntity condition = ticket("Bootstrap test node", true);

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.transition(
                    Archetype.TICKET,
                    condition.id,
                    "DONE",
                    Mover.desk("desk-elsewhere", "another-project")));

    assertTrue(
        refusal.getMessage().contains("CONDITION_MET: closing a condition to DONE needs a person"),
        refusal.getMessage());
    assertEquals("REPORTED", status(condition));
  }

  // --- which ticket may close ---------------------------------------------------------------------

  @Test
  void anUntaggedTicketIsRefusedEvenForTheDesk() {
    WorkEntity ticket = ticket("An ordinary bug", false);

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TICKET, ticket.id, "DONE", OWN_DESK));

    String message = refusal.getMessage();
    assertTrue(
        message.contains(
            "CONDITION_MET: it is not tagged condition — only a ticket tagged condition closes"
                + " straight to DONE; any other ticket is set DONE by the platform when the wrapper"
                + " release request carrying its branch is finalized"),
        message);
    assertFalse(message.contains("needs a person or the delivery desk"), message);
    assertEquals("REPORTED", status(ticket));
  }

  /** Both refusals at once, joined in the one 409. */
  @Test
  void anUntaggedTicketMovedByAMachineNamesBothReasons() {
    WorkEntity ticket = ticket("An ordinary bug", false);

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () ->
                workEntities.transition(
                    Archetype.TICKET, ticket.id, "DONE", Mover.machine("agent-7")));

    String message = refusal.getMessage();
    assertTrue(message.contains("not tagged condition"), message);
    assertTrue(
        message.contains(
            "finalized; closing a condition to DONE needs a person or the delivery desk; agent-7"),
        message);
  }

  /** VERIFIED → DONE stays the walk's ungated FORWARD, for an untagged ticket by a machine too. */
  @Test
  void verifiedToDoneStaysUngated() {
    WorkEntity ticket = ticket("Walked", false);
    for (String step : new String[] {"REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED"}) {
      workEntities.transition(Archetype.TICKET, ticket.id, step, PERSON);
    }

    workEntities.transition(Archetype.TICKET, ticket.id, "DONE", Mover.machine("qits-projects"));

    assertEquals("DONE", status(ticket));
  }

  // --- what it judges -----------------------------------------------------------------------------

  @Test
  void itJudgesATicketsCloseMovesAndNothingElse() {
    for (Archetype archetype : Archetype.values()) {
      for (EntityStatus from : EntityStatus.values()) {
        for (EntityStatus to : EntityStatus.values()) {
          boolean close =
              archetype == Archetype.TICKET
                  && to == EntityStatus.DONE
                  && EntityStateMachine.states(archetype).contains(from)
                  && from != EntityStatus.VERIFIED
                  && from != EntityStatus.DONE
                  && from != EntityStatus.DROPPED;
          assertEquals(
              close, gate.appliesTo(archetype, from, to), archetype + " " + from + " -> " + to);
        }
      }
    }
  }

  @Test
  void childrenClosedNoLongerJudgesATicket() {
    ChildrenClosedGate children = new ChildrenClosedGate();
    for (EntityStatus from : EntityStatus.values()) {
      assertFalse(
          children.appliesTo(Archetype.TICKET, from, EntityStatus.DONE), "TICKET " + from);
    }
    assertTrue(children.appliesTo(Archetype.EPIC, EntityStatus.REFINED, EntityStatus.DONE));
    assertTrue(children.appliesTo(Archetype.FEATURE, EntityStatus.REFINED, EntityStatus.DONE));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private WorkEntity ticket(String title, boolean condition) {
    EntityWrite write =
        EntityWrite.ticket(title, "it must exist", null, "BUG", null)
            .withAcceptanceCriteria(CRITERIA);
    if (condition) {
      write = write.withTags(Set.of(WorkTag.CONDITION));
    }
    return workEntities.create(Archetype.TICKET, PROJECT, write, WHO).entity();
  }

  private String status(WorkEntity row) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workEntities.get(row.archetype, row.id))
        .status;
  }
}
